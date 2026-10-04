# 梦欢 · R8 规则（release 压缩/混淆）
# Compose / Room / OkHttp / Okio / coroutines / DataStore 都自带 consumer rules，无需手写 keep。
# 本 App 无反射取类（无 Gson/Retrofit），Manifest 组件由 AGP 自动 keep。

# 崩溃堆栈保留行号，自用排错可读；体积影响 <10KB
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sherpa-onnx（v0.44.1 端侧语音识别）：Kotlin 数据类的字段（ptr/modelConfig 等）
# 由 JNI 按名字访问，混淆后 native 层找不到字段会直接崩 → 整包 keep
-keep class com.k2fsa.sherpa.onnx.** { *; }
