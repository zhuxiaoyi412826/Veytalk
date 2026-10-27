import http from './request'

/**
 * 全服公告的用户侧只读入口（四期第四批）。
 *
 * 后端是 im-admin 模块的 {@code AnnouncementController}（映射 {@code /api/announcement/**}，
 * 类上没有权限注解，登录即可读）；后台那五个写动作在 {@code /api/admin/announcement/**}
 * 并要求 {@code system:announcement}，<b>聊天端一个都不碰</b>——和举报那边同一条边界。
 *
 * 这里只有「补拉」这一条：实时送达走 WebSocket 的 notify 帧（{@code data.action === 'announce'}），
 * 由 ws/dispatch 分发。两条路在 {@code stores/announce} 里汇到同一个待确认列表，
 * 所以「推送时在线」和「之后才登录」这两种人不会各弹一次同一条公告。
 */

/**
 * 当前生效的公告：已发布且未过期的最新 5 条，按发布时刻倒序。
 *
 * silent：这一条在连接建立后顺手拉，后端没执行本批 SQL、接口 404、网络抖动都不该
 * 在聊天界面上弹一屏红条——公告是锦上添花，不能变成故障源。
 */
export function fetchActiveAnnouncements() {
  return http.get('/announcement/active', { silent: true })
}
