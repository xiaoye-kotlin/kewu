# kewu_admin 正式打包（packageReleaseMsi 等）专用 ProGuard 规则
# 作用：抑制"可选依赖"的未解析引用警告。
# 这些类由 okhttp3 / ktor-client-okhttp 的 TLS 平台适配器与 slf4j 在编译期
# 引用，但在桌面 JVM 上并不会被加载（BouncyCastle / Conscrypt / OpenJSSE 仅
# 在 Android 或特定 JVM 上启用，slf4j 的绑定实现运行时由日志框架自行降级）。
# 若不抑制，ProGuard 会把警告当错误导致打包失败
# （"Please correct the above warnings first"）。

# okhttp3 的可选平台 TLS 适配器及其依赖
-dontwarn okhttp3.internal.platform.android.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# slf4j 在桌面端没有绑定实现（StaticLoggerBinder 等），运行时自动降级
-dontwarn org.slf4j.**

# 桌面端不需要 Android / Dalvik 平台类（okhttp / androidx 在 Android 才用到）
-dontwarn android.**
-dontwarn android.net.**
-dontwarn dalvik.**

# JBR（JetBrains Runtime）仅在用 JBR 运行时有意义，标准 JDK 上缺失无害
-dontwarn com.jetbrains.**
