package com.zucham.qbsmarter.data.profile

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.data.db.UserRepository
import com.zucham.qbsmarter.domain.user.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * The "active profile" Koin singleton. ViewModels and other long-lived
 * objects subscribe to [id] / [profile] instead of caching a userId at
 * construction. When the user switches profiles, every consumer reactively
 * follows – no VM rebuild needed, no stale userIds, no crashes.
 *
 * Why a separate class (instead of just calling `userRepo.observeActiveId()`
 * from each consumer): we want a single shared StateFlow (so each subscriber
 * doesn't open its own DB observer) AND a synchronous [idSnapshot] for the
 * occasional non-reactive call site (e.g. an event handler that needs the
 * userId now).
 *
 * [ensureBootstrapped] is called once from the Application class so that by
 * the time any UI composes, [id] already has a real value rather than the
 * brief null window during a fresh-install bootstrap.
 */
class ActiveProfile(
    private val userRepo: UserRepository,
    scope: CoroutineScope,
) {
    private val log = Logger.withTag("ActiveProfile")

    /**
     * Reactive id of the active profile. Emits null only in the very first
     * frames before [ensureBootstrapped] runs; after that it always has a
     * value (the bootstrap contract guarantees a profile + active pointer
     * exists).
     */
    val id: StateFlow<String?> = userRepo.observeActiveId()
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, userRepo.activeId())

    /** Reactive snapshot of the active [UserProfile]. Same null window as [id]. */
    val profile: StateFlow<UserProfile?> = userRepo.observeActive()
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * One-shot snapshot. Returns null only before [ensureBootstrapped] has
     * run; after bootstrap, never returns null.
     */
    fun idSnapshot(): String? = id.value

    /**
     * Idempotent first-run bootstrap. Called from Application.onCreate
     * (Android) before any UI is composed. Subsequent calls are no-ops
     * (UserRepository.bootstrap is itself idempotent).
     */
    fun ensureBootstrapped(): UserProfile = userRepo.bootstrap()

    /**
     * Switch to [newId]. The active-profile flow updates immediately; every
     * subscriber will recompose on the next frame.
     */
    fun switchTo(newId: String) {
        log.d { "switchTo($newId)" }
        userRepo.setActive(newId)
    }
}
