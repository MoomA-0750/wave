// 端末間の受け渡しの中継（Phase 6、ADR-0008）。暗号文を振り分けるだけで、本文・曲・アカウントを知らない。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("xyz.mooma0750.wave.relay.MainKt")
}

dependencies {
    implementation(project(":handoff-protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}
