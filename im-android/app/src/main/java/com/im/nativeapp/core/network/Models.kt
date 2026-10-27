package com.im.nativeapp.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 后端 Result 信封。全局异常处理器不带 @ResponseStatus，
 * 所有业务失败都是 HTTP 200 + 本结构，真正的非 2xx 只来自传输层（对齐 request.js 的注释）。
 */
@Serializable
data class ApiEnvelope(
    val code: Int = 0,
    val success: Boolean = false,
    val message: String? = null,
    val msg: String? = null,
    val data: JsonElement? = null,
    val traceId: String? = null
)

/** 业务错误，携带后端 Result 里的 code 与 traceId（对齐 request.js 的 ApiError） */
class ApiError(val code: Int, message: String, val traceId: String = "") : Exception(message) {
    companion object {
        /** 未登录 / 登录态失效 */
        const val UNAUTHORIZED = 1002
        /** 被其他设备顶下线 */
        const val KICKED_OUT = 2010

        fun isAuthError(code: Int) = code == UNAUTHORIZED || code == KICKED_OUT
    }
}

/** code==200 时解码 data 字段，否则抛 ApiError */
inline fun <reified T> ApiEnvelope.unwrap(json: Json): T {
    if (code == 200) {
        val element = data ?: throw ApiError(-1, "响应缺少 data 字段", traceId ?: "")
        return json.decodeFromJsonElement(element)
    }
    throw ApiError(code, message ?: msg ?: "请求失败", traceId ?: "")
}

/** 不关心返回体的调用（logout 等） */
fun ApiEnvelope.unwrapUnit() {
    if (code != 200) throw ApiError(code, message ?: msg ?: "请求失败", traceId ?: "")
}

// ------------------------------ DTO（与后端契约对齐） ------------------------------

@Serializable
data class LoginRequest(
    val loginType: String = "username",
    val account: String,
    val password: String,
    val deviceId: String
)

@Serializable
data class SmsLoginRequest(
    val phone: String,
    val smsCode: String,
    val deviceId: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val deviceId: String
)

@Serializable
data class SendSmsCodeRequest(
    val phone: String,
    val scene: String = "login",
    val captchaKey: String,
    val captchaCode: String
)

/** 登录/注册成功的返回体。tokenName 由后端下发，对应 sa-token.token-name，不能写死 */
@Serializable
data class LoginVO(
    val token: String = "",
    val tokenName: String = "",
    val userInfo: UserInfoVO? = null
)

@Serializable
data class UserInfoVO(
    /** 雪花 ID 超 JS 安全整数会被后端序列化成字符串，这里统一按字符串兼容（见 IdAsString） */
    @Serializable(IdAsString::class) val id: String = "",
    val username: String = "",
    val nickname: String = "",
    val avatar: String? = null
)

/** 图形验证码：image 是可直接展示的 Data URI */
@Serializable
data class CaptchaVO(
    val captchaKey: String = "",
    val image: String = ""
)

/** 发送短信/邮箱验证码的返回：开发环境带 debugCode 回显 */
@Serializable
data class SendCodeVO(
    val debugCode: String? = null,
    val retryAfter: Int? = null,
    val expiresIn: Int? = null
)

/** WebSocket 连接票据：一次性短票，代替把登录 token 挂到 URL 上 */
@Serializable
data class WsTicketVO(
    val endpoint: String = "/ws",
    val ticket: String = ""
)
