import http from './request'

/**
 * 直播页「图片 / 音乐 / 小说」三板块 REST 接口（作者侧）。
 *
 * 后端三个控制器各占一个前缀（/api/live/image|music|novel，request.js 的 baseURL 已含 /api，
 * 这里只写 /live/xxx/*），形状完全一致，所以这里用 type -> 路径段的映射收成一套函数，
 * 前端新增板块只改 PREFIX 一处。板块标识在服务端写死，不随请求传 type：否则一条
 * type=novel 就能把图集发到小说栏去。
 *
 * 文件本体仍走通用文件接口（api/file.js 的 uploadFileSmart，bizType 传 image_work /
 * music / music_cover / novel_cover）：先上传拿 fileId，再把 ID 提交给本模块建记录。
 * 返回的 fileUrl / coverUrl / imageUrls 都是长期受控地址，前端经 utils/media.js 的
 * mediaUrl 取 blob 渲染，绝不落票据直链。
 *
 * 与文章板块的差别：这里没有草稿与审核状态机，发布即 status=1 可见（2 为后台下架）。
 * 小说列表响应刻意不含正文，点开时另调 fetchWorkDetail 现取（同一次调用服务端已把阅读数 +1，
 * 所以小说不要再补一次 reportWorkPlay，计数会翻倍）。
 */

/** 板块 -> 后端路径段；键就是 WorkFeed.vue 的 type prop */
const PREFIX = {
  image: 'live/image',
  music: 'live/music',
  novel: 'live/novel'
}

function pathOf(type, suffix = '') {
  const prefix = PREFIX[type]
  if (!prefix) {
    throw new Error(`未知的作品板块：${type}`)
  }
  return `/${prefix}${suffix}`
}

/**
 * 作品分页：仅正常状态；列表不含小说正文。
 * @param {'image'|'music'|'novel'} type
 * @param {{ current?: number, size?: number, category?: string, keyword?: string,
 *           sort?: 'hot', mine?: boolean }} params
 */
export function fetchWorkPage(type, params) {
  return http.get(pathOf(type, '/page'), { params })
}

/**
 * 分类列表：本板块顶部分类栏与发布分类下拉的数据源（后台可增删改，不硬编码）。
 * @param {'image'|'music'|'novel'} type
 * @returns {Promise<string[]>} 启用分类名，按 sort 升序
 */
export function fetchWorkCategories(type) {
  return http.get(pathOf(type, '/categories'), { silent: true })
}

/**
 * 发布作品：body 形状按板块不同（图片传 imageIds、音乐传 fileId+coverFileId、小说传 content）。
 * @param {'image'|'music'|'novel'} type
 * @param {object} body
 */
export function createWork(type, body) {
  return http.post(pathOf(type, ''), body)
}

/**
 * 编辑作品：仅作者本人；整表单替换（漏传字段即清空，不是「不改」）。
 * @param {'image'|'music'|'novel'} type
 * @param {string|number} id
 * @param {object} body
 */
export function updateWork(type, id, body) {
  return http.put(pathOf(type, `/${id}`), body)
}

/** 删除作品：仅作者本人可删（逻辑删除）；记录不存在后端幂等返回成功 */
export function deleteWork(type, id) {
  return http.delete(pathOf(type, `/${id}`))
}

/**
 * 作品详情（含小说正文）：只有小说板块有对应端点，且服务端顺手把阅读数 +1。
 * @param {'novel'} type
 * @param {string|number} id
 */
export function fetchWorkDetail(type, id) {
  return http.get(pathOf(type, `/${id}`))
}

/** 打开 / 播放上报：静默失败，不打扰观看 */
export function reportWorkPlay(type, id) {
  return http.post(pathOf(type, `/${id}/play`), {}, { silent: true })
}
