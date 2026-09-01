plugins {
    // AGP 8.7.x requires Gradle 8.9+; the wrapper here is pinned to 8.10.2,
    // comfortably above the "8.7 and up" requirement.
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
