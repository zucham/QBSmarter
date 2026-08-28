package com.zucham.qbsmarter.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.data.ble.ConnectionOrchestrator
import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.db.DevicesRepository
import com.zucham.qbsmarter.data.db.PairedCube
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.data.db.SolveRow
import com.zucham.qbsmarter.data.db.SolvesRepository
import com.zucham.qbsmarter.data.db.UserRepository
import com.zucham.qbsmarter.data.profile.ActiveProfile
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.user.UserProfile
import com.zucham.qbsmarter.ui.i18n.AppLanguage
import com.zucham.qbsmarter.ui.i18n.LocaleController
import com.zucham.qbsmarter.ui.theme.ThemeController
import com.zucham.qbsmarter.ui.theme.ThemeMode
import com.zucham.qbsmarter.ui.theme.ThemeSeed
import com.zucham.qbsmarter.util.FileExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings VM. Surfaces toggles + theme + language, profile management,
 * and import/export.
 *
 * **Per-profile settings.** Every read/write goes through
 * [SettingsRepository] keyed on the *current* active profile (not a userId
 * captured at construction). Switching profiles in the UI flips every
 * observed flow without a VM rebuild.
 *
 * **Schema versioning** lives in the export envelope. The current export
 * schema is **v1** (reset for the public release after pre-release builds
 * cycled through v1/v2/v3 with a different envelope shape). The constant
 * [EXPORT_SCHEMA_VERSION] near the bottom of this file is the source of
 * truth – bump it (and add a clearly-named migration path) if the schema
 * ever changes again. The import side rejects bundles whose
 * `schemaVersion` doesn't match.
 */
class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val solvesRepo: SolvesRepository,
    private val userRepo: UserRepository,
    private val devicesRepo: DevicesRepository,
    val themeController: ThemeController,
    val localeController: LocaleController,
    private val fileExporter: FileExporter,
    /**
     * Long-lived app-singleton scope (the same one Koin uses for
     * ConnectionOrchestrator). Import runs here instead of [viewModelScope]
     * so an Activity recreate triggered mid-import (locale/theme change
     * cascading from imported settings) doesn't cancel the import
     * coroutine and leave the DB in a partial state.
     */
    private val appScope: CoroutineScope,
    private val activeProfile: ActiveProfile,
    private val cache: AppCache,
    private val orchestrator: ConnectionOrchestrator,
) : ViewModel() {

    private val log = Logger.withTag("SettingsVM")

    private val _statusMessage = MutableStateFlow<ImportExportStatus?>(null)

    /**
     * Last status from the most recent Import/Export action.
     *
     * Exposed as a structured [ImportExportStatus] (rather than a raw
     * String) because the actual user-visible text needs to be looked up
     * via `stringResource` – only Composable code has access to that, so
     * the VM can't pre-localise here. The screen resolves the variant to
     * a translated message and renders it.
     */
    val statusMessage: StateFlow<ImportExportStatus?> = _statusMessage.asStateFlow()

    /** Currently active profile, surfaced to the UI. */
    val user: StateFlow<UserProfile?> = activeProfile.profile

    /** All profiles for the profile-list UI. */
    val allProfiles: StateFlow<List<UserProfile>> = cache.allProfiles

    /**
     * Active profile's settings map (cached). Used by the switch rows on
     * the Settings screen so they reflect the active profile reactively.
     */
    val cacheSettings: StateFlow<Map<String, String>> = cache.settings

    // -- Settings accessors (always operate on the active profile) -------

    fun setBool(key: String, value: Boolean) {
        val uid = activeProfile.idSnapshot() ?: return
        settingsRepo.setBool(uid, key, value)
    }

    fun setInt(key: String, value: Int) {
        val uid = activeProfile.idSnapshot() ?: return
        settingsRepo.setInt(uid, key, value)
    }

    fun setSeed(seed: ThemeSeed) = themeController.setSeed(seed)
    fun setMode(mode: ThemeMode) = themeController.setMode(mode)
    fun setLanguage(language: AppLanguage) = localeController.setLanguage(language)

    // -- Profile management ----------------------------------------------

    /**
     * Create a new profile and switch to it. The new profile starts with
     * default settings (no rows yet, so getBool returns the typed defaults).
     * If [displayName] is null/blank, the UI shows "New profile" via the
     * fallback in [resolveDisplayName] below.
     *
     * Switching profiles always disconnects the active cube first: the
     * cube belongs conceptually to the previous profile, and the Solve VM
     * observes BLE state and aborts to IDLE on disconnect – so this single
     * line cleans up both the BLE link and any in-flight solve state.
     */
    fun createProfile(displayName: String? = null) {
        orchestrator.disconnect()
        val created = userRepo.createProfile(displayName)
        activeProfile.switchTo(created.id)
    }

    /** Switch to an existing profile. Disconnects any active cube first. */
    fun switchTo(id: String) {
        if (id == activeProfile.idSnapshot()) return
        orchestrator.disconnect()
        activeProfile.switchTo(id)
    }

    /** Rename a profile. */
    fun renameProfile(id: String, displayName: String) {
        userRepo.setDisplayName(id, displayName)
    }

    /**
     * Delete a profile. If the deleted profile was the active one,
     * [UserRepository.deleteProfile] picks a new active. If it was the
     * last profile, a fresh empty one is auto-created. Either way the
     * active-profile flow updates and consumers reactively follow.
     *
     * If the deleted profile is the current active one, we also force
     * a disconnect so the Solve VM's connection-loss handling resets
     * any in-flight solve to IDLE before the new active profile takes
     * over – same flow as [switchTo].
     */
    fun deleteProfile(id: String) {
        if (id == activeProfile.idSnapshot()) orchestrator.disconnect()
        userRepo.deleteProfile(id)
    }

    // -- Export / Import -------------------------------------------------

    fun exportAll() {
        viewModelScope.launch {
            val activeId = activeProfile.idSnapshot()
            if (activeId == null) {
                _statusMessage.value = ImportExportStatus.NoActiveProfile
                return@launch
            }
            val profiles = userRepo.snapshotAll()
            val exportProfiles = profiles.map { p ->
                ExportProfile(
                    id = p.id,
                    displayName = p.displayName,
                    createdAt = p.createdAt,
                    settings = settingsRepo.snapshot(p.id),
                    solves = solvesRepo.snapshotAllForUser(p.id).map(::toExportSolve),
                    cubes = devicesRepo.snapshotAllForUser(p.id).map(::toExportCube),
                )
            }
            val bundle = ExportBundle(
                schemaVersion = EXPORT_SCHEMA_VERSION,
                activeProfileId = activeId,
                profiles = exportProfiles,
            )
            val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
            val bytes = json.encodeToString(ExportBundle.serializer(), bundle).encodeToByteArray()
            val ok = fileExporter.saveFile("qbsmarter-backup.json", "application/json", bytes)
            _statusMessage.value =
                if (ok) ImportExportStatus.Exported else ImportExportStatus.ExportCancelled
        }
    }

    /**
     * Export a single profile by id. Same envelope shape as [exportAll]
     * (so the importer can consume both with one code path), but
     * `profiles` contains exactly one entry. The `activeProfileId`
     * is set to the exported profile so a subsequent import on a fresh
     * device makes that profile the active one.
     *
     * Filename embeds a sanitised version of the display name to make
     * it easy to tell exports apart on disk when the user has multiple
     * profiles. Falls back to the profile id if no display name is set.
     */
    fun exportProfile(id: String) {
        viewModelScope.launch {
            val profile = userRepo.snapshotAll().firstOrNull { it.id == id }
            if (profile == null) {
                _statusMessage.value = ImportExportStatus.ProfileNotFound
                return@launch
            }
            val exportProfile = ExportProfile(
                id = profile.id,
                displayName = profile.displayName,
                createdAt = profile.createdAt,
                settings = settingsRepo.snapshot(profile.id),
                solves = solvesRepo.snapshotAllForUser(profile.id).map(::toExportSolve),
                cubes = devicesRepo.snapshotAllForUser(profile.id).map(::toExportCube),
            )
            val bundle = ExportBundle(
                schemaVersion = EXPORT_SCHEMA_VERSION,
                activeProfileId = profile.id,
                profiles = listOf(exportProfile),
            )
            val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
            val bytes = json.encodeToString(ExportBundle.serializer(), bundle).encodeToByteArray()
            val safeName = profile.displayName
                ?.takeIf { it.isNotBlank() }
                ?.replace(FILENAME_UNSAFE_CHARS, "_")
                ?: profile.id.take(8)
            val ok = fileExporter.saveFile(
                "qbsmarter-$safeName.json",
                "application/json",
                bytes,
            )
            _statusMessage.value =
                if (ok) ImportExportStatus.Exported else ImportExportStatus.ExportCancelled
        }
    }

    /**
     * Total solves recorded under [profileId]. Used by the per-profile
     * settings dialog to show a quick summary alongside the rename
     * field – partly as confirmation that the user is editing the
     * right profile, and partly to set context for the export action
     * ("you're about to export 47 solves" feels different from "0").
     */
    fun solveCountFor(profileId: String): Int =
        solvesRepo.snapshotAllForUser(profileId).size

    /**
     * Import a previously-exported bundle. Merges with existing data:
     *   • For each profile in the bundle that EXISTS locally (matched by
     *     id), settings are overwritten and solves/cubes are merged.
     *   • For each profile in the bundle that DOESN'T exist locally, a
     *     fresh row is created with the bundle's id/createdAt.
     *   • Local profiles that aren't in the bundle are left untouched.
     * The bundle's [activeProfileId] is honoured if it resolves to a
     * profile that now exists (either pre-existing or freshly imported).
     *
     * Import runs on [appScope] so an Activity recreate triggered by an
     * imported locale doesn't cancel the import mid-DB-write. The
     * platform locale is flushed AFTER all writes commit, on the main
     * thread.
     */
    fun importAll() {
        appScope.launch {
            try {
                val bytes = fileExporter.openFile(listOf("application/json"))
                if (bytes == null) {
                    _statusMessage.value = ImportExportStatus.ImportCancelled
                    return@launch
                }
                log.d { "importAll: read ${bytes.size} bytes" }

                val bundle = withContext(Dispatchers.Default) {
                    val json = Json { ignoreUnknownKeys = true; isLenient = true }
                    json.decodeFromString(ExportBundle.serializer(), bytes.decodeToString())
                }
                log.d {
                    "importAll: schemaVersion=${bundle.schemaVersion} " +
                        "profiles=${bundle.profiles.size}"
                }

                // Strict version check. Pre-release builds emitted v1/v2/v3
                // bundles with a different envelope shape; those are no longer
                // supported now that the public release uses a clean v1
                // schema. If the user really needs to migrate an old export,
                // they need to import it on the pre-release version of the
                // app first and re-export from there.
                if (bundle.schemaVersion != EXPORT_SCHEMA_VERSION) {
                    throw IllegalStateException(
                        "Unsupported export schema version ${bundle.schemaVersion}; expected $EXPORT_SCHEMA_VERSION."
                    )
                }
                if (bundle.profiles.isEmpty()) {
                    throw IllegalStateException(
                        "Export bundle contains no profiles."
                    )
                }

                withContext(Dispatchers.Default) {
                    importProfiles(bundle)
                }

                // All DB writes are committed. NOW push the locale through
                // the platform applier – this is what may trigger Activity
                // recreate on Android 12. By doing it last we ensure no
                // half-finished DB work gets cancelled.
                withContext(Dispatchers.Main) {
                    localeController.flushApplied()
                }
                _statusMessage.value = ImportExportStatus.Imported
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(e) { "importAll: failed" }
                // Pass the underlying error message as a parameter so the
                // status string ("Import failed: …") can interpolate it.
                // Class simpleName is the fallback when the throwable has
                // no message (e.g. some kotlinx.serialization exceptions).
                _statusMessage.value = ImportExportStatus.ImportFailed(
                    reason = e.message ?: e::class.simpleName ?: "Unknown",
                )
            }
        }
    }

    /**
     * Apply a parsed [bundle]. The bundle is assumed to be schema v1
     * (validated upstream in [importAll]); each entry in [bundle.profiles]
     * is mapped onto either an existing local profile (matched by id) or
     * a freshly inserted one.
     *
     * Merge policy ("merge on all items"):
     *  - Settings: imported values overwrite local values for the same key.
     *    Local-only keys (not in bundle) survive.
     *  - Solves: appended, **with full-field deduplication** within the
     *    target profile. Two solves are considered identical when every
     *    *recorded* field matches: `solvedAt`, `durationMs`, `scramble`,
     *    `fluency`, `extras`, `isDnf`, `penaltyMs`, `moveCount`,
     *    `cubeMac`. The derived `ao5Ms` is not compared and not
     *    imported; the whole profile's averages are rebuilt once the
     *    batch is in.
     *    The solve's auto-generated DB `id` is intentionally NOT part of
     *    the fingerprint – it's local to each DB and would never match
     *    across exports. `moveCount` participates despite being a
     *    "history-only" field (no stat consumes it) because two solves at
     *    the same epoch ms with the same time and scramble but different
     *    turn counts genuinely are different recordings; for v1 bundles
     *    produced before the column existed, the field defaults to 0L on
     *    both sides of the comparison so older bundles round-trip
     *    unchanged. This makes re-importing the same backup a no-op (the
     *    original concern); a freshly-recorded solve done between exports
     *    will not collide because at minimum its `solvedAt`
     *    epoch-millisecond differs.
     *  - Cubes: rememberCube + updateHardwareInfo per row, mapped onto
     *    the destination profile id. The per-profile name override is
     *    applied separately (`rename`) so it lands on the destination
     *    profile alone, never on the shared cube row.
     *  - DisplayName: imported value overwrites the local one ONLY if
     *    non-null (don't blank out a local name with an imported null).
     */
    private fun importProfiles(bundle: ExportBundle) {
        for (p in bundle.profiles) {
            val targetId = ensureProfileExists(p)

            // Settings: overwrite by key (merge style – local-only keys survive).
            val accepted = p.settings.filterKeys { it in ALLOWED_SETTING_KEYS }
            settingsRepo.applyAll(targetId, accepted)

            // DisplayName: overwrite if imported non-null.
            p.displayName?.let { userRepo.setDisplayName(targetId, it) }

            // Cubes: register each onto the local profile.
            for (cube in p.cubes) {
                devicesRepo.rememberCube(targetId, cube.mac, cube.name)
                // Restore the profile's own label separately from the
                // hardware row, so importing a bundle into profile B
                // can't rename the cube out from under profile A. A null
                // here means the exporting profile had no override, and
                // clearing rather than skipping is right: import is
                // "make this profile look like the bundle".
                devicesRepo.rename(
                    userId = targetId,
                    mac = cube.mac,
                    name = cube.customName,
                )
                if (cube.hwVersion != null && cube.swVersion != null && cube.gyroSupported != null) {
                    devicesRepo.updateHardwareInfo(
                        mac = cube.mac,
                        hwVersion = cube.hwVersion,
                        swVersion = cube.swVersion,
                        gyroSupported = cube.gyroSupported,
                    )
                }
                // Stamp the vendor onto the freshly-remembered row.
                // [CubeVendor.fromKey] defaults unknown strings to GAN,
                // which also matches older v1 bundles that pre-date the
                // vendor field (they parse with the default "gan").
                devicesRepo.updateVendor(
                    mac = cube.mac,
                    vendor = CubeVendor.fromKey(cube.vendor),
                )
            }

            // Solves: append with full-field dedup. Snapshot the existing
            // rows once (not once per import row) and check fingerprints
            // in a HashSet – O(N+M) over O(N×M). The mutable set lets us
            // also dedup against earlier rows of THIS import batch, so a
            // single bundle that itself contains duplicates won't be
            // double-inserted either.
            val existingFingerprints = solvesRepo.snapshotAllForUser(targetId)
                .mapTo(HashSet()) { it.toFingerprint() }

            for (s in p.solves) {
                val fp = s.toFingerprint()
                if (!existingFingerprints.add(fp)) continue
                solvesRepo.insertForImport(
                    userId = targetId,
                    solvedAt = s.solvedAt,
                    durationMs = s.durationMs,
                    scramble = s.scramble,
                    fluency = s.fluency,
                    extras = s.extras,
                    isDnf = s.isDnf,
                    penaltyMs = s.penaltyMs,
                    moveCount = s.moveCount,
                    cubeMac = s.cubeMac,
                )
            }

            // Derive every Ao5 in the merged history in one ordered pass.
            // It has to happen after the whole batch: an imported solve
            // dated between two existing ones changes their averages
            // too, and nothing about the insert order tells us which.
            solvesRepo.rebuildAo5ForUser(targetId)
        }

        // Honour the bundle's activeProfileId if it resolves locally.
        bundle.activeProfileId?.let { wantedActive ->
            if (userRepo.byId(wantedActive) != null) {
                activeProfile.switchTo(wantedActive)
            }
        }
    }

    /**
     * Ensure a profile with [profile.id] exists locally. Returns the id to
     * write child data against. Two cases:
     *   • Profile exists → return its id, no insert.
     *   • Profile doesn't exist → insert with bundle id/createdAt.
     */
    private fun ensureProfileExists(profile: ExportProfile): String {
        val existing = userRepo.byId(profile.id)
        if (existing != null) return existing.id

        val toInsert = UserProfile(
            id = profile.id,
            displayName = profile.displayName,
            createdAt = if (profile.createdAt > 0) profile.createdAt
                else com.zucham.qbsmarter.util.currentTimeMillis(),
        )
        val ok = userRepo.insertExisting(toInsert)
        // insertExisting returns false only if a race re-created the row;
        // either way, the id now exists, so return it.
        if (!ok) log.w { "insertExisting unexpectedly returned false for ${profile.id}" }
        return toInsert.id
    }

    // -- Conversion helpers ----------------------------------------------

    private fun toExportSolve(row: SolveRow) = ExportSolve(
        solvedAt = row.solvedAt, durationMs = row.durationMs, scramble = row.scramble,
        ao5Ms = row.ao5Ms, fluency = row.fluency, extras = row.extras,
        isDnf = row.isDnf, penaltyMs = row.penaltyMs, moveCount = row.moveCount,
        cubeMac = row.cubeMac, ao5Times = row.ao5Times,
    )

    private fun toExportCube(cube: PairedCube) = ExportCube(
        mac = cube.mac,
        name = cube.advertisedName,
        customName = cube.customName,
        lastSeen = cube.lastSeen,
        hwVersion = cube.hwVersion, swVersion = cube.swVersion,
        gyroSupported = cube.gyroSupported,
        vendor = cube.vendor.key,
    )

    private companion object {
        /**
         * Whitelist of [SettingsRepository.Keys] values + any other
         * persisted settings keys the rest of the app reads. New keys must
         * be added here before they round-trip through import. Filtering
         * this way stops a malformed/malicious bundle from injecting
         * arbitrary keys that other observers might react to.
         */
        val ALLOWED_SETTING_KEYS = setOf(
            SettingsRepository.Keys.INSPECTION_ENABLED,
            SettingsRepository.Keys.AUTO_DISCONNECT_MINUTES,
            // SettingsRepository.Keys.SOUND_ENABLED,  // disabled – see SettingsRepository.Keys
            SettingsRepository.Keys.KEEP_SCREEN_ON,
            SettingsRepository.Keys.GYRO_ENABLED,
            SettingsRepository.Keys.ANY_MOVE_STARTS_NEW_SOLVE,
            SettingsRepository.Keys.THEME_SEED,
            SettingsRepository.Keys.THEME_MODE,
            SettingsRepository.Keys.LANGUAGE,
            SettingsRepository.Keys.CACHE_ENABLED,
        )

        /**
         * Characters that are illegal or trouble-prone in filenames on
         * one or more of our target platforms (Android FAT/exFAT SD
         * cards, MacOS, Windows). Replaced with `_` when building
         * per-profile export filenames from display names. Conservative
         * – anything outside basic ASCII plus a small set of safe
         * punctuation gets replaced.
         */
        val FILENAME_UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

// -- Export schema -----------------------------------------------------------
//
// The export format went through a v1/v2 (single-user) → v3 (multi-profile)
// transition during pre-release development. Now that the app is launching
// fresh – no legacy on-disk backups in the wild – the legacy fields and
// fallback synthesis path have been removed. Schema version reset to **1**
// for the clean public release; if the schema ever changes again, bump
// this and add a clearly-named migration path.

/** Current export schema version. Reset to 1 for the public release. */
private const val EXPORT_SCHEMA_VERSION = 1

@Serializable
data class ExportBundle(
    val schemaVersion: Int,
    val activeProfileId: String? = null,
    val profiles: List<ExportProfile> = emptyList(),
)

@Serializable
data class ExportProfile(
    val id: String,
    val displayName: String?,
    val createdAt: Long,
    val settings: Map<String, String>,
    val solves: List<ExportSolve>,
    val cubes: List<ExportCube>,
)

@Serializable
data class ExportSolve(
    val solvedAt: Long,
    val durationMs: Long,
    val scramble: String,
    val ao5Ms: Long?,
    val fluency: Double?,
    val extras: String?,
    val isDnf: Boolean = false,
    val penaltyMs: Long = 0L,
    // Defaulted so older v1 bundles (created before the moveCount
    // column landed) parse unchanged: kotlinx.serialization treats a
    // missing JSON field as the default. New exports always emit the
    // value; the dedup fingerprint includes it so genuinely different
    // solves with the same time/scramble but different turn counts
    // don't merge.
    val moveCount: Long = 0L,
    /**
     * Which physical cube the solve was done on. Defaulted so bundles
     * written before the column existed parse unchanged, and part of the
     * dedup fingerprint because it is a recorded fact about the solve —
     * two identical times on two different cubes are two solves.
     */
    val cubeMac: String? = null,
    /**
     * The five times behind [ao5Ms], in the encoding
     * `com.zucham.qbsmarter.domain.stats.Ao5` defines. Exported for
     * completeness; the importer does **not** restore it, because both
     * it and [ao5Ms] are derived from a set of solves that the importing
     * database does not have — see `SolvesRepository.insertForImport`.
     */
    val ao5Times: String? = null,
)

@Serializable
data class ExportCube(
    val mac: String,
    /**
     * The cube's advertised BLE name — hardware-level, the same for
     * every profile. A profile's own label for the cube travels in
     * [customName].
     *
     * Bundles produced before cube names were split per profile put the
     * user's rename in this field, because at the time there was only
     * one name. Those import as an advertised name, which is the only
     * reading available without a second field to compare it against;
     * the importing profile still sees the name it expects.
     */
    val name: String?,
    /**
     * The importing profile's own name for this cube, or null if it
     * never renamed it. Optional so pre-split bundles (which have no
     * such field) parse unchanged — same additive trick as [vendor].
     */
    val customName: String? = null,
    val lastSeen: Long,
    val hwVersion: String?,
    val swVersion: String?,
    val gyroSupported: Boolean?,
    // Defaulted to "gan" so older v1 bundles (created before the vendor
    // column landed) parse unchanged: kotlinx.serialization treats a
    // missing JSON field as the default, which matches the SQL column
    // default. The value is the lowercase [CubeVendor.key]; unknown
    // strings parse back to GAN via [CubeVendor.fromKey] on import.
    val vendor: String = "gan",
)

/**
 * Identity tuple for solve-row deduplication during import. A row in the
 * DB is "the same solve" as an imported row when every persisted field
 * matches – `solvedAt` (epoch ms, the strongest discriminator),
 * `durationMs`, `scramble`, the optional cached `ao5Ms`/`fluency`/`extras`,
 * the post-solve `isDnf` / `penaltyMs` flags, and `moveCount`. Auto-
 * incrementing DB `id` and the `userId` FK are intentionally excluded:
 * id is local to each DB and would never match across exports; userId
 * is implicit because we partition the dedup set per target profile.
 *
 * `moveCount` is part of the fingerprint despite being a "history-only"
 * field (no stat consumes it) because two solves at the same epoch ms
 * with the same scramble and time but different turn counts genuinely
 * are different recordings – without `moveCount` here, importing a
 * bundle that contains both would silently keep only one. For pre-
 * moveCount bundles the field defaults to 0L on both sides of the
 * comparison, so older bundles round-trip unchanged. `cubeMac` is in for
 * the same reason and defaults the same way.
 *
 * `ao5Ms` was in this list and has been taken out, because it stopped
 * being a fact about the solve and became a fact about its *neighbours*.
 * It is now re-derived after every import, penalty edit and delete, so
 * the local value legitimately differs from the one in a bundle written
 * before those solves were merged — and with it in the fingerprint, that
 * difference read as "a different solve" and re-importing your own
 * backup would have duplicated your entire history. A derived column
 * cannot identify the row it is derived for.
 *
 * Equality / hashCode come for free from `data class`. We hold these in
 * a `HashSet<SolveFingerprint>` to make the dedup check O(1) per row
 * instead of O(N).
 */
private data class SolveFingerprint(
    val solvedAt: Long,
    val durationMs: Long,
    val scramble: String,
    val fluency: Double?,
    val extras: String?,
    val isDnf: Boolean,
    val penaltyMs: Long,
    val moveCount: Long,
    val cubeMac: String?,
)

private fun SolveRow.toFingerprint() = SolveFingerprint(
    solvedAt = solvedAt, durationMs = durationMs, scramble = scramble,
    fluency = fluency, extras = extras,
    isDnf = isDnf, penaltyMs = penaltyMs, moveCount = moveCount, cubeMac = cubeMac,
)

private fun ExportSolve.toFingerprint() = SolveFingerprint(
    solvedAt = solvedAt, durationMs = durationMs, scramble = scramble,
    fluency = fluency, extras = extras,
    isDnf = isDnf, penaltyMs = penaltyMs, moveCount = moveCount, cubeMac = cubeMac,
)
