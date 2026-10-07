pluginManagement {
    repositories {
        // 国内镜像必须放在 google()/mavenCentral() 之前，否则 dl.google.com 直连超时
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
    }
}
rootProject.name = "DaoshiApp"
include(":app")
// Live2D Cubism Framework（v0.58.0 视频通话视觉层）：**不建成独立模块**。
// 官方 SDK 的 Framework 是个 library 模块，而它 `compileOnly` 的闭源 Core 是**本地 .aar**——
// AGP 明确禁止 library 模块带本地 .aar 依赖出 AAR
// （实测报 "Direct local .aar file dependencies are not supported when building an AAR"，
//   :cubism-framework:bundleReleaseLocalLintAar 直接 FAILED）。
// 所以 Framework 的源码当作 app 的额外 srcDir 编进来（见 app/build.gradle.kts 的 sourceSets），
// Core AAR 由 app 直接 implementation(files(...)) —— 和 app/libs/sherpa-onnx-1.13.8.aar 同一种接法。
