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
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which collection *is* Favorites, and what a collection is called (#10).
 *
 * The defect: two representations were in use. `loadAllData` asked whether any
 * collection was **named** Favorites; `getFavoritesCollection` and the panel ask which
 * one carries **`isFavorite`**. A file holding a collection named Favorites without the
 * flag - which `BookmarkCollection(id, name = "Favorites")` produces, since the flag
 * defaults to false - satisfied the first and not the second, so the panel rendered no
 * Favorites section at all and the collection sat in the ordinary list offering
 * "Delete Collection". The mirror case was as bad: a user who renamed Favorites kept the
 * flag but lost the name, so every load decided Favorites was missing and prepended
 * another one.
 *
 * The flag wins, because it survives a rename. Names are then made unique, since adds
 * resolve by name and two collections sharing one means every add goes to the first.
 */
class BookmarkFavoritesIdentityTest {

    private lateinit var tempDir: File
    private lateinit var fileManager: BookmarkFileManager
    private lateinit var manager: BookmarkManager

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("bookmark-favorites-test").toFile()
        fileManager = BookmarkFileManager(tempDir.absolutePath)
        manager = BookmarkManager(fileManager)
        awaitThat("the initial load to settle") { manager.collections.value.any { it.isFavorite } }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { runCatching { manager.close() } }
        tempDir.deleteRecursively()
    }

    private fun bookmark(id: String) = Bookmark(
        id = id,
        tabConfig = TabConfig(type = "browser", title = id, url = "https://example.com/$id"),
        workspaceName = "Test",
    )

    private fun collection(id: String, name: String, isFavorite: Boolean = false) =
        BookmarkCollection(id = id, name = name, isFavorite = isFavorite)

    // --------------------------------------------------- the repair itself

    @Test
    fun `a collection named Favorites without the flag is adopted, not duplicated`() {
        val repaired = manager.withSingleFavorites(
            listOf(collection("c1", BookmarkCollection.FAVORITES_NAME), collection("c2", "Work")),
        )

        assertEquals(listOf("c1"), repaired.filter { it.isFavorite }.map { it.id })
        // Adopted, not replaced: the user's bookmarks are in that collection.
        assertEquals(2, repaired.size)
    }

    @Test
    fun `a renamed Favorites is left alone rather than joined by a second one`() {
        val renamed = listOf(collection("c1", "Starred", isFavorite = true))

        val repaired = manager.withSingleFavorites(renamed)

        assertSame(renamed, repaired, "a list with one Favorites must come back untouched")
    }

    @Test
    fun `a second flagged collection is demoted so it can be seen and deleted`() {
        // A second flagged collection is unreachable: the panel shows the first and
        // filters the rest out of the collection list, and deleteCollection refuses to
        // remove a flagged one - so it is invisible and undeletable at once.
        val repaired = manager.withSingleFavorites(
            listOf(
                collection("c1", BookmarkCollection.FAVORITES_NAME, isFavorite = true),
                collection("c2", "Imported", isFavorite = true),
            ),
        )

        assertEquals(listOf(true, false), repaired.map { it.isFavorite })
        assertEquals(listOf("c1", "c2"), repaired.map { it.id }, "demoting must not drop or reorder")
    }

    @Test
    fun `a list that is already right is returned unchanged`() {
        // Identity, not just equality: loadAllData compares the repaired list against
        // what was on disk to decide whether to rewrite the file, so a no-op repair has
        // to stay a no-op.
        val fine = listOf(
            collection("c1", BookmarkCollection.FAVORITES_NAME, isFavorite = true),
            collection("c2", "Work"),
        )

        assertSame(fine, manager.withSingleFavorites(fine))
    }

    @Test
    fun `nothing named or flagged is left for the caller to fill in`() {
        val none = listOf(collection("c1", "Work"))

        assertSame(none, manager.withSingleFavorites(none))
    }

    // ------------------------------------------------------ through a load

    @Test
    fun `an unflagged Favorites on disk is flagged on load, and the file is repaired`() {
        // Exactly what createCollection("Favorites") wrote before this fix.
        val loaded = loadFromDisk(
            """
            [
              { "id": "c1", "name": "Favorites", "isFavorite": false, "bookmarks": [] },
              { "id": "c2", "name": "Work", "isFavorite": false, "bookmarks": [] }
            ]
            """.trimIndent(),
        ) { it.collections.value.size == 2 && it.collections.value.any { c -> c.isFavorite } }

        assertEquals(listOf("c1"), loaded.filter { it.isFavorite }.map { it.id })
        assertEquals(2, loaded.size, "a second Favorites collection was created")
    }

    @Test
    fun `a renamed Favorites does not get a second Favorites added on load`() {
        // The mirror of the bug: the flag is there, the name is not, and the old
        // name-based check would prepend a fresh Favorites on every launch.
        val loaded = loadFromDisk(
            """[{ "id": "c1", "name": "Starred", "isFavorite": true, "bookmarks": [] }]""",
        ) { it.collections.value.isNotEmpty() }

        assertEquals(listOf("Starred"), loaded.map { it.name })
    }

    // ------------------------------------------------------------- naming

    @Test
    fun `asking to create Favorites hands back the one that exists`() {
        val existing = manager.collections.value.single { it.isFavorite }

        val created = manager.createCollection(BookmarkCollection.FAVORITES_NAME)

        assertEquals(existing.id, created.id)
        assertEquals(1, manager.collections.value.count { it.name == BookmarkCollection.FAVORITES_NAME })
    }

    @Test
    fun `a taken name is suffixed rather than duplicated`() {
        manager.createCollection("Work")
        val second = manager.createCollection("Work")
        val third = manager.createCollection("Work")

        assertEquals("Work (2)", second.name)
        assertEquals("Work (3)", third.name)
    }

    @Test
    fun `renaming onto a taken name is suffixed too`() {
        manager.createCollection("Work")
        val other = manager.createCollection("Personal")

        manager.renameCollection(other.id, "Work")
        awaitThat("the rename to land") { manager.collections.value.none { it.name == "Personal" } }

        assertEquals(
            listOf("Work", "Work (2)"),
            manager.collections.value.filter { it.name.startsWith("Work") }.map { it.name },
        )
    }

    @Test
    fun `renaming Favorites keeps it as Favorites`() {
        val favorites = manager.collections.value.single { it.isFavorite }

        manager.renameCollection(favorites.id, "Starred")
        awaitThat("the rename to land") { manager.collections.value.any { it.name == "Starred" } }

        val renamed = manager.collections.value.single { it.id == favorites.id }
        assertTrue(renamed.isFavorite, "renaming Favorites must not stop it being Favorites")
        assertEquals(favorites.id, manager.getFavoritesCollection().id)
    }

    @Test
    fun `an import into Favorites after a rename does not flag a second collection`() {
        val favorites = manager.collections.value.single { it.isFavorite }
        manager.renameCollection(favorites.id, "Starred")
        awaitThat("the rename to land") { manager.collections.value.any { it.name == "Starred" } }

        // addBookmarks creates by name, and used to flag anything called Favorites.
        manager.addBookmarks(BookmarkCollection.FAVORITES_NAME, listOf(bookmark("b1")))
        awaitThat("the import to land") {
            manager.collections.value.any { it.name == BookmarkCollection.FAVORITES_NAME }
        }

        assertEquals(1, manager.collections.value.count { it.isFavorite })
        assertEquals(favorites.id, manager.collections.value.single { it.isFavorite }.id)
        assertFalse(
            manager.collections.value.single { it.name == BookmarkCollection.FAVORITES_NAME }.isFavorite,
        )
    }

    @Test
    fun `the flagged collection still cannot be deleted`() {
        val favorites = manager.collections.value.single { it.isFavorite }

        manager.deleteCollection(favorites.id)

        assertTrue(manager.collections.value.any { it.id == favorites.id })
    }

    @Test
    fun `duplicate names already on disk still resolve to the first`() {
        // The limitation that stays, and is worth stating: repairing these would mean
        // renaming a user's collections behind their back, which is worse than the
        // ambiguity. withDistinctIds logs them; nothing new can create them.
        val loaded = loadFromDisk(
            """
            [
              { "id": "c1", "name": "Favorites", "isFavorite": true, "bookmarks": [] },
              { "id": "c2", "name": "Twin", "isFavorite": false, "bookmarks": [] },
              { "id": "c3", "name": "Twin", "isFavorite": false, "bookmarks": [] }
            ]
            """.trimIndent(),
        ) { it.collections.value.size == 3 }

        assertEquals(listOf("Twin", "Twin"), loaded.filter { it.name == "Twin" }.map { it.name })
    }

    @Test
    fun `import during load joins renamed Favorites without hiding either bookmark`() {
        val saved = collection("saved", "Starred", isFavorite = true).copy(bookmarks = listOf(bookmark("old")))
        duringLoad(listOf(saved)) { loading, release ->
            loading.addBookmarks("Favorites", listOf(bookmark("imported")))
            release()
            awaitThat("Favorites to merge") { loading.collections.value.any { it.id == "saved" } }
            val favorite = loading.collections.value.single { it.isFavorite }
            assertEquals("Starred", favorite.name)
            assertEquals(setOf("old", "imported"), favorite.bookmarks.map { it.id }.toSet())
            assertEquals(1, loading.collections.value.size)
        }
    }

    @Test
    fun `Favorites flag wins over a same named ordinary destination during load`() {
        val saved = listOf(
            collection("saved", "Starred", isFavorite = true),
            collection("ordinary", "Favorites").copy(bookmarks = listOf(bookmark("ordinary-bookmark"))),
        )
        duringLoad(saved) { loading, release ->
            loading.addBookmarks("Favorites", listOf(bookmark("imported")))
            release()
            awaitThat("saved collections to merge") { loading.collections.value.any { it.id == "saved" } }
            assertEquals(listOf("imported"), loading.getFavoritesCollection().bookmarks.map { it.id })
            assertEquals(listOf("ordinary-bookmark"), loading.collections.value.single { it.id == "ordinary" }.bookmarks.map { it.id })
            assertEquals(1, loading.collections.value.count { it.isFavorite })
        }
    }

    @Test
    fun `a Favorites rename made during load survives the identity merge`() {
        duringLoad(listOf(collection("saved", "Favorites", isFavorite = true))) { loading, release ->
            val pending = loading.createCollection("Favorites")
            loading.renameCollection(pending.id, "Starred")
            release()
            awaitThat("the renamed Favorites to merge") { loading.collections.value.any { it.id == "saved" } }
            assertEquals("Starred", loading.getFavoritesCollection().name)
            assertEquals(1, loading.collections.value.size)
        }
    }

    @Test
    fun `concurrent creates reserve distinct names and return the actual collection`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val created = java.util.concurrent.ConcurrentLinkedQueue<BookmarkCollection>()
        val threads = List(32) {
            Thread {
                gate.await()
                created.add(manager.createCollection("Concurrent"))
            }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive }, "a concurrent create did not finish")
        assertEquals(32, created.size)
        assertEquals(32, created.map { it.name }.toSet().size)
        created.forEach { returned ->
            assertEquals(returned, manager.collections.value.single { it.id == returned.id })
        }
    }

    private fun duringLoad(
        saved: List<BookmarkCollection>,
        action: (BookmarkManager, () -> Unit) -> Unit,
    ) {
        val gate = java.util.concurrent.CountDownLatch(1)
        val dir = Files.createTempDirectory("bookmark-favorites-gated").toFile()
        val files = object : BookmarkFileManager(dir.absolutePath) {
            override suspend fun loadCollections(): List<BookmarkCollection> {
                gate.await()
                return saved
            }
        }
        val loading = BookmarkManager(files)
        try {
            action(loading) { gate.countDown() }
        } finally {
            gate.countDown()
            runBlocking { loading.close() }
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ helpers

    /** Run a manager over [json] already on disk, and return what it settled on. */
    private fun loadFromDisk(
        json: String,
        settled: (BookmarkManager) -> Boolean,
    ): List<BookmarkCollection> {
        val dir = Files.createTempDirectory("bookmark-favorites-load").toFile()
        File(dir, BookmarkFileManager.COLLECTIONS_FILE).writeText(json)
        val loading = BookmarkManager(BookmarkFileManager(dir.absolutePath))
        try {
            awaitThat("the load to settle") { settled(loading) }
            return loading.collections.value
        } finally {
            runBlocking { runCatching { loading.close() } }
            dir.deleteRecursively()
        }
    }

    private fun awaitThat(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
