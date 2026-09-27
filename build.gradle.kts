plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // JVM 截图（Robolectric RNG）：提供 recordRoborazziDebug 等任务
    id("io.github.takahirom.roborazzi") version "1.26.0" apply false
}
