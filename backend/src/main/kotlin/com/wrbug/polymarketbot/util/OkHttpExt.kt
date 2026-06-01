package com.wrbug.polymarketbot.util

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * 获取代理配置（用于 WebSocket 和 HTTP 请求）
 * 从数据库读取代理配置
 * @return Proxy 对象，如果未启用代理则返回 null
 */
fun getProxyConfig(): Proxy? {
    return ProxyConfigProvider.getProxy()
}

/**
 * 创建OkHttpClient客户端
 * 自动应用代理配置（从数据库读取）
 * @return OkHttpClient.Builder
 */
fun createClient(): OkHttpClient.Builder {
    val builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)

    // 从数据库读取代理配置
    val dbProxy = ProxyConfigProvider.getProxy()
    if (dbProxy != null) {
        builder.proxy(dbProxy)
        builder.createSSLSocketFactory()

        // 如果配置了用户名和密码，添加代理认证
        val username = ProxyConfigProvider.getProxyUsername()
        val password = ProxyConfigProvider.getProxyPassword()
        if (username != null && password != null) {
            builder.proxyAuthenticator { _, response ->
                val credential = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
    }

    return builder
}

/**
 * 为OkHttpClient创建信任所有证书的SSL工厂
 * @return OkHttpClient.Builder
 */
fun OkHttpClient.Builder.createSSLSocketFactory(): OkHttpClient.Builder {
    return apply {
        try {
            val sc: SSLContext = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<TrustManager>(TrustAllManager()), SecureRandom())
            sslSocketFactory(sc.socketFactory, TrustAllManager())
        } catch (t: Error) {

        }
    }
}

/**
 * 信任所有证书的TrustManager
 */
class TrustAllManager : X509TrustManager {
    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {
    }

    override fun getAcceptedIssuers() = arrayOfNulls<X509Certificate>(0)
}

/**
 * 信任所有主机名的HostnameVerifier
 */
class TrustAllHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        return true
    }
}

