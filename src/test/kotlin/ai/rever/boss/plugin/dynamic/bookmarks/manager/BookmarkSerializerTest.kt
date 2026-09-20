package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Timestamps have to survive the round trip (#7, #9).
 *
 * The defect these pin: kotlinx omits a defaulted field when the instance's value
 * equals the default *re-evaluated at encode time*, and three of these defaults are
 * `Clock.System.now()`. A record saved in the millisecond it was created therefore
 * lost its timestamp, and every load afterwards invented a new one - so `createdAt`
 * was not a creation time but the time of the last parse, and two parses of one
 * document were not equal.
 *
 * The same-millisecond cases below are the sharp end of that: they run a tight
 * construct-then-serialize loop, which is what a bulk import does. Against the
 * unfixed serializer the issue measured 196 of 200 records losing the field.
 */
class BookmarkSerializerTest {

    private companion object {
        /** Enough iterations that a same-millisecond save is near-certain. */
        const val ROUNDS = 200

        /** A fixed instant, so "the fallback was used" is exactly assertable. */
        const val WRITTEN_AT = 1_700_000_000_000L

        /** Distinguishable from [WRITTEN_AT] at a glance in a failure message. */
        const val ORIGINAL_CREATED_AT = 1_600_000_000_000L
    }

    private fun bookmark(id: String, createdAt: Long = ORIGINAL_CREATED_AT) = Bookmark(
        id = id,
        tabConfig = TabConfig(type = "browser", title = id, url = "https://example.com/$id"),
        workspaceName = "",
        createdAt = createdAt,
    )

    private fun collection(name: String, createdAt: Long = ORIGINAL_CREATED_AT) = BookmarkCollection(
        id = "collection-$name",
        name = name,
        bookmarks = listOf(bookmark("$name-0"), bookmark("$name-1")),
        createdAt = createdAt,
    )

    // ------------------------------------------------- the durability itself

    @Test
    fun `createdAt survives a save in the millisecond the record was created`() {
        var lost = 0
        repeat(ROUNDS) { round ->
            // Constructed with the default, i.e. now: exactly the case the encoder
            // used to drop. Nothing sleeps, so most iterations land in one millisecond.
            val original = BookmarkCollection(
                id = "c-$round",
                name = "Round $round",
                bookmarks = listOf(
                    Bookmark(
                        id = "b-$round",
                        tabConfig = TabConfig(type = "browser", title = "t", url = "https://example.com"),
                        workspaceName = "",
                    ),
                ),
            )
            val reloaded = BookmarkSerializer
                .deserializeCollections(BookmarkSerializer.serializeCollections(listOf(original)), WRITTEN_AT)
                .single()

            if (reloaded.createdAt != original.createdAt ||
                reloaded.bookmarks.single().createdAt != original.bookmarks.single().createdAt
            ) {
                lost++
            }
        }
        assertEquals(0, lost, "$lost of $ROUNDS records lost their createdAt")
    }

    @Test
    fun `two parses of one document are equal`() {
        // The sharp edge from #9: with the field omitted, each parse ran
        // Clock.System.now() again, so identical bytes decoded to unequal objects
        // and anything comparing loaded collections was quietly flaky.
        val document = BookmarkSerializer.serializeCollections(listOf(collection("Work")))

        val first = BookmarkSerializer.deserializeCollections(document, WRITTEN_AT)
        Thread.sleep(2)
        val second = BookmarkSerializer.deserializeCollections(document, WRITTEN_AT + 5_000)

        // Whole objects, not a projection: that is the claim.
        assertEquals(first, second)
    }

    @Test
    fun `a saved document carries the timestamps rather than leaving them to be invented`() {
        val document = BookmarkSerializer.serializeCollections(listOf(collection("Work")))

        // Stated against the bytes as well as the round trip, because a round trip
        // would also pass if both ends re-invented the same value in one millisecond.
        assertContains(document, "\"createdAt\": $ORIGINAL_CREATED_AT")
    }

    @Test
    fun `the other defaulted fields round-trip too`() {
        val original = BookmarkCollection(
            id = "c",
            name = "Work",
            isFavorite = true,
            bookmarks = listOf(bookmark("b").copy(notes = "read later", tags = listOf("kotlin"), lastAccessedAt = 42L)),
            createdAt = ORIGINAL_CREATED_AT,
        )

        val reloaded = BookmarkSerializer
            .deserializeCollections(BookmarkSerializer.serializeCollections(listOf(original)), WRITTEN_AT)
            .single()

        assertEquals(original, reloaded)
    }

    // ------------------------------------------------------- legacy documents

    @Test
    fun `a record written before timestamps were persisted is dated from the file`() {
        // Exactly what an existing user's collections.json looks like.
        val legacy = """
            [
              {
                "id": "collection-Work",
                "name": "Work",
                "bookmarks": [
                  {
                    "id": "Work-0",
                    "tabConfig": { "type": "browser", "title": "Work-0", "url": "https://example.com/0" },
                    "workspaceName": ""
                  }
                ]
              }
            ]
        """.trimIndent()

        val loaded = BookmarkSerializer.deserializeCollections(legacy, WRITTEN_AT).single()

        assertEquals(WRITTEN_AT, loaded.createdAt)
        assertEquals(WRITTEN_AT, loaded.bookmarks.single().createdAt)
    }

    @Test
    fun `a legacy record does not drift between loads`() {
        // The property that makes the file's write time the right fallback: it is
        // the same answer every time, so no write-back is needed to make it stick.
        val legacy = """[{ "id": "c", "name": "Work", "bookmarks": [] }]"""

        val first = BookmarkSerializer.deserializeCollections(legacy, WRITTEN_AT)
        Thread.sleep(2)
        val second = BookmarkSerializer.deserializeCollections(legacy, WRITTEN_AT)

        assertEquals(first, second)
    }

    @Test
    fun `a stored timestamp is never overwritten by the fallback`() {
        val stored = """
            [{ "id": "c", "name": "Work", "createdAt": $ORIGINAL_CREATED_AT, "bookmarks": [] }]
        """.trimIndent()

        val loaded = BookmarkSerializer.deserializeCollections(stored, WRITTEN_AT).single()

        assertEquals(ORIGINAL_CREATED_AT, loaded.createdAt)
    }

    @Test
    fun `a null or non-numeric timestamp counts as missing`() {
        // coerceInputValues is on, so a null would otherwise be handed to the same
        // drifting default instead of the fallback.
        val damaged = """
            [
              { "id": "a", "name": "Null", "createdAt": null, "bookmarks": [] },
              { "id": "b", "name": "Text", "createdAt": "yesterday", "bookmarks": [] }
            ]
        """.trimIndent()

        val loaded = BookmarkSerializer.deserializeCollections(damaged, WRITTEN_AT)

        assertEquals(listOf(WRITTEN_AT, WRITTEN_AT), loaded.map { it.createdAt })
    }

    @Test
    fun `a collection with no bookmarks key still loads`() {
        val loaded = BookmarkSerializer
            .deserializeCollections("""[{ "id": "c", "name": "Work" }]""", WRITTEN_AT)
            .single()

        assertTrue(loaded.bookmarks.isEmpty())
        assertEquals(WRITTEN_AT, loaded.createdAt)
    }

    // -------------------------------------------------------- favorites file

    @Test
    fun `markedAt survives a save in the millisecond the workspace was marked`() {
        var lost = 0
        repeat(ROUNDS) { round ->
            val original = FavoriteWorkspace(workspaceId = "w-$round", workspaceName = "W $round")
            val reloaded = BookmarkSerializer
                .deserializeFavoriteWorkspaces(
                    BookmarkSerializer.serializeFavoriteWorkspaces(listOf(original)),
                    WRITTEN_AT,
                )
                .single()
            if (reloaded.markedAt != original.markedAt) lost++
        }
        assertEquals(0, lost, "$lost of $ROUNDS favorite workspaces lost their markedAt")
    }

    @Test
    fun `a legacy favorite workspace is dated from the file`() {
        val legacy = """[{ "workspaceId": "w", "workspaceName": "Work" }]"""

        val loaded = BookmarkSerializer.deserializeFavoriteWorkspaces(legacy, WRITTEN_AT).single()

        assertEquals(WRITTEN_AT, loaded.markedAt)
    }

    // ------------------------------------------------------ nothing else moved

    @Test
    fun `unknown keys are still ignored`() {
        // Forward compatibility is the reason ignoreUnknownKeys is on, and the
        // backfill now walks the same document, so it is worth restating.
        val fromANewerBuild = """
            [{ "id": "c", "name": "Work", "colour": "blue", "bookmarks": [], "createdAt": $ORIGINAL_CREATED_AT }]
        """.trimIndent()

        val loaded = BookmarkSerializer.deserializeCollections(fromANewerBuild, WRITTEN_AT).single()

        assertEquals("Work", loaded.name)
        assertEquals(ORIGINAL_CREATED_AT, loaded.createdAt)
    }

    @Test
    fun `an unparseable document still throws`() {
        // BookmarkFileManager keeps a .corrupt copy on this path, so parsing has to
        // keep failing rather than being smoothed over by the tree walk.
        assertFailsWith<SerializationException> {
            BookmarkSerializer.deserializeCollections("{ not json", WRITTEN_AT)
        }
        assertFailsWith<SerializationException> {
            BookmarkSerializer.deserializeCollections("""{ "id": "not-a-list" }""", WRITTEN_AT)
        }
    }
}
