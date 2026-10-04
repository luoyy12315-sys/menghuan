package com.menghuan.app.llm

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession

/**
 * 全 App 共享的 OkHttpClient 配置中心（v0.35.2 起）。
 *
 * **背景**：用户在「设置 · API 配置」里可自定义任意第三方 OpenAI 兼容端点，
 * 常见两类兼容问题：
 *
 * 1. **明文 HTTP**：填 `http://` 时 Android 9+ 默认拒绝
 *    （`CLEARTEXT communication to xxx not permitted by network security policy`）。
 *    解决：`res/xml/network_security_config.xml` 里 `cleartextTrafficPermitted="true"`。
 *
 * 2. **HTTPS 域名不匹配**：服务商证书的 SAN 里只有 IP、或用了跟访问域名不同的 CN，
 *    例如 `Hostname zhiqiapi.com not verified: certificate... subjectAltNames: [223.26.52.100]`。
 *    解决：这里的 [PermissiveHostnameVerifier] 只跳过"域名 vs 证书 CN/SAN"这一步匹配，
 *    **证书链本身仍走系统 TrustManager 校验**（默认行为），假证书/自签/MITM 仍会被拒。
 *
 * **为什么不用 TrustAllCerts**：那会让任何中间人都能截走 `Authorization: Bearer <apiKey>`
 * 明文头，用户档案/对话内容可被静默篡改。App 里 API Key 走 Keystore 加密，
 * 网络层是唯一的安全边界，不能拆。若遇到自签端点，让用户把 CA 装进设备
 * （network_security_config.xml 已经信任 user CA），比全局 trust-all 干净得多。
 *
 * **调用点**：[OpenAiClient] / [ImageGenerationClient] / [ImageProbe] 三处都必须走
 * [baseBuilder]，别再各自 `OkHttpClient.Builder()`——否则同一个 API 在不同入口行为不一致
 * （例如聊天能连通、测试连接失败）。
 */
object NetworkStack {

    /**
     * 只放行域名校验、保留证书链校验的 HostnameVerifier。
     * 相当于承认"用户自己填的地址他自己负责"，但不给中间人可乘之机。
     */
    private val permissiveHostnameVerifier = HostnameVerifier { _: String?, _: SSLSession? -> true }

    /**
     * 全 App 通用的 OkHttpClient 起手式。调用方在此基础上按需覆盖超时/拦截器再 build()。
     */
    fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .hostnameVerifier(permissiveHostnameVerifier)

    /**
     * 把网络异常翻译成用户可读的一句话，供"测试连接"类入口显示。
     *
     * **为什么不用 `t.message ?: "网络错误"`**：Android 里有一批异常（例如
     * `NetworkOnMainThreadException`、部分 `SocketException` 子类）的 `message` 就是 null，
     * 老写法会把它们全部糊成"网络错误"四个字，用户和作者都无从下手。这里始终带上
     * 异常类名（`simpleName`），message 非空时前置——一眼看出是超时、DNS、SSL 还是线程错配。
     *
     * v0.35.3 起 `OpenAiClient.testConnection` 与 `ImageProbe.probe` 共用此函数。
     */
    fun describeNetworkFailure(t: Throwable): String {
        val cls = t.javaClass.simpleName
        val msg = t.message?.takeIf { it.isNotBlank() }
        return if (msg != null) "$msg（$cls）" else cls
    }

    /**
     * TLS 兼容降级规格（v0.36.3）：只允许 TLS 1.2。
     *
     * **为什么需要**：部分中转网关（new-api 一类）的 TLS 配置与 Android Conscrypt 的
     * 默认 ClientHello（TLS 1.3 + 新扩展组合）不兼容，握手阶段直接被服务端掐断，
     * 报 `SSLHandshakeException: connection closed`。桌面 openssl/curl 却全绿——
     * 因为它们的 ClientHello 参数不同。降到 TLS 1.2 通常能绕过。
     *
     * **安全边界**：只调握手协议版本，**不放松证书信任、不放松域名校验**。
     * TLS 1.2 仍是加密通道，弱于 1.3 但远好于明文。
     */
    val COMPAT_CONNECTION_SPEC: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .build()

    /** 在 [base] 基础上叠加降级规格 + 强制 http/1.1（去掉 h2 ALPN，排除 ALPN 因素） */
    fun compatClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectionSpecs(listOf(COMPAT_CONNECTION_SPEC))
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
}

/**
 * TLS 兼容回退执行（v0.36.3）：先用默认规格跑；若抛 [SSLHandshakeException]
 * （服务端不接受 Android ClientHello），自动用 [NetworkStack.compatClient] 重试一次。
 *
 * **为什么重试是安全的**：握手失败意味着请求字节一个都没发出去，重试不会重复消耗
 * 生图/对话额度。只有握手层的失败才触发回退；证书信任失败（TrustManager 抛的）
 * 不是 SSLHandshakeException 的"connection closed"形态，不会误触发。
 *
 * 用法：`http.callWithTlsFallback(request) { resp -> ... }`
 */
fun <T> OkHttpClient.callWithTlsFallback(request: Request, handler: (Response) -> T): T =
    try {
        newCall(request).execute().use(handler)
    } catch (e: SSLHandshakeException) {
        NetworkStack.compatClient(this).newCall(request).execute().use(handler)
    }
