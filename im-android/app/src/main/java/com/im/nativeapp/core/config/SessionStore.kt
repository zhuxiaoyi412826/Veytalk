package com.im.nativeapp.core.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore by preferencesDataStore(name = "im_session")

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
        /** 出厂默认后端地址（需求指定），清空覆盖即恢复到这里 */
        const val DEFAULT_SERVER = "http://192.168.1.2:8080"
        /** 流媒体与后端同机部署，端口固定 8088（对应 Web 端 env.js 的 LIVE_MEDIA_PORT） */
        const val MEDIA_PORT = 8088
        /** sa-token 默认头名，仅在本地没有记录时兜底（对应 token.js 的 DEFAULT_TOKEN_NAME） */
        const val DEFAULT_TOKEN_NAME = "satoken"
        /** DeviceType 枚举只认 web/pc/android/ios/mini，原生安卓报 android */
        const val DEVICE_ID = "android"

        private val KEY_SERVER = stringPreferencesKey("server_base")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_TOKEN_NAME = stringPreferencesKey("token_name")
    }

    private val store = context.sessionStore

    @Volatile private var serverBase: String = DEFAULT_SERVER
    @Volatile private var token: String = ""
    @Volatile private var tokenName: String = DEFAULT_TOKEN_NAME

    /** ImApp.onCreate 里调用：把 DataStore 里的值灌进内存缓存，之后全部走同步读取 */
    suspend fun init() {
        val prefs = store.data.first()
        serverBase = normalizeServer(prefs[KEY_SERVER]) ?: DEFAULT_SERVER
        token = prefs[KEY_TOKEN] ?: ""
        tokenName = prefs[KEY_TOKEN_NAME] ?: DEFAULT_TOKEN_NAME
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
    }

    /** REST 根地址：{server}/api */
    fun apiBase(): String = "$serverBase/api"

    /**
     * WS 根地址：http→ws、https→wss（对应 Web 端 wsBaseURL 的壳分支）。
     * 票据接口返回的 endpoint（/ws）与 ticket 由调用方拼在后面。
     */
    fun wsBase(): String = serverBase.replace(Regex("^http", RegexOption.IGNORE_CASE), "ws")

    /** 流媒体地址：跟随 serverBase 的 host，端口换 8088（对应 livePlayBase 的 override 分支） */
    fun mediaBase(): String = serverBase.replace(Regex(":\\d+$"), "") + ":$MEDIA_PORT"

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
