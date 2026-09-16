@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "xyz.mooma0750.wave.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // Phase 1 では共通コードをホストで速く試すためのターゲット（Windows 版の実装方針とは無関係）
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(libs.innertubex)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
    }
}
