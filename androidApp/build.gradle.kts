import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    target {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    dependencies {
        implementation(projects.shared)

        // Compose
        implementation(libs.androidx.activity.compose)
        implementation(libs.androidx.activity.ktx)
        implementation(libs.compose.uiToolingPreview)

        // ProcessLifecycleOwner – drives AppLifecycle.onForegrounded/onBackgrounded
        implementation(libs.androidx.lifecycle.process)

        // Koin: Application init + Compose ViewModel injection
        implementation(platform(libs.koin.bom))
        implementation(libs.koin.android)
        implementation(libs.koin.compose)
        implementation(libs.koin.compose.viewmodel)
    }
}

android {
    namespace = "com.zucham.qbsmarter"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.zucham.qbsmarter"
        // minSdk 29 (Android 10) is the floor supported by Korender 0.6.1.
        // The Bluetooth permission set differs between API 29-30 and 31+,
        // so MainActivity / BleManager branch on Build.VERSION.SDK_INT:
        // legacy ACCESS_FINE_LOCATION on the older versions, modern
        // BLUETOOTH_SCAN + BLUETOOTH_CONNECT (with neverForLocation) on
        // API 31+. See AndroidManifest.xml for the declarations.
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdkVersion(rootProject.extra["defaultTargetSdkVersion"] as Int)
        versionCode = 9
        versionName = "1.3.0"
    }
    // -- Release signing --------------------------------------------------
    //
    // Signing config reads from environment variables. Three deployment modes:
    //
    //   1. CI (Codeberg or GitHub) – the workflow base64-decodes the keystore
    //      secret to a temporary file and exports the four QBS_* env vars.
    //      assembleRelease then produces a signed APK.
    //
    //   2. Local signed release builds – set the four env vars in your shell
    //      (or a gitignored signing.properties + a tiny shell snippet) before
    //      invoking `./gradlew :androidApp:assembleRelease`. The keystore
    //      file is yours, kept outside the repo.
    //
    //   3. Local debug builds – nothing is required. The env vars are absent,
    //      the signing block becomes a no-op, and the debug build type uses
    //      its own auto-generated debug keystore as it always has.
    //
    // The `if (storeFileEnv != null && ...)` guard means the block silently
    // does nothing when the env vars are missing. Without the guard, a
    // missing keystore would fail every Gradle sync on a fresh clone, which
    // would be miserable for new contributors.
    signingConfigs {
        create("release") {
            val storeFileEnv = System.getenv("QBS_KEYSTORE_PATH")
            val storePwd     = System.getenv("QBS_KEYSTORE_PASSWORD")
            val keyAliasEnv  = System.getenv("QBS_KEY_ALIAS")
            val keyPwd       = System.getenv("QBS_KEY_PASSWORD")
            if (
                storeFileEnv != null && storePwd != null &&
                keyAliasEnv != null && keyPwd != null
            ) {
                storeFile     = file(storeFileEnv)
                storePassword = storePwd
                keyAlias      = keyAliasEnv
                keyPassword   = keyPwd
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            // Debug builds keep all logging – that's the whole point of
            // the debug build. R8 disabled too because shrinking
            // sometimes makes stack traces less readable, and we want
            // them readable while developing.
            isMinifyEnabled = false
        }
        getByName("release") {
            // R8 (Android's successor to ProGuard) is enabled in release
            // so that:
            //   1. The lowest-severity log calls (Kermit Logger.d/v/i and
            //      android.util.Log.d/v/i) get stripped at compile time
            //      via the -assumenosideeffects rules in proguard-rules.pro.
            //      We keep .w and .e because they help with crash reports
            //      and post-shipping diagnostics.
            //   2. Dead code from libraries we don't use is dropped,
            //      shrinking the APK.
            //   3. Class/method names get obfuscated, which makes
            //      reverse-engineering modestly harder. Crash reports
            //      remain readable via the mapping file (uploaded to
            //      Play Console / kept locally for sideloads).
            isMinifyEnabled = true
            // Resource shrinking removes unused string resources and
            // drawables. Safe with compose-resources setup because
            // the Res.string.* references are tracked statically.
            isShrinkResources = true
            // Wire the release signingConfig only when all four QBS_*
            // env vars are present. Skipping the assignment entirely on
            // a fresh clone (rather than wiring an empty config) avoids
            // any chance of AGP's signing validation firing on a
            // misconfigured build. Result: a fresh clone produces an
            // unsigned release APK, which is exactly what we want for
            // a contributor who only has the source.
            if (System.getenv("QBS_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
