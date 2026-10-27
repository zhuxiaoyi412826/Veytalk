package com.im.nativeapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject

/**
 * 登录页状态。字段分组对齐 Web 端 Login.vue：
 * 账号登录（无需验证码）+ 短信登录（图形验证码闸门 → 短信验证码）+ 注册弹窗 + 服务器地址设置。
 */
data class LoginUiState(
    val tab: String = "account",           // account | sms
    // 账号登录
    val account: String = "",
    val password: String = "",
    // 短信登录
    val phone: String = "",
    val captchaCode: String = "",
    val smsCode: String = "",
    val captchaKey: String = "",
    val captchaImage: String = "",         // Data URI，空串表示未加载
    val captchaLoading: Boolean = false,
    val smsCountdown: Int = 0,
    val smsSending: Boolean = false,
    val smsDebugCode: String = "",
    // 通用
    val submitting: Boolean = false,
    val error: String = "",
    // 服务器地址
    val serverUrl: String = "",
    val showServerDialog: Boolean = false,
    val serverInput: String = "",
    // 注册弹窗
    val showRegister: Boolean = false,
    val regUsername: String = "",
    val regNickname: String = "",
    val regPassword: String = "",
    val regConfirm: String = "",
    val regPhone: String = "",
    val regEmail: String = "",
    val registerError: String = ""
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore
) : ViewModel() {

    private val _ui = MutableStateFlow(LoginUiState(serverUrl = session.currentServer()))
    val ui: StateFlow<LoginUiState> = _ui

    private val _loginSuccess = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginSuccess: SharedFlow<Unit> = _loginSuccess

    private var countdownJob: Job? = null

    init {
        loadCaptcha()
    }

    fun update(block: (LoginUiState) -> LoginUiState) = _ui.update(block)

    // ------------------------------ 图形验证码 ------------------------------

    /**
     * 失败时清空 key：留着旧 key，用户填旧图上的字提交会得到「验证码已过期」，
     * 而界面上显示的图跟提示完全对不上（对齐 Login.vue 的 loadCaptcha 注释）。
     */
    fun loadCaptcha() {
        viewModelScope.launch {
            _ui.update { it.copy(captchaLoading = true) }
            try {
                val vo = auth.captcha()
                _ui.update { it.copy(captchaKey = vo.captchaKey, captchaImage = vo.image) }
            } catch (e: Exception) {
                _ui.update { it.copy(captchaKey = "", captchaImage = "") }
            } finally {
                _ui.update { it.copy(captchaLoading = false) }
            }
        }
    }

    // ------------------------------ 登录 ------------------------------

    fun submitAccount() {
        val s = _ui.value
        if (s.account.isBlank()) return fail("请输入账号")
        if (s.password.isBlank()) return fail("请输入密码")
        submit {
            auth.login(s.account.trim(), s.password)
        }
    }

    fun submitSms() {
        val s = _ui.value
        if (!Regex("^1[3-9]\\d{9}$").matches(s.phone)) return fail("手机号格式不正确")
        if (!Regex("^\\d{6}$").matches(s.smsCode)) return fail("验证码为 6 位数字")
        submit {
            auth.loginBySms(s.phone.trim(), s.smsCode.trim())
        }
    }

    private fun fail(message: String) = _ui.update { it.copy(error = message) }

    private fun submit(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(submitting = true, error = "") }
            try {
                block()
                _loginSuccess.tryEmit(Unit)
            } catch (e: Exception) {
                _ui.update { it.copy(error = toMessage(e)) }
            } finally {
                _ui.update { it.copy(submitting = false) }
            }
        }
    }

    // ------------------------------ 短信验证码 ------------------------------

    /**
     * 先校验手机号与图形验证码，图形验证码通过后后端才发短信。
     * 图形验证码是一次性的——无论成功还是被拒都要换新图并清空输入（对齐 Login.vue）。
     */
    fun sendSmsCode() {
        val s = _ui.value
        if (!Regex("^1[3-9]\\d{9}$").matches(s.phone)) return fail("手机号格式不正确")
        if (s.captchaCode.isBlank()) return fail("请输入图形验证码")
        if (s.captchaKey.isEmpty()) return fail("图形验证码未加载，请点图片刷新")
        viewModelScope.launch {
            _ui.update { it.copy(smsSending = true, error = "") }
            try {
                val vo = auth.sendSmsCode(s.phone.trim(), s.captchaKey, s.captchaCode.trim())
                _ui.update { it.copy(smsDebugCode = vo.debugCode ?: "") }
                startCountdown(vo.retryAfter ?: vo.expiresIn ?: 60)
            } catch (e: Exception) {
                _ui.update { it.copy(error = toMessage(e)) }
            } finally {
                _ui.update { it.copy(smsSending = false, captchaCode = "") }
                loadCaptcha()
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remain = seconds.coerceAtLeast(1)
            while (remain > 0) {
                _ui.update { it.copy(smsCountdown = remain) }
                delay(1000)
                remain--
            }
            _ui.update { it.copy(smsCountdown = 0) }
        }
    }

    // ------------------------------ 注册 ------------------------------

    fun submitRegister() {
        val s = _ui.value
        if (!Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$").matches(s.regUsername)) {
            return failRegister("字母开头，4-32 位字母、数字或下划线")
        }
        if (s.regPassword.length !in 6..32) return failRegister("密码长度 6-32 位")
        if (s.regPassword != s.regConfirm) return failRegister("两次输入的密码不一致")
        if (s.regPhone.isNotEmpty() && !Regex("^1[3-9]\\d{9}$").matches(s.regPhone)) {
            return failRegister("手机号格式不正确")
        }
        if (s.regEmail.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(s.regEmail)) {
            return failRegister("邮箱格式不正确")
        }
        viewModelScope.launch {
            _ui.update { it.copy(submitting = true, registerError = "") }
            try {
                auth.register(
                    username = s.regUsername.trim(),
                    password = s.regPassword,
                    nickname = s.regNickname.trim().ifEmpty { null },
                    phone = s.regPhone.trim().ifEmpty { null },
                    email = s.regEmail.trim().ifEmpty { null }
                )
                _ui.update { it.copy(showRegister = false) }
                _loginSuccess.tryEmit(Unit)
            } catch (e: Exception) {
                _ui.update { it.copy(registerError = toMessage(e)) }
            } finally {
                _ui.update { it.copy(submitting = false) }
            }
        }
    }

    private fun failRegister(message: String) = _ui.update { it.copy(registerError = message) }

    // ------------------------------ 服务器地址 ------------------------------

    /** 保存后立即生效：SessionStore 内存缓存已换，下一个请求即走新地址（ApiClient 按需重建） */
    fun saveServer() {
        val input = _ui.value.serverInput
        viewModelScope.launch {
            session.setServer(input)
            _ui.update {
                it.copy(
                    serverUrl = session.currentServer(),
                    showServerDialog = false
                )
            }
        }
    }

    fun openServerDialog() = _ui.update { it.copy(serverInput = it.serverUrl, showServerDialog = true) }

    // ------------------------------ 错误翻译 ------------------------------

    /** 对齐 request.js 的错误文案口径 */
    private fun toMessage(e: Throwable): String = when (e) {
        is ApiError -> e.message ?: "请求失败"
        is SocketTimeoutException -> "请求超时，请检查网络后重试"
        is HttpException -> "服务异常（HTTP ${e.code()}）"
        is IOException -> "无法连接服务器，请确认后端已启动"
        else -> e.message ?: "请求失败"
    }

    override fun onCleared() {
        countdownJob?.cancel()
    }
}
