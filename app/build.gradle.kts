plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "com.menghuan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.menghuan.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 171
        versionName = "0.82.0"
        // 只保留中英文资源，去掉依赖库里几十种语言的 strings/日期符号
        resourceConfigurations.addAll(listOf("zh", "zh-rCN", "en"))
        // sherpa-onnx AAR 自带 4 套 ABI（arm64/v7a/x86/x86_64）共 127MB so，
        // 打进一个包要 120MB+。x86 只活在模拟器/少数 x86 平板上，真机用不到 → 砍掉。
        // v0.50.0 再去掉 armeabi-v7a：22MB so 只为兼容 2017 年前的 32 位老机，
        // 而这批机器本来也跑不动 228MB 的 SenseVoice 离线识别（用户已确认取舍）。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    // sherpa-onnx 从 assets 读模型走 NDK AAssetManager：**压缩过的 asset 无法随机读**
    // （官方示例同样关闭压缩）。v0.50.0 起 assets 里只剩 silero_vad.onnx
    // （识别大模型改成装后下载到 filesDir，走普通文件路径，不经 AAssetManager），
    // 所以只需保 onnx；原来一起写的 txt 是给内置 tokens.txt 用的，现已无用。
    androidResources {
        noCompress.addAll(listOf("onnx"))
    }

    // Live2D Cubism Framework 源码（v0.58.0）：官方 SDK 的 105 个 .java 原样放在 cubism/ 下，
    // 当作本模块的额外源码目录编进来（**不能建成 library 模块**：它依赖的闭源 Core 是本地 .aar，
    // AGP 禁止 library 模块带本地 .aar 出 AAR）。目录独立 = 升级 SDK 时替换整个 cubism/ 就行，
    // 不和 app 自己的代码混在一起。
    sourceSets {
        getByName("main") {
            java.srcDirs("../cubism/framework/src/main/java")
            // ⚠️ **这条不能省**（v0.60.3 真机才查出来）：Cubism Java 5-r.5 的 GLSL 源码
            // **不是内嵌在 Java 里的**，`CubismShaderAndroid` 运行时按
            // `com/live2d/sdk/cubism/framework/shaders/standardES/*.vert|.frag` 去读文件。
            // 官方 Framework 是 library 模块、这些文件放在它的 `src/main/assets` 下会自动打进 AAR；
            // 我们只挂了 `java.srcDirs` 把源码搬进 app 模块，**assets 就整个漏掉了**
            // → 一个着色器都编译不出来 → 模型加载"成功"、`ready=true`、零异常，
            // 但 `drawModel()` 什么也不画，屏幕上永远是一片透明/白。
            // ⚠️ 用 **srcDir（单数，追加）** 而不是 srcDirs：后者的语义是"替换"，
            // 一旦替换会把模块自己的 `app/src/main/assets`（里面有离线识别用的 asr/silero_vad.onnx）整个挤掉。
            assets.srcDir(rootProject.file("cubism/framework/src/main/assets"))
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
    // 窗口宽度等级（平板/折叠屏 >=600dp 宽版式的唯一判源）
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 离线大模型识别用不上：相机（自绘拍摄界面，v0.43.0 加号面板的「拍摄」）
    // 只用 core/camera2/lifecycle/view 四个，不要 camera-compose（API 尚不稳定，
    // 这里用 AndroidView 包 PreviewView，兼容性和可控性都更好）
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 端侧离线语音识别（v0.44.1）：k2-fsa/sherpa-onnx 官方 AAR（Apache-2.0），
    // 自带 4 个 ABI 的 onnxruntime/jni so；中文流式模型放在 src/main/assets/asr/
    // （见 SherpaAsr）。proguard-rules.pro 里有对应 keep 规则。
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // Live2D Cubism Core（v0.58.0 视频通话的闭源渲染内核）：官方 AAR，
    // 官方 Core/RedistributableFiles.txt 明确允许随应用分发这个文件。
    // Framework 那层开源 Java 源码走 sourceSets 的额外 srcDir（见上面 android.sourceSets）。
    // ⚠️ 用 rootProject.file 而不是相对路径：模块目录是 app/，源码却放在仓库根的 cubism/ 下。
    implementation(files(rootProject.file("cubism/libs/Live2DCubismCore.aar")))

    // 伪视频通话（v0.58.0）：Media3 ExoPlayer 播本地 mp4。
    // 只引 exoplayer，不引 media3-ui —— 通话页要的是"切状态不黑屏"，
    // PlayerView 那块 exo_shutter 反而是黑来源；自己挂 TextureView 给播放器更可控，
    // 顺带省掉 media3-ui 的 330KB。解码走系统 MediaCodec，不新增 so。
    //
    // ⚠️ **版本钉死在 1.9.0，不要顺手升级**：1.10 起 AAR metadata 的 minCompileSdk=36，
    // 本工程 compileSdk=35 + AGP 8.7.3 会在 `:app:checkReleaseAarMetadata` 直接构建失败
    // （实测 1.11.1 报 "requires libraries and applications to compile against version 36"）。
    // 要升 media3 就得先升 compileSdk 到 36 并换支持它的 AGP，那是另一件事。
    implementation("androidx.media3:media3-exoplayer:1.9.0")

    // 本地数据库：对话 / 人设 / 任务
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 用户配置（API 地址、密钥引用、当前人设等）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // LLM 流式请求（OpenAI 兼容 SSE）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 桌面小组件（今日任务：黑底白字 3 条，点击直达任务页）
    implementation("androidx.glance:glance-appwidget:1.1.1")

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
