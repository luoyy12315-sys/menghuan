plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "com.daoshi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.daoshi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.12.0"
        // 只保留中英文资源，去掉依赖库里几十种语言的 strings/日期符号
        resourceConfigurations.addAll(listOf("zh", "zh-rCN", "en"))
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME：设置面板「关于」显示版本，避免文案过期
        buildConfig = true
    }

    // 自用分发：release 用 debug 签名直接安装，签名与已装 debug 包一致可覆盖升级
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // 瘦身：依赖库的版本清单与协程调试探针资源对运行时无用（堆栈恢复是尽力而为的能力）
    packaging {
        resources {
            excludes += setOf("META-INF/*.version", "DebugProbesKt.bin")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // JVM 截图测试：Robolectric RNG 用与真机相同的 Skia 管线渲染 Compose 页面出 PNG
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // android-all 系统镜像（~150MB）走阿里云镜像
                test.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
                test.systemProperty("robolectric.dependency.repo.id", "aliyun")
                // 不经 Gradle 插件直接跑测试时，需显式开启 Roborazzi 截图
                test.systemProperty("roborazzi.enabled", "true")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Room schema 导出（exportSchema = true 需要），迁移排错时 diff 相邻版本 JSON
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 本地数据库：对话 / 人设 / 任务
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 用户配置（API 地址、密钥引用、当前人设等）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // LLM 流式请求（OpenAI 兼容 SSE）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JVM 截图测试（Robolectric RNG + Roborazzi 截图）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
    // 纯 JVM 单测里用真 org.json（android.jar 的 org.json 是桩，方法直接抛异常）
    testImplementation("org.json:json:20231013")
    // SSE 客户端单测：本地 mock 服务器（走真实 socket，Robolectric/纯 JVM 均可用）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // ChatViewModel 状态机测试：可控的 Main 调度器（虚拟时间推进 delay）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
