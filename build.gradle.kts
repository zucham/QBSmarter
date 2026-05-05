// Root build.gradle.kts – declare plugin classpath only.

plugins {
    // apply false: each plugin is declared so that submodules can use the
    // alias() DSL, but only applied where needed.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sqldelight) apply false
}

val defaultTargetSdkVersion by extra(36)
