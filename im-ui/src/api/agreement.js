import http from './request'

/**
 * 法定文本（用户协议 / 隐私政策 / 服务条款）的前端入口。
 *
 * 后端是 im-user 的 {@code AgreementController}（映射 {@code /api/agreement/**}）。
 * 前两个是匿名接口——公示页要发给没登录的人、要放进官网页脚，
 * 所以它们必须能在没有登录头的情况下打开；后两个只在登录态下有意义。
 * 真正的写动作（改正文、发新版、查同意留痕）全部在 {@code /api/admin/agreement/**}，
 * 聊天端一个都不碰，与公告、举报那两侧同一条边界。
 */

/**
 * 协议清单：三份当前生效版的简表（不含正文），顺序固定。
 *
 * silent：注册页与公示页都靠它渲染导航链接，后端还没执行本批 SQL、
 * 表是空的那几天里，不该在登录页上弹一屏红条把注册流程挡住。
 */
export function fetchAgreements() {
  return http.get('/agreement/list', { silent: true })
}

/**
 * 某一份协议的正文。
 * @param code terms / privacy / service
 * @param version 缺省取当前生效版；传数字则取历史某版（归档版同样可读，草稿读不到）
 */
export function fetchAgreement(code, version) {
  return http.get(`/agreement/view/${code}`, { params: { version: version || undefined }, silent: true })
}

/**
 * 当前账号还欠哪几份表态。
 *
 * silent：这一条挂在聊天首屏的 bootstrap 上，拉不到就当不弹——
 * 协议弹窗是为了合规，不能反过来变成「打开软件先卡一下」的故障源。
 */
export function fetchPendingAgreements() {
  return http.get('/agreement/pending', { silent: true })
}

/**
 * 提交表态。
 *
 * 不 silent：版本不符（2023）与限流都必须让用户看见，他刚点的是「同意」，
 * 静默失败等于让他以为已经同意了 —— 那是这一整套设计里最不能出现的一种误会。
 * @param items {@code [{ code, version, accept }]}，accept 省略按同意解释
 */
export function submitAgreementConsent(items) {
  return http.post('/agreement/consent', { agreements: items })
}
