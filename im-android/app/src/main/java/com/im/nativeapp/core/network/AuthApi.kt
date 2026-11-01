package com.im.nativeapp.core.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * REST 接口清单（事实源：im-ui/src/api/auth.js 与 ws.js）。
 * 相对路径不带前导斜杠，baseUrl 固定以 /api/ 结尾（见 ApiClient）。
 * M0 只收认证与票据相关端点，业务端点随里程碑逐个并入。
 */
interface AuthApi {

    /** 账号登录：loginType 取 username（默认）或 phone */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): ApiEnvelope

    /** 手机号 + 短信验证码登录 */
    @POST("auth/login/sms")
    suspend fun loginBySms(@Body body: SmsLoginRequest): ApiEnvelope

    /** 注册，成功后直接返回登录态，无需再调 login */
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiEnvelope

    @POST("auth/logout")
    suspend fun logout(): ApiEnvelope

    /** 当前登录用户完整资料，用于冷启动恢复登录态 */
    @GET("auth/me")
    suspend fun me(): ApiEnvelope

    /** 主动续期，返回新的 token */
    @POST("auth/refresh")
    suspend fun refresh(): ApiEnvelope

    /** 图形验证码，image 字段是 Data URI */
    @GET("captcha/image")
    suspend fun captchaImage(): ApiEnvelope

    /** 发送短信验证码；scene 取 login/register/bind，开启闸门时需带 captchaKey/captchaCode */
    @POST("captcha/sms")
    suspend fun sendSmsCode(@Body body: SendSmsCodeRequest): ApiEnvelope

    /** 换取 WebSocket 连接票据（一次性短票，握手时放查询参数） */
    @POST("ws/ticket")
    suspend fun wsTicket(@Query("deviceId") deviceId: String): ApiEnvelope

    /**
     * 扫码登录-标记已扫码（APP 登录态调用）。
     * 让网页从「待扫码」推进到「已扫码，请在手机确认」。二维码过期返回 code=1001。
     */
    @POST("auth/qr-login/scanned")
    suspend fun qrScanned(@Body body: QrSceneRequest): ApiEnvelope

    /**
     * 扫码登录-确认授权（APP 登录态调用）。
     * 后端以当前 APP 登录用户为网页签发 web 设备令牌；请求体刻意不含 userId，无法替他人授权。
     */
    @POST("auth/qr-login/authorize")
    suspend fun qrAuthorize(@Body body: QrSceneRequest): ApiEnvelope
}
