import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidLibrary {
        namespace = "com.zucham.qbsmarter.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    // iOS targets are intentionally not declared here.
    //
    // korender (the 3D engine that powers the cube view) does not publish
    // iOS variants – only Android, JVM-desktop, JS, and WASM. As long as
    // korender types pervade the common cube model (Vec3, Quaternion,
    // Transform, TouchEvent), iOS cannot consume commonMain.
    //
    // When iOS support is needed, options are:
    //   (a) wait for korender to add iOS variants, OR
    //   (b) wrap korender behind an expect/actual seam and provide an
    //       iOS-only renderer (Metal, SceneKit, etc.).
    //
    // For now we ship Android + desktop + web stubs only.

    jvm()

    // Removing webApp targets - breaks with korender
//    js {
//        browser()
//    }
//
//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        browser()
//    }

    // -- Source-set hierarchy ------------------------------------------------
    //
    // We use the "default" hierarchy plus one intermediate source set:
    //
    //   common
    //   ├── android
    //   ├── jvm
    //   └── web (intermediate)
    //       ├── js
    //       └── wasmJs
    //
    // The `web` group lets us share platform stubs (BleManager, DriverFactory,
    // etc.) across both browser-bound targets without copy-pasting actuals.
    applyDefaultHierarchyTemplate()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonMain.dependencies {
            // Compose Multiplatform UI
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.materialIconsCore)
            implementation(libs.compose.materialIconsExtended)

            // Lifecycle / ViewModel / Navigation (multiplatform)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)

            // Kotlinx
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)

            // 3D
            implementation(libs.korender)

            // Persistence
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // No multiplatform-settings dependency: all settings live in
            // the SQLDelight `settings` table per profile (see
            // SettingsRepository) so a single backing store covers
            // hot-path toggles AND profile-scoped preferences.

            // DI: BOM keeps every koin-* artifact at the same version.
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)

            // Logging
            implementation(libs.kermit)

            // Markdown rendering for the Usage Guide screen. Pure Compose,
            // no WebView; the -m3 artifact pulls in Material3-themed
            // defaults that pick up our theme tokens automatically.
            implementation(libs.multiplatform.markdown.renderer)
            implementation(libs.multiplatform.markdown.renderer.m3)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.android)
            implementation(libs.koin.android)
        }

        jvmMain.dependencies {
            implementation(libs.sqldelight.jvm)
            implementation(libs.kotlinx.coroutinesSwing)
        }

//        // Web (js + wasmJs) intermediate source set
//        val webMain by getting {
//            dependencies {
//                // sqldelight-web requires npm bits to actually run; we only
//                // declare it so the stubs compile against the same API.
//                implementation(libs.sqldelight.web)
//            }
//        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

sqldelight {
    databases {
        create("QbsmarterDatabase") {
            packageName.set("com.zucham.qbsmarter.db")
            // Default dialect is SQLite 3.18, which lacks UPSERT
            // (ON CONFLICT ... DO UPDATE, added in SQLite 3.24).
            // Bump to 3.25 – Android API 31+ ships SQLite 3.32+,
            // so this is safe.
            dialect("app.cash.sqldelight:sqlite-3-25-dialect:${libs.versions.sqldelight.get()}")
        }
    }
}

