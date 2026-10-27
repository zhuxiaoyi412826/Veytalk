package com.im.nativeapp

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.SettingsStore
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.data.WsDispatcher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
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
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(apiClient.mediaHttpClient)
            .build()
}
