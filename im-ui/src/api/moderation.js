import http from './request'

/**
 * 图片内容审核（鉴黄）相关接口。
 *
 * 识别本身跑在本机浏览器里（utils/nsfw.js，模型随前端构建产物离线分发），
 * 这两个接口只负责「把阈值问下来」和「把命中结果留个痕」，图片字节被拦下时根本没出本机。
 *
 * 路径前缀是 /file/moderation 而不是 /moderation：后端控制器挂在文件域下（复用 Knife4j 的
 * /api/file/** 文档分组），这里跟着它走，别按「审核是独立业务」的直觉去改。
 */

/**
 * 拉取审核策略：{ enabled, auditEnabled, thresholds }。
 *
 * silent：这是发送图片前的旁路查询，失败不该弹全局错误条——
 * 拉不到就由 utils/nsfw.js 用内置默认阈值兜底，用户只该看到「图发不出去」或正常发出去。
 */
export function fetchModerationPolicy() {
  return http.get('/file/moderation/policy', { timeout: 10000, silent: true })
}

/**
 * 上报一次命中（图片已被本机拦下、拒发）。
 *
 * 服务端不采信这个结论，只写审计日志，因此这里刻意不重试：
 * 断网时重试只是把一张已经拦住的图再折腾一遍，还会挤占限流配额（每分钟 30 次）。
 */
export function reportModerationHit(payload) {
  return http.post('/file/moderation/report', payload, { timeout: 10000, silent: true })
}
