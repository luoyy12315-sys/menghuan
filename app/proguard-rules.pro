# 梦欢 · R8 规则（release 压缩/混淆）
# Compose / Room / OkHttp / Okio / coroutines / DataStore 都自带 consumer rules，无需手写 keep。
# 本 App 无反射取类（无 Gson/Retrofit），Manifest 组件由 AGP 自动 keep。

# 崩溃堆栈保留行号，自用排错可读；体积影响 <10KB
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sherpa-onnx（v0.44.1 端侧语音识别）：Kotlin 数据类的字段（ptr/modelConfig 等）
# 由 JNI 按名字访问，混淆后 native 层找不到字段会直接崩 → 整包 keep
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Live2D Cubism Core（v0.58.0 视频通话）：com.live2d.sdk.cubism.core 下的类由
# libLive2DCubismCoreJNI.so 通过 JNI 按类名/字段名访问（instantiateMoc / syncToNativeModel 等 13 个
# 导出符号都绑在这些描述符上），混淆即崩 —— 和 sherpa-onnx 是同一条教训。
# ⚠️ 官方 Framework 自带的 consumer-rules.pro 是**空文件**，规则不会自动进来，只能写在这。
-keep class com.live2d.sdk.cubism.core.** { *; }
# Framework 层（com.live2d.sdk.cubism.framework.*）是普通 Java 源码、无反射，交给 R8 正常收缩。
