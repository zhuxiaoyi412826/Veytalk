/**
 * 血腥 / 暴力识别（第二道闸门的第二个模型）。
 *
 * 单独一个文件是因为它和 nsfw.js 里的 nsfwjs 毫无关系：两个不同的模型、两套推理后端
 * （nsfwjs 走 TensorFlow.js + WebGL，这里走 ONNX + onnxruntime-web），只是评分最后
 * 都汇进 nsfw.js 的统一判定表。nsfwjs 本身只有 Drawing/Hentai/Neutral/Porn/Sexy 五类，
 * 里面根本没有血腥与暴力，所以必须另接模型，不是调阈值能补出来的。
 *
 * 模型：jaranohaal/vit-base-violence-detection（Apache-2.0）在 Kaggle
 * 「Real Life Violence Situations」上微调的 ViT-Base 二分类，取它的 int8 量化 ONNX 导出
 * （onnx-community/vit-base-violence-detection-ONNX/onnx/model_quantized.onnx）。
 * 那个数据集是真实场景的斗殴、伤害、血迹照片，**血腥和暴力在模型里是同一个类**，
 * 别指望像 nsfwjs 那样按类别分开，也别指望它能看懂卡通打斗（训练集里没有）。
 *
 * 代价必须写清楚：模型本体 82.78MB、推理运行时（wasm）约 13.6MB，比色情那条链路重一个数量级；
 * 其中只有模型不入库（见 .gitignore），要运维按 md/图片内容审核接入指南.md 投放到
 * public/models/violence/model.onnx。没投放就整个检测器停用，且只撞一次 404 就记死，绝不每张图重试。
 * 反过来，只要后端阈值里没出现 Violent，nsfw.js 根本不会走到这里，一行代码都不加载。
 *
 * 耗时是真实的：实测 Chromium（无 SharedArrayBuffer 因而单线程 wasm）热态单次推理 1.1~1.3s，
 * 两个模型串在闸门里之后单张图约 2.3s（色情那一路在 WebGL 上只占几十毫秒），
 * 首次还要多付一次 83MB 下载与建会话。它不是后台异步校验，而是卡在发送前的同步等，
 * 所以开不开这个检测器是个运营决策，不是免费的。
 *
 * 校准的边界也要写清楚：合成图（纯黑、纯红加黑块这种无结构输入）照样能拿到
 * Violent 0.66~0.73，真实照片上的分布未经人工验收。因此阈值起点不该低于 0.75，
 * 投放后先看 MODERATION/moderation.log 里的分数分布再调。
 *
 * 归一化口径照抄模型的 preprocessor_config.json：resize 224x224 → /255 → (x-0.5)/0.5，
 * 尺寸缩放沿用 nsfw.js 已经画好的那张 224x224 画布（拉伸，与 nsfwjs 一致）。
 *
 * 降级优先：与 nsfw.js 同一条规矩，任何异常都往上抛给编排层，由它决定放行还是继续。
 */

/**
 * 模型的离线位置：不入库，由运维投放（见下方注释与 md/图片内容审核接入指南.md）。
 *
 * 前缀取 import.meta.env.BASE_URL 而不是写死 '/'：web 构建里它是 '/'（页面可能停在
 * /chat/123 这种 History 路由上，相对路径会解析到错误的目录），electron 构建里是 './'
 * （写死 '/' 在 file:// 下会被当成文件系统根目录，模型永远取不到）。两种构建拼出来都对。
 */
const MODEL_URL = `${import.meta.env.BASE_URL || '/'}models/violence/model.onnx`

/**
 * wasm 运行时的两个产物。
 *
 * 用 `?url` 让打包器把它们当资源发，而不是拷到 public/ 再指过去：后者在 dev server 下会坏——
 * onnxruntime-web 是动态 import 那个 .mjs 入口的，Vite 对 public 下的文件加 `?import` 重定向
 * 会直接回 500（public 不参与依赖分析），现象却是「no available backend found」，很难查。
 * 走 `?url` 后 dev / build 两边都是真实资源地址，运维也就不必再投放运行时，只需放模型本身。
 * 代价是每次构建都会多带一份 wasm 进 dist（不在仓库里，也不会在没启用时下发）。
 *
 * 子路径要从包的 exports 里点到的那几条取（onnxruntime-web/ort-wasm-*.wasm），写成
 * onnxruntime-web/dist/... 会被 exports 挡住，Vite 直接 500。入口取 onnxruntime-web/wasm
 * （只含 wasm EP）而不是默认入口：后者带 WebGPU，会转而找 27MB 的 jsep 那份，白多一倍。
 */
import wasmMjsUrl from 'onnxruntime-web/ort-wasm-simd-threaded.mjs?url'
import wasmBinUrl from 'onnxruntime-web/ort-wasm-simd-threaded.wasm?url'

/** ViT-Base 的训练尺寸，同时也是 NCHW 张量的边长 */
const INPUT_SIZE = 224

/**
 * 输出索引 → 类别名。顺序核 HF 上同族模型（Tite2/violence-detection、
 * locih/violence_classification）的 config.id2label 一致：0 是安全/非暴力，1 是暴力。
 * 只有下标 1 是「违规类」，NonViolent 永远不参与拦截判定——否则有人把阈值写成
 * NonViolent: 0.9 就会把每张正常图都拦下。
 */
const LABELS = ['NonViolent', 'Violent']

/** 违规类的名字，nsfw.js 的阈值白名单要用同一个字符串，别两处各写一遍 */
export const VIOLENCE_LABEL = 'Violent'

/** 加载超时：83MB 在内网走 HTTP 也要几秒，再算上解权重建计算图，给到 3 分钟 */
const LOAD_TIMEOUT_MS = 180 * 1000

/**
 * 推理超时：int8 ViT-Base 在 WASM 上是秒级（有 SharedArrayBuffer 时多线程快几倍，
 * 没有则单线程硬扛），给足余量，超时就当这个模型今天不干活。
 */
const INFER_TIMEOUT_MS = 60 * 1000

/** 会话空闲回收：比色情模型更该回收——它一份就占上百 MB 堆内存 */
const SESSION_IDLE_MS = 120 * 1000

let entryPromise = null
/** 模型没投放：记死这个事实，整个页面只撞那一次 404 */
let notProvisioned = false
let idleTimer = null

/** dispose 在个别版本上是同步返回 void，统一包一层，别为了释放失败把主流程带崩 */
function releaseSession(session) {
  try {
    const released = session && typeof session.dispose === 'function' ? session.dispose() : null
    if (released && typeof released.catch === 'function') {
      released.catch(() => {})
    }
  } catch {
    // 释放失败没什么可补救的，交给浏览器回收
  }
}

function withTimeout(promise, label, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`violence ${label} timeout`)), ms)
    promise.then(resolve, reject).finally(() => clearTimeout(timer))
  })
}

function clearIdleTimer() {
  if (idleTimer) {
    clearTimeout(idleTimer)
    idleTimer = null
  }
}

function resetIdleTimer(entry) {
  clearIdleTimer()
  idleTimer = setTimeout(() => {
    idleTimer = null
    entryPromise = null
    releaseSession(entry.session)
  }, SESSION_IDLE_MS)
}

/**
 * 获取（或懒创建）共享推理会话。
 *
 * wasm 路径必须显式指向打包器发出的那两份资源：不指的话 ORT 会按它自己那份 JS 的位置去找
 * ort-wasm-*.wasm，运行时那个地址根上并不存在，表现是「会话创建失败」而不是「404 一个文件」，很难查。
 *
 * 线程数要 SharedArrayBuffer，而 SAB 只在跨源隔离（COOP/COEP）页面里存在（实测本机 dev 页
 * 就是 crossOriginIsolated=false、SAB=undefined）。这里不为了提速去加那两道响应头——
 * COEP 会连带影响页面上所有跨源资源（媒体、后端接口都要配 CORP），代价远大于推理快几倍。
 * 检测到没有就退回单线程，慢但确定能跑。
 */
async function getEntry() {
  if (notProvisioned) {
    throw new Error('violence model not provisioned')
  }
  if (!entryPromise) {
    const task = (async () => {
      const ort = await import('onnxruntime-web/wasm')
      ort.env.wasm.wasmPaths = { mjs: wasmMjsUrl, wasm: wasmBinUrl }
      ort.env.wasm.numThreads = typeof SharedArrayBuffer === 'undefined' ? 1 : navigator.hardwareConcurrency || 1
      ort.env.wasm.proxy = false
      // 自己 fetch 而不是把 URL 交给 ORT：404 与网络抖动要分开处理，
      // 前者是「这台部署没投放模型」，后者下一张图还该再试
      const response = await withTimeout(fetch(MODEL_URL, { credentials: 'same-origin' }), 'fetch', LOAD_TIMEOUT_MS)
      if (response.status === 404) {
        notProvisioned = true
        throw new Error('violence model missing (404)')
      }
      if (!response.ok) {
        throw new Error(`violence model http ${response.status}`)
      }
      const bytes = new Uint8Array(await response.arrayBuffer())
      const session = await withTimeout(
        ort.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' }),
        'session',
        LOAD_TIMEOUT_MS
      )
      // ort 跟着会话一起交出去：调用方还要用它建张量，再 import 一次纯属多余
      return { ort, session }
    })()
    entryPromise = task
    // 失败必须置空，否则一次偶发的内存不足会让整个会话拿同一个 rejection
    task.catch(() => {
      if (entryPromise === task) {
        entryPromise = null
      }
    })
  } else {
    resetIdleTimer(await entryPromise)
  }
  return entryPromise
}

/**
 * 224x224 画布 → ViT 需要的 float32 NCHW 张量。
 *
 * 画布尺寸不对时先就地缩放一份，绝不改动调用方传进来的那张（色情模型可能还要用它）。
 * 逐像素写进三个平面是 HWC→CHW 的转置，224x224x3 共 15 万次赋值，成本可以忽略。
 */
function toTensor(ort, canvas) {
  const source = canvas.width === INPUT_SIZE && canvas.height === INPUT_SIZE
    ? canvas
    : scaleCanvas(canvas)
  const { data } = source.getContext('2d').getImageData(0, 0, INPUT_SIZE, INPUT_SIZE)
  const plane = INPUT_SIZE * INPUT_SIZE
  const values = new Float32Array(3 * plane)
  for (let i = 0; i < plane; i++) {
    // /255 再 (x-0.5)/0.5：把 0..255 变成 -1..1，与模型训练时的归一化一致
    values[i] = (data[i * 4] / 255 - 0.5) / 0.5
    values[plane + i] = (data[i * 4 + 1] / 255 - 0.5) / 0.5
    values[2 * plane + i] = (data[i * 4 + 2] / 255 - 0.5) / 0.5
  }
  return new ort.Tensor('float32', values, [1, 3, INPUT_SIZE, INPUT_SIZE])
}

function scaleCanvas(canvas) {
  const scaled = document.createElement('canvas')
  scaled.width = INPUT_SIZE
  scaled.height = INPUT_SIZE
  const ctx = scaled.getContext('2d')
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, INPUT_SIZE, INPUT_SIZE)
  ctx.drawImage(canvas, 0, 0, INPUT_SIZE, INPUT_SIZE)
  return scaled
}

/** ONNX 导出的是 ViTForImageClassification，输出还是 raw logits，得自己过一遍 softmax */
function softmax(logits, count) {
  let max = -Infinity
  for (let i = 0; i < count; i++) {
    if (logits[i] > max) {
      max = logits[i]
    }
  }
  const exps = new Float64Array(count)
  let sum = 0
  for (let i = 0; i < count; i++) {
    exps[i] = Math.exp(logits[i] - max)
    sum += exps[i]
  }
  return exps.map((value) => value / sum)
}

/**
 * 给一张图打暴力分。
 *
 * @param {HTMLCanvasElement} canvas 已经缩到 224x224 的画布（由 nsfw.js 统一准备）
 * @returns {Promise<Array<{className: string, probability: number}>>} 按概率降序，
 *   类别名取自 LABELS，与 nsfwjs 的 className/probability 形状完全一致，编排层可以一视同仁
 */
export async function scoreViolence(canvas) {
  const entry = await getEntry()
  try {
    const inputName = entry.session.inputNames[0]
    const feeds = { [inputName]: toTensor(entry.ort, canvas) }
    const results = await withTimeout(entry.session.run(feeds), 'run', INFER_TIMEOUT_MS)
    const output = results[entry.session.outputNames[0]]
    const count = Math.min(LABELS.length, output.dims[output.dims.length - 1])
    const probabilities = softmax(output.data, count)
    resetIdleTimer(entry)
    return LABELS.slice(0, count)
      .map((className, index) => ({ className, probability: probabilities[index] }))
      .sort((a, b) => b.probability - a.probability)
  } catch (error) {
    // 出错时会话可能是半坏状态，丢掉重建；「模型没投放」那种已经记死的不会被这里救回来
    clearIdleTimer()
    entryPromise = null
    releaseSession(entry.session)
    throw error
  }
}

/**
 * 主动释放（页面关闭前的清理不必调用，浏览器会回收；留着给「设置里关掉审核」这类操作用）。
 */
export async function disposeViolenceSession() {
  clearIdleTimer()
  const pending = entryPromise
  entryPromise = null
  if (!pending) {
    return
  }
  try {
    const entry = await pending
    releaseSession(entry.session)
  } catch {
    // 拿不到会话说明本来就没建起来，没什么可释放的
  }
}
