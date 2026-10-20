import http from './request'

/**
 * 后台用到的登录接口。
 *
 * 只留三个：账号密码登录、拿当前登录者资料、退出。<b>没有注册、没有短信与邮箱验证码登录、
 * 没有找回密码</b>——后台账号由管理员在数据库里建（种子数据只有一个 admin），
 * 把注册入口做进后台界面等于自己把门拆了。
 *
 * 登录也不需要图形验证码：后端 AuthServiceImpl.login 只在密码校验通过后走 doLogin，
 * captchaKey / captchaCode 对账号密码这条路完全不参与校验（那两个字段是给
 * 「发短信/发邮件前的闸门」用的）。照抄聊天端登录页那块验证码图，只会多一个
 * 没人校验的输入框和一次无意义的图片请求。
 *
 * 接口本身是 im-user 的 /api/auth/**，与聊天端共用同一套 Sa-Token 登录态：
 * 后台并不是一套独立账号体系，它只是「同一个账号 + 有 system:manage 权限」才能进。
 */

/** 账号密码登录：{ account, password }，deviceId 由本函数固定填 web */
export function login(data) {
  return http.post('/auth/login', { loginType: 'username', deviceId: 'web', ...data })
}

/**
 * 当前登录者资料（含 roles 与 permissions）。
 *
 * 刷新页面后靠它恢复身份，所以是后台的<b>必需</b>接口而不是可选优化：
 * 权限清单由后端 PermissionProviderImpl 从 im_permission 联表查出，前端不存副本，
 * 自己判一份「admin 角色名」就会和后端的权限点口径漂移。
 */
export function fetchMe() {
  return http.get('/auth/me')
}

export function logout() {
  return http.post('/auth/logout')
}
