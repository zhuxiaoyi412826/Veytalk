import http from './request'

/**
 * 图片内容审核（鉴黄）相关接口。
 *
 * 识别本身跑在本机浏览器里（utils/nsfw.js，模型随前端构建产物离线分发），
 * 这三个接口负责「把阈值问下来」与「把命中结果留个痕」，被拦下的图本来是不出本机的。
 * 唯一例外是隔离区（uploadModerationEvidence）：它把原图另传一份作为证据，
 * 由后端 im.moderation.quarantine-enabled 控制——它一开，「图片从未离开本机」就不再成立，
 * 所以这个开关的隐私口径写在后端配置注释与 md/图片内容审核接入指南.md 里。
 *
 * 路径前缀是 /file/moderation 而不是 /moderation：后端控制器挂在文件域下（复用 Knife4j 的
 * /api/file/** 文档分组），这里跟着它走，别按「审核是独立业务」的直觉去改。
 */

/**
 * 拉取审核策略：{ enabled, auditEnabled, thresholds, quarantineEnabled, quarantineMaxSize }。
 *
 * silent：这是发送图片前的旁路查询，失败不该弹全局错误条——
 * 拉不到就由 utils/nsfw.js 用内置默认值兜底，用户只该看到「图发不出去」或正常发出去。
 */
export function fetchModerationPolicy() {
  return http.get('/file/moderation/policy', { timeout: 10000, silent: true })
}

/**
 * 上报一次命中（图片已被本机拦下、拒发）。
 *
 * 服务端不采信这个结论，只写审计日志与流水表，因此这里刻意不重试：
 * 断网时重试只是把一张已经拦住的图再折腾一遍，还会挤占限流配额（每分钟 30 次）。
 *
 * @returns {Promise<number|null>} 落库的流水 ID；开关关闭、审计关闭或写库失败时为 null，
 *          调用方拿到 null 就不该再传副本（没有记录可挂的图不会上传，存储里不留孤儿字节）
 */
export function reportModerationHit(payload) {
  return http.post('/file/moderation/report', payload, { timeout: 10000, silent: true })
}

/**
 * 把被拦下的原图另传一份到隔离区，挂到指定命中记录上（后台「审核记录」因此能看图本体）。
 *
 * 与 report 是一先一后的两步而不是一个请求：先落库才能拿到可挂的 logId，
 * 反过来则会出现「图传上来了、没有记录可挂」的孤儿字节。
 *
 * @param logId reportModerationHit 回到的流水 ID
 * @param file  被拦下的那张图（File|Blob）
 */
export function uploadModerationEvidence(logId, file) {
  const form = new FormData()
  // 后端用 @RequestPart("file") 接收，字段名必须是 file
  form.append('file', file, file.name || 'evidence.jpg')
  return http.post(`/file/moderation/evidence/${logId}`, form, {
    // 同 uploadFile：不手写 Content-Type，multipart 的 boundary 必须交给浏览器生成
    // silent：取证失败与用户无关，图已经拦下来了，不该为一个旁路动作弹错误条
    timeout: 60000,
    silent: true
  })
}
