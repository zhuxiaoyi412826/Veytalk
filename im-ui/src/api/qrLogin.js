import http from './request'

/**
 * 网页端扫码登录后端契约。
 *
 * 两个匿名端点（已在 SaTokenConfigure 白名单放行）：出码与轮询状态；
 * APP 侧的 scanned / authorize 需登录，由客户端调用，不在此列出。
 */

/** 生成登录二维码：{ scene, qrcode, expiresIn }；base 传当前站点 origin 供后端拼扫码回跳链接 */
export function createQrLogin(base) {
  return http.post('/auth/qr-login/create', { base })
}

/** 轮询扫码状态：{ status, token?, tokenName?, expiresIn? }，status=2 时拿到一次性登录包 */
export function pollQrLoginStatus(scene) {
  return http.get('/auth/qr-login/status', { params: { scene }, silent: true })
}
