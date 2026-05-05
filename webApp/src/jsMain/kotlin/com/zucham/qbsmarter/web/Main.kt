package com.zucham.qbsmarter.web

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * JS browser entry. Same caveat as desktop: stubbed because the web
 * platform module throws on DriverFactory + GanEncryptor.
 */
fun main() {
    ComposeViewport(document.body!!) {
        Surface { Text("QBSmarter web is not yet implemented.") }
    }
}
