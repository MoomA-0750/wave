// ほかのアプリ向けの API（wave-api、ADR-0015）。中継の機械で常駐し、家の端末の1つとして家の再生を読み・操作し、HTTP で出す。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("xyz.mooma0750.wave.api.MainKt")
    applicationName = "api"
}

dependencies {
    implementation(project(":handoff-protocol"))
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}
