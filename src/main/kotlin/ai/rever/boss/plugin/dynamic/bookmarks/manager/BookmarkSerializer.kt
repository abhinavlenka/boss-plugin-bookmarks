package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * JSON serializer for bookmark-related data structures
 *
 * Handles serialization/deserialization of:
 * - BookmarkCollection lists
 * - FavoriteWorkspace lists
 *
 * Uses kotlinx.serialization with pretty printing and unknown key ignoring
 * for forward/backward compatibility.
 */
internal object BookmarkSerializer {

    /** `Bookmark.createdAt` and `BookmarkCollection.createdAt`. */
    private const val CREATED_AT = "createdAt"

    /** `FavoriteWorkspace.markedAt`, the same computed default in the other file. */
    private const val MARKED_AT = "markedAt"

    /** The nested records inside a collection. */
    private const val BOOKMARKS = "bookmarks"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Allow default values for missing fields
        coerceInputValues = true
        // Load-bearing, and the fix for #7 / #9 rather than a tidiness setting.
        //
        // kotlinx's default is false, which does not mean "omit the fields nobody
        // set". The generated serializer re-evaluates the default *expression* at
        // encode time and omits the field when it matches, and three of these
        // defaults are `Clock.System.now()`:
        //
        //   Bookmark.createdAt, BookmarkCollection.createdAt, FavoriteWorkspace.markedAt
        //
        // So a record serialized in the same millisecond it was constructed had its
        // timestamp dropped (measured at 196 of 200 in a tight loop, #9), and every
        // later load invented a new one from the same expression. The timestamp was
        // not merely wrong, it moved forward on each load, so two parses of one
        // document were not equal and anything sorting by age was reading the last
        // load time.
        //
        // Writing defaults costs a few bytes per record and makes the document say
        // what it means. Files written by older builds are handled on the way in,
        // by [stamped] below, since there is nothing in them to recover.
        encodeDefaults = true
    }

    /**
     * Serialize a list of bookmark collections to JSON string
     *
     * @param collections List of bookmark collections to serialize
     * @return JSON string representation
     */
    fun serializeCollections(collections: List<BookmarkCollection>): String {
        return json.encodeToString(
            ListSerializer(BookmarkCollection.serializer()),
            collections
        )
    }

    /**
     * Deserialize JSON string to list of bookmark collections
     *
     * Records written before timestamps were persisted carry no `createdAt`, and
     * decoding one runs the constructor default - `Clock.System.now()` - so the
     * value would move forward on every load for the rest of the file's life. They
     * are stamped with [writtenAtMillis] instead: see [stamped] for why that is a
     * stable answer rather than a different guess.
     *
     * @param jsonString JSON string to deserialize
     * @param writtenAtMillis When this document was last written, for records that
     *   predate timestamp persistence
     * @return List of bookmark collections
     * @throws kotlinx.serialization.SerializationException if JSON is invalid
     */
    fun deserializeCollections(jsonString: String, writtenAtMillis: Long): List<BookmarkCollection> {
        val document = json.parseToJsonElement(jsonString).mapArray { collection ->
            collection.mapObject { fields ->
                fields[BOOKMARKS] = fields[BOOKMARKS]
                    ?.mapArray { bookmark -> bookmark.stamped(CREATED_AT, writtenAtMillis) }
                    ?: return@mapObject
            }.stamped(CREATED_AT, writtenAtMillis)
        }
        return json.decodeFromJsonElement(ListSerializer(BookmarkCollection.serializer()), document)
    }

    /**
     * Serialize a list of favorite workspaces to JSON string
     *
     * @param favorites List of favorite workspaces to serialize
     * @return JSON string representation
     */
    fun serializeFavoriteWorkspaces(favorites: List<FavoriteWorkspace>): String {
        return json.encodeToString(
            ListSerializer(FavoriteWorkspace.serializer()),
            favorites
        )
    }

    /**
     * Deserialize JSON string to list of favorite workspaces
     *
     * `markedAt` is the same computed default as `createdAt` and is backfilled the
     * same way - see [deserializeCollections].
     *
     * @param jsonString JSON string to deserialize
     * @param writtenAtMillis When this document was last written, for records that
     *   predate timestamp persistence
     * @return List of favorite workspaces
     * @throws kotlinx.serialization.SerializationException if JSON is invalid
     */
    fun deserializeFavoriteWorkspaces(jsonString: String, writtenAtMillis: Long): List<FavoriteWorkspace> {
        val document = json.parseToJsonElement(jsonString).mapArray { workspace ->
            workspace.stamped(MARKED_AT, writtenAtMillis)
        }
        return json.decodeFromJsonElement(ListSerializer(FavoriteWorkspace.serializer()), document)
    }

    /**
     * This record with [key] set to [millis], if it does not already carry a usable one.
     *
     * **Why the file's own write time, rather than "now".** There is nothing in a
     * legacy record to recover the real creation time from, so the only choice is
     * which wrong answer to give. `now()` is wrong *differently on every load*,
     * which is the defect itself; the file's last-modified time is an upper bound
     * on when the record was written, it orders correctly against records saved
     * later, and - the property that matters - it does not change until the file
     * does. A document that is read a hundred times yields the same timestamps a
     * hundred times, with no write-back needed to make it stick, because the next
     * write puts a real value in the file.
     *
     * A key present but null or non-numeric counts as missing: `coerceInputValues`
     * would otherwise quietly hand it to the same drifting default.
     */
    private fun JsonElement.stamped(key: String, millis: Long): JsonElement {
        val record = this as? JsonObject ?: return this
        val usable = (record[key] as? JsonPrimitive)?.longOrNull != null
        return if (usable) record else JsonObject(record + (key to JsonPrimitive(millis)))
    }

    /** Apply [transform] to each element, leaving anything that is not an array alone. */
    private fun JsonElement.mapArray(transform: (JsonElement) -> JsonElement): JsonElement {
        val array = this as? JsonArray ?: return this
        return JsonArray(array.map(transform))
    }

    /** Edit this record's fields, leaving anything that is not an object alone. */
    private fun JsonElement.mapObject(edit: (MutableMap<String, JsonElement>) -> Unit): JsonElement {
        val record = this as? JsonObject ?: return this
        val fields = record.toMutableMap()
        edit(fields)
        return JsonObject(fields)
    }
}
