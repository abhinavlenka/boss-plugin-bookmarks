package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers id uniqueness, which the bookmarks panel depends on to render at all.
 *
 * The failure this guards against: `BookmarkCollection.generateId()` is
 * `"collection-<epochMillis>"`, so a browser import — which creates "Bookmarks
 * Bar" and "Other Bookmarks" back to back — gave both the same id. The panel
 * uses the id as a LazyColumn item key, two items under one key share a single
 * LayoutNode, and expanding one of them crashed Compose with
 * `IllegalStateException: layout state is not idle before measure starts`.
 *
 * Three properties are needed: ids this plugin mints never collide, ids handed
 * to us by a caller are made unique as they are written, and ids that older
 * versions already wrote to disk get repaired on load — without rewriting a file
 * that was already fine.
 */
class BookmarkManagerIdTest {
    private lateinit var tempDir: File
    private lateinit var fileManager: BookmarkFileManager
    private lateinit var manager: BookmarkManager

    @BeforeTest
    fun setUp() {
        // Never the real ~/Documents/BOSS/bookmarks — see the injectable ctor.
        tempDir = Files.createTempDirectory("bookmark-manager-id-test").toFile()
        fileManager = BookmarkFileManager(tempDir.absolutePath)
        manager = BookmarkManager(fileManager)

        // Wait for the constructor's asynchronous load to land before any test
        // touches this manager. Without it every test races loadAllData: the load
        // adds the Favorites collection, so a test that snapshots
        // `collections.value` and then compares against it later can see the
        // snapshot change underneath it for reasons that have nothing to do with
        // what it is asserting. That is a real failure mode, not a theoretical
        // one — it took down a release build after passing locally.
        //
        // Tests that need to act *inside* the load window build their own manager
        // with GatedFileManager instead.
        awaitThat("the initial load to settle") {
            manager.collections.value.any { it.name == BookmarkCollection.FAVORITES_NAME }
        }
    }

    @AfterTest
    fun tearDown() {
        // An unclosed save worker sits on Dispatchers.Default for the rest of the
        // JVM and can recreate the directory this is about to delete.
        runBlocking { runCatching { manager.close() } }
        tempDir.deleteRecursively()
    }

    private fun bookmark(id: String, title: String = id): Bookmark =
        Bookmark(
            id = id,
            tabConfig = TabConfig(type = "browser", title = title, url = "https://example.com/$id"),
            workspaceName = "Test",
        )

    private fun collection(
        id: String,
        name: String,
        bookmarks: List<Bookmark> = emptyList(),
        // Defaulted from the name: a collection called "Favorites" without the flag
        // is a shape the app handles inconsistently (loadAllData recognises it by
        // name, the panel by the flag), so tests should not quietly model it. See
        // issue #10.
        isFavorite: Boolean = name == BookmarkCollection.FAVORITES_NAME,
    ) = BookmarkCollection(id = id, name = name, bookmarks = bookmarks, isFavorite = isFavorite)

    @Test
    fun `collections created in the same millisecond get different ids`() {
        // The original bug in one line: this loop is what a bookmarks import
        // does, and it used to hand out one id for the whole burst.
        val created = (0 until 50).map { manager.createCollection("Collection $it") }

        val ids = created.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "createCollection minted a duplicate id")
    }

    @Test
    fun `bulk add into new collections gives each a different id`() {
        manager.addBookmarks("Bookmarks Bar", listOf(bookmark("b1")))
        manager.addBookmarks("Other Bookmarks", listOf(bookmark("b2")))

        val ids = manager.collections.value.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "addBookmarks minted a duplicate collection id")
    }

    @Test
    fun `duplicate collection ids on disk are renumbered, first one keeps its id`() {
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("collection-1785194024699", "Bookmarks Bar"),
                    collection("collection-1785194024699", "Other Bookmarks"),
                ),
            )

        assertEquals(
            listOf("collection-1785194024699", "collection-1785194024699-2"),
            repaired.map { it.id },
        )
        // Renumbering must not reorder or rename: the user's two folders stay two
        // folders, in the order they were written.
        assertEquals(listOf("Bookmarks Bar", "Other Bookmarks"), repaired.map { it.name })
    }

    @Test
    fun `duplicate bookmark ids are renumbered across collections`() {
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("c1", "One", listOf(bookmark("bookmark-1"), bookmark("bookmark-1"))),
                    collection("c2", "Two", listOf(bookmark("bookmark-1"))),
                ),
            )

        val bookmarkIds = repaired.flatMap { c -> c.bookmarks.map { it.id } }
        assertEquals(
            listOf("bookmark-1", "bookmark-1-2", "bookmark-1-3"),
            bookmarkIds,
            "bookmark ids must be unique across all collections, not just within one",
        )
    }

    @Test
    fun `a renumbered id never lands on one the file already uses`() {
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("c", "First"),
                    // The naive "$id-2" would collide with this.
                    collection("c-2", "Taken"),
                    collection("c", "Needs a new id"),
                ),
            )

        assertEquals(listOf("c", "c-2", "c-3"), repaired.map { it.id })
    }

    @Test
    fun `a pre-existing owner appearing after the duplicate keeps its id`() {
        // The ordering the probe-as-you-go version got wrong: it handed "c-2" to
        // the duplicate and pushed the real owner to "c-2-2".
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("c", "First"),
                    collection("c", "Duplicate"),
                    collection("c-2", "Owns c-2 on disk"),
                ),
            )

        assertEquals(listOf("c", "c-3", "c-2"), repaired.map { it.id })
        assertEquals(
            listOf("First", "Duplicate", "Owns c-2 on disk"),
            repaired.map { it.name },
            "renumbering must not reorder",
        )

        // And still idempotent from that state.
        assertEquals(repaired, manager.withDistinctIds(repaired))
    }

    @Test
    fun `renumbering a collection id leaves its bookmarks untouched`() {
        val bookmarks = listOf(bookmark("b1"), bookmark("b2"), bookmark("b3"))
        val repaired =
            manager.withDistinctIds(
                listOf(
                    collection("c", "First"),
                    collection("c", "Renumbered", bookmarks),
                ),
            )

        val renumbered = repaired[1]
        assertNotEquals("c", renumbered.id, "expected this collection to be renumbered")
        assertEquals(bookmarks, renumbered.bookmarks, "bookmark order or content changed")
    }

    @Test
    fun `a bulk add re-ids bookmarks that would collide with each other`() {
        // What the host hands us on an import: every Bookmark carrying the
        // default millisecond id, so the whole batch aliases.
        val colliding = (0 until 20).map { bookmark("bookmark-same", title = "Site $it") }
        manager.addBookmarks("Bookmarks Bar", colliding)

        val stored = manager.collections.value.first { it.name == "Bookmarks Bar" }.bookmarks
        assertEquals(colliding.size, stored.size, "a bookmark was dropped")
        assertEquals(
            stored.size,
            stored.map { it.id }.toSet().size,
            "duplicate bookmark ids reached the collection",
        )
        // Re-iding must not lose what the bookmark actually is.
        assertEquals(
            colliding.map { it.tabConfig.title },
            stored.map { it.tabConfig.title },
            "re-iding reordered or altered the batch",
        )
    }

    @Test
    fun `adding a bookmark whose id is already taken re-ids the newcomer`() {
        manager.addBookmarks("Work", listOf(bookmark("bookmark-same", title = "Original")))
        manager.addBookmark("Work", bookmark("bookmark-same", title = "Newcomer"))

        val stored = manager.collections.value.first { it.name == "Work" }.bookmarks
        assertEquals(2, stored.size)
        assertEquals(2, stored.map { it.id }.toSet().size, "the newcomer aliased the original")
        // The existing bookmark keeps its id; the newcomer is the one moved.
        assertEquals("bookmark-same", stored.first { it.tabConfig.title == "Original" }.id)
    }

    @Test
    fun `copyBookmark gives the copy its own identity and keeps the original`() {
        val viewModel = ai.rever.boss.plugin.dynamic.bookmarks.BookmarksViewModel(
            bookmarkManager = manager,
            workspaceDataProvider = null,
            splitViewOperations = null,
        )
        val original = bookmark("b1", title = "Shared title")
        manager.addBookmarks("Work", listOf(original))
        manager.addBookmarks("Research", listOf(bookmark("other")))

        viewModel.copyBookmark("Research", original)

        val work = manager.collections.value.first { it.name == "Work" }.bookmarks
        val research = manager.collections.value.first { it.name == "Research" }.bookmarks
        val copy = research.first { it.tabConfig.title == "Shared title" }

        assertEquals(1, work.size, "the original collection changed")
        assertEquals("b1", work.single().id, "the original lost its id")
        assertNotEquals("b1", copy.id, "the copy kept the original's id")
        assertEquals(original.tabConfig, copy.tabConfig, "the copy is not the same bookmark")
    }

    @Test
    fun `a batch of already-unique ids is stored verbatim, same instances`() {
        // The positive half of the provider-API caveat: ids are only touched when
        // they have to be. The identity check also pins withFreeBookmarkIds
        // returning `incoming` itself, which is what makes the no-allocation
        // claim in its KDoc true.
        val batch = listOf(bookmark("u1"), bookmark("u2"), bookmark("u3"))
        manager.addBookmarks("Work", batch)

        val stored = manager.collections.value.first { it.name == "Work" }.bookmarks
        assertEquals(listOf("u1", "u2", "u3"), stored.map { it.id }, "a unique id was rewritten")
        batch.indices.forEach { assertSame(batch[it], stored[it], "the batch was copied needlessly") }
    }

    @Test
    fun `a single add with a free id keeps that id and the same instance`() {
        manager.addBookmarks("Work", listOf(bookmark("existing")))
        val newcomer = bookmark("brand-new")

        manager.addBookmark("Work", newcomer)

        val stored = manager.collections.value.first { it.name == "Work" }.bookmarks
        assertSame(newcomer, stored.first { it.id == "brand-new" }, "a free id was rewritten")
    }

    @Test
    fun `adding to a collection that does not exist changes nothing`() {
        // The `index < 0` early return, which now also skips the re-id.
        manager.addBookmarks("Work", listOf(bookmark("b1")))
        val before = manager.collections.value

        manager.addBookmark("No Such Collection", bookmark("b1"))

        assertEquals(before, manager.collections.value, "a missing collection must be a no-op")
    }

    @Test
    fun `minted ids carry the documented prefix-millis-random shape`() {
        // Both BookmarksMcpTools and the copy path describe this shape in comments.
        val id = manager.newBookmarkId()

        val parts = id.split("-")
        assertEquals(3, parts.size, "expected bookmark-<millis>-<rand>, got $id")
        assertEquals("bookmark", parts[0])
        assertTrue(parts[1].toLongOrNull() != null, "middle field is not a timestamp: $id")
        assertTrue(parts[2].isNotEmpty(), "missing random suffix: $id")
        // The random suffix is the whole point — two in a row must differ.
        assertNotEquals(id, manager.newBookmarkId())
    }

    @Test
    fun `repairing an empty list or an empty collection is a no-op`() {
        assertEquals(emptyList(), manager.withDistinctIds(emptyList()))

        val noBookmarks = listOf(collection("c1", "Empty"), collection("c2", "Also empty"))
        val repaired = manager.withDistinctIds(noBookmarks)
        assertEquals(noBookmarks.size, repaired.size)
        noBookmarks.indices.forEach { assertSame(noBookmarks[it], repaired[it]) }
    }

    @Test
    fun `a file with unique ids is returned untouched`() {
        val original =
            listOf(
                collection("c1", "One", listOf(bookmark("b1"))),
                collection("c2", "Two", listOf(bookmark("b2"))),
            )

        val repaired = manager.withDistinctIds(original)

        // Same instances, not merely equal ones: loadAllData compares the result
        // against what was read to decide whether to rewrite the file, so an
        // untouched load must not schedule a save.
        assertEquals(original.size, repaired.size)
        original.indices.forEach { assertSame(original[it], repaired[it]) }
    }

    @Test
    fun `repairing is idempotent`() {
        val once =
            manager.withDistinctIds(
                listOf(
                    collection("c", "First"),
                    collection("c", "Second"),
                ),
            )

        val twice = manager.withDistinctIds(once)

        assertEquals(once, twice, "a second load must not renumber again")
    }

    @Test
    fun `loading a file with colliding ids repairs it and writes the repair back`() {
        // Its own directory, untouched by setUp's manager: that one's initial
        // load also saves (it creates Favorites), and on a shared directory that
        // write races the seed file below and can clobber it.
        val seedDir = Files.createTempDirectory("bookmark-manager-id-test-seed").toFile()
        val seedFiles = BookmarkFileManager(seedDir.absolutePath)
        runBlocking {
            seedFiles.saveCollections(
                listOf(
                    collection("collection-dup", "Bookmarks Bar", listOf(bookmark("b1"))),
                    collection("collection-dup", "Other Bookmarks", listOf(bookmark("b2"))),
                ),
            )
        }

        // A fresh manager runs loadAllData against the file written above.
        val reloaded = BookmarkManager(seedFiles)
        try {
            awaitThat("the repaired collections to be persisted") {
                val onDisk = runBlocking { seedFiles.loadCollections() }
                val ids = onDisk.map { it.id }
                onDisk.size >= 2 && ids.size == ids.toSet().size
            }

            val onDisk = runBlocking { seedFiles.loadCollections() }
            val duplicates = onDisk.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(duplicates.isEmpty(), "collections.json still holds duplicate ids: $duplicates")

            // Both folders survive the repair — an id-keyed merge would have
            // silently dropped one of them.
            assertTrue(
                onDisk.any { it.name == "Bookmarks Bar" } && onDisk.any { it.name == "Other Bookmarks" },
                "repair lost a collection: ${onDisk.map { it.name }}",
            )
            assertNotEquals(
                onDisk.first { it.name == "Bookmarks Bar" }.id,
                onDisk.first { it.name == "Other Bookmarks" }.id,
            )
        } finally {
            runBlocking { runCatching { reloaded.close() } }
            seedDir.deleteRecursively()
        }
    }

    /** Holds the initial load open so a test can mutate inside the load window. */
    private class GatedFileManager(
        directory: String,
        private val gate: java.util.concurrent.CountDownLatch,
    ) : BookmarkFileManager(directory) {
        override suspend fun loadCollections(): List<BookmarkCollection> {
            gate.await()
            return super.loadCollections()
        }
    }

    @Test
    fun `two collections created during the load window both survive`() {
        // mergeLoadedWithPending's name fallback exists to match a pending
        // collection against one the *disk* knows under a different id. It used
        // to match pendings against each other too, because they were folded into
        // the same map as it went — so the second collapsed onto the first and
        // vanished. Gated rather than timed: this raced the async load, which is
        // why it surfaced as an intermittent CI failure rather than a bug report.
        val dir = Files.createTempDirectory("bookmark-manager-id-test-window").toFile()
        val gate = java.util.concurrent.CountDownLatch(1)
        val gated = BookmarkManager(GatedFileManager(dir.absolutePath, gate))
        try {
            val first = gated.createCollection("Twin")
            val second = gated.createCollection("Twin")
            assertNotEquals(first.id, second.id, "createCollection minted a duplicate id")
            // The historical trigger was a shared name, which collapsed the second onto
            // the first as the merge folded pendings into one map. createCollection now
            // suffixes (#10), so these are "Twin" and "Twin (2)" and the collapse is
            // unreachable from the public API - but the property under test is that a
            // collection created during the load window keeps its own identity, and that
            // is still worth holding the merge to.
            assertEquals("Twin (2)", second.name)
            gated.addBookmarks("Twin", listOf(bookmark("b1")))

            gate.countDown()
            awaitThat("the load to merge") {
                gated.collections.value.any { it.name == BookmarkCollection.FAVORITES_NAME }
            }

            val twins = gated.collections.value.filter { it.name.startsWith("Twin") }
            assertEquals(2, twins.size, "a collection created during the load window was lost")
            assertEquals(
                setOf(first.id, second.id),
                twins.map { it.id }.toSet(),
                "both collections must keep their own identity",
            )
            assertEquals(1, twins.first().bookmarks.size, "the add did not land in the first match")
            assertEquals(0, twins.last().bookmarks.size, "the add leaked into the second collection")
        } finally {
            runBlocking { runCatching { gated.close() } }
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a pending collection still merges into the disk copy under a different id`() {
        // The behaviour the name fallback exists for, which the fix must not
        // break: same name, different id, one collection out — with both sides'
        // bookmarks unioned.
        val dir = Files.createTempDirectory("bookmark-manager-id-test-rename").toFile()
        val gate = java.util.concurrent.CountDownLatch(1)
        val files = BookmarkFileManager(dir.absolutePath)
        runBlocking {
            files.saveCollections(listOf(collection("on-disk-id", "Work", listOf(bookmark("disk-1")))))
        }

        val gated = BookmarkManager(GatedFileManager(dir.absolutePath, gate))
        try {
            gated.addBookmarks("Work", listOf(bookmark("pending-1")))
            gate.countDown()
            awaitThat("the load to merge") {
                gated.collections.value.any { it.name == BookmarkCollection.FAVORITES_NAME }
            }

            val work = gated.collections.value.filter { it.name == "Work" }
            assertEquals(1, work.size, "the pending collection did not merge into the disk copy")
            assertEquals(
                setOf("disk-1", "pending-1"),
                work.single().bookmarks.map { it.id }.toSet(),
                "the union of both sides' bookmarks must survive",
            )
        } finally {
            runBlocking { runCatching { gated.close() } }
            dir.deleteRecursively()
        }
    }

    /** Counts writes, so "no save happened" is an exact assertion and not a timing one. */
    private class SaveCountingFileManager(directory: String) : BookmarkFileManager(directory) {
        val collectionSaves = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun saveCollections(collections: List<BookmarkCollection>): Boolean {
            collectionSaves.incrementAndGet()
            return super.saveCollections(collections)
        }
    }

    @Test
    fun `loading a file that needs no repair does not rewrite it`() {
        // The other side of the `_collections.value != onDisk` branch: the
        // assertSame test pins the helper, this pins the decision that uses it.
        // Favorites is seeded by name, or loadAllData would add it and save.
        val seedDir = Files.createTempDirectory("bookmark-manager-id-test-clean").toFile()
        val seedFiles = SaveCountingFileManager(seedDir.absolutePath)
        val seeded =
            listOf(
                collection("collection-favs", BookmarkCollection.FAVORITES_NAME, listOf(bookmark("b1"))),
                collection("collection-work", "Work", listOf(bookmark("b2"))),
            )
        runBlocking { seedFiles.saveCollections(seeded) }
        val savesAfterSeeding = seedFiles.collectionSaves.get()

        val file = File(seedDir, BookmarkFileManager.COLLECTIONS_FILE)
        val before = file.readText()

        val reloaded = BookmarkManager(seedFiles)
        try {
            // Await a positive signal that the load ran, so this cannot pass
            // vacuously by asserting a non-event before anything has happened.
            awaitThat("the load to complete") { reloaded.collections.value.isNotEmpty() }

            // The deterministic half: the loaded state still describes the same
            // collections and bookmarks it read, so `_collections.value != onDisk`
            // was false — which *is* the decision not to save. No timing involved.
            //
            // Compared as a projection rather than whole objects: `createdAt` is
            // dropped from the JSON whenever a record is serialised in the same
            // millisecond it was constructed (kotlinx `encodeDefaults = false`
            // re-evaluates the `Clock.System.now()` default and omits the field
            // when it matches), so two parses of identical JSON can disagree on it.
            // Pre-existing and orthogonal to this assertion — but it makes a
            // whole-object comparison across two reads flaky.
            fun shape(cs: List<BookmarkCollection>) =
                cs.map { c -> c.id to c.bookmarks.map { it.id } }

            assertEquals(
                shape(runBlocking { seedFiles.loadCollections() }),
                shape(reloaded.collections.value),
                "load changed a file that needed no repair, so it would save",
            )

            // The observable half: an exact write count rather than mtime, which
            // some filesystems only report to 1s. Settle rather than sleep a fixed
            // span — a save scheduled just after the merge still gets counted.
            settleSaves(seedFiles)
            assertEquals(
                savesAfterSeeding,
                seedFiles.collectionSaves.get(),
                "an unaffected file was rewritten",
            )
            assertEquals(before, file.readText(), "file contents changed")
        } finally {
            runBlocking { runCatching { reloaded.close() } }
            seedDir.deleteRecursively()
        }
    }

    /**
     * Wait until the save count has been quiet for a short period.
     *
     * Throws on the deadline rather than returning: falling out silently would
     * turn a save that never settles — a wedged writer, exactly the pathology
     * worth catching — into a *passing* test.
     */
    private fun settleSaves(files: SaveCountingFileManager, quietMillis: Long = 300) {
        val deadline = System.currentTimeMillis() + 5_000
        var last = files.collectionSaves.get()
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            val now = files.collectionSaves.get()
            if (now != last) {
                last = now
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= quietMillis) {
                return
            }
        }
        throw AssertionError("saves never settled: still writing after 5s (count=$last)")
    }

    @Test
    fun `loading a file with no Favorites collection does write one back`() {
        // The other outcome of the same `!= onDisk` comparison: the no-repair test
        // asserts it stays false, this asserts it goes true and a write follows.
        val seedDir = Files.createTempDirectory("bookmark-manager-id-test-nofavs").toFile()
        val seedFiles = SaveCountingFileManager(seedDir.absolutePath)
        runBlocking {
            seedFiles.saveCollections(listOf(collection("collection-work", "Work", listOf(bookmark("b1")))))
        }
        val savesAfterSeeding = seedFiles.collectionSaves.get()

        val reloaded = BookmarkManager(seedFiles)
        try {
            awaitThat("Favorites to be added and persisted") {
                runBlocking { seedFiles.loadCollections() }
                    .any { it.name == BookmarkCollection.FAVORITES_NAME }
            }

            assertTrue(
                seedFiles.collectionSaves.get() > savesAfterSeeding,
                "adding Favorites did not schedule a save",
            )
            val onDisk = runBlocking { seedFiles.loadCollections() }
            assertTrue(onDisk.any { it.name == "Work" }, "the existing collection was lost")
        } finally {
            runBlocking { runCatching { reloaded.close() } }
            seedDir.deleteRecursively()
        }
    }

    /**
     * Wait until [condition] holds.
     *
     * The load and its save are asynchronous; a fixed sleep would flake on a
     * loaded runner.
     */
    private fun awaitThat(
        what: String,
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
