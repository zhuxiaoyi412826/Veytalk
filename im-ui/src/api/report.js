import http from './request'

/**
 * 举报：提交、字典、我的进展。
 *
 * 后端是 im-admin 模块的 ReportController（用户侧）与 AdminReportController（后台侧），
 * 这里只碰用户侧那三个。<b>后台那一组接口前端不调</b>：路径前缀 /api/admin/** 就是那道权限边界，
 * 聊天端里出现一个管理端点说明代码放错地方了。
 *
 * config 这个接口是「入口该不该显示」的唯一依据：举报总开关关在服务器上
 * （im.admin.enabled），前端复制一份开关迟早和后端不一致。
 */

/** 举报字典与开关：{ enabled, reasonTextMax, reasons[], targetTypes[], dispositions[] } */
export function fetchReportConfig() {
  // silent：进主界面时顺手拉一次，开关关着或后端在重启都不该弹一屏红条
  return http.get('/report/config', { silent: true })
}

/**
 * 提交举报。
 *
 * 请求体里<b>没有举报人</b>——举报人只取登录态。targetType 取 user / message / room / group，
 * 超限与重复会分别回 14004 / 14003，提示文案由 request.js 统一弹出。
 */
export function submitReport(data) {
  return http.post('/report', data)
}

/** 我报出去的工单与处理结论，按时间倒序 */
export function fetchMyReports(params) {
  return http.get('/report/mine', { params })
}
