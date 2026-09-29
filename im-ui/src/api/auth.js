import http from './request'

/** 账号登录：loginType 取 username（默认）或 phone */
export function login(data) {
  return http.post('/auth/login', data)
}

/** 手机号 + 短信验证码登录 */
export function loginBySms(data) {
  return http.post('/auth/login/sms', data)
}

/** 邮箱 + 邮箱验证码登录，邮箱未注册时后端自动建号 */
export function loginByEmail(data) {
  return http.post('/auth/login/email', data)
}

/** 注册，成功后直接返回登录态，前端无需再调一次 login */
export function register(data) {
  return http.post('/auth/register', data)
}

export function logout() {
  return http.post('/auth/logout')
}

/** 当前登录用户的完整资料，用于刷新页面后恢复 store */
export function fetchMe() {
  return http.get('/auth/me')
}

/** 主动续期，返回新的 token */
export function refreshToken() {
  return http.post('/auth/refresh')
}

/** 图形验证码，image 字段是可直接放进 img src 的 Data URI */
export function fetchCaptchaImage() {
  return http.get('/captcha/image')
}

/** 发送短信验证码，scene 取 login / register / bind；开启闸门时需带 captchaKey / captchaCode */
export function sendSmsCode(data) {
  return http.post('/captcha/sms', data)
}

/** 发送邮箱验证码：{ email } */
export function sendEmailCode(data) {
  return http.post('/captcha/email', data)
}

/**
 * 找回密码：发送手机验证码。{ phone, captchaKey, captchaCode }
 * 场景由后端钉死为 reset，前端不用（也不能）传 scene——
 * 登录用的验证码在分场景的 Redis 键里与找回密码的码完全隔离。
 * 手机号未绑定任何账号时返回 2017。
 */
export function sendResetSmsCode(data) {
  return http.post('/auth/password/sms-code', data)
}

/** 找回密码：发送邮箱验证码。{ email, captchaKey, captchaCode }，邮箱未绑定时返回 2018 */
export function sendResetEmailCode(data) {
  return http.post('/auth/password/email-code', data)
}

/**
 * 提交新密码：{ resetType: 'phone' | 'email', phone | email, code, newPassword }
 * 成功后该账号在所有设备上的登录态都会失效，需要用新密码重新登录。
 */
export function resetPassword(data) {
  return http.post('/auth/password/reset', data)
}
