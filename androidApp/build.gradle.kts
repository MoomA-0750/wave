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
        // 端末の連携の中継（Connect v2、ADR-0010）。無ければ連携は使えない
        buildConfigField("String", "RELAY_V2_URL", "\"" + (localProperties.getProperty("wave.relayV2Url") ?: "") + "\"")
        buildConfigField("String", "RELAY_V2_HOST", "\"" + (localProperties.getProperty("wave.relayV2Host") ?: "") + "\"")
    }

    // スマホ（phone）と時計（wear）を同じソースから組む。applicationId を同じにする（時計とスマホの間で
    // Data Layer を使うには、同じパッケージ名・同じ署名が要る）。共通の部品は main、画面はそれぞれの flavor に置く
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
        }
        create("wear") {
            dimension = "device"
            // Galaxy Watch 7 は Wear OS 5（API 34）以降
            minSdk = 34
            // スマホ版と同じ applicationId で並べて配るため、versionCode を分ける（時計の方を大きく）
            versionCode = 100001
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 「Claude に聞く」のシステムプロンプトと道具の定義（ai/agent.json。Mac/iOS・Windows と同じファイル）
    sourceSets["main"].assets.srcDir(rootProject.file("ai"))
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
    implementation(libs.androidx.compose.runtime)
    "phoneImplementation"(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Phase 6 のペアリングの QR（表示は ZXing、読み取りは CameraX の画像を ZXing で解く）
    implementation(libs.zxing.core)
    // ホーム画面のウィジェット（再生中の曲・プレイリスト）。時計には無い
    "phoneImplementation"(libs.androidx.glance.appwidget)
    "phoneImplementation"(libs.androidx.glance.material3)
    "phoneImplementation"(libs.androidx.camera.camera2)
    "phoneImplementation"(libs.androidx.camera.lifecycle)
    "phoneImplementation"(libs.androidx.camera.view)
    // スマホと時計の間でログインを渡す
    implementation(libs.play.services.wearable)
    // 時計の画面
    "wearImplementation"(libs.wear.compose.material3)
    "wearImplementation"(libs.wear.compose.foundation)
    "wearImplementation"(libs.androidx.graphics.shapes)
    "wearImplementation"(libs.wear.input)
    "wearImplementation"(libs.wear.ongoing)
    "wearImplementation"(libs.wear.phone.interactions)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}
