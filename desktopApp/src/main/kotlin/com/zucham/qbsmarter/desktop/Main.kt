package com.zucham.qbsmarter.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Desktop entry point. The real App() composable depends on Android-only
 * actuals (BLE, SAF, AES) – running it here would NotImplementedError on
 * first DI lookup. So we render a placeholder.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "QBSmarter") {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "QBSmarter desktop is not yet implemented.\n" +
                        "Run the Android app for now.",
                    style = MaterialTheme.typography.body1,
                )
            }
        }
    }
}
