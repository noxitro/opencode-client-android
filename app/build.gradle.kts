plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.opencode.android"
    compileSdk = 36

    defaultConfig {
        // 非公式クライアント。opencode プロジェクトのドメインを名乗らない(Kotlin の namespace は
        // ソース配置の都合で据え置き。端末・ストア上の識別子はこちら)。
        applicationId = "io.github.noxitro.opencodeclient"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 配布は自前ビルドAPK想定のためdebug署名。ストア公開時は見直す。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // 設定(サーバーURL/Basic認証パスワード)の永続化。
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // HTTP / SSE。RESTはOkHttp直叩き+kotlinx.serialization(公式SDKはJS系のみのため)。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    // logging-interceptor は**あえて入れない**。Basic認証ヘッダと、`GET /provider` の
    // 応答に平文で載るプロバイダのAPIキー(spec `Provider.key`)を logcat に流す唯一の経路が
    // これであり、依存として置いておくと「デバッグのために1行足す」で開いてしまう。
    // 「付けない」を注釈ではなく**クラスパスに存在しないこと**で守る。

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Q2: Markdown描画。CommonMark+GFMのパースは JetBrains の org.intellij.markdown
    // (このライブラリの推移的依存)が行い、Compose側の描画だけを差し替えられる。
    // 選定理由と版の根拠は docs/API_CONTRACT.md ではなく報告書に記す。要点だけ:
    //  - 0.26.0 は 2026-08-27 時点の最新**安定**版(0.27.0 は rc/beta のみ)
    //  - `:app:dependencies` で確認したとおり、Compose の解決版を BOM 2024.12.01(=1.7.6)から
    //    **1つも動かさない**。ツールチェーンにも触れない
    //  - コードフェンスの言語ラベルは既定コンポーネントに無いので `markdownComponents(codeFence=…)`
    //    で差し替える(ui/MarkdownBlock.kt)
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.26.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
