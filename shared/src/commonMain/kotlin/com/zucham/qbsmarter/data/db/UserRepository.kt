package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.zucham.qbsmarter.db.QbsmarterDatabase
import com.zucham.qbsmarter.domain.user.UserProfile
import com.zucham.qbsmarter.util.currentTimeMillis
import com.zucham.qbsmarter.util.generateUuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * User profile (account) management.
 *
 * The app always has at least one profile. On first launch [bootstrap] creates
 * one with `display_name = NULL` (UI shows "New profile" fallback). The active
 * profile is tracked in [com.zucham.qbsmarter.db.AppStateQueries] and exposed
 * via [observeActiveId] / [activeId]; switching is one [setActive] call.
 *
 * **Last-profile-deletion** ([deleteProfile]) is safe: if the deleted profile
 * was the only one, a fresh empty profile is created in the same transaction
 * and made active. Callers always observe [observeActive] and so naturally
 * follow the new active profile without crashing.
 *
 * Cascading delete: removing a profile cascades through cubes/solves/settings
 * via the FK ON DELETE CASCADE clauses on each child table.
 */
class UserRepository(
    private val db: QbsmarterDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Idempotent first-run bootstrap. After this returns:
     *  • There is at least one row in `users`.
     *  • `app_state.active_user_id` points to a real row.
     * Returns the active profile post-bootstrap.
     */
    fun bootstrap(): UserProfile = db.transactionWithResult {
        // Make sure the singleton app_state row exists.
        db.appStateQueries.ensureRow()

        val users = db.usersQueries.selectAll().executeAsList()
        val active = db.appStateQueries.selectActiveUserId()
            .executeAsOneOrNull()?.active_user_id

        // Three cases:
        //   (a) No users → create one and make it active.
        //   (b) Users exist, active is null/stale → pick the first and store it.
        //   (c) Users exist and active is valid → no-op.
        val pickedId = when {
            users.isEmpty() -> {
                val id = generateUuid()
                val now = currentTimeMillis()
                db.usersQueries.insert(id, null, now)
                db.appStateQueries.setActiveUserId(id)
                id
            }
            active == null || users.none { it.id == active } -> {
                val id = users.first().id
                db.appStateQueries.setActiveUserId(id)
                id
            }
            else -> active
        }
        val row = db.usersQueries.selectById(pickedId).executeAsOne()
        UserProfile(row.id, row.display_name, row.created_at)
    }

    /**
     * Active profile id, snapshot. Use [observeActiveId] for reactive consumers.
     * Returns null only in the brief window before [bootstrap] has been called
     * for the first time on a fresh install.
     */
    fun activeId(): String? =
        db.appStateQueries.selectActiveUserId()
            .executeAsOneOrNull()?.active_user_id

    /** Reactive active-profile id. Emits null while the singleton row is missing. */
    fun observeActiveId(): Flow<String?> =
        db.appStateQueries.selectActiveUserId().asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { it?.active_user_id }

    /**
     * Reactive snapshot of the currently active profile.
     *
     * Implemented as a [flatMapLatest] from the active id into a reactive
     * query on that specific row, so any UPDATE on the row (display_name,
     * created_at) fans out to every downstream consumer of
     * [activeProfile.profile]. A naive map-of-`selectById` would only
     * re-emit on id change, missing rename events.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActive(): Flow<UserProfile?> =
        observeActiveId().flatMapLatest { id ->
            if (id == null) flowOf(null)
            else db.usersQueries.selectById(id)
                .asFlow()
                .mapToOneOrNull(ioDispatcher)
                .map { row ->
                    row?.let { UserProfile(it.id, it.display_name, it.created_at) }
                }
        }

    /** Reactive list of all profiles, ordered by created_at ascending. */
    fun observeAll(): Flow<List<UserProfile>> =
        db.usersQueries.selectAll().asFlow().mapToList(ioDispatcher)
            .map { rows -> rows.map { UserProfile(it.id, it.display_name, it.created_at) } }

    /** Snapshot of all profiles. Used by export. */
    fun snapshotAll(): List<UserProfile> =
        db.usersQueries.selectAll().executeAsList()
            .map { UserProfile(it.id, it.display_name, it.created_at) }

    /** Snapshot one profile. */
    fun byId(id: String): UserProfile? =
        db.usersQueries.selectById(id).executeAsOneOrNull()
            ?.let { UserProfile(it.id, it.display_name, it.created_at) }

    /** Persist a new display name. Empty string normalises to null. */
    fun setDisplayName(id: String, displayName: String?) {
        val normalised = displayName?.trim()?.ifEmpty { null }
        db.usersQueries.updateDisplayName(displayName = normalised, id = id)
    }

    /**
     * Create a new profile with a fresh UUID. Optional [displayName] (null
     * = "New profile" fallback in the UI). Returns the created profile.
     * Does NOT change the active profile; call [setActive] explicitly.
     */
    fun createProfile(displayName: String? = null): UserProfile = db.transactionWithResult {
        val id = generateUuid()
        val now = currentTimeMillis()
        val normalised = displayName?.trim()?.ifEmpty { null }
        db.usersQueries.insert(id, normalised, now)
        UserProfile(id, normalised, now)
    }

    /**
     * Insert an existing profile (with a known id and createdAt). Used by
     * the import flow to restore a backup that contains a foreign profile
     * id. Returns true if inserted, false if a row with this id already
     * existed (caller decides whether to merge or overwrite).
     */
    fun insertExisting(profile: UserProfile): Boolean = db.transactionWithResult {
        val existing = db.usersQueries.selectById(profile.id).executeAsOneOrNull()
        if (existing != null) return@transactionWithResult false
        db.usersQueries.insert(profile.id, profile.displayName, profile.createdAt)
        true
    }

    /**
     * Delete [id]. If this was the active profile, the next-most-recent
     * profile becomes active. If it was the *only* profile, a fresh empty
     * one is created and made active – the contract that "a profile always
     * exists" is enforced here, not at every call site.
     *
     * Cubes/solves/settings cascade via FK.
     */
    fun deleteProfile(id: String) {
        db.transaction {
            db.usersQueries.deleteById(id)
            // Re-establish the "always one profile" invariant.
            val remaining = db.usersQueries.selectAll().executeAsList()
            if (remaining.isEmpty()) {
                val newId = generateUuid()
                val now = currentTimeMillis()
                db.usersQueries.insert(newId, null, now)
                db.appStateQueries.setActiveUserId(newId)
            } else {
                // If the active pointer was nulled by FK ON DELETE SET NULL
                // (which fires when the active profile itself was deleted),
                // pick the most recently created remaining profile.
                val active = db.appStateQueries.selectActiveUserId()
                    .executeAsOne().active_user_id
                if (active == null || remaining.none { it.id == active }) {
                    db.appStateQueries.setActiveUserId(remaining.last().id)
                }
            }
        }
    }

    /**
     * Switch the active profile to [id]. Throws if the id is unknown – it
     * would leave the system in a confusing state where flows emit null
     * profiles indefinitely.
     */
    fun setActive(id: String) {
        val exists = db.usersQueries.selectById(id).executeAsOneOrNull() != null
        require(exists) { "Cannot setActive($id): no such profile" }
        db.appStateQueries.setActiveUserId(id)
    }
}
