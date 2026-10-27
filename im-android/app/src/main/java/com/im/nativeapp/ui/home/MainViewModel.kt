package com.im.nativeapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.data.AuthRepository
import com.im.nativeapp.data.ConversationRepository
import com.im.nativeapp.data.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 主容器：冷启动校验登录态（1002/2010 强制回登录页），并首拉会话列表与好友待处理数（两个角标的数据源） */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val conversations: ConversationRepository,
    private val friends: FriendRepository
) : ViewModel() {

    val totalUnread = conversations.totalUnread
    val pendingCount = friends.pendingCount

    private val _forceLogin = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val forceLogin: SharedFlow<Unit> = _forceLogin

    init {
        viewModelScope.launch {
            try {
                // 本地 token 只是缓存，服务端是否仍认要问过 /auth/me 才知道（对齐 M0 HomeViewModel 的校验）
                auth.fetchMe()
            } catch (e: ApiError) {
                if (ApiError.isAuthError(e.code)) {
                    session.clearToken()
                    _forceLogin.tryEmit(Unit)
                    return@launch
                }
            } catch (_: Exception) {
                // 网络不可达时不踢回登录页：本地缓存仍可用，WS 重连会自己追平
            }
            runCatching { conversations.fetchList() }
            runCatching { friends.refreshPendingCount() }
        }
    }
}
