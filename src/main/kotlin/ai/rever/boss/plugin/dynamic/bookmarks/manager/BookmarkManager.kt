package ai.rever.boss.plugin.dynamic.bookmarks.manager

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/**
 * Manages bookmark collections and favorite workspaces.
 *
 * Provides reactive state flows for UI and handles persistence.
 * Automatically creates the "Favorites" collection on first run.
 *
 * This is the internal bookmark manager for the bookmarks plugin.
 * It is self-contained and does not depend on BossConsole's implementation.
 */
class BookmarkManager internal constructor(
    // Injectable for tests; production callers use the no-arg constructor and
    // get the real ~/Documents/BOSS/bookmarks location.
    private val fileManager: BookmarkFileManager
) {
    constructor() : this(BookmarkFileManager())

    // Getter (no backing field): see BookmarkFileManager.logger — avoids a
    // Compose-emitted $stable reference the host's ComponentLogger lacks.
    private val logger get() = BossLogger.forComponent("BookmarkManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("BookmarkManager"))



    // One conflating channel per persisted file. Requests coalesce while a
    // save is running, and a single consumer keeps writes strictly ordered —
    // see saveCollectionsToFile.
    private val collectionSaveRequests = Channel<Unit>(Channel.CONFLATED)
    private val favoriteSaveRequests = Channel<Unit>(Channel.CONFLATED)

    /** Held so [close] can drain them before the scope goes away. */
    private val saveWorkers: List<Job> =
        listOf(
            scope.launch {
                for (unused in collectionSaveRequests) {
                    fileManager.saveCollections(_collections.value)
                }
            },
            scope.launch {
                for (unused in favoriteSaveRequests) {
                    fileManager.saveFavoriteWorkspaces(_favoriteWorkspaces.value)
                }
            },
        )

    private val _collections = MutableStateFlow<List<BookmarkCollection>>(emptyList())
    val collections: StateFlow<List<BookmarkCollection>> = _collections.asStateFlow()

    private val _favoriteWorkspaces = MutableStateFlow<List<FavoriteWorkspace>>(emptyList())
    val favoriteWorkspaces: StateFlow<List<FavoriteWorkspace>> = _favoriteWorkspaces.asStateFlow()

    init {
        // Load bookmarks from disk
        loadAllData()
    }

    /**
     * Load all bookmark data from disk.
     */
    private fun loadAllData() {
        scope.launch {
            try {
                // Load collections
                val onDisk = fileManager.loadCollections()

                // Repair id collisions before anything keys off an id. Files
                // written by older versions can hold two collections under one
                // id — see withDistinctIds.
                // Then settle which collection *is* Favorites, by the flag the panel
                // reads rather than by name - see withSingleFavorites.
                val loaded = withSingleFavorites(withDistinctIds(onDisk))

                // Ensure a "Favorites" collection exists
                val withFavorites =
                    if (loaded.none { it.isFavorite }) {
                        listOf(
                            BookmarkCollection(
                                id = newCollectionId(),
                                name = BookmarkCollection.FAVORITES_NAME,
                                isFavorite = true
                            )
                        ) + loaded
                    } else {
                        loaded
                    }

                // Merge rather than replace. This load is asynchronous, so a
                // mutation — a bulk import fired right after registration, say
                // — can land in _collections before it finishes. Assigning
                // would drop it from memory, and the mutation's own save would
                // then persist the post-load snapshot, losing it from disk too.
                //
                // Merging at collection level is not enough: an import goes into
                // a collection that usually already exists on disk (Favorites
                // always does), so a name-keyed filter would discard exactly the
                // bookmarks it is meant to protect. Union the bookmarks instead.
                val before =
                    _collections.getAndUpdate { pending ->
                        mergeLoadedWithPending(loaded = withFavorites, pending = pending)
                    }

                // Compare against what was on disk, not against the pre-merge
                // in-memory value: _collections starts empty, so `!= before` was
                // unconditionally true and every launch rewrote the file — an
                // iCloud sync upload per start for no state change.
                //
                // `onDisk` rather than the post-repair `loaded`, or an id
                // collision that withDistinctIds just fixed would live only in
                // memory and come back on the next launch.
                if (_collections.value != onDisk) {
                    saveCollectionsToFile()
                }

                // Load favorite workspaces
                // Same load-window race as collections: a workspace favourited
                // while this was in flight must survive the load.
                val loadedFavorites = fileManager.loadFavoriteWorkspaces()
                val favoritesBefore =
                    _favoriteWorkspaces.getAndUpdate { pending ->
                        val known = loadedFavorites.map { it.workspaceId }.toSet()
                        loadedFavorites + pending.filterNot { it.workspaceId in known }
                    }

                // Collections schedule a save after merging; favourites must too.
                // Otherwise a favourite added during the window survives in
                // memory, the load's stale read wins on disk, and it is gone on
                // next launch.
                if (_favoriteWorkspaces.value != loadedFavorites || favoritesBefore.isNotEmpty()) {
                    saveFavoriteWorkspacesToFile()
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Error loading bookmarks", error = e)
                // Same merge rule as the success path: never clobber a mutation
                // that arrived while the load was in flight.
                _collections.update { pending ->
                    if (pending.any { it.isFavorite }) {
                        pending
                    } else {
                        listOf(
                            BookmarkCollection(
                                id = newCollectionId(),
                                name = BookmarkCollection.FAVORITES_NAME,
                                isFavorite = true
                            )
                        ) + pending
                    }
                }
            }
        }
    }

    /**
     * Mint an id that cannot collide with one minted a moment ago.
     *
     * The generators in the shared types — `BookmarkCollection.generateId()` and
     * `Bookmark.generateId()` — are `"<prefix>-<epochMillis>"`, so anything
     * created inside the same millisecond gets the *same* id. A browser import
     * does that routinely: two collections back to back through
     * [createCollection], and bookmarks by the thousand.
     *
     * That is not a cosmetic clash. Ids are the identity every operation keys
     * off: [renameCollection], [deleteCollection], [removeBookmark] and
     * [moveBookmark] all act on whichever collides first,
     * [mergeLoadedWithPending] keys a map by collection id and so silently drops
     * one of a pair, and the panel uses ids to derive LazyColumn item keys — see
     * `keyedUniquely` for what a duplicate key does to Compose.
     */
    private fun mintId(prefix: String): String =
        "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

    private fun newCollectionId(): String = mintId("collection")

    internal fun newBookmarkId(): String = mintId("bookmark")

    /**
     * Re-id any of [incoming] whose id is already taken in [current], or repeated
     * inside the batch itself.
     *
     * Uniqueness has to hold at the point bookmarks are written, not only when
     * they are read back: callers hand us `Bookmark`s carrying the default
     * millisecond `Bookmark.generateId()`, and a browser import adds thousands in
     * a tight loop. Repairing on load alone would leave those duplicates live in
     * memory and on disk until the next launch — a window in which
     * [removeBookmark], [updateBookmark] and [moveBookmark] all resolve by id and
     * act on whichever matches first, the search provider publishes a duplicate
     * result id, and [mergeLoadedWithPending]'s `distinctBy { it.id }` drops one
     * of a colliding pair.
     *
     * Returns [incoming] itself when nothing collides, so the common path adds no
     * copy and an unchanged list still compares equal.
     */
    /**
     * Single-bookmark form of [withFreeBookmarkIds], without the set.
     *
     * Returns [bookmark] itself when its id is free, so the ordinary add path
     * allocates nothing. Kept separate rather than folded in because the batch
     * form needs a set to catch ids repeated *within* the batch, and one add
     * cannot repeat itself.
     */
    private fun withFreeId(
        bookmark: Bookmark,
        current: List<BookmarkCollection>,
    ): Bookmark {
        fun taken(id: String) = current.any { c -> c.bookmarks.any { it.id == id } }
        if (!taken(bookmark.id)) return bookmark

        var fresh = newBookmarkId()
        while (taken(fresh)) fresh = newBookmarkId()
        logger.warn(
            LogCategory.UI,
            "Re-assigned a bookmark id that was already in use",
            mapOf("suppliedId" to bookmark.id, "assignedId" to fresh),
        )
        return bookmark.copy(id = fresh)
    }

    private fun withFreeBookmarkIds(
        incoming: List<Bookmark>,
        current: List<BookmarkCollection>,
    ): List<Bookmark> {
        // Pre-sized past the 0.75 load factor: this holds one entry per bookmark
        // in the store, and a library of tens of thousands would otherwise rehash
        // its way up on the add path.
        val existing = current.sumOf { it.bookmarks.size }
        val taken = HashSet<String>(((existing + incoming.size) / 0.75f).toInt() + 1)
        current.forEach { collection -> collection.bookmarks.forEach { taken.add(it.id) } }

        // Built only once something actually collides, so the no-collision path
        // really does allocate nothing — `map` would have built and discarded a
        // full copy of the batch, which for an import is 20k elements. Note the
        // check cannot be a pre-scan of `taken` alone: two incoming bookmarks can
        // share an id that no *existing* bookmark holds, and that has to be caught
        // too, which is why `taken` accumulates as we go.
        var reIded = 0
        var rewritten: MutableList<Bookmark>? = null

        incoming.forEachIndexed { index, bookmark ->
            if (taken.add(bookmark.id)) {
                rewritten?.add(bookmark)
            } else {
                if (rewritten == null) {
                    rewritten = ArrayList<Bookmark>(incoming.size).apply {
                        addAll(incoming.subList(0, index))
                    }
                }
                reIded++
                // Checked, not asserted. A random suffix makes a second
                // collision vanishingly unlikely, but the premise of this whole
                // function is that an id generator's uniqueness claim is not
                // worth trusting — so claim it through `taken` like any other.
                var fresh = newBookmarkId()
                while (!taken.add(fresh)) fresh = newBookmarkId()
                rewritten.add(bookmark.copy(id = fresh))
            }
        }

        if (reIded > 0) {
            // Once per batch with a count, never per bookmark: an import re-ids by
            // the thousand. Logged at all because the host cannot observe this any
            // other way — addBookmark returns Unit, so from its side the symptom is
            // "the id I minted no longer resolves".
            //
            // May repeat if mutateCollections' transform re-runs on CAS
            // contention; that is rare and a duplicate info line is harmless.
            // warn, not info: the KDoc on BookmarkDataProviderImpl.addBookmark is
            // explicit that a host has no other way to see this, and that a later
            // remove/update with the supplied id will quietly fail to resolve.
            // Someone debugging "my delete did nothing" is far likelier to have
            // warn enabled than info.
            logger.warn(
                LogCategory.UI,
                "Re-assigned bookmark ids that were already in use",
                mapOf("count" to reIded.toString(), "batchSize" to incoming.size.toString()),
            )
        }
        return rewritten ?: incoming
    }

    /**
     * Return [collections] with exactly one collection carrying [BookmarkCollection.isFavorite],
     * where that is possible without inventing one.
     *
     * **The flag is the identity; the name is not.** Two representations of "this is
     * the Favorites collection" were in use: [loadAllData] tested the name, while
     * [getFavoritesCollection] and the panel test the flag. A `collections.json`
     * holding a collection *named* Favorites without the flag - which
     * `BookmarkCollection(id, name = "Favorites")` produces, since the flag defaults to
     * false - satisfied the first and not the second, so no Favorites section rendered
     * at all and the name-only collection sat in the ordinary list offering
     * "Delete Collection". Issue #10.
     *
     * The flag wins because it survives a rename: a user who renames Favorites to
     * "Starred" still has their Favorites collection, whereas under the name rule the
     * next load would decide Favorites was missing and prepend a second one.
     *
     * Two repairs, both minimal:
     *
     * - **Promote** the first collection named Favorites when nothing is flagged. It is
     *   what the user has been calling Favorites; giving it the flag is what makes the
     *   panel show it again.
     * - **Demote** any flagged collection after the first. Only the first is reachable:
     *   `find { it.isFavorite }` returns it and the collection list filters
     *   `!it.isFavorite`, so a second flagged collection is invisible *and* undeletable,
     *   since [deleteCollection] refuses to remove a flagged one.
     *
     * Names are never changed here, and a list that already holds exactly one flagged
     * collection is returned unchanged - identity included, so an untouched file
     * compares equal to what was loaded and no save is scheduled.
     */
    internal fun withSingleFavorites(collections: List<BookmarkCollection>): List<BookmarkCollection> {
        val flagged = collections.count { it.isFavorite }
        if (flagged == 1) return collections

        if (flagged == 0) {
            val promote = collections.indexOfFirst { it.name == BookmarkCollection.FAVORITES_NAME }
            if (promote < 0) return collections
            logger.warn(
                LogCategory.UI,
                "A collection named Favorites was not flagged as one; adopting it rather than adding a second",
                mapOf("id" to collections[promote].id),
            )
            return collections.toMutableList().also { it[promote] = it[promote].copy(isFavorite = true) }
        }

        logger.warn(
            LogCategory.UI,
            "More than one collection is flagged as Favorites; keeping the first",
            mapOf("count" to flagged.toString()),
        )
        var kept = false
        return collections.map { collection ->
            when {
                !collection.isFavorite -> collection
                !kept -> collection.also { kept = true }
                // Demoted rather than dropped: it holds the user's bookmarks, and now
                // that it is unflagged the panel lists it and it can be deleted.
                else -> collection.copy(isFavorite = false)
            }
        }
    }

    /**
     * [desired], or the first " (n)" variant of it that no collection in [taken] holds.
     *
     * Adds resolve their target with `indexOfFirst { it.name == … }`, so two collections
     * sharing a name means every add goes to whichever comes first and the other can
     * never be added to by name (#10). Nothing stopped that: [createCollection] appends
     * unconditionally and [renameCollection] did no uniqueness check.
     *
     * Suffixing is what a browser does on import, and it is visible in the panel, which
     * an ambiguity that silently misroutes bookmarks is not. Duplicates already on disk
     * are left alone - see [withDistinctIds], which logs them rather than renaming a
     * user's collections behind their back.
     */
    private fun uniqueName(desired: String, taken: List<BookmarkCollection>): String {
        val names = taken.mapTo(HashSet()) { it.name }
        if (desired !in names) return desired
        var suffix = 2
        while ("$desired ($suffix)" in names) suffix++
        return "$desired ($suffix)"
    }

    /**
     * Return [collections] with every collection id, and every bookmark id,
     * distinct — repairing what older versions already wrote to disk.
     *
     * Colliding ids are not hypothetical: see [newCollectionId] for how
     * millisecond-resolution ids collide, and note that `Bookmark.generateId()`
     * has the same shape. This runs on load so a file written before the fix
     * stops crashing the panel, rather than only new files being safe.
     *
     * The first holder of an id keeps it and later ones are renumbered, so the
     * repair touches as little as possible and is idempotent: the ids it hands
     * out are themselves unused, so a second load is a no-op and does not
     * rewrite the file.
     *
     * Bookmark ids are made unique across *all* collections, not just within
     * one. The search provider publishes `bookmark.id` as the result id, and
     * [findBookmarkForTab] returns an id pair that callers resolve globally.
     */
    internal fun withDistinctIds(collections: List<BookmarkCollection>): List<BookmarkCollection> {
        // Duplicate *names* are the same class of bug one level up and are not
        // repaired here — renaming a user's collection behind their back is worse
        // than the ambiguity. But addBookmark/addBookmarks resolve with
        // `indexOfFirst { it.name == … }`, so every add lands in whichever comes
        // first, and that is worth being able to see in a log. See issue #10.
        //
        // New ones can no longer be created: createCollection and renameCollection
        // both go through uniqueName. This is for files written before that.
        val duplicateNames =
            collections.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            logger.warn(
                LogCategory.UI,
                "Collections share a name; adds resolved by name will all go to the first",
                mapOf("names" to duplicateNames.joinToString()),
            )
        }

        val collectionIds = IdAllocator(collections.map { it.id })
        val bookmarkIds = IdAllocator(collections.flatMap { c -> c.bookmarks.map { it.id } })

        return collections.map { collection ->
            val collectionId = collectionIds.claim(collection.id)

            var bookmarksChanged = false
            val bookmarks =
                collection.bookmarks.map { bookmark ->
                    val bookmarkId = bookmarkIds.claim(bookmark.id)
                    if (bookmarkId == bookmark.id) {
                        bookmark
                    } else {
                        bookmarksChanged = true
                        bookmark.copy(id = bookmarkId)
                    }
                }

            when {
                collectionId != collection.id ->
                    collection.copy(id = collectionId, bookmarks = bookmarks)
                bookmarksChanged -> collection.copy(bookmarks = bookmarks)
                // Untouched collections are returned as-is so an unaffected file
                // compares equal to what was loaded and no save is scheduled.
                else -> collection
            }
        }
    }

    /**
     * Hands out ids that are distinct from each other *and* from every id the
     * input already held.
     *
     * Reserving the whole input up front is what keeps "the first holder of an
     * id keeps it" true. Probing only against the ids seen so far displaces a
     * legitimate later owner: for `["c", "c", "c-2"]` the duplicate `c` would
     * take `c-2`, and the third collection — which owns `c-2` on disk — would be
     * pushed to `c-2-2`. Reserved, it becomes `["c", "c-3", "c-2"]`, and a
     * second pass over that is a no-op.
     */
    private class IdAllocator(existing: Collection<String>) {
        /** Every id the input held, so renumbering never displaces its owner. */
        private val reserved = existing.toHashSet()

        // Sized past the 0.75 load factor: HashSet(n) resizes once on the way to
        // holding n elements, and this holds one entry per id in the file.
        private val used = HashSet<String>((existing.size / 0.75f).toInt() + 1)

        /**
         * Where to resume probing per base id.
         *
         * Without it each duplicate restarts at 2 and re-walks the suffixes
         * already handed out — quadratic in the size of a collided group, on the
         * startup path, for exactly the large-import files this repair targets.
         */
        private val nextSuffix = HashMap<String, Int>()

        fun claim(id: String): String {
            if (used.add(id)) return id

            var suffix = nextSuffix[id] ?: 2
            var candidate = "$id-$suffix"
            // `in reserved` is checked first and short-circuits, so a candidate
            // belonging to a later owner is skipped without being consumed.
            while (candidate in reserved || !used.add(candidate)) {
                suffix++
                candidate = "$id-$suffix"
            }
            nextSuffix[id] = suffix + 1
            return candidate
        }
    }

    /**
     * Apply [transform] to the favorite-workspace list atomically, saving only
     * if it actually changed. Favourites need the same treatment as
     * collections — see [mutateCollections].
     */
    private fun mutateFavorites(transform: (List<FavoriteWorkspace>) -> List<FavoriteWorkspace>) {
        val before = _favoriteWorkspaces.getAndUpdate(transform)
        if (_favoriteWorkspaces.value != before) saveFavoriteWorkspacesToFile()
    }

    /**
     * Apply [transform] to the collection list atomically, saving only if it
     * actually changed.
     *
     * `update` retries on conflict, so two mutations racing from different
     * threads can't lose one another the way a read-then-assign pair would.
     */
    private fun mutateCollections(transform: (List<BookmarkCollection>) -> List<BookmarkCollection>) {
        // getAndUpdate returns the exact pre-update value, so the comparison
        // can't be against a snapshot that another writer has since replaced.
        val before = _collections.getAndUpdate(transform)
        if (_collections.value != before) saveCollectionsToFile()
    }

    /**
     * Combine what was on disk with whatever landed while the load was running.
     *
     * Collections are matched by id, then Favorites flag, falling back to name for a pending
     * collection the loaded set knows under a different id. Bookmarks are
     * unioned and de-duplicated by id, so an import into an existing collection
     * survives instead of being replaced by the disk copy.
     *
     * Where both sides carry the same bookmark id, or disagree on a collection's
     * name, the *pending* value wins: it is the newer state, so preferring the
     * loaded copy would silently undo an edit, rename or delete made during the
     * window and then persist the reversion.
     *
     * KNOWN GAP — the one place this plugin's id-uniqueness invariant does not
     * hold. [withDistinctIds] repairs the on-disk list *before* this runs, and
     * [withFreeBookmarkIds] dedupes an incoming bookmark against the pre-load
     * in-memory list, so neither covers a bookmark added during the load window
     * whose id matches a *different* bookmark on disk. The `distinctBy` below
     * then keeps pending and silently drops the disk one.
     *
     * Left as-is deliberately. The policy above rests on "same id means same
     * bookmark", and from ids alone an edit made during the window (pending must
     * win, and re-iding it would duplicate the bookmark instead) is
     * indistinguishable from two genuinely different bookmarks that collided —
     * so any repair here would have to guess, and guessing wrong reverts a user
     * edit. Repairing the merge *result* would not help either: the drop happens
     * inside `distinctBy`, before a repair could see it. It needs a
     * caller-supplied id equal to a historical `bookmark-<epochMillis>`, and both
     * paths that mint ids now use random suffixes, so the window is
     * near-theoretical.
     */
    private fun mergeLoadedWithPending(
        loaded: List<BookmarkCollection>,
        pending: List<BookmarkCollection>,
    ): List<BookmarkCollection> {
        if (pending.isEmpty()) return loaded

        val merged = LinkedHashMap<String, BookmarkCollection>()
        loaded.forEach { merged[it.id] = it }

        // Snapshotted before any pending is folded in, and each entry is claimed
        // at most once. The name fallback below is for "a pending collection the
        // loaded set knows under a different id" — it must only ever match
        // something that came from disk.
        //
        // Without this it also matched pendings already added to `merged`, so two
        // same-named collections created during the load window collapsed into
        // one and the second silently replaced the first. That is reachable: two
        // createCollection("Twin") calls, or a browser import creating two
        // like-named folders, while the initial load is still in flight.
        // Claim-once matters for the same reason — otherwise two pendings both
        // match the one loaded collection of that name and collapse anyway.
        val unclaimedFromDisk = HashSet(merged.keys)

        pending.forEach { p ->
            val match =
                merged[p.id]
                    ?: merged.values.firstOrNull {
                        p.isFavorite && it.isFavorite && it.id in unclaimedFromDisk
                    }
                    ?: merged.values.firstOrNull { it.id in unclaimedFromDisk && it.name == p.name }
            if (match == null) {
                merged[p.id] = p
            } else {
                unclaimedFromDisk.remove(match.id)
                // Pending wins on conflict. It is the newer state: an edit, a
                // rename or a removal made during the window would otherwise be
                // reverted by the disk copy — and then written back. Bookmarks
                // are unioned so nothing loaded is dropped, but where both sides
                // hold the same id the pending version is kept.
                merged[match.id] =
                    p.copy(
                        id = match.id,
                        // A pending import created before load has not renamed the
                        // on-disk Favorites. Match its flag even when the user has
                        // called the saved collection Starred, and keep that name.
                        name = if (
                            p.id != match.id && p.isFavorite && match.isFavorite &&
                            p.name == BookmarkCollection.FAVORITES_NAME
                        ) match.name else p.name,
                        isFavorite = p.isFavorite || match.isFavorite,
                        bookmarks = (p.bookmarks + match.bookmarks).distinctBy { it.id },
                    )
            }
        }
        return withSingleFavorites(merged.values.toList())
    }

    // ==================== Bookmark Operations ====================

    /**
     * Add a bookmark to a collection.
     */
    fun addBookmark(collectionName: String, bookmark: Bookmark) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.name == collectionName }
            if (index < 0) {
                current
            } else {
                // Deliberately not withFreeBookmarkIds: that builds a HashSet over
                // every bookmark in the store, and this is the "user clicked the
                // star" path, re-run on every CAS retry. Scanning for one id costs
                // the same O(library) walk without the allocation.
                val safe = withFreeId(bookmark, current)
                current.toMutableList().also { it[index] = it[index].addBookmark(safe) }
            }
        }
    }

    /**
     * Add many bookmarks to a collection in one operation, creating the
     * collection if it does not already exist.
     *
     * The point of this over a loop of [addBookmark] is that it saves **once**.
     * Adding N bookmarks individually queues N full rewrites of
     * collections.json — for an import of a few hundred entries that is both
     * slow and, before the write became atomic, a way to lose the file.
     *
     * There is now a second reason to prefer it. [withFreeBookmarkIds] scans
     * every bookmark in the store to find a free id, so it costs O(library) per
     * call — once for a batch here, but N times for a loop of [addBookmark],
     * which makes importing into a large library O(N x library). That cost is
     * per *attempt* rather than per call, since [mutateCollections] re-runs its
     * transform if the underlying CAS loses a race — and on a retry the minting
     * is re-paid too, which for a fully-colliding batch means re-drawing every
     * id from `SecureRandom`. Correctness is unaffected (a retry just produces
     * different fresh ids); this is the one place the retry gets expensive.
     */
    fun addBookmarks(collectionName: String, bookmarks: List<Bookmark>) {
        if (bookmarks.isEmpty()) return

        mutateCollections { current ->
            // createCollection appends unconditionally rather than get-or-create,
            // so resolve by name first or an import into an existing folder would
            // leave a duplicate empty collection behind.
            val index = current.indexOfFirst { it.name == collectionName }

            // This is the import path, so it is where millisecond-resolution
            // caller ids collide in bulk — see withFreeBookmarkIds.
            val safeBookmarks = withFreeBookmarkIds(bookmarks, current)

            if (index >= 0) {
                // One append of the whole batch, not a fold of per-item copies.
                // getAndUpdate re-runs this transform on CAS contention, and
                // BookmarkCollection.addBookmark copies the list each call — so
                // folding would be O(n²) copies, re-paid per retry, on whatever
                // thread the host called from. A browser import is 10-20k
                // entries, which is where that stops being theoretical.
                current.toMutableList().also {
                    it[index] = it[index].copy(bookmarks = it[index].bookmarks + safeBookmarks)
                }
            } else {
                current +
                    BookmarkCollection(
                        // Not the default millisecond id: a bulk import creates
                        // several collections in a row — see newCollectionId.
                        id = newCollectionId(),
                        name = collectionName,
                        bookmarks = safeBookmarks,
                        // Without this, importing into "Favorites" would create a
                        // collection named Favorites that getFavoritesCollection()
                        // — which matches on the flag — cannot find, and that
                        // deleteCollection would happily remove.
                        //
                        // Only when there is no Favorites collection already, though: if
                        // the user renamed theirs, an import into "Favorites" would
                        // otherwise flag a second one, and a second flagged collection is
                        // invisible in the panel and undeletable (#10).
                        isFavorite = collectionName == BookmarkCollection.FAVORITES_NAME &&
                            current.none { it.isFavorite },
                    )
            }
        }
    }

    /**
     * Remove a bookmark from a collection.
     */
    fun removeBookmark(collectionId: String, bookmarkId: String) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            if (index < 0) {
                current
            } else {
                current.toMutableList().also { it[index] = it[index].removeBookmark(bookmarkId) }
            }
        }
    }

    /**
     * Does this bookmark stand for [tab]?
     *
     * Type plus target — the URL or file path — is the identity. The title is
     * deliberately *not* part of it for those tabs: [renameBookmark] lets the
     * user relabel a bookmark, and comparing titles would make a renamed
     * bookmark stop matching the very tab it was saved from, so the star would
     * read "not bookmarked" and re-bookmarking would duplicate it.
     *
     * A tab with neither a URL nor a file path — a terminal — has no other
     * identity to compare, so there the title is still all there is to go on.
     * Renaming such a bookmark does detach it from its tab; that is the
     * pre-existing behaviour for terminals, not something the rename introduced.
     *
     * Blank counts as absent, not just null: this plugin already reads `""` as
     * "no target" ([BookmarksViewModel.openTab] skips an editor tab whose
     * filePath is empty), so treating it as a real one would make every blank
     * tab of a type identical — bookmark one and the star lights up on all of
     * them, and un-starring any would remove another's bookmark. Only [tab] is
     * checked because the equality below already forces the bookmark's own
     * target to be the same string.
     */
    private fun Bookmark.matches(tab: TabConfig): Boolean {
        if (tabConfig.type != tab.type) return false
        val hasTarget = !tab.url.isNullOrBlank() || !tab.filePath.isNullOrBlank()
        return tabConfig.url == tab.url &&
            tabConfig.filePath == tab.filePath &&
            (hasTarget || tabConfig.title == tab.title)
    }

    /**
     * Check if a tab is already bookmarked in any collection.
     */
    fun isTabBookmarked(tabConfig: TabConfig): Boolean {
        return _collections.value.any { collection ->
            collection.bookmarks.any { it.matches(tabConfig) }
        }
    }

    /**
     * Find which collection and bookmark ID contain this tab.
     * Returns Pair(collectionId, bookmarkId) or null if not found.
     */
    fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? {
        _collections.value.forEach { collection ->
            collection.bookmarks.firstOrNull { it.matches(tabConfig) }?.let { bookmark ->
                return Pair(collection.id, bookmark.id)
            }
        }
        return null
    }

    /**
     * Update a bookmark in a collection.
     */
    fun updateBookmark(collectionId: String, bookmark: Bookmark) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            if (index < 0) {
                current
            } else {
                current.toMutableList().also { it[index] = it[index].updateBookmark(bookmark) }
            }
        }
    }

    /**
     * Retitle the bookmark [bookmarkId] in [collectionId].
     *
     * Resolved by id inside the mutation rather than taking a whole [Bookmark]
     * from the caller: the panel holds the snapshot it rendered before the
     * rename dialog opened, and writing that back would undo a
     * [markBookmarkAsAccessed] that landed while the dialog was up.
     *
     * A blank or unchanged title is a no-op, so it does not schedule a save.
     */
    fun renameBookmark(collectionId: String, bookmarkId: String, newTitle: String) {
        val title = newTitle.trim()
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            val bookmark = current.getOrNull(index)?.findBookmark(bookmarkId)

            if (index < 0 || bookmark == null || title.isEmpty() || bookmark.tabConfig.title == title) {
                current
            } else {
                current.toMutableList().also {
                    it[index] = it[index].updateBookmark(
                        bookmark.copy(tabConfig = bookmark.tabConfig.copy(title = title))
                    )
                }
            }
        }
    }

    /**
     * Move a bookmark from one collection to another.
     */
    fun moveBookmark(bookmarkId: String, fromCollectionId: String, toCollectionId: String) {
        mutateCollections { current ->
            val fromIndex = current.indexOfFirst { it.id == fromCollectionId }
            val toIndex = current.indexOfFirst { it.id == toCollectionId }
            val bookmark = current.getOrNull(fromIndex)?.findBookmark(bookmarkId)

            if (fromIndex < 0 || toIndex < 0 || bookmark == null) {
                current
            } else {
                current.toMutableList().also {
                    it[fromIndex] = it[fromIndex].removeBookmark(bookmarkId)
                    // No id check needed only because bookmark ids are unique
                    // across every collection — withDistinctIds repairs that on
                    // load and withFreeBookmarkIds/withFreeId hold it on write.
                    // Relax either and this call starts aliasing a bookmark in
                    // the destination.
                    it[toIndex] = it[toIndex].addBookmark(bookmark)
                }
            }
        }
    }

    /**
     * Mark a bookmark as accessed (updates lastAccessedAt timestamp).
     */
    fun markBookmarkAsAccessed(collectionId: String, bookmarkId: String) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            val bookmark = current.getOrNull(index)?.findBookmark(bookmarkId)

            if (index < 0 || bookmark == null) {
                current
            } else {
                current.toMutableList().also {
                    it[index] = it[index].updateBookmark(bookmark.markAsAccessed())
                }
            }
        }
    }

    // ==================== Collection Operations ====================

    /**
     * Create a new bookmark collection.
     */
    fun createCollection(name: String): BookmarkCollection {
        // Asking for "Favorites" means the Favorites collection, so hand back the one
        // that exists rather than appending a second collection by that name which the
        // panel would never show as Favorites (#10). When there is none, the new one is
        // flagged, because a collection called Favorites without the flag is precisely
        // the stuck state withSingleFavorites exists to repair.
        val wantsFavorites = name == BookmarkCollection.FAVORITES_NAME
        var created = BookmarkCollection(id = newCollectionId(), name = name, isFavorite = wantsFavorites)
        mutateCollections { current ->
            // Inside the transform, so the name is unique against the list this actually
            // appends to - mutateCollections re-runs on CAS contention, and two imports
            // racing would otherwise both resolve the same free name.
            val existingFavorites = current.find { it.isFavorite }
            if (wantsFavorites && existingFavorites != null) {
                created = existingFavorites
                current
            } else {
                created = created.copy(name = uniqueName(name, current))
                current + created
            }
        }
        return created
    }

    /**
     * Delete a bookmark collection.
     *
     * Cannot delete the special "Favorites" collection.
     */
    fun deleteCollection(collectionId: String) {
        mutateCollections { current ->
            // Cannot delete "Favorites" collection
            val collection = current.find { it.id == collectionId }
            if (collection == null || collection.isFavorite) {
                current
            } else {
                current.filter { it.id != collectionId }
            }
        }
    }

    /**
     * Rename a bookmark collection.
     */
    fun renameCollection(collectionId: String, newName: String) {
        mutateCollections { current ->
            val index = current.indexOfFirst { it.id == collectionId }
            if (index < 0) {
                current
            } else {
                // Unique against the others, so a rename cannot create the ambiguity
                // adds resolve by name into (#10). The collection keeps whatever flag it
                // has: Favorites renamed to "Starred" is still Favorites.
                val name = uniqueName(newName, current.filterNot { it.id == collectionId })
                current.toMutableList().also { it[index] = it[index].copy(name = name) }
            }
        }
    }

    /**
     * Get the "Favorites" collection.
     *
     * Normally the real one from [collections]. If none is flagged — which
     * [loadAllData] only prevents when the load has finished — this returns a
     * detached placeholder that is *not* in [collections] and is not persisted.
     * Each such call mints a fresh id, so two calls in that state do not agree
     * on one; treat the result as a display fallback, not an identity.
     */
    fun getFavoritesCollection(): BookmarkCollection {
        return _collections.value.find { it.isFavorite }
            ?: BookmarkCollection(
                id = newCollectionId(),
                name = BookmarkCollection.FAVORITES_NAME,
                isFavorite = true
            )
    }

    // ==================== Favorite Workspace Operations ====================

    /**
     * Add a workspace to favorites.
     */
    fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) {
        mutateFavorites { current ->
            if (current.any { it.workspaceId == workspaceId }) {
                current
            } else {
                current + FavoriteWorkspace.create(workspaceId, workspaceName)
            }
        }
    }

    /**
     * Remove a workspace from favorites.
     */
    fun removeFavoriteWorkspace(workspaceId: String) {
        mutateFavorites { current -> current.filter { it.workspaceId != workspaceId } }
    }

    /**
     * Check if a workspace is favorited.
     */
    fun isFavorite(workspaceId: String): Boolean {
        return _favoriteWorkspaces.value.any { it.workspaceId == workspaceId }
    }

    // ==================== Persistence ====================

    /**
     * Ask for collections to be written.
     *
     * Every mutating operation calls this, so a burst — a bulk import, say —
     * would otherwise queue one full serialise-and-write of an ever-growing
     * document per mutation: O(n²) bytes for a linear import, where only the
     * last write's content actually matters.
     *
     * A conflating channel gives ordering and coalescing together. Requests
     * that arrive while a save is in flight collapse into one, and the single
     * consumer reads state at write time, so the file always converges on the
     * latest snapshot. Five hundred mutations cost one or two writes.
     *
     * Note this only orders writers *inside this instance*. Two host processes,
     * or a plugin reload, are made safe by the atomic move in
     * [BookmarkFileManager] — not by this. Don't remove the rename on the
     * grounds that the writes look serialised here.
     */
    private fun saveCollectionsToFile() {
        collectionSaveRequests.trySend(Unit)
    }

    /** Ask for favorite workspaces to be written. See [saveCollectionsToFile]. */
    private fun saveFavoriteWorkspacesToFile() {
        favoriteSaveRequests.trySend(Unit)
    }



    /**
     * Drain pending saves and stop the workers.
     *
     * Without this the two `for (request in channel)` loops run forever on
     * shared Dispatchers.Default threads: they keep this manager, its state
     * flows and the plugin classloader reachable, so a dynamic plugin can never
     * be unloaded, and each reload adds another pair of writers competing over
     * the same file from its own stale snapshot.
     *
     * Closing a channel lets its loop finish the requests already queued and
     * then exit, so joining here is also the flush-on-shutdown that conflation
     * would otherwise make easy to lose: a mutation whose trySend lands while a
     * save is in flight is only written by the *next* drain.
     */
    suspend fun close() {
        collectionSaveRequests.close()
        favoriteSaveRequests.close()
        try {
            saveWorkers.joinAll()
        } finally {
            // In a finally because dispose() calls this under withTimeout: when
            // the timeout fires — the wedged-save case the timeout exists for —
            // joinAll throws and a trailing cancel() would never run, leaking
            // exactly what close() was written to release. cancel() does not
            // suspend, so it is safe here, and it gives a stuck worker a
            // cancellation point to die at.
            scope.cancel()
        }
    }
}
