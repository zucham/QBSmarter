package com.zucham.qbsmarter.ui.screens.guide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.zucham.qbsmarter.util.UrlOpener
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.guide_file
import qbsmarter.shared.generated.resources.guide_load_failed

/**
 * Renders the bundled `usage_guide_<lang>.md` resource via the Compose Multiplatform
 * markdown renderer. The actual file path is resolved at runtime from the
 * localised `guide_file` string resource:
 * `composeResources/files/usage_guides/usage_guide_en.md` for English,
 * `composeResources/files/usage_guides/usage_guide_cs.md` for Czech, and so
 * on for any future locale. The Markdown source is editable as plain text –
 * authors don't have to touch this file to update tutorial copy.
 *
 * **Link handling.** The markdown renderer opens link targets through the
 * Compose [LocalUriHandler] CompositionLocal. We override it here with one
 * backed by the app's [UrlOpener] Koin singleton (Android: `Intent.ACTION_VIEW`,
 * which routes to the user's default browser/email app – never a WebView).
 * This guarantees that `https://`, `mailto:`, and any other registered
 * scheme leaves the app to be handled natively, regardless of the renderer's
 * platform default. Without this, the desktop and JS targets would route
 * link clicks through their own platform handlers, which on Android is
 * already the same `Intent.ACTION_VIEW` so this override is a no-op there
 * but defensive on other targets.
 *
 * **Loading.** The markdown file is read once via [Res.readBytes], which
 * is suspending – we hold the result in a [String] state and show a
 * minimal placeholder while it loads. Failures (e.g. resource missing in
 * a stripped APK) surface through [guide_load_failed]; we deliberately
 * don't try to recover or retry, because if the bundle is corrupted the
 * user has bigger problems than missing tutorial copy.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun GuideScreen() {
    val urlOpener: UrlOpener = koinInject()

    // Override Compose's UriHandler with one that delegates to UrlOpener.
    // remember(urlOpener) so it's stable across recompositions; the Koin
    // singleton itself is stable for the app lifetime, so this is just
    // defensive identity-key for memoisation.
    val handler = remember(urlOpener) {
        object : UriHandler {
            override fun openUri(uri: String) = urlOpener.open(uri)
        }
    }

    val guideFile: String = stringResource(Res.string.guide_file)
    // Markdown source. Held nullable so we can show a placeholder while
    // Res.readBytes (suspend) resolves on first composition. After the
    // initial load we never re-read; the file is bundled with the APK,
    // so its content won't change without an app update.
    var markdownText by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { Res.readBytes(guideFile).decodeToString() }
            .onSuccess { markdownText = it }
            .onFailure { loadFailed = true }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        when {
            loadFailed -> Text(
                text = stringResource(Res.string.guide_load_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center),
            )
            markdownText == null -> {
                // Empty placeholder – the markdown read is fast (a few
                // KB at most), so a spinner would just flicker. The
                // user typically sees the content render immediately.
            }
            else -> CompositionLocalProvider(LocalUriHandler provides handler) {
                // verticalScroll on the host Box so a long guide scrolls
                // freely. The Markdown composable lays out as a Column
                // internally; we don't need a LazyColumn for this scale
                // of content.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Markdown(content = markdownText!!)
                }
            }
        }
    }
}
