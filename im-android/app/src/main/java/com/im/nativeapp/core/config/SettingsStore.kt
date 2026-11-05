package com.im.nativeapp.core.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "im_settings")

/**
 * 本机设置的同步快照：Compose 里直接读字段即可，不需要 suspend。
 * 每次写入后 [rev] 自增，收集它即可在设置变化时重组。
 */
object UiPrefs {
    private val _rev = MutableStateFlow(0)
    val rev: StateFlow<Int> = _rev

    @Volatile var themeColor: String = "green"
    @Volatile var fontScale: Float = 1.0f
    @Volatile var avatarSize: String = "md"        // sm/md/lg
    @Volatile var bubbleShape: String = "round"    // round/plain
    @Volatile var bubbleMeColor: String = ""       // 空=跟随主题默认
    @Volatile var bubblePeerColor: String = ""
    @Volatile var chatBgMode: String = "default"   // default/color
    @Volatile var chatBgColor: String = "#EDEDED"
    @Volatile var showPreview: Boolean = true
    @Volatile var showTimestamp: Boolean = true
    @Volatile var readReceipt: Boolean = true
    @Volatile var imageZoom: Boolean = true
    @Volatile var notifyEnabled: Boolean = true
    @Volatile var notifySound: Boolean = true
    @Volatile var watermark: Boolean = false
    @Volatile var allowSaveImage: Boolean = true
    @Volatile var debugLog: Boolean = false
    @Volatile var fontFamily: String = "system"     // system/serif/mono
    @Volatile var listDensity: String = "normal"    // compact/normal/loose
    @Volatile var chatBgImage: String = ""          // 内部存储绝对路径
    @Volatile var chatBgOpacity: Float = 1.0f       // 0.3~1.0（背景图不透明度）
    @Volatile var enterToSend: Boolean = false      // true=回车发送，false=回车换行
    @Volatile var autoLoadHistory: Boolean = true
    @Volatile var autoPlayVideo: Boolean = false
    @Volatile var pollUnread: Boolean = true
    @Volatile var mediaCacheEnabled: Boolean = true
    @Volatile var mediaCacheLimitMb: Int = 0        // 0=不限

    fun bump() { _rev.value++ }
}

/**
 * 本机设置持久层（对齐 Web 端 stores/settings.js 里「仅当前设备生效」的那一组）。
 *
 * 与 SessionStore 同款「内存缓存 + 启动预热」模式：Compose 读主题要同步返回，
 * 不能每次读都挂起。这些设置只影响本机表现，不回传服务端（对齐 Web 端 localStorage 语义）。
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")          // light/dark/system
        private val KEY_THEME_COLOR = stringPreferencesKey("theme_color")    // green/blue/purple/orange/red/dark
        private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")       // 0.85~1.5
        private val KEY_LIVE_QUALITY = stringPreferencesKey("live_quality")  // auto/480/720/1080/1440
        private val KEY_NOTIFY_SOUND = booleanPreferencesKey("notify_sound") // 新消息提示音
        private val KEY_NOTIFY_ON = booleanPreferencesKey("notify_on")       // 消息提醒总开关
        private val KEY_AVATAR = stringPreferencesKey("avatar_size")         // sm/md/lg
        private val KEY_BUBBLE_SHAPE = stringPreferencesKey("bubble_shape")  // round/plain
        private val KEY_BUBBLE_ME = stringPreferencesKey("bubble_me")
        private val KEY_BUBBLE_PEER = stringPreferencesKey("bubble_peer")
        private val KEY_CHAT_BG_MODE = stringPreferencesKey("chat_bg_mode")
        private val KEY_CHAT_BG_COLOR = stringPreferencesKey("chat_bg_color")
        private val KEY_SHOW_PREVIEW = booleanPreferencesKey("show_preview")
        private val KEY_SHOW_TIME = booleanPreferencesKey("show_time")
        private val KEY_READ_RECEIPT = booleanPreferencesKey("read_receipt")
        private val KEY_IMAGE_ZOOM = booleanPreferencesKey("image_zoom")
        private val KEY_WATERMARK = booleanPreferencesKey("watermark")
        private val KEY_ALLOW_SAVE_IMG = booleanPreferencesKey("allow_save_img")
        private val KEY_DEBUG_LOG = booleanPreferencesKey("debug_log")
        private val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        private val KEY_LIST_DENSITY = stringPreferencesKey("list_density")
        private val KEY_CHAT_BG_IMAGE = stringPreferencesKey("chat_bg_image")
        private val KEY_CHAT_BG_OPACITY = floatPreferencesKey("chat_bg_opacity")
        private val KEY_ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        private val KEY_AUTO_LOAD_HISTORY = booleanPreferencesKey("auto_load_history")
        private val KEY_AUTO_PLAY_VIDEO = booleanPreferencesKey("auto_play_video")
        private val KEY_POLL_UNREAD = booleanPreferencesKey("poll_unread")
        private val KEY_MEDIA_CACHE_ON = booleanPreferencesKey("media_cache_on")
        private val KEY_MEDIA_CACHE_MB = intPreferencesKey("media_cache_mb")
    }

    private val store = context.settingsStore

    @Volatile var themeMode: String = "system"
        private set
    @Volatile var liveQuality: String = "auto"
        private set
    @Volatile var notifySound: Boolean = true
        private set

    suspend fun init() {
        val p = store.data.first()
        themeMode = p[KEY_THEME] ?: "system"
        liveQuality = p[KEY_LIVE_QUALITY] ?: "auto"
        notifySound = p[KEY_NOTIFY_SOUND] ?: true
        UiPrefs.themeColor = p[KEY_THEME_COLOR] ?: "green"
        UiPrefs.fontScale = p[KEY_FONT_SCALE] ?: 1.0f
        UiPrefs.avatarSize = p[KEY_AVATAR] ?: "md"
        UiPrefs.bubbleShape = p[KEY_BUBBLE_SHAPE] ?: "round"
        UiPrefs.bubbleMeColor = p[KEY_BUBBLE_ME] ?: ""
        UiPrefs.bubblePeerColor = p[KEY_BUBBLE_PEER] ?: ""
        UiPrefs.chatBgMode = p[KEY_CHAT_BG_MODE] ?: "default"
        UiPrefs.chatBgColor = p[KEY_CHAT_BG_COLOR] ?: "#EDEDED"
        UiPrefs.showPreview = p[KEY_SHOW_PREVIEW] ?: true
        UiPrefs.showTimestamp = p[KEY_SHOW_TIME] ?: true
        UiPrefs.readReceipt = p[KEY_READ_RECEIPT] ?: true
        UiPrefs.imageZoom = p[KEY_IMAGE_ZOOM] ?: true
        UiPrefs.notifyEnabled = p[KEY_NOTIFY_ON] ?: true
        UiPrefs.notifySound = p[KEY_NOTIFY_SOUND] ?: true
        UiPrefs.watermark = p[KEY_WATERMARK] ?: false
        UiPrefs.allowSaveImage = p[KEY_ALLOW_SAVE_IMG] ?: true
        UiPrefs.debugLog = p[KEY_DEBUG_LOG] ?: false
        UiPrefs.fontFamily = p[KEY_FONT_FAMILY] ?: "system"
        UiPrefs.listDensity = p[KEY_LIST_DENSITY] ?: "normal"
        UiPrefs.chatBgImage = p[KEY_CHAT_BG_IMAGE] ?: ""
        UiPrefs.chatBgOpacity = p[KEY_CHAT_BG_OPACITY] ?: 1.0f
        UiPrefs.enterToSend = p[KEY_ENTER_TO_SEND] ?: false
        UiPrefs.autoLoadHistory = p[KEY_AUTO_LOAD_HISTORY] ?: true
        UiPrefs.autoPlayVideo = p[KEY_AUTO_PLAY_VIDEO] ?: false
        UiPrefs.pollUnread = p[KEY_POLL_UNREAD] ?: true
        UiPrefs.mediaCacheEnabled = p[KEY_MEDIA_CACHE_ON] ?: true
        UiPrefs.mediaCacheLimitMb = p[KEY_MEDIA_CACHE_MB] ?: 0
        UiPrefs.bump()
    }

    suspend fun setThemeMode(v: String) {
        themeMode = v
        store.edit { it[KEY_THEME] = v }
    }

    suspend fun setThemeColor(v: String) {
        UiPrefs.themeColor = v
        UiPrefs.bump()
        store.edit { it[KEY_THEME_COLOR] = v }
    }

    suspend fun setFontScale(v: Float) {
        UiPrefs.fontScale = v
        UiPrefs.bump()
        store.edit { it[KEY_FONT_SCALE] = v }
    }

    suspend fun setLiveQuality(v: String) {
        liveQuality = v
        store.edit { it[KEY_LIVE_QUALITY] = v }
    }

    suspend fun setNotifySound(v: Boolean) {
        notifySound = v
        UiPrefs.notifySound = v
        UiPrefs.bump()
        store.edit { it[KEY_NOTIFY_SOUND] = v }
    }

    suspend fun setNotifyEnabled(v: Boolean) {
        UiPrefs.notifyEnabled = v
        UiPrefs.bump()
        store.edit { it[KEY_NOTIFY_ON] = v }
    }

    suspend fun setAvatarSize(v: String) {
        UiPrefs.avatarSize = v
        UiPrefs.bump()
        store.edit { it[KEY_AVATAR] = v }
    }

    suspend fun setBubbleShape(v: String) {
        UiPrefs.bubbleShape = v
        UiPrefs.bump()
        store.edit { it[KEY_BUBBLE_SHAPE] = v }
    }

    suspend fun setBubbleMeColor(v: String) {
        UiPrefs.bubbleMeColor = v
        UiPrefs.bump()
        store.edit { it[KEY_BUBBLE_ME] = v }
    }

    suspend fun setBubblePeerColor(v: String) {
        UiPrefs.bubblePeerColor = v
        UiPrefs.bump()
        store.edit { it[KEY_BUBBLE_PEER] = v }
    }

    suspend fun setChatBg(mode: String, color: String) {
        UiPrefs.chatBgMode = mode
        UiPrefs.chatBgColor = color
        UiPrefs.bump()
        store.edit {
            it[KEY_CHAT_BG_MODE] = mode
            it[KEY_CHAT_BG_COLOR] = color
        }
    }

    suspend fun setShowPreview(v: Boolean) {
        UiPrefs.showPreview = v
        UiPrefs.bump()
        store.edit { it[KEY_SHOW_PREVIEW] = v }
    }

    suspend fun setShowTimestamp(v: Boolean) {
        UiPrefs.showTimestamp = v
        UiPrefs.bump()
        store.edit { it[KEY_SHOW_TIME] = v }
    }

    suspend fun setReadReceipt(v: Boolean) {
        UiPrefs.readReceipt = v
        UiPrefs.bump()
        store.edit { it[KEY_READ_RECEIPT] = v }
    }

    suspend fun setImageZoom(v: Boolean) {
        UiPrefs.imageZoom = v
        UiPrefs.bump()
        store.edit { it[KEY_IMAGE_ZOOM] = v }
    }

    suspend fun setWatermark(v: Boolean) {
        UiPrefs.watermark = v
        UiPrefs.bump()
        store.edit { it[KEY_WATERMARK] = v }
    }

    suspend fun setAllowSaveImage(v: Boolean) {
        UiPrefs.allowSaveImage = v
        UiPrefs.bump()
        store.edit { it[KEY_ALLOW_SAVE_IMG] = v }
    }

    suspend fun setDebugLog(v: Boolean) {
        UiPrefs.debugLog = v
        UiPrefs.bump()
        store.edit { it[KEY_DEBUG_LOG] = v }
    }

    suspend fun setFontFamily(v: String) {
        UiPrefs.fontFamily = v
        UiPrefs.bump()
        store.edit { it[KEY_FONT_FAMILY] = v }
    }

    suspend fun setListDensity(v: String) {
        UiPrefs.listDensity = v
        UiPrefs.bump()
        store.edit { it[KEY_LIST_DENSITY] = v }
    }

    suspend fun setChatBgImage(path: String) {
        UiPrefs.chatBgImage = path
        UiPrefs.bump()
        store.edit { it[KEY_CHAT_BG_IMAGE] = path }
    }

    suspend fun setChatBgOpacity(v: Float) {
        UiPrefs.chatBgOpacity = v.coerceIn(0.3f, 1.0f)
        UiPrefs.bump()
        store.edit { it[KEY_CHAT_BG_OPACITY] = UiPrefs.chatBgOpacity }
    }

    suspend fun setEnterToSend(v: Boolean) {
        UiPrefs.enterToSend = v
        UiPrefs.bump()
        store.edit { it[KEY_ENTER_TO_SEND] = v }
    }

    suspend fun setAutoLoadHistory(v: Boolean) {
        UiPrefs.autoLoadHistory = v
        UiPrefs.bump()
        store.edit { it[KEY_AUTO_LOAD_HISTORY] = v }
    }

    suspend fun setAutoPlayVideo(v: Boolean) {
        UiPrefs.autoPlayVideo = v
        UiPrefs.bump()
        store.edit { it[KEY_AUTO_PLAY_VIDEO] = v }
    }

    suspend fun setPollUnread(v: Boolean) {
        UiPrefs.pollUnread = v
        UiPrefs.bump()
        store.edit { it[KEY_POLL_UNREAD] = v }
    }

    suspend fun setMediaCacheEnabled(v: Boolean) {
        UiPrefs.mediaCacheEnabled = v
        UiPrefs.bump()
        store.edit { it[KEY_MEDIA_CACHE_ON] = v }
    }

    suspend fun setMediaCacheLimitMb(v: Int) {
        UiPrefs.mediaCacheLimitMb = v.coerceIn(0, 2048)
        UiPrefs.bump()
        store.edit { it[KEY_MEDIA_CACHE_MB] = UiPrefs.mediaCacheLimitMb }
    }
}
