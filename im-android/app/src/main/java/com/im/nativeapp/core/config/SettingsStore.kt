package com.im.nativeapp.core.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "im_settings")

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
        private val KEY_LIVE_QUALITY = stringPreferencesKey("live_quality")  // auto/480/720/1080/1440
        private val KEY_NOTIFY_SOUND = booleanPreferencesKey("notify_sound") // 新消息提示音
    }

    private val store = context.settingsStore

    @Volatile var themeMode: String = "system"
        private set
    @Volatile var liveQuality: String = "auto"
        private set
    @Volatile var notifySound: Boolean = true
        private set

    suspend fun init() {
        val prefs = store.data.first()
        themeMode = prefs[KEY_THEME] ?: "system"
        liveQuality = prefs[KEY_LIVE_QUALITY] ?: "auto"
        notifySound = prefs[KEY_NOTIFY_SOUND] ?: true
    }

    suspend fun setThemeMode(v: String) {
        themeMode = v
        store.edit { it[KEY_THEME] = v }
    }

    suspend fun setLiveQuality(v: String) {
        liveQuality = v
        store.edit { it[KEY_LIVE_QUALITY] = v }
    }

    suspend fun setNotifySound(v: Boolean) {
        notifySound = v
        store.edit { it[KEY_NOTIFY_SOUND] = v }
    }
}
