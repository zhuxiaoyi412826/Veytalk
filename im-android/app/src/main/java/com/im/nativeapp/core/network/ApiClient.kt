package com.im.nativeapp.core.network

import com.im.nativeapp.core.config.SessionStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态网络客户端：服务器地址被修改后，下一次 service() 调用返回按新地址重建的实例。
 *
 * 为什么不把 Retrofit 交给 Hilt 当 @Singleton 一次建死：
 * 登录页要求「改地址立即生效」，而登录页恰恰是没有登录态、依赖网络层的地方。
 * 这里用最朴素的「按需重建 + 双检锁」满足它，比给 Hilt 做 provider 重载直白得多。
 */
@Singleton
class ApiClient @Inject constructor(
    private val session: SessionStore
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val lock = Any()
    @Volatile private var builtFor: String? = null
    @Volatile private var retrofit: Retrofit? = null
    private val serviceCache = mutableMapOf<Class<*>, Any>()

    /** 鉴权拦截器独立成属性：Retrofit 与 Coil/ExoPlayer 的媒体客户端共用同一份逻辑 */
    private val authInterceptor = okhttp3.Interceptor { chain ->
        val builder = chain.request().newBuilder()
            // 后端 TraceIdFilter 认这个头并原样回写，客户端日志能与服务器日志对上
            .header("X-Trace-Id", newTraceId())
        val token = session.currentToken()
        if (token.isNotEmpty()) {
            // 头名取自后端下发的 tokenName，不写死 satoken（对齐 token.js 的教训）
            builder.header(session.currentTokenName(), token)
        }
        chain.proceed(builder.build())
    }

    /**
     * 带鉴权头与 TraceId 的 OkHttpClient，给 Coil 图片加载与 ExoPlayer 语音播放用。
     * 不随服务器地址重建：头是拦截时动态读的，base URL 由调用方拼。
     */
    val mediaHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
    }

    fun <T> service(api: Class<T>): T {
        val base = session.apiBase()
        retrofit?.let { r ->
            if (builtFor == base) {
                @Suppress("UNCHECKED_CAST")
                return serviceCache[api] as? T ?: r.create(api).also { serviceCache[api] = it as Any }
            }
        }
        return synchronized(lock) {
            val r = retrofit
            if (r != null && builtFor == base) {
                @Suppress("UNCHECKED_CAST")
                serviceCache[api] as? T ?: r.create(api).also { serviceCache[api] = it as Any }
            } else {
                val fresh = build(base)
                retrofit = fresh
                builtFor = base
                serviceCache.clear()
                fresh.create(api).also { serviceCache[api] = it as Any }
            }
        }
    }

    /** 兼容 M0 的认证入口调用方式 */
    fun api(): AuthApi = service(AuthApi::class.java)

    private fun build(apiBase: String): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl("$apiBase/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    private fun newTraceId(): String {
        val ts = System.currentTimeMillis().toString(36)
        val rand = (1..8).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
        return "android-$ts-$rand"
    }
}
