package com.im.nativeapp.core.config

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.im.nativeapp.core.network.TlsCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore by preferencesDataStore(name = "im_session")

/** 服务器环境预设（生产 / 测试），登录页与设置页共用 */
data class ServerEnv(val key: String, val label: String, val url: String)

/**
 * 会话与服务器地址的持久层（对应 Web 端 utils/token.js + env.js 的 override 逻辑）。
 *
 * 为什么用「内存缓存 + 启动时 runBlocking 预热」而不是全链路 suspend/Flow：
 * OkHttp 拦截器、WS URL 拼装这些地方是同步上下文，拿不到挂起函数；
 * 而登录页的「保存后立即生效」又要求改完立刻能被下一个请求读到。
 * 预热后所有读取都是纯内存操作，写入才走 DataStore。
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        /** 生产环境：暂时用公网 IP 直连（域名未备案被阻断），走 nginx 443 */
        const val PROD_SERVER = "https://120.78.126.132"
        /** 生产域名：备案接入完成后可切回（已备案域名解析到同一台机器） */
        const val PROD_DOMAIN = "https://im.dsaol.asia"
        /** 测试环境：局域网直连后端，保留原有的出厂地址 */
        const val TEST_SERVER = "http://192.168.1.2:8080"
        /** 出厂默认后端地址：清空覆盖即恢复到这里 */
        const val DEFAULT_SERVER = PROD_SERVER

        /** 环境预设：登录页与设置页共用，顺序即展示顺序 */
        val SERVER_ENVS = listOf(
            ServerEnv("prod", "生产环境", PROD_SERVER),
            ServerEnv("test", "测试环境", TEST_SERVER)
        )

        /** 当前地址命中的预设 key；自定义地址返回 null */
        fun envKeyOf(url: String): String? = SERVER_ENVS.firstOrNull { it.url == url }?.key

        /** 流媒体与后端同机部署，端口固定 8088（对应 Web 端 env.js 的 LIVE_MEDIA_PORT） */
        const val MEDIA_PORT = 8088
        /** sa-token 默认头名，仅在本地没有记录时兜底（对应 token.js 的 DEFAULT_TOKEN_NAME） */
        const val DEFAULT_TOKEN_NAME = "satoken"
        /** DeviceType 枚举只认 web/pc/android/ios/mini，原生安卓报 android */
        const val DEVICE_TYPE = "android"
        /** 设备友好名（X-Device-Name）的最大长度，与后端 DeviceIds.MAX_NAME_LENGTH 对齐 */
        private const val DEVICE_NAME_MAX = 50

        private val KEY_SERVER = stringPreferencesKey("server_base")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_TOKEN_NAME = stringPreferencesKey("token_name")
    }

    private val store = context.sessionStore

    /**
     * 本机实例 ID：取 ANDROID_ID。
     *
     * Android 8 起它是「同一设备 + 同一签名密钥 + 同一用户」稳定的值，恢复出厂会变，
     * 不需要自己往 DataStore 里写；拿不到（极少数定制 ROM）时退化为本次进程随机一个，
     * 宁可让这台设备在列表里多出一行，也不能拿裸类型码去登录——那等于关掉了同端顶号。
     */
    private val deviceUid: String = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    /**
     * 设备位 类型:实例（对应 Web 端 token.js 的 getDeviceId）。
     *
     * 登录、注册、WS 票据三处必须用同一个值：后端拿它定位 Sa-Token 会话、
     * WS 注册表键与 im_user_device 的行，三处不同源就会出现「会话踢中了、socket 还活着」。
     */
    val deviceSlot: String get() = "$DEVICE_TYPE:$deviceUid"

    /**
     * 设备友好名（品牌 + 型号），只用于展示与被顶时的提示文案。
     *
     * 只留可打印 ASCII：这个值走 HTTP 请求头，而容器按 ISO-8859-1 解头，
     * 塞中文进去到那边就是一串乱码（部分定制 ROM 的 MODEL 确实是中文）。
     * 洗完全空就不发这个头，后端会用 User-Agent 兜底。
     */
    val deviceName: String? = run {
        val raw = listOf(Build.BRAND, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .filter { it.code in 0x20..0x7E }
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(DEVICE_NAME_MAX)
        raw.ifEmpty { null }
    }

    @Volatile private var serverBase: String = DEFAULT_SERVER
    @Volatile private var token: String = ""
    @Volatile private var tokenName: String = DEFAULT_TOKEN_NAME

    /** ImApp.onCreate 里调用：把 DataStore 里的值灌进内存缓存，之后全部走同步读取 */
    suspend fun init() {
        val prefs = store.data.first()
        serverBase = normalizeServer(prefs[KEY_SERVER]) ?: DEFAULT_SERVER
        token = prefs[KEY_TOKEN] ?: ""
        tokenName = prefs[KEY_TOKEN_NAME] ?: DEFAULT_TOKEN_NAME
        syncTlsMode()
    }

    /** 地址是 IP 字面量时（自签证书无 IP SAN）放开 TLS 校验，切回域名自动恢复 */
    private fun syncTlsMode() {
        val host = serverBase.substringAfter("://").substringBefore('/').substringBefore(':')
        TlsCompat.allowInsecure = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
    }

    // ---------- 服务器地址 ----------

    fun currentServer(): String = serverBase

    /**
     * 归一化用户输入：去空格与尾部斜杠；没带协议头时按 http 处理。
     * 返回 null 表示「空输入 = 清除覆盖，恢复默认」。
     */
    fun normalizeServer(raw: String?): String? {
        val v = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return if (v.startsWith("http://") || v.startsWith("https://")) v else "http://$v"
    }

    /** 保存服务器地址；传空/空白 = 恢复出厂默认。写入后 currentServer() 立即返回新值 */
    suspend fun setServer(raw: String?) {
        serverBase = normalizeServer(raw) ?: DEFAULT_SERVER
        store.edit { it[KEY_SERVER] = serverBase }
        syncTlsMode()
    }

    /** REST 根地址：{server}/api */
    fun apiBase(): String = "$serverBase/api"

    /**
     * WS 根地址：http→ws、https→wss（对应 Web 端 wsBaseURL 的壳分支）。
     * 票据接口返回的 endpoint（/ws）与 ticket 由调用方拼在后面。
     */
    fun wsBase(): String = serverBase.replace(Regex("^http", RegexOption.IGNORE_CASE), "ws")

    /**
     * 流媒体地址：
     * - https（生产域名，nginx 反代）→ 同源 443，直接复用 serverBase，不拼 8088
     * - http（局域网直连后端）→ host 换 8088 流媒体口（对应 Web 端 env.js 的 LIVE_MEDIA_PORT）
     */
    fun mediaBase(): String =
        if (serverBase.startsWith("https://", ignoreCase = true)) serverBase
        else serverBase.replace(Regex(":\\d+$"), "") + ":$MEDIA_PORT"

    /**
     * 把后端返回的相对地址（/api/file/download/{fileId} 这类受控地址）拼成完整 URL。
     * 完整 http(s) 地址原样返回。Coil/ExoPlayer 的请求都经 mediaHttpClient 带登录头。
     */
    fun resolveUrl(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val origin = serverBase
        return if (path.startsWith("/")) "$origin$path" else "$origin/$path"
    }

    // ---------- 登录凭证 ----------

    fun currentToken(): String = token
    fun currentTokenName(): String = tokenName
    fun isLoggedIn(): Boolean = token.isNotEmpty()

    suspend fun setToken(newToken: String, newTokenName: String?) {
        if (newToken.isNotEmpty()) {
            token = newToken
        }
        if (!newTokenName.isNullOrEmpty()) {
            tokenName = newTokenName
        }
        store.edit {
            it[KEY_TOKEN] = token
            it[KEY_TOKEN_NAME] = tokenName
        }
    }

    /** 清登录态。tokenName 保留：下次登录前拦截器仍需要正确的头名（对齐 token.js 的 clearToken） */
    suspend fun clearToken() {
        token = ""
        store.edit { it.remove(KEY_TOKEN) }
    }
}
