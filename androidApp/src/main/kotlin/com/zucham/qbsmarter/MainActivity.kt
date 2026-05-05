package com.zucham.qbsmarter

import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.zucham.qbsmarter.app.App
import com.zucham.qbsmarter.data.ble.BleManager
import com.zucham.qbsmarter.util.AndroidFileExporter
import com.zucham.qbsmarter.util.AndroidScreenKeeper
import com.zucham.qbsmarter.util.FileExporter
import com.zucham.qbsmarter.util.ScreenKeeper
import org.koin.android.ext.android.get

/**
 * Entry point. Koin is already started (in QbsmarterApp.onCreate); it just
 * injects the bindings that need an Activity reference.
 *
 * Why AppCompatActivity (not ComponentActivity): AppCompat's per-app
 * locale API is used for the language selector. AppCompat handles the per-app
 * locale change including Activity recreate; ComponentActivity alone
 * wouldn't pick up the new Configuration. AppCompatActivity also extends
 * ComponentActivity so all the ActivityResult/edge-to-edge wiring still
 * works.
 *
 * Permission flow (API-level aware):
 *   - Android 12+: BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
 *   - Android 10/11: ACCESS_FINE_LOCATION (the legacy BLUETOOTH /
 *     BLUETOOTH_ADMIN are install-time normal-protection so they
 *     don't enter the runtime ask).
 * The exact set comes from [BleManager.requiredRuntimePermissions], so
 * the activity doesn't have to duplicate the API-version branching.
 */
class MainActivity : AppCompatActivity() {

    private val screenKeeper: ScreenKeeper by lazy { get() }
    private val fileExporter: FileExporter by lazy { get() }
    private val bleManager: BleManager by lazy { get() }

    private val requestBlePermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result is not used directly; BleManager re-checks on every call */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Explicit transparent SystemBarStyle for both bars. The no-arg
        // enableEdgeToEdge() variant uses an `auto` style that on API < 30
        // applies an opaque scrim derived from the system theme. Forcing TRANSPARENT
        // both ways pairs cleanly with Compose-side ApplySystemBarsTheme,
        // which controls icon color at every recomposition.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Bind the platform helpers that need a real Activity reference.
        // Both bind() calls register ActivityResult launchers, which MUST
        // happen before onStart per AndroidX rules.
        (screenKeeper as? AndroidScreenKeeper)?.bind(this)
        (fileExporter as? AndroidFileExporter)?.bind(this)

        ensureBlePermissions()

        setContent { App() }
    }

    override fun onDestroy() {
        (screenKeeper as? AndroidScreenKeeper)?.unbind(this)
        (fileExporter as? AndroidFileExporter)?.unbind(this)
        super.onDestroy()
    }

    private fun ensureBlePermissions() {
        val needed = bleManager.requiredRuntimePermissions()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestBlePermsLauncher.launch(needed.toTypedArray())
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
