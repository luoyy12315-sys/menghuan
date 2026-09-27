# 导师 · R8 规则（release 压缩/混淆）
# Compose / Room / OkHttp / Okio / coroutines / DataStore 都自带 consumer rules，无需手写 keep。
# 本 App 无反射取类（无 Gson/Retrofit），Manifest 组件由 AGP 自动 keep。

# 崩溃堆栈保留行号，自用排错可读；体积影响 <10KB
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
