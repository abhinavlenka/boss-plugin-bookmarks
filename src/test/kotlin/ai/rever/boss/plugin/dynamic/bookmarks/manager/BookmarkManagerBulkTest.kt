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
import kotlin.test.assertTrue

/**
 * Covers [BookmarkManager.addBookmarks], the bulk path used by password/bookmark
 * import.
 *
 * The behaviour that matters is that a batch persists **once**. Adding a few
 * hundred bookmarks one at a time queues one full rewrite of collections.json
 * per bookmark, which is what made a bulk import slow and — before the write
 * became atomic — capable of destroying the file.
 */
class BookmarkManagerBulkTest {
    private lateinit var tempDir: File
    private lateinit var manager: BookmarkManager

    /** Counts how many times the manager asked for a save. */
    private class CountingFileManager(
        directory: String,
    ) : BookmarkFileManager(directory) {
        // Atomic: incremented on a Dispatchers.Default worker, read from the
        // test thread, and a sleep is not a memory barrier.
        val saveCount = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun saveCollections(collections: List<BookmarkCollection>): Boolean {
            saveCount.incrementAndGet()
            return super.saveCollections(collections)
        }
    }

    private lateinit var fileManager: CountingFileManager

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("bookmark-bulk-test").toFile()
        fileManager = CountingFileManager(tempDir.absolutePath)
        manager = BookmarkManager(fileManager)
    }

    @AfterTest
    fun tearDown() {
        // Without this both save workers stay alive on Dispatchers.Default —
        // one pair per test — and the directory vanishes from under an
        // in-flight save, which is swallowed and logged as unexplained noise.
        runBlocking { runCatching { manager.close() } }
        tempDir.deleteRecursively()
    }

    private fun bookmark(index: Int): Bookmark =
        Bookmark(
            id = "bookmark-$index",
            tabConfig = TabConfig(
                type = "browser",
                title = "Site $index",
                url = "https://site$index.test/"
            ),
            workspaceName = ""
        )

    /** Poll to a deadline rather than betting on a fixed wall-clock budget. */
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

    /**
     * Wait until no save has happened for a short quiet period.
     *
     * A fixed sleep is what made this flaky: the initial load's own save could
     * land *after* the baseline count was read, so a batch that cost one write
     * measured as two.
     */
    /**
     * Wait until the initial load is observable.
     *
     * Measuring quiet alone can start before the load's own save has touched
     * the counter, so the baseline reads 0, the load's save lands alongside the
     * batch, and a one-write batch measures as two.
     */
    private fun awaitLoaded() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (manager.collections.value.any { it.name == BookmarkCollection.FAVORITES_NAME }) return
            Thread.sleep(10)
        }
    }

    private fun awaitSaves(quietMillis: Long = 250) {
        awaitLoaded()
        val deadline = System.currentTimeMillis() + 5_000
        var last = fileManager.saveCount.get()
        var quietSince = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            val now = fileManager.saveCount.get()
            if (now != last) {
                last = now
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= quietMillis) {
                return
            }
        }
    }

    @Test
    fun `a batch is persisted once, not once per bookmark`() = runBlocking {
        awaitSaves()
        val before = fileManager.saveCount.get()

        manager.addBookmarks("Imported", (1..200).map { bookmark(it) })
        awaitThat("the batch to be written") { fileManager.saveCount.get() > before }
        awaitSaves()

        assertEquals(
            1,
            fileManager.saveCount.get() - before,
            "200 bookmarks should cost one save, not one per bookmark"
        )
    }

    @Test
    fun `a missing collection is created`() = runBlocking {
        manager.addBookmarks("Fresh", listOf(bookmark(1)))
        awaitSaves()

        val created = manager.collections.value.single { it.name == "Fresh" }
        assertEquals(1, created.bookmarks.size)
    }

    @Test
    fun `an existing collection is appended to, not duplicated`() = runBlocking {
        manager.createCollection("Work")
        awaitSaves()

        manager.addBookmarks("Work", listOf(bookmark(1), bookmark(2)))
        awaitSaves()

        // createCollection appends unconditionally, so a naive implementation
        // would leave two collections named "Work" and file the bookmarks in
        // whichever came first.
        assertEquals(1, manager.collections.value.count { it.name == "Work" })
        assertEquals(2, manager.collections.value.single { it.name == "Work" }.bookmarks.size)
    }

    @Test
    fun `an empty batch does nothing`() = runBlocking {
        awaitSaves()
        val before = fileManager.saveCount.get()

        manager.addBookmarks("Imported", emptyList())
        awaitSaves()

        assertEquals(before, fileManager.saveCount.get(), "an empty batch should not touch the file")
        assertTrue(manager.collections.value.none { it.name == "Imported" })
    }

    @Test
    fun `bulk-added bookmarks survive a reload`() = runBlocking {
        manager.addBookmarks("Imported", (1..50).map { bookmark(it) })
        awaitSaves()

        val reloaded = BookmarkFileManager(tempDir.absolutePath).loadCollections()

        assertEquals(50, reloaded.single { it.name == "Imported" }.bookmarks.size)
    }

    @Test
    fun `importing into Favorites keeps the favourite flag`() = runBlocking {
        // Otherwise getFavoritesCollection(), which matches on the flag rather
        // than the name, cannot find it — and deleteCollection, which refuses
        // only flagged collections, would happily remove it.
        manager.addBookmarks(BookmarkCollection.FAVORITES_NAME, listOf(bookmark(1)))
        awaitThat("the collection to exist") {
            manager.collections.value.any { it.name == BookmarkCollection.FAVORITES_NAME }
        }

        val favorites = manager.collections.value.single { it.name == BookmarkCollection.FAVORITES_NAME }
        assertTrue(favorites.isFavorite, "a Favorites collection was created without the flag")
    }

    @Test
    fun `a second collection asking for a taken name gets a name of its own`() = runBlocking {
        // This used to pin the opposite: two collections could share a name, and every
        // add by name went to whichever came first while the other could never be added
        // to. That was left as "a decision, not a surprise, if someone changes the
        // resolution later" - #10 is that decision, and createCollection now suffixes.
        manager.createCollection("Twin")
        manager.createCollection("Twin")
        awaitThat("both collections to exist") {
            manager.collections.value.count { it.name.startsWith("Twin") } == 2
        }

        manager.addBookmarks("Twin", listOf(bookmark(9)))
        awaitThat("the bookmark to land") {
            manager.collections.value.first { it.name == "Twin" }.bookmarks.isNotEmpty()
        }

        val twins = manager.collections.value.filter { it.name.startsWith("Twin") }
        assertEquals(listOf("Twin", "Twin (2)"), twins.map { it.name })
        // The add is now unambiguous rather than merely first-wins.
        assertEquals(1, twins.first().bookmarks.size)
        assertEquals(0, twins.last().bookmarks.size)
    }
}
