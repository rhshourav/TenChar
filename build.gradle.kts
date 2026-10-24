// AGP 9 bundles Kotlin Gradle Plugin 2.2.10 and uses it unless a newer one is
// on the build classpath. Keep this version equal to `kotlin` in
// gradle/libs.versions.toml. The version catalog is not available inside
// buildscript {}, so the value is repeated here.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
