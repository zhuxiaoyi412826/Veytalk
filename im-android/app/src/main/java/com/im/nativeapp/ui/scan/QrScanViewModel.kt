package com.im.nativeapp.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.AuthApi
import com.im.nativeapp.core.network.QrSceneRequest
import com.im.nativeapp.core.network.unwrapUnit
import com.im.nativeapp.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 扫码登录的状态与两个 APP 侧端点（scanned / authorize）。
 *
 * 网页那半边（create 出码、status 轮询）由浏览器自己做，APP 只负责把 scene 回传。
 * 授权身份来自 APP 当前登录态，请求体刻意不带 userId，无法替他人授权。
 */
@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val auth: AuthRepository,
    private val session: SessionStore
) : ViewModel() {

    /** 确认页要展示的「当前登录账号」，让用户核对是不是自己 */
    val me = auth.currentUser

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 需要以 Toast 呈现的提示（过期/失败/网络异常） */
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = _message

    /** 授权成功：网页下一次轮询就会拿到登录包并自动登录 */
    private val _authorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authorized: SharedFlow<Unit> = _authorized

    fun resolveAvatar(path: String?): String? = session.resolveUrl(path)

    /**
     * 标记已扫码：纯体验优化（让网页显示「已扫码，请在手机确认」），失败不阻断确认页。
     * 只有「二维码已失效」值得提示——用户正盯着这个码，得知道要对方刷新。
     */
    fun markScanned(scene: String) {
        viewModelScope.launch {
            try {
                apiClient.service(AuthApi::class.java).qrScanned(QrSceneRequest(scene)).unwrapUnit()
            } catch (e: ApiError) {
                if (e.code == CODE_QR_INVALID) _message.tryEmit("二维码已失效，请让对方刷新")
            } catch (_: Exception) {
                // 标记失败不影响授权，静默
            }
        }
    }

    /** 用户显式点「确认登录」后调用：为网页签发 web 设备登录态 */
    fun authorize(scene: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                apiClient.service(AuthApi::class.java).qrAuthorize(QrSceneRequest(scene)).unwrapUnit()
                _authorized.tryEmit(Unit)
            } catch (e: ApiError) {
                _message.tryEmit(
                    when {
                        e.code == CODE_QR_INVALID -> e.message ?: "二维码已失效，请让对方刷新"
                        ApiError.isAuthError(e.code) -> "登录状态已失效，请重新登录 APP"
                        else -> e.message ?: "授权失败，请重试"
                    }
                )
            } catch (_: Exception) {
                _message.tryEmit("网络异常，请稍后重试")
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        /** 二维码过期/不存在（后端约定） */
        const val CODE_QR_INVALID = 1001
    }
}
