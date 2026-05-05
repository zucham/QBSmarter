package com.zucham.qbsmarter.web

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

fun main() {
    ComposeViewport(document.body!!) {
        Surface { Text("QBSmarter wasm is not yet implemented.") }
    }
}
