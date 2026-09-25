// ツールチェーンは実証済みの組合せにピン留めする。
// (Gradle 8.14.4 / AGP 8.13.2 / Kotlin 2.4.10 / compileSdk 36)
// バージョン上げは必要が生じたフェーズでのみ行う。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
