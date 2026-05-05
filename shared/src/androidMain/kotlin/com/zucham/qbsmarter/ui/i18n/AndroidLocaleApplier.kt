package com.zucham.qbsmarter.ui.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Android implementation of [LocaleApplier]. Uses AppCompat's per-app locale
 * API (1.6+) so the language choice:
 *  - shows up in the system Settings > Apps > QBSmarter > Languages panel,
 *  - persists across process restarts without us re-applying it manually,
 *  - causes the current Activity to recreate with the new Configuration,
 *    which is exactly what compose-resources needs to pick up the new
 *    string set (it reads from the Activity's resources).
 *
 * Calling this with [AppLanguage.SYSTEM] passes an empty locale list, which
 * AppCompat interprets as "follow system default".
 */
class AndroidLocaleApplier : LocaleApplier {
    override fun apply(language: AppLanguage) {
        val list = when (val tag = language.tag) {
            null -> LocaleListCompat.getEmptyLocaleList()
            else -> LocaleListCompat.forLanguageTags(tag)
        }
        // AppCompatDelegate handles the delegate pattern internally; calling
        // this on app start as well as on user toggle is safe and idempotent.
        AppCompatDelegate.setApplicationLocales(list)
    }
}
