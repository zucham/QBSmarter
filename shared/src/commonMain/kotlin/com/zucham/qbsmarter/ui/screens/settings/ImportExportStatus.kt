package com.zucham.qbsmarter.ui.screens.settings

/**
 * Result of the most recent Import/Export action, surfaced from
 * [SettingsViewModel] to the screen so the screen can localise it.
 *
 * The VM can't call `stringResource` (only Composable code can), so it
 * publishes a structured variant here and the screen resolves it to a
 * translated string when rendering. This keeps the resource lookup
 * centralised in the UI layer where locale changes are observed
 * automatically by Compose.
 *
 * Variants are flat (no inheritance, no payload beyond the failure
 * reason) – fewer types to wire through composition than a generic
 * sealed hierarchy and the localisation step is a simple `when`.
 */
sealed interface ImportExportStatus {
    /** Tried to export but no profile is currently active. */
    data object NoActiveProfile : ImportExportStatus

    /** Tried to export a profile but it wasn't found in the DB. */
    data object ProfileNotFound : ImportExportStatus

    /** Export succeeded – bundle written to the chosen file. */
    data object Exported : ImportExportStatus

    /** User dismissed the system file-save picker. */
    data object ExportCancelled : ImportExportStatus

    /** Import succeeded – bundle parsed and applied. */
    data object Imported : ImportExportStatus

    /** User dismissed the system file-open picker. */
    data object ImportCancelled : ImportExportStatus

    /**
     * Import failed mid-flight (invalid JSON, schema mismatch, DB write
     * error, …). [reason] is the underlying error message, kept as a
     * raw string because it comes from [Throwable.message] / class
     * simpleName and isn't itself localisable.
     */
    data class ImportFailed(val reason: String) : ImportExportStatus
}
