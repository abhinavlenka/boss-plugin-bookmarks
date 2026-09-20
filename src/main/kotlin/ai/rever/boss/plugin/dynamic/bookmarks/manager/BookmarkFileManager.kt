package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * Manages file-based bookmark storage.
 *
 * Stores bookmark data in ~/Documents/BOSS/bookmarks/:
 * - collections.json: All bookmark collections
 * - favorite-workspaces.json: Favorite workspace IDs
 *
 * This is a JVM-only implementation for the bookmarks plugin.
 */
internal open class BookmarkFileManager(
    // Injectable so tests can point at a temp directory instead of the real
    // ~/Documents/BOSS/bookmarks — a test that hammers concurrent saves must
    // never touch the user's actual bookmarks.
    private val bookmarksDirectory: String = defaultBookmarksDirectory()
) {
    // Getter (no backing field): a ComponentLogger-typed field makes the Compose
    // compiler emit a cross-jar $stable reference that the host's parent-first
    // copy of ComponentLogger doesn't have, failing binary-compat validation.
    private val logger get() = BossLogger.forComponent("BookmarkFileManager")

    companion object {
        /** Bookmark collections file name */
        const val COLLECTIONS_FILE = "collections.json"

        /** Favorite workspaces file name */
        const val FAVORITE_WORKSPACES_FILE = "favorite-workspaces.json"

        /** Suffix for in-flight write staging files. */
        private const val TMP_SUFFIX = ".tmp"

        /** Where an unparseable file is kept so it is not silently lost. */
        private const val CORRUPT_SUFFIX = ".corrupt"

        /** Only sweep staging files old enough that no write can still own them. */
        private const val STALE_TMP_AGE_MS = 5 * 60 * 1000L

        /** Default bookmarks directory name under Documents */
        private const val BOOKMARKS_DIR = "BOSS/bookmarks"

        /** Production location: ~/Documents/BOSS/bookmarks/ */
        fun defaultBookmarksDirectory(): String {
            val userHome = System.getProperty("user.home")
            return Paths.get(userHome, "Documents", BOOKMARKS_DIR).toString()
        }
    }

    /**
     * Get the bookmarks directory path.
     *
     * @return Full path to bookmarks directory (e.g., ~/Documents/BOSS/bookmarks/)
     */
    fun getBookmarksDirectory(): String = bookmarksDirectory

    /**
     * Ensure the bookmarks directory exists.
     *
     * @return true if directory exists or was created successfully
     */
    suspend fun ensureBookmarksDirectory(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(getBookmarksDirectory())
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.isDirectory
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Error ensuring bookmarks directory", error = e)
            false
        }
    }

    /**
     * Write [json] to [filePath] without ever leaving a partial file behind.
     *
     * A plain `File.writeText` truncates the target before writing, so a reader
     * — or a second writer — that arrives mid-write sees a truncated or
     * interleaved document. For collections.json that means the user's entire
     * bookmark set is lost. Writing to a sibling temp file and moving it into
     * place makes the swap a single filesystem operation instead.
     *
     * ATOMIC_MOVE is unsupported on a few filesystems; fall back to a plain
     * replacing move, which is still far narrower a window than truncate-write.
     */
    private fun writeAtomically(filePath: String, json: String) {
        // Resolve a symlink to its target before replacing it. ~/Documents is
        // iCloud-synced by default on macOS, and a moved-into-place file would
        // otherwise replace the *link* with a regular file rather than writing
        // through it.
        val requested = Paths.get(filePath)
        // A dangling link (stale relative path, half-synced iCloud) makes
        // toRealPath throw; falling back to the link path keeps saving instead
        // of failing every write until someone notices.
        val target =
            if (Files.isSymbolicLink(requested)) {
                runCatching { requested.toRealPath() }.getOrDefault(requested)
            } else {
                requested
            }

        // A unique temp file per call, NOT a fixed "$filePath.tmp". Two writers
        // sharing one temp path would corrupt each other's staging file and
        // then move the wreckage into place — reintroducing the very race this
        // method exists to close.
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), TMP_SUFFIX)
        try {
            // force(true) before the rename: writeString returns once the bytes
            // are in the page cache, and the rename is separate metadata. A
            // crash in between can leave the rename persisted with the contents
            // not yet written — a present-but-empty collections.json, which is
            // the same loss this method exists to prevent.
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel ->
                // write() is permitted to write fewer bytes than the buffer
                // holds. It virtually always completes for a regular file, but a
                // short write here would stage a truncated document and then
                // atomically install it — the exact failure this method exists
                // to eliminate.
                val buffer = ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }

            // createTempFile is 0600; the file this replaces is usually 0644.
            // Carry the old mode over so replacing it isn't a silent
            // permissions change on upgrade.
            copyPermissions(from = target, to = tmp)

            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: FileSystemException) {
                // Not just AtomicMoveNotSupportedException: on Windows a target
                // held open by an indexer, a backup agent or OneDrive surfaces
                // as AccessDeniedException, and ~/Documents is exactly where
                // those run. Both are FileSystemException.
                logger.debug(
                    LogCategory.FILE,
                    "Atomic move rejected - falling back to a replacing move",
                    mapOf("error" to e.toString())
                )
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            // Force the directory too, so the rename itself survives power
            // loss rather than only the bytes it points at.
            runCatching {
                FileChannel.open(target.parent, StandardOpenOption.READ).use { it.force(true) }
            }
        } finally {
            // A successful move already consumed the temp file, so this only
            // fires when the write or move threw. Guarded because a failing
            // delete must not mask the original exception, nor turn a
            // successful save's result into a failure.
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Mirror [from]'s POSIX mode onto [to], where the platform has one. */
    private fun copyPermissions(from: java.nio.file.Path, to: java.nio.file.Path) {
        runCatching {
            if (!Files.exists(from)) return
            val view = Files.getFileAttributeView(from, PosixFileAttributeView::class.java) ?: return
            Files.setPosixFilePermissions(to, view.readAttributes().permissions())
        }
    }

    /** Copy an unparseable file aside before anything overwrites it. */
    private fun preserveCorruptFile(file: File, cause: Exception) {
        runCatching {
            val backup = File(file.absolutePath + CORRUPT_SUFFIX)
            // One copy only: a repeated failure must not overwrite the first,
            // which is the one closest to the original good state.
            if (!backup.exists()) {
                Files.copy(file.toPath(), backup.toPath())
                logger.warn(
                    LogCategory.FILE,
                    "collections.json could not be parsed - kept a copy before it is replaced",
                    mapOf("backup" to backup.name, "reason" to (cause.message ?: "unknown")),
                )
            }
        }
    }

    /**
     * When [file] was last written, for records that carry no timestamp of their own.
     *
     * Records written before timestamps were persisted have no `createdAt`, and the
     * constructor default that fills one in is `Clock.System.now()` - a value that
     * would be different on every load (#7, #9). The file's own modification time is
     * the closest evidence available and, unlike `now()`, it does not change until
     * the file does.
     *
     * `lastModified()` answers 0 when it cannot tell, which would date every legacy
     * bookmark to 1970; the clock is a better wrong answer than the epoch, and the
     * next save replaces it with a real value either way.
     */
    private fun writtenAt(file: File): Long =
        file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

    /**
     * Remove temp files a previous run left behind.
     *
     * The write path cleans up after itself on failure, but a kill -9 or power
     * loss mid-write cannot — and nothing else ever sweeps them.
     */
    private fun sweepStaleTempFiles() {
        runCatching {
            val cutoff = System.currentTimeMillis() - STALE_TMP_AGE_MS
            File(getBookmarksDirectory())
                .listFiles { f: File -> f.isFile && f.name.endsWith(TMP_SUFFIX) }
                .orEmpty()
                // Age guard: a second host process, or a previous instance whose
                // workers are still draining after a reload, may have a write in
                // flight. Deleting its staging file makes the following move fail
                // with nothing but a log line to show for it.
                .filter { it.lastModified() < cutoff }
                .forEach { it.delete() }
        }
    }

    /**
     * Save bookmark collections to file.
     *
     * Saves all collections to collections.json.
     *
     * @param collections List of bookmark collections to save
     * @return true if saved successfully
     */
    open suspend fun saveCollections(collections: List<BookmarkCollection>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureBookmarksDirectory()

                val filePath = Paths.get(getBookmarksDirectory(), COLLECTIONS_FILE).toString()

                // Serialize collections
                val json = BookmarkSerializer.serializeCollections(collections)

                writeAtomically(filePath, json)

                true
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error saving collections", error = e)
                false
            }
        }

    /**
     * Load bookmark collections from file.
     *
     * Loads from collections.json.
     *
     * @return List of bookmark collections, empty list if file doesn't exist
     */
    open suspend fun loadCollections(): List<BookmarkCollection> =
        withContext(Dispatchers.IO) {
            try {
                sweepStaleTempFiles()
                val filePath = Paths.get(getBookmarksDirectory(), COLLECTIONS_FILE).toString()
                val file = File(filePath)

                if (!file.exists()) {
                    return@withContext emptyList()
                }

                val json = file.readText()
                try {
                    BookmarkSerializer.deserializeCollections(json, writtenAt(file))
                } catch (parseError: Exception) {
                    // An unparseable file is about to be replaced by a fresh
                    // [Favorites] document — and now that writes are atomic, that
                    // is a clean, complete commit of the loss. Keep a copy first
                    // so whatever was in there is still recoverable by hand.
                    preserveCorruptFile(file, parseError)
                    throw parseError
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error loading collections", error = e)
                emptyList()
            }
        }

    /**
     * Save favorite workspaces to file.
     *
     * Saves to favorite-workspaces.json.
     *
     * @param favorites List of favorite workspaces to save
     * @return true if saved successfully
     */
    open suspend fun saveFavoriteWorkspaces(favorites: List<FavoriteWorkspace>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureBookmarksDirectory()

                val filePath = Paths.get(getBookmarksDirectory(), FAVORITE_WORKSPACES_FILE).toString()

                // Serialize favorite workspaces
                val json = BookmarkSerializer.serializeFavoriteWorkspaces(favorites)

                writeAtomically(filePath, json)

                true
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error saving favorite workspaces", error = e)
                false
            }
        }

    /**
     * Load favorite workspaces from file.
     *
     * Loads from favorite-workspaces.json.
     *
     * @return List of favorite workspaces, empty list if file doesn't exist
     */
    suspend fun loadFavoriteWorkspaces(): List<FavoriteWorkspace> =
        withContext(Dispatchers.IO) {
            try {
                val filePath = Paths.get(getBookmarksDirectory(), FAVORITE_WORKSPACES_FILE).toString()
                val file = File(filePath)

                if (!file.exists()) {
                    return@withContext emptyList()
                }

                val json = file.readText()
                BookmarkSerializer.deserializeFavoriteWorkspaces(json, writtenAt(file))
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Error loading favorite workspaces", error = e)
                emptyList()
            }
        }
}
