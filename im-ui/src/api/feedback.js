import http from './request'

/**
 * 意见反馈（设置页）。
 *
 * 后端是 im-admin 模块的 {@code FeedbackController}（映射 {@code /api/feedback}，
 * 类上只有 {@code @SaCheckLogin} 没有权限点）：任何登录用户都能提，防刷靠服务端的按用户限流
 * （1 分钟最多 5 条）。后台的检索/回复/删除在 {@code /api/admin/feedback/**} 并要求
 * {@code system:feedback}，聊天端一个都不碰——和公告、举报那边同一条边界。
 */

/**
 * 提交一条意见反馈。
 *
 * @param {Object} data { type, content, contact }
 *   - type：反馈类型码，取值见后端 FeedbackType（bug / suggestion / experience / other）
 *   - content：反馈正文，最多 1000 字
 *   - contact：联系方式，选填，最多 64 字
 */
export function submitFeedback(data) {
  return http.post('/feedback', data)
}
