import http from './request'

/**
 * 直播页「文章」板块 REST 接口（作者侧）。
 *
 * 后端挂在 /api/article（request.js 的 baseURL 已含 /api，这里只写 /article/*）。
 * 正文里的图片与封面本体的上传复用通用文件接口（api/file.js 的 uploadFileSmart，
 * bizType 传 article_img / article_cover）：先上传拿 fileId，再把受控地址
 * /api/file/download/{fileId} 写进 Markdown 或作为 coverFileId 提交。
 * 返回的 coverUrl 是长期受控地址，前端经 utils/media.js 的 mediaUrl 取 blob 渲染。
 *
 * 状态机（status）：0 草稿 1 待审核 2 已发布 3 已驳回 4 已下架。
 * 作者存草稿/提交（action=draft|submit），已发布文章在广场对所有人可见。
 */

/**
 * 文章广场分页：仅已发布（status=2），按创建时间倒序；列表不含正文。
 * @param {{ current?: number, size?: number }} params
 */
export function fetchArticlePage(params) {
  return http.get('/article/page', { params })
}

/**
 * 我的文章分页：当前用户所写，含草稿/待审/驳回/已发布/已下架全部状态；列表不含正文。
 * @param {{ current?: number, size?: number }} params
 */
export function fetchMyArticles(params) {
  return http.get('/article/mine', { params })
}

/**
 * 文章详情：含正文。已发布对任何人可见，未发布仅本人与管理员可见；已发布时浏览量 +1。
 * @param {string|number} id
 */
export function fetchArticleDetail(id) {
  return http.get(`/article/${id}`)
}

/**
 * 新建文章：action=draft 存草稿 / submit 提交审核。
 * @param {{ title: string, summary?: string, content: string, coverFileId?: string, action: 'draft'|'submit' }} body
 */
export function createArticle(body) {
  return http.post('/article', body)
}

/**
 * 编辑文章：仅本人且草稿/驳回态可编辑；action 决定改完维持草稿还是重新送审。
 * @param {string|number} id
 * @param {{ title: string, summary?: string, content: string, coverFileId?: string, action: 'draft'|'submit' }} body
 */
export function updateArticle(id, body) {
  return http.put(`/article/${id}`, body)
}

/** 提交审核：把本人草稿/驳回文章置为待审（1） */
export function submitArticle(id) {
  return http.post(`/article/${id}/submit`, {})
}

/** 删除文章：仅作者本人可删（逻辑删除）；记录不存在后端幂等返回成功 */
export function deleteArticle(id) {
  return http.delete(`/article/${id}`)
}
