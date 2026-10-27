package com.im.nativeapp.ui.theme

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 主题变更事件总线。
 * SettingsStore 写盘是纯数据层不知道 UI 存在；「我的」改完主题发一帧，
 * MainActivity 收到后从 SettingsStore 读最新值触发 Compose 重排，立即生效。
 */
object ThemeEvents {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
