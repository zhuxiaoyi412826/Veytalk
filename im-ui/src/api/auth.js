import http from './request'

/** 账号登录：loginType 取 username（默认）或 phone */
export function login(data) {
  return http.post('/auth/login', data)
}

/** 手机号 + 短信验证码登录 */
export function loginBySms(data) {
  return http.post('/auth/login/sms', data)
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

/** 发送短信验证码，scene 取 login / register / bind */
export function sendSmsCode(data) {
  return http.post('/captcha/sms', data)
}
