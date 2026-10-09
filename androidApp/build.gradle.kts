import java.util.Properties

// 中継の場所は tailnet の名前を含むので、公開するソースに書かず local.properties（git に入れない）から読む（Phase 6）
val localProperties = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // 端末の設定で、アプリだけの言語（English / 日本語）を選べるようにする
    androidResources {
        generateLocaleConfig = true
    }
    namespace = "xyz.mooma0750.wave"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.mooma0750.wave"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "RELAY_URL", "\"" + (localProperties.getProperty("wave.relayUrl") ?: "") + "\"")
        buildConfigField("String", "RELAY_HOST", "\"" + (localProperties.getProperty("wave.relayHost") ?: "") + "\"")
        // Connect v2（Phase 6b）。無ければ v2 は動かない（L-9: v1 と並べて動かす）
        buildConfigField("String", "RELAY_V2_URL", "\"" + (localProperties.getProperty("wave.relayV2Url") ?: "") + "\"")
        buildConfigField("String", "RELAY_V2_HOST", "\"" + (localProperties.getProperty("wave.relayV2Host") ?: "") + "\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 画面は Material 3 Expressive で作る（AGENTS.md）。Expressive の部品はまだ実験扱いなので、モジュール全体で opt-in する
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Phase 6 のペアリングの QR（表示は ZXing、読み取りは CameraX の画像を ZXing で解く）
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}
