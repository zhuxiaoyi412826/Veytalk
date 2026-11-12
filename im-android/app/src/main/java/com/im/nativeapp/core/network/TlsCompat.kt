package com.im.nativeapp.core.network

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS 兼容层。
 *
 * 当前生产环境用 IP 直连（https://120.78.126.132），服务器上装的是自签证书（CN=im.dsaol.asia），
 * 既不在系统根证书链里、也没有 IP SAN，标准校验必然失败（SSLHandshakeException）。
 *
 * 这里只在「当前服务器地址是 IP 字面量」时放开校验（[allowInsecure] 由 SessionStore 按地址刷新）；
 * 一旦切回域名（或换正式证书），自动恢复完整校验，不需要改代码。
 *
 * 风险：IP 直连期间无法防御中间人攻击，域名备案/证书就绪后应尽快切回域名。
 */
object TlsCompat {

    /** 是否放开 TLS 校验；由 SessionStore 在 init/setServer 时按当前服务器地址写入 */
    @Volatile
    var allowInsecure: Boolean = false

    /** 系统默认信任链，正常证书仍走它校验 */
    private val defaultTrustManager: X509TrustManager = run {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                defaultTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                // 自签 / 过期 / 主机名不符：只在 IP 直连模式下放行
                if (!allowInsecure) throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.acceptedIssuers
    }

    private val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }.socketFactory
    }

    /** 给所有 OkHttp 客户端统一装上：Retrofit、Coil、ExoPlayer、WS 共用同一套校验策略 */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .sslSocketFactory(sslSocketFactory, trustManager)
        .hostnameVerifier { hostname, session ->
            // IP 直连时证书里不会有该 IP 的 SAN，主机名校验必然失败，按开关放行
            allowInsecure || HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        }
}
