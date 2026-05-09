package com.zucham.qbsmarter.ui.screens.settings

// import qbsmarter.shared.generated.resources.settings_sound  // disabled – see SettingsRepository.Keys.SOUND_ENABLED
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.domain.user.UserProfile
import com.zucham.qbsmarter.ui.components.ConfirmationDialog
import com.zucham.qbsmarter.ui.i18n.AppLanguage
import com.zucham.qbsmarter.ui.theme.AppColorSchemes
import com.zucham.qbsmarter.ui.theme.ThemeController
import com.zucham.qbsmarter.ui.theme.ThemeMode
import com.zucham.qbsmarter.ui.theme.ThemeSeed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.app_version
import qbsmarter.shared.generated.resources.devices_cancel
import qbsmarter.shared.generated.resources.language_czech
import qbsmarter.shared.generated.resources.language_english
import qbsmarter.shared.generated.resources.language_manual
import qbsmarter.shared.generated.resources.language_system
import qbsmarter.shared.generated.resources.profile_active
import qbsmarter.shared.generated.resources.profile_close
import qbsmarter.shared.generated.resources.profile_create
import qbsmarter.shared.generated.resources.profile_create_hint
import qbsmarter.shared.generated.resources.profile_create_title
import qbsmarter.shared.generated.resources.profile_default_name
import qbsmarter.shared.generated.resources.profile_delete
import qbsmarter.shared.generated.resources.profile_delete_message
import qbsmarter.shared.generated.resources.profile_delete_title
import qbsmarter.shared.generated.resources.profile_edit_hint
import qbsmarter.shared.generated.resources.profile_ok
import qbsmarter.shared.generated.resources.profile_settings_open
import qbsmarter.shared.generated.resources.profile_settings_title
import qbsmarter.shared.generated.resources.profile_total_solves
import qbsmarter.shared.generated.resources.settings_cache_enabled
import qbsmarter.shared.generated.resources.settings_cache_explanation
import qbsmarter.shared.generated.resources.settings_display_name
import qbsmarter.shared.generated.resources.settings_display_name_placeholder
import qbsmarter.shared.generated.resources.settings_export
import qbsmarter.shared.generated.resources.settings_import
import qbsmarter.shared.generated.resources.settings_inspection
import qbsmarter.shared.generated.resources.settings_keep_screen_on
import qbsmarter.shared.generated.resources.settings_language
import qbsmarter.shared.generated.resources.settings_section_about
import qbsmarter.shared.generated.resources.settings_section_advanced
import qbsmarter.shared.generated.resources.settings_section_display
import qbsmarter.shared.generated.resources.settings_section_profile
import qbsmarter.shared.generated.resources.settings_section_solving
import qbsmarter.shared.generated.resources.settings_status_export_cancelled
import qbsmarter.shared.generated.resources.settings_status_exported
import qbsmarter.shared.generated.resources.settings_status_import_cancelled
import qbsmarter.shared.generated.resources.settings_status_import_failed
import qbsmarter.shared.generated.resources.settings_status_imported
import qbsmarter.shared.generated.resources.settings_status_no_active_profile
import qbsmarter.shared.generated.resources.settings_status_profile_not_found
import qbsmarter.shared.generated.resources.settings_theme
import qbsmarter.shared.generated.resources.settings_theme_color
import qbsmarter.shared.generated.resources.settings_user_id
import qbsmarter.shared.generated.resources.settings_version
import qbsmarter.shared.generated.resources.theme_mode_dark
import qbsmarter.shared.generated.resources.theme_mode_light
import qbsmarter.shared.generated.resources.theme_mode_system

@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = koinViewModel()
    val mode by vm.themeController.mode.collectAsState()
    val seed by vm.themeController.seed.collectAsState()
    val language by vm.localeController.language.collectAsState()
    val status by vm.statusMessage.collectAsState()
    // Resolve the structured ImportExportStatus to a localised string here,
    // at the top of the Composable tree, so ProfilePicker stays a plain
    // dumb component that takes a String? and renders it. stringResource
    // is only callable from @Composable code, so it has to happen on the
    // UI side – see ImportExportStatus for why the VM publishes a
    // structured value rather than a pre-formatted string.
    val statusText: String? = status?.let { resolveStatusMessage(it) }
    val user by vm.user.collectAsState()
    val allProfiles by vm.allProfiles.collectAsState()

    val focusManager = LocalFocusManager.current

    var showCreateProfile by remember { mutableStateOf(false) }
    var pendingDeleteProfile by remember { mutableStateOf<UserProfile?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(stringResource(Res.string.settings_section_profile)) {
            ProfilePicker(
                active = user,
                allProfiles = allProfiles,
                pendingDeleteId = pendingDeleteProfile?.id,
                onSwitch = vm::switchTo,
                onCreate = { showCreateProfile = true },
                onDelete = { pendingDeleteProfile = it },
                vm = vm,
                status = statusText
            )
            // The standalone display-name field has been folded into the
            // per-profile settings dialog (gear icon on each ProfileRow).
            // Editing the active profile's name now goes through the
            // same UI as editing any other profile, which makes the
            // mental model uniform.
        }

        SettingsSection(stringResource(Res.string.settings_section_solving)) {
            SwitchRow(
                stringResource(Res.string.settings_inspection),
                SettingsRepository.Keys.INSPECTION_ENABLED, true, vm,
            )
            SwitchRow(
                stringResource(Res.string.settings_keep_screen_on),
                SettingsRepository.Keys.KEEP_SCREEN_ON, true, vm,
            )
            // Sound-effects toggle disabled until cube-event sound design
            // lands. Preserved (not deleted) so re-enabling it is a
            // one-line revert. See SettingsRepository.Keys.SOUND_ENABLED.
            // SwitchRow(
            //     stringResource(Res.string.settings_sound),
            //     SettingsRepository.Keys.SOUND_ENABLED, false, vm,
            // )
        }
        SettingsSection(stringResource(Res.string.settings_section_display)) {
            LabeledControl(stringResource(Res.string.settings_theme)) {
                // The mode selector samples the active seed's light AND
                // dark palettes to color its three buttons – Light shows
                // the seed's light scheme, Dark shows its dark scheme,
                // and System diagonally splits the two. So a seed change
                // recomposes the selector with new tones automatically.
                ThemeModeSelector(mode, vm::setMode)
            }
            LabeledControl(stringResource(Res.string.settings_theme_color)) {
                ThemeSeedPicker(seed, vm::setSeed)
            }
            LabeledControl(stringResource(Res.string.settings_language)) {
                LanguageSelector(language, vm::setLanguage)
            }
        }
        SettingsSection(stringResource(Res.string.settings_section_advanced)) {
            // Cache toggle. The setting is honoured immediately by AppCache:
            // turning it off drops cached data in real-time, turning it
            // back on warms up new observers.
            SwitchRow(
                stringResource(Res.string.settings_cache_enabled),
                SettingsRepository.Keys.CACHE_ENABLED, true, vm,
            )
            Text(
                stringResource(Res.string.settings_cache_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection(stringResource(Res.string.settings_section_about)) {
            Row (horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(Res.string.settings_version),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        stringResource(Res.string.app_version),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row (horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(Res.string.settings_user_id),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        user?.id ?: "–",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (showCreateProfile) {
        CreateProfileDialog(
            onCreate = { name ->
                vm.createProfile(name.takeIf { it.isNotBlank() })
                showCreateProfile = false
            },
            onDismiss = { showCreateProfile = false },
        )
    }

    pendingDeleteProfile?.let { profile ->
        ConfirmationDialog(
            title = stringResource(Res.string.profile_delete_title),
            message = stringResource(Res.string.profile_delete_message),
            confirmLabel = stringResource(Res.string.profile_delete),
            cancelLabel = stringResource(Res.string.devices_cancel),
            onConfirm = {
                vm.deleteProfile(profile.id)
                pendingDeleteProfile = null
            },
            onDismiss = { pendingDeleteProfile = null },
        )
    }
}

/**
 * Profile picker:
 *   - One unified list with the active profile on top.
 *   - The active row uses [primaryContainer] background + an "Active"
 *     label so it stands out without a separate "active card" widget.
 *   - Every row is swipe-to-delete (matching the History screen) and
 *     also has a trailing delete IconButton for users who don't discover
 *     the swipe gesture.
 *   - Tapping a non-active row switches to it (the whole row is tappable).
 *   - "New profile" CTA below.
 *
 * Resolution of the displayed name: any profile's name field can be null
 * (we never force a name on creation). Display falls back to
 * "New profile" via [profileLabel].
 */
@Composable
private fun ProfilePicker(
    active: UserProfile?,
    allProfiles: List<UserProfile>,
    pendingDeleteId: String?,
    onSwitch: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (UserProfile) -> Unit,
    vm: SettingsViewModel,
    status: String?
) {
    // Active profile rendered first; all others (sorted by created_at
    // ascending, which is what observeAll already gives us) follow.
    val activeId = active?.id
    val sorted = remember(active, allProfiles) {
        val activeOnTop = active?.let { listOf(it) }.orEmpty()
        val others = allProfiles.filter { it.id != activeId }
        activeOnTop + others
    }

    // Which profile (if any) currently has its settings dialog open.
    // Held by id rather than UserProfile so an in-flight rename in the
    // dialog (which causes UserProfile to re-emit with new displayName)
    // doesn't dismiss the dialog as a "changed identity".
    var settingsForProfileId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Hint above the profile list. Important because the gear icon
        // is a discoverability problem for the rename action – without
        // this line a user used to the old standalone "Display name"
        // field would just see the list without obvious editing
        // affordances. The hint itself is muted (bodySmall +
        // onSurfaceVariant) so it doesn't compete with the rows.
        Text(
            stringResource(Res.string.profile_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        for (profile in sorted) {
            // `key(profile.id)` is essential here. Without it, Compose's
            // slot table reuses the position when the list reorders
            // (e.g. after deleting the active profile A, the next
            // profile B is promoted to active and moves to slot 0). The
            // `rememberSwipeToDismissBoxState` for slot 0 would then
            // carry over from A's mid-dismiss state to B – which made
            // B render in the swiped position AND immediately re-fire
            // the delete confirmation dialog (the LaunchedEffect on
            // `state.currentValue` runs again for the new profile while
            // the value is still StartToEnd). Keying by id forces a
            // fresh state instance per profile identity, so B starts
            // at Settled regardless of what A's row was doing.
            key(profile.id) {
                ProfileRow(
                    profile = profile,
                    isActive = profile.id == activeId,
                    pendingDeleteId = pendingDeleteId,
                    onTap = { if (profile.id != activeId) onSwitch(profile.id) },
                    onDelete = { onDelete(profile) },
                    onOpenSettings = { settingsForProfileId = profile.id },
                )
            }
        }

        // Create + Import laid out side-by-side. Create is the primary
        // action (filled Button); Import is secondary (OutlinedButton)
        // because most sessions create new profiles and only a minority
        // need to restore from a backup. Equal weights so the row stays
        // balanced when the localised labels differ in length.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCreate,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(Res.string.profile_create),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = vm::importAll,
                modifier = Modifier.weight(1f),
            ) {
                // FileDownload icon (a downward-arrow into a tray) reads
                // as "pull a file in" – the conventional import-from-disk
                // affordance. Was previously Icons.Filled.Create (pencil)
                // which suggested editing rather than ingestion.
                Icon(
                    imageVector = Icons.Filled.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(Res.string.settings_import),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    // Per-profile settings dialog. Opens when a row's gear is tapped
    // and stays open until the user closes it explicitly (or the
    // profile is deleted out from under it via another path, in which
    // case the resolution-by-id below returns null and the dialog
    // dismisses itself on the next recomposition).
    val dialogProfile = sorted.firstOrNull { it.id == settingsForProfileId }
    if (settingsForProfileId != null && dialogProfile != null) {
        ProfileSettingsDialog(
            profile = dialogProfile,
            vm = vm,
            onDismiss = { settingsForProfileId = null },
        )
    } else if (settingsForProfileId != null && dialogProfile == null) {
        // Profile vanished while the dialog was open (deleted by
        // another action). Clear the pointer on the next composition.
        LaunchedEffect(Unit) { settingsForProfileId = null }
    }
}

/**
 * One row in the profile list. Layout:
 *
 *   [profile name (+ "Active" pill if active)]   [delete icon]
 *
 * Swipe right reveals an "Delete" red background and triggers
 * confirmation; matches the History screen's `SwipeableSolveItem`
 * pattern so the whole app feels consistent. Confirmation is handled
 * upstream – this row only fires [onDelete] (which raises the
 * confirmation dialog) and resets its swipe state once that dialog is
 * dismissed.
 *
 * The active row's background is `primaryContainer` so it pops out from
 * the list; non-active rows use `surface`. Tapping the active row is a
 * no-op (you can't switch to yourself), but tapping any other row
 * switches to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileRow(
    profile: UserProfile,
    isActive: Boolean,
    pendingDeleteId: String?,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.33f },
    )

    // Raise the delete request when the user swipes past the threshold.
    // We deliberately do NOT call `state.reset()` here; it's an animated
    // suspending operation, and SwipeToDismissBox snaps to the dismissed
    // position the moment `currentValue` settles at StartToEnd, so a
    // reset call here can race with the dismiss animation and leave the
    // row visually stuck. Instead, the second LaunchedEffect below resets
    // the row when the global pending pointer clears (confirm or cancel)
    // – same pattern as `SwipeableSolveItem` in HistoryScreen.
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.StartToEnd) {
            onDelete()
        }
    }

    // When the global "pending delete" no longer points at THIS row
    // (user confirmed and the row dropped from the list, or user
    // cancelled the dialog), snap the row's swipe state back to
    // Settled.
    LaunchedEffect(pendingDeleteId, profile.id) {
        if (pendingDeleteId != profile.id && state.currentValue != SwipeToDismissBoxValue.Settled) {
            state.reset()
        }
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = { ProfileSwipeBackground() },
    ) {
        ProfileRowContent(
            profile = profile,
            isActive = isActive,
            onTap = onTap,
            onDelete = onDelete,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun ProfileSwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(Res.string.profile_delete),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ProfileRowContent(
    profile: UserProfile,
    isActive: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val containerColor =
        if (isActive) MaterialTheme.colorScheme.primaryContainer
        // Inactive rows: surfaceContainer rather than `surface`. Two
        // problems with `surface`:
        //   - in light mode it now equals `background` (page edge),
        //     which made inactive rows invisible against the page;
        //   - in dark mode it sits a hair above page brightness,
        //     which made the row read as part of the page rather than
        //     a tappable card.
        // surfaceContainer steps the row distinctly off the page in
        // both modes (light: #EBEBF1 vs #FFFFFF, dark: #1F1F22 vs
        // #0B0B0D) so the per-profile rows read as a real list.
        else MaterialTheme.colorScheme.surfaceContainer
    val contentColor =
        if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isActive, onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 2.dp else 0.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            // Reduced horizontal start padding (was 16.dp) – the gear
            // IconButton has its own internal touch target padding so
            // the visual edge of the icon already sits at ~12-16dp from
            // the card edge. Without this trim the row reads as
            // gear-pushed-too-far-right.
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Gear icon at the START of the row. Opens the per-profile
            // settings dialog (rename + total solves + export). Placed
            // here rather than at the end because the existing Delete
            // icon already lives at the end and we don't want two
            // adjacent icons competing for the user's tap.
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(Res.string.profile_settings_open),
                    tint = contentColor,
                )
            }
            Text(
                text = profileLabel(profile),
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                // Small "Active" tag rendered as a faint pill so it
                // reads as metadata, not a button.
                Text(
                    text = stringResource(Res.string.profile_active),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(Res.string.profile_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Resolve a profile's display label. Null/blank → "New profile" fallback. */
@Composable
private fun profileLabel(profile: UserProfile): String =
    profile.displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.profile_default_name)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProfileDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.profile_create_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(Res.string.profile_default_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }) {
                Text(stringResource(Res.string.profile_ok), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(Res.string.devices_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * Per-profile settings dialog. Folds the rename field, total-solves
 * summary, and per-profile export action into one place. Replaces:
 *   • the old standalone DisplayNameField under the profile picker
 *     (which only edited the active profile),
 *   • the old single Export button under the profile picker (which
 *     exported every profile in one bundle).
 *
 * The rename field commits on every change (no separate Save button)
 * to match the old behaviour – there's nothing to "save", changes are
 * applied immediately.
 *
 * Total-solves count is read once when the dialog opens. We don't
 * make it reactive: solves don't get added while sitting in this
 * dialog (the timer's on a different screen), so a snapshot is fine
 * and avoids subscribing to a per-profile flow that the rest of the
 * settings UI doesn't use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSettingsDialog(
    profile: UserProfile,
    vm: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    // Local mirror of the display name so the user can type without
    // every keystroke round-tripping through the DB. Commits on
    // change – same trade-off as the old DisplayNameField.
    var name by remember(profile.id) { mutableStateOf(profile.displayName ?: "") }

    // Snapshot of the total solve count for this profile. Read once
    // at dialog open. The Int? distinguishes "still loading" (null)
    // from "loaded as zero" (0) so the summary line can show a stable
    // "Total solves: 0" rather than briefly flashing nothing.
    var totalSolves by remember(profile.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(profile.id) {
        totalSolves = vm.solveCountFor(profile.id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Editable name. Commits on every change – same
                // semantics as the legacy DisplayNameField but without
                // the focus-loss-commit dance because we have no other
                // input to lose focus to inside the dialog.
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        vm.renameProfile(profile.id, it)
                    },
                    label = { Text(stringResource(Res.string.settings_display_name)) },
                    placeholder = { Text(stringResource(Res.string.settings_display_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Total solves line. Falls back to a dash while loading.
                Text(
                    text = stringResource(
                        Res.string.profile_total_solves,
                        totalSolves ?: 0,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Export this profile only. Outlined button rather than
                // a primary filled button because the dialog's primary
                // action is "Close" (you opened the dialog to look at
                // the profile, exporting is a side errand).
                //
                // Centered in a wrapping Row rather than `fillMaxWidth`:
                // a full-width outlined button on a narrow modal looks
                // like a confirm/CTA, which fights the dialog's actual
                // confirm row at the bottom. A wrap-content button
                // sized to its label reads as the secondary action it
                // is and balances the dialog visually.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    OutlinedButton(
                        onClick = {
                            vm.exportProfile(profile.id)
                            onDismiss()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.settings_export))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.profile_close), fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun LabeledControl(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, key: String, default: Boolean, vm: SettingsViewModel) {
    // Drive from the cached settings flow so the switch reflects the
    // active profile. Switching profiles while on this screen recomposes
    // the row with the new profile's value automatically.
    val settings by vm.cacheSettings.collectAsState()
    val checked = settings[key]?.let { it == "1" || it.equals("true", ignoreCase = true) }
        ?: default
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = { vm.setBool(key, it) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    val controller: ThemeController = koinInject()
    val seed by controller.seed.collectAsState()

    val lightScheme = remember(seed) { AppColorSchemes.light(seed) }
    val darkScheme = remember(seed) { AppColorSchemes.dark(seed) }
    val systemTheme = if (isSystemInDarkTheme()) darkScheme else lightScheme

    // Fixed display order: Light, System, Dark.
    val count = 3

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ThemeModeSegmentedButton(
            index = 0,
            count = count,
            mode = ThemeMode.LIGHT,
            scheme = lightScheme,
            highlightColor = lightScheme.primary,
            selected = current == ThemeMode.LIGHT,
            onChange = onChange,
        )
        ThemeModeSegmentedButton(
            index = 1,
            count = count,
            mode = ThemeMode.SYSTEM,
            scheme = systemTheme,
            highlightColor = systemTheme.primary,
            selected = current == ThemeMode.SYSTEM,
            onChange = onChange,
        )
        ThemeModeSegmentedButton(
            index = 2,
            count = count,
            mode = ThemeMode.DARK,
            scheme = darkScheme,
            highlightColor = darkScheme.primary,
            selected = current == ThemeMode.DARK,
            onChange = onChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleChoiceSegmentedButtonRowScope.ThemeModeSegmentedButton(
    index: Int,
    count: Int,
    mode: ThemeMode,
    scheme: ColorScheme,
    highlightColor: Color,
    selected: Boolean,
    onChange: (ThemeMode) -> Unit,
) {
    val shape = SegmentedButtonDefaults.itemShape(index = index, count = count)

    SegmentedButton(
        shape = shape,
        onClick = { onChange(mode) },
        selected = selected,
        colors = SegmentedButtonDefaults.colors(
            activeContainerColor = scheme.surface,
            activeContentColor = highlightColor,
            activeBorderColor = highlightColor,
            inactiveContainerColor = scheme.surface,
            inactiveContentColor = scheme.onSurfaceVariant,
            inactiveBorderColor = scheme.outline,
        ),
        label = {
            if (selected) {
                Text(stringResource(themeModeLabelOf(mode)), fontWeight = FontWeight.Bold)
            } else {
                Text(stringResource(themeModeLabelOf(mode)), fontWeight = FontWeight.Normal)
            }
        },
        border = if (selected) {
            BorderStroke(width = 2.dp, color = highlightColor)
        } else {
            SegmentedButtonDefaults.borderStroke(scheme.outline)
        },
    )
}



private fun themeModeLabelOf(mode: ThemeMode): StringResource = when (mode) {
    ThemeMode.SYSTEM -> Res.string.theme_mode_system
    ThemeMode.LIGHT -> Res.string.theme_mode_light
    ThemeMode.DARK -> Res.string.theme_mode_dark
}

/**
 * Two-segment language picker. No separate dropdown that toggles
 * enable/disable – the dropdown lives INSIDE the Manual segment.
 *
 * Layout:
 *
 *   ┌──────────┬──────────────────────┐
 *   │ ✓ System │   Manual – English ▾ │
 *   └──────────┴──────────────────────┘
 *   (System selected)
 *
 *   ┌──────────┬──────────────────────┐
 *   │   System │ ✓ Manual – Čeština ▾ │
 *   └──────────┴──────────────────────┘
 *   (Manual + Czech selected)
 *
 * Behaviour:
 *   - Tap System → switches to SYSTEM mode (no dropdown needed).
 *   - Tap Manual → if currently System, switches to manual with the
 *     last-remembered language. If already in manual mode, opens the
 *     dropdown so the user can pick a different language.
 *
 * State derivation: `current` is the persisted [AppLanguage]. We map it
 * to (mode, manualLang):
 *   - `current == SYSTEM` → mode = SYSTEM, manualLang remembers the
 *     last non-SYSTEM choice (default ENGLISH on first use).
 *   - `current == ENGLISH/CZECH/...` → mode = MANUAL, manualLang = current.
 *
 * Languages list is alphabetically sorted with English forced to the top
 * (it's the default when no manual choice has been made yet, so seeing
 * it first matches the user's expectation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(current: AppLanguage, onChange: (AppLanguage) -> Unit) {
    // Last picked manual language. Defaults to ENGLISH; if `current` is
    // already a manual language, it tracks that. The remembered value
    // survives recomposition and is re-derived from `current` whenever
    // current is non-SYSTEM (LaunchedEffect below).
    var rememberedManual by remember {
        mutableStateOf(if (current != AppLanguage.SYSTEM) current else AppLanguage.ENGLISH)
    }
    LaunchedEffect(current) {
        if (current != AppLanguage.SYSTEM) rememberedManual = current
    }

    val isSystem = current == AppLanguage.SYSTEM
    val displayedManual = if (isSystem) rememberedManual else current

    // Anchor for the dropdown menu attached to the Manual segment.
    var dropdownExpanded by remember { mutableStateOf(false) }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        // ---------- System segment ----------
        // Just selects SYSTEM mode. No dropdown. The default icon slot
        // shows a checkmark when this segment is selected (Material3
        // default for SegmentedButton).
        SegmentedButton(
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            onClick = {
                dropdownExpanded = false
                if (!isSystem) onChange(AppLanguage.SYSTEM)
            },
            selected = isSystem,
        ) {
            Text(
                stringResource(Res.string.language_system),
                maxLines = 1,
            )
        }

        // ---------- Manual segment ----------
        // Click behaviour:
        //   - in System mode: switch to manual with the remembered
        //     language. (Don't open the dropdown immediately – the user
        //     just wanted to flip the mode; if they want a different
        //     language they'll click again.)
        //   - in Manual mode: open the dropdown so the user can pick a
        //     different language. The currently-selected language is
        //     visible in the segment label, so the dropdown is purely
        //     for changing it.
        //
        // The dropdown lives INSIDE the SegmentedButton's label slot
        // (wrapped in a Box together with the label Text). Reasoning:
        // [SegmentedButton] is a member of [SingleChoiceSegmentedButtonRowScope]
        // and BoxScope carries the @LayoutScopeMarker DslMarker, so
        // wrapping a SegmentedButton inside a `Box { ... }` at the row
        // level would shadow the segmented-row scope and break the call
        // site. Putting the Box INSIDE the SegmentedButton's label slot
        // sidesteps that – the label is a plain Composable lambda
        // (RowScope receiver) which composes Box freely. The Popup-based
        // DropdownMenu still anchors visually to its host position,
        // which lands directly under the Manual segment.
        SegmentedButton(
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            onClick = {
                if (isSystem) {
                    // Mode flip System → Manual: commit the
                    // remembered language as the manual choice.
                    onChange(rememberedManual)
                } else {
                    // Already manual: toggle the dropdown to
                    // allow language change.
                    dropdownExpanded = !dropdownExpanded
                }
            },
            selected = !isSystem,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = manualSegmentLabel(displayedManual),
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null, // decorative; text already conveys it
                    )

                    // The DropdownMenu doesn't take layout space, so it can live
                    // anywhere in the composition – inside the Row is fine.
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        for (option in MANUAL_LANGUAGES_ORDERED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(languageLabelOf(option))) },
                                onClick = {
                                    dropdownExpanded = false
                                    rememberedManual = option
                                    onChange(option)
                                },
                            )
                        }
                    }
                }
            }
        )
    }
}

/**
 * Compose the label shown inside the Manual segment: a short prefix
 * plus an em-dash and the currently-displayed language name. Adding
 * a trailing chevron so it reads as a dropdown affordance.
 */
@Composable
private fun manualSegmentLabel(language: AppLanguage): String {
    val manualText = stringResource(Res.string.language_manual)
    val languageText = stringResource(languageLabelOf(language))
    // Em-dash with thin spaces around it. Trailing chevron makes the
    // dropdown affordance explicit; the Manual segment IS the dropdown
    // anchor when it's the active one.
    return "$manualText – $languageText"
}

/**
 * Manual-language ordering for the dropdown. English is forced to the
 * top (it's the de-facto default when no manual choice has been made
 * yet); the remaining languages follow alphabetically by their enum
 * name. With only English + Czech today the alphabetical part is
 * trivial, but this scales when more languages are added.
 */
private val MANUAL_LANGUAGES_ORDERED: List<AppLanguage> by lazy {
    val all = AppLanguage.entries.filter { it != AppLanguage.SYSTEM }
    val english = all.firstOrNull { it == AppLanguage.ENGLISH }
    val rest = all.filter { it != AppLanguage.ENGLISH }.sortedBy { it.name }
    listOfNotNull(english) + rest
}

private fun languageLabelOf(language: AppLanguage): StringResource = when (language) {
    AppLanguage.SYSTEM -> Res.string.language_system
    AppLanguage.ENGLISH -> Res.string.language_english
    AppLanguage.CZECH -> Res.string.language_czech
}

@Composable
private fun ThemeSeedPicker(current: ThemeSeed, onChange: (ThemeSeed) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (seed in ThemeSeed.entries) {
            val sample = AppColorSchemes.light(seed).primary
            SeedSwatch(sample, selected = seed == current, onClick = { onChange(seed) })
        }
    }
}

@Composable
private fun SeedSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = color,
        modifier = Modifier.size(if (selected) 36.dp else 28.dp).padding(2.dp),
        onClick = onClick,
    ) { Box(Modifier.fillMaxSize()) }
}

/**
 * Map an [ImportExportStatus] variant to its localised user-visible
 * message. Lives in the screen file because it pulls strings via
 * [stringResource], which is only callable from @Composable code.
 *
 * The `ImportFailed` variant interpolates the underlying error message
 * via the format-arg overload of `stringResource(StringResource, vararg Any)`
 * – the format arg itself is the raw exception message, which isn't
 * localised but typically wouldn't be (it's a developer-facing detail
 * appended for support).
 */
@Composable
private fun resolveStatusMessage(status: ImportExportStatus): String = when (status) {
    ImportExportStatus.NoActiveProfile ->
        stringResource(Res.string.settings_status_no_active_profile)
    ImportExportStatus.ProfileNotFound ->
        stringResource(Res.string.settings_status_profile_not_found)
    ImportExportStatus.Exported ->
        stringResource(Res.string.settings_status_exported)
    ImportExportStatus.ExportCancelled ->
        stringResource(Res.string.settings_status_export_cancelled)
    ImportExportStatus.Imported ->
        stringResource(Res.string.settings_status_imported)
    ImportExportStatus.ImportCancelled ->
        stringResource(Res.string.settings_status_import_cancelled)
    is ImportExportStatus.ImportFailed ->
        stringResource(Res.string.settings_status_import_failed, status.reason)
}
