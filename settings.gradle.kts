@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // innertubex は JitPack からだけ取り、JitPack からは innertubex だけを取る
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.MetrolistGroup.innertubex") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "wave"

include(":handoff-protocol")
include(":core")
include(":androidApp")
include(":relay")
