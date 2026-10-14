/**
 * 图片内容审核工具（发送端 NSFW 识别）。
 *
 * 用 nsfwjs + TensorFlow.js 在本机浏览器里给图片打分，命中违规就直接拒发——
 * 图片字节根本不离开本机，也就没有「先传到服务器再删掉」那段窗口。
 * 选本地推理的理由与 OCR 一致：不依赖任何第三方识别服务，内网无外网出口也能跑
 * （模型权重以 base64 形式内置在 npm 包里，随前端构建产物一起分发，运行期零外部请求），
 * 也不占服务端一份 GPU/CPU。
 *
 * 必须清楚它的边界：这是「正常使用路径上的闸门」，不是对抗性防御。判定跑在客户端，
 * 绕过它只需要绕开这个页面直接调上传接口。要变成真防线，得在服务端补一条同样的推理链路
 * （阈值与上报口径可以原样复用），详见 md/图片内容审核接入指南.md。
 *
 * 模型只加载一个：nsfwjs 的默认入口会静态引入三个模型（合计约 39MB），所以这里刻意
 * 走 nsfwjs/core + 显式传 modelDefinitions，只把 MobileNetV2（约 3.5MB 权重）打进产物；
 * 且全部用动态 import，不发消息的用户永远不会下载这个 chunk。
 *
 * 这里是「多模型编排层」：色情走 nsfwjs，血腥 / 暴力走 utils/violence.js 那个独立的
 * ONNX 模型（nsfwjs 五类里根本没有这两类）。两个模型的评分汇进同一张判定表，
 * 阈值仍由后端同一份 thresholds 下发。血腥 / 暴力那个模型本体 83MB、不入库（要运维投放），
 * 所以它默认不启用：后端 thresholds 里没出现 Violent，下面的注册表就筛掉它，
 * 连 violence.js 那个 chunk 都不会去请求（因此这里是 await import 而不是顶上的静态 import）。
 *
 * 类别名在注册表里重写了一遍：'Violent' 必须与 violence.js 导出的 VIOLENCE_LABEL 一致。
 * 刻意不在这里 import 那个常量——静态 import 会把整个模块拖进本文件的 chunk，
 * 上面那句「不会去请求」就不成立了。
 *
 * 降级优先：识别是旁路能力，模型加载失败、解码超时、WebGL 不可用等任何异常都返回
 * skipped（照常发送），绝不因为审核环节出问题把用户的话卡住。
 */

import { fetchModerationPolicy, reportModerationHit } from '@/api/moderation'

/** 与后端 application.yml 的 im.moderation.thresholds 默认值一致：策略接口拉不到时用它兜底 */
const FALLBACK_THRESHOLDS = { Porn: 0.8, Hentai: 0.8 }
const FALLBACK_POLICY = { enabled: true, auditEnabled: true, thresholds: FALLBACK_THRESHOLDS }

/** MobileNetV2 的训练尺寸，同时是喂给模型的画布边长 */
const IMAGE_SIZE = 224

/** 取前 N 个类别的评分：模型一共只输出 5 类，全带上正好够审计用（服务端上限 16 条） */
const TOP_K = 5

/**
 * 模型空闲回收时间：比 OCR 长得多，因为 tfjs 建 WebGL 上下文与上传权重的开销不小，
 * 连续发图时反复重建会被用户感知成「每次发图都卡一下」；而它一旦闲置太久
 * （用户切去聊别的话题、关掉了页面）又该把上百 MB 的显存还给浏览器。
 */
const MODEL_IDLE_MS = 120 * 1000

/** 各阶段超时：加载最慢（首次要解析 3.5MB base64），识别本身在 WebGL 上是百毫秒级 */
const LOAD_TIMEOUT_MS = 90 * 1000
const DECODE_TIMEOUT_MS = 15 * 1000
const INFER_TIMEOUT_MS = 20 * 1000

/**
 * 策略缓存有效期：阈值是运营动作，而聊天页面可能一开就是好几天，
 * 不能把会话开头拉到的那一份钉死整个会话（调阈值后重启后端，最多 10 分钟内全端生效）。
 */
const POLICY_TTL_MS = 10 * 60 * 1000

/** 命中后的提示文案，与后端 ResultCode.FILE_CONTENT_UNSAFE(7010) 保持一致 */
export const CONTENT_UNSAFE_MESSAGE = '图片含违规内容，已拒发'

let policyCache = null
let modelPromise = null
let idleTimer = null

function withTimeout(promise, label, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`nsfw ${label} timeout`)), ms)
    promise.then(resolve, reject).finally(() => clearTimeout(timer))
  })
}

function clearIdleTimer() {
  if (idleTimer) {
    clearTimeout(idleTimer)
    idleTimer = null
  }
}

/**
 * 获取审核策略（带 TTL 的进程内缓存）。
 *
 * 不缓存失败：策略接口抖动时下一张图还会再问一次，免得一次网络故障把整个会话的
 * 阈值钉死在内置默认值上。拉不到就用内置默认值，绝不因为问不到策略就不发图。
 */
async function getPolicy() {
  if (!policyCache || Date.now() - policyCache.at > POLICY_TTL_MS) {
    const task = fetchModerationPolicy()
      .then((policy) => (policy && typeof policy === 'object' ? { ...FALLBACK_POLICY, ...policy } : FALLBACK_POLICY))
      .catch(() => FALLBACK_POLICY)
    policyCache = { promise: task, at: Date.now() }
    task.then((policy) => {
      // 拿到的是兜底值（请求失败）时撤销缓存，下一张图重新问
      if (policy === FALLBACK_POLICY && policyCache && policyCache.promise === task) {
        policyCache = null
      }
    })
  }
  return policyCache.promise
}

function resetIdleTimer(model) {
  clearIdleTimer()
  idleTimer = setTimeout(() => {
    idleTimer = null
    modelPromise = null
    // 只 dispose 当前这个模型，避免回收把并发中刚重建好的实例一起清掉
    if (model && typeof model.dispose === 'function') {
      model.dispose()
    }
  }, MODEL_IDLE_MS)
}

/**
 * 获取（或懒创建）共享模型。
 *
 * 创建失败必须把 promise 置空，否则一次偶发的显存不足会让整个会话的图片永远拿同一个 rejection。
 */
async function getModel() {
  if (!modelPromise) {
    const task = (async () => {
      const [{ load }, { MobileNetV2Model }] = await Promise.all([
        import('nsfwjs/core'),
        import('nsfwjs/models/mobilenet_v2')
      ])
      return withTimeout(
        load('MobileNetV2', { modelDefinitions: [MobileNetV2Model], size: IMAGE_SIZE }),
        'load',
        LOAD_TIMEOUT_MS
      )
    })()
    modelPromise = task
    task.catch(() => {
      if (modelPromise === task) {
        modelPromise = null
      }
    })
  } else {
    resetIdleTimer(await modelPromise)
  }
  return modelPromise
}

/**
 * 图片文件 → 224x224 画布。
 *
 * 两个动作合在这里是为了省内存与耗时：直接把原图解码后交给模型，tfjs 会先建一个
 * 原图尺寸的像素张量（一张 4000x3000 的图就是 36MB）再缩放；先在 canvas 里缩到 224
 * 再喂进去，模型看到的形状已经对，那次 resize 直接被跳过。
 * 缩放用拉伸而不是裁切，与 nsfwjs 内部的 resizeBilinear 行为一致，判定结果不会因路径不同而漂移。
 */
async function toSquareCanvas(file) {
  const url = URL.createObjectURL(file)
  try {
    const image = new Image()
    image.decoding = 'async'
    image.src = url
    await withTimeout(image.decode(), 'decode', DECODE_TIMEOUT_MS)
    // 尺寸无效（0 宽高）或超大到画布放不下的图，交给模型也会报错，这里统一按降级处理
    if (!image.naturalWidth || !image.naturalHeight) {
      throw new Error('nsfw empty image')
    }
    const canvas = document.createElement('canvas')
    canvas.width = IMAGE_SIZE
    canvas.height = IMAGE_SIZE
    const ctx = canvas.getContext('2d')
    // 白底垫一层：PNG 的透明区域直接画会得到未定义像素，铺白后透明图参与判定的
    // 那一块至少是看得见的白色，与用户肉眼看到的合成结果一致
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE)
    ctx.drawImage(image, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
    image.src = ''
    return canvas
  } finally {
    // objectURL 必须显式释放，否则每张发过的图都会占着一份文件 Blob 直到页面刷新
    URL.revokeObjectURL(url)
  }
}

/** 忽略大小写取阈值：配置由人手写，类别名首字母大小写写错的代价不该是「这条阈值永远不生效」 */
function thresholdOf(thresholds, className) {
  if (!thresholds || className == null) {
    return undefined
  }
  if (Object.prototype.hasOwnProperty.call(thresholds, className)) {
    return thresholds[className]
  }
  const lower = String(className).toLowerCase()
  const key = Object.keys(thresholds).find((k) => k.toLowerCase() === lower)
  return key === undefined ? undefined : thresholds[key]
}

/**
 * 按阈值判定是否违规。
 *
 * 评分已按概率从高到低排好，所以第一个命中就是最有把握的那一路，直接拿它当上报主体。
 * 阈值写成 0 或 1 之外都视为无效（与后端 cleanThresholds 同口径），避免前端拿一个
 * 「0」把每张图都判成违规。
 *
 * detector.blockable 是一道白名单：只有「违规类」才允许拦图。模型输出的类别里必然带着
 * Neutral / NonViolent 这类反向标签，有人把它们也写成阈值（比如 NonViolent: 0.9）时，
 * 不加这道白名单就会把每张正常照片都拦下。
 */
function matchViolation(scores, thresholds, detector) {
  for (const score of scores) {
    if (!detector.blockable.includes(score.className)) {
      continue
    }
    const threshold = thresholdOf(thresholds, score.className)
    if (!validThreshold(threshold)) {
      continue
    }
    if (score.probability >= threshold) {
      return { hitClass: score.className, hitProbability: score.probability, threshold }
    }
  }
  return null
}

function validThreshold(value) {
  return typeof value === 'number' && value > 0 && value <= 1
}

/** 跑一次色情模型，失败时把可能半坏的实例丢掉再抛给编排层 */
async function scoreNsfw(canvas) {
  const model = await getModel()
  try {
    const scores = await withTimeout(model.classify(canvas, TOP_K), 'classify', INFER_TIMEOUT_MS)
    resetIdleTimer(model)
    return scores
  } catch (error) {
    // 出错时模型可能是半坏状态（显存被抢占、实例已 dispose），直接把缓存丢掉，下一张图重建
    clearIdleTimer()
    modelPromise = null
    if (model && typeof model.dispose === 'function') {
      try {
        model.dispose()
      } catch {
        // 释放失败没什么可补救的，交给浏览器回收
      }
    }
    throw error
  }
}

/**
 * 检测器注册表。
 *
 * blockable 是这个模型能提供的「违规类」；optional 表示它需要运维额外投放模型文件，
 * 只有在 thresholds 里配了它的类别才会被激活（血腥 / 暴力那条链路 96MB，不能默认拉）。
 * 顺序有意义：便宜且必装的排前面，命中即早退，不再为它跑后面更慢的模型。
 */
const MODELS = [
  {
    id: 'nsfw',
    blockable: ['Porn', 'Hentai', 'Sexy'],
    score: scoreNsfw
  },
  {
    id: 'violence',
    // 与 violence.js 的 VIOLENCE_LABEL 同值，见文件头注释
    blockable: ['Violent'],
    optional: true,
    // 懒到真正要跑了才 import：这个模块顶上有两份 ?url 的静态依赖（wasm 运行时），
    // 它本身不重，但把它算进 eagerly 可达的模块会让「未启用 = 零开销」这句话不成立
    async score(canvas) {
      const { scoreViolence } = await import('./violence')
      return scoreViolence(canvas)
    }
  }
]

/** 这个检测器有没有被配置点名：只要它的任一违规类有一条有效阈值就算点名了 */
function detectorRequested(detector, thresholds) {
  return detector.blockable.some((label) => validThreshold(thresholdOf(thresholds, label)))
}

/**
 * 识别单张图片。
 *
 * @param {File|Blob} file 图片文件
 * @returns {Promise<object>}
 *   { skipped: true, reason }                  未判定（开关关闭/非图片/识别失败），调用方应照常发送
 *   { skipped: false, violation: false, scores } 判定通过
 *   { skipped: false, violation: true, model, hitClass, hitProbability, threshold, scores } 命中违规
 */
export async function inspectImage(file) {
  if (typeof window === 'undefined' || !file || !file.type?.startsWith('image/')) {
    return { skipped: true, reason: 'not-image' }
  }
  let policy
  try {
    policy = await getPolicy()
  } catch {
    return { skipped: true, reason: 'policy-error' }
  }
  if (!policy.enabled) {
    return { skipped: true, reason: 'disabled' }
  }
  if (!policy.thresholds || !Object.keys(policy.thresholds).length) {
    // 一条阈值都没有＝只留审计不拦截。这时候连模型都不必加载，省下一次几十 MB 的下载
    return { skipped: true, reason: 'no-threshold' }
  }
  const active = MODELS.filter((detector) => detectorRequested(detector, policy.thresholds))
  if (!active.length) {
    // 配了阈值但没有一条落在已知违规类上（多半是类别名写错了），不加载任何模型
    return { skipped: true, reason: 'no-active-model' }
  }

  let canvas = null
  try {
    canvas = await toSquareCanvas(file)
  } catch (error) {
    return { skipped: true, reason: error?.message || 'decode-error' }
  }

  const scores = []
  const failures = []
  for (const detector of active) {
    try {
      const part = await detector.score(canvas)
      scores.push(...part)
      const violation = matchViolation(part, policy.thresholds, detector)
      if (violation) {
        return { skipped: false, violation: true, model: detector.id, ...violation, scores }
      }
    } catch (error) {
      // 单个模型挂掉不影响另一个判定，继续往下跑
      failures.push(`${detector.id}:${error?.message || 'error'}`)
    }
  }
  if (failures.length === active.length) {
    // 所有该跑的模型都没跑成：降级放行，但把原因带上，否则事后没法解释「为什么没拦住」
    return { skipped: true, reason: failures.join(' | '), scores }
  }
  return { skipped: false, violation: false, scores, degraded: failures }
}

/**
 * 发送/上传前的那道闸门：识别 + 命中即上报审计。
 *
 * @param {File|Blob} file 图片文件
 * @param {object} options { scene: chat_image/avatar/video_cover, conversationId? }
 * @returns {Promise<{ pass: boolean, message?: string, result: object }>}
 *   pass=true 表示可以照常上传（含「未判定」的放行）；pass=false 时 message 是给用户的提示文案
 */
export async function guardImage(file, options = {}) {
  const result = await inspectImage(file)
  if (result.skipped || !result.violation) {
    return { pass: true, result }
  }
  const policy = await getPolicy().catch(() => FALLBACK_POLICY)
  // 拦与报是两件事：audit-enabled=false 时照旧拒发，只是不落审计
  if (policy.auditEnabled) {
    // 尽力而为：这个请求失败不重试也不提示，图已经在本地被拦下了
    reportModerationHit({
      scene: options.scene,
      hitClass: result.hitClass,
      hitProbability: result.hitProbability,
      threshold: result.threshold,
      fileName: file.name,
      fileSize: file.size,
      conversationId: options.conversationId,
      // 不按 TOP_K 截：多个模型的评分是拼在一起的，截前五个会把后一个模型的证据全部丢掉。
      // 条目上限由服务端的 max-score-entries（默认 16）兜着，两个模型合计最多 7 条
      scores: (result.scores || []).map((s) => ({ className: s.className, probability: s.probability }))
    }).catch(() => {})
  }
  return { pass: false, message: CONTENT_UNSAFE_MESSAGE, result }
}
