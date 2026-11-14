package com.im.nativeapp

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.SettingsStore
import com.im.nativeapp.core.config.UiPrefs
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.data.WsDispatcher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class ImApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var session: SessionStore

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    lateinit var apiClient: ApiClient

    @Inject
    lateinit var wsDispatcher: WsDispatcher

    override fun onCreate() {
        super.onCreate()
        // 预热会话/设置缓存：之后 OkHttp 拦截器 / WS URL 拼装 / Compose 主题等同步上下文直接读内存。
        // DataStore 首次读是磁盘 IO，放在启动路径上一次付清，换来全链路同步读取的简洁。
        runBlocking { session.init(); settings.init() }
        // WS 下行 → 各仓库的分发（对齐 im-ui/ws/dispatch.js 的 installWsDispatch）
        wsDispatcher.install()
    }

    /**
     * Coil 全局 ImageLoader：聊天图片/头像走带鉴权头的 OkHttp 客户端。
     * 后端受控地址 /api/file/download/{fileId} 不带凭证会 401（对齐 Web 端 utils/media.js 的教训）。
     */
    override fun newImageLoader(): ImageLoader = buildImageLoader(this, apiClient)

    companion object {
        /**
         * 媒体缓存设置（开关 + 磁盘上限）落在 ImageLoader 的 diskCache/memoryCache 上。
         * 设置改动后由 [SettingsViewModel] 调 Coil.setImageLoader 重建（Coil 2 不支持热更新缓存配置）。
         */
        fun buildImageLoader(context: Context, apiClient: ApiClient): ImageLoader =
            ImageLoader.Builder(context)
                .okHttpClient(apiClient.mediaHttpClient)
                .apply {
                    if (!UiPrefs.mediaCacheEnabled) {
                        // 关闭缓存：不落盘也不占内存，换会话即重新请求
                        diskCache(null)
                        memoryCache(null)
                    } else {
                        val builder = DiskCache.Builder()
                            .directory(File(context.cacheDir, "image_cache"))
                        if (UiPrefs.mediaCacheLimitMb > 0) {
                            builder.maxSizeBytes(UiPrefs.mediaCacheLimitMb.toLong() * 1024L * 1024L)
                        } else {
                            builder.maxSizePercent(0.02)
                        }
                        diskCache(builder.build())
                    }
                }
                .build()
    }
}
