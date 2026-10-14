/**
 * 图片 OCR 工具（发送端敏感词过滤的取文环节）。
 *
 * 基于 tesseract.js 在浏览器本地识别图内文字，识别结果随图片消息一起上传，
 * 由服务端过敏感词库遮蔽后存入 extra.ocrText。选择本地 OCR 是为了不把用户图片
 * 转给任何第三方识别服务，也不占用服务端资源。
 *
 * 全离线：worker 脚本、wasm 核心、中文语言包全部放在 public 下（/tesseract、/tessdata），
 * 部署到内网无外网出口也能用。首次识别要加载约 12MB 核心 + 20MB 语言包，
 * 之后 worker 常驻复用；空闲 30 秒自动回收，避免聊天页长期占着上百 MB 内存。
 *
 * 降级优先：OCR 是锦上添花的旁路能力，加载失败、识别超时、语言包缺失等任何异常
 * 都只返回空串（照常发送图片），绝不阻断上传发送主流程。
 */

/** 三个本地静态目录：与 vite public 下的拷贝产物一一对应 */
const OCR_WORKER_PATH = '/tesseract/worker.min.js'
const OCR_CORE_PATH = '/tesseract'
const OCR_LANG_PATH = '/tessdata'

/** worker 空闲回收时间：太短则连续发图反复重建，太长则白白占内存 */
const WORKER_IDLE_MS = 30 * 1000

/** 单项操作兜底超时：加载与识别各 60s，慢到这份上不如放弃识别直接发 */
const OCR_TIMEOUT_MS = 60 * 1000

/** 与服务端 AttachmentContentHandler.OCR_TEXT_MAX_LENGTH 对齐，超长先在这里截掉 */
const OCR_TEXT_MAX = 2000

let workerPromise = null
let idleTimer = null

function resetIdleTimer(worker) {
  if (idleTimer) {
    clearTimeout(idleTimer)
  }
  idleTimer = setTimeout(() => {
    workerPromise = null
    idleTimer = null
    worker.terminate().catch(() => {})
  }, WORKER_IDLE_MS)
}

function withTimeout(promise, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`ocr ${label} timeout`)), OCR_TIMEOUT_MS)
    promise.then(resolve, reject).finally(() => clearTimeout(timer))
  })
}

/**
 * 获取（或懒创建）共享 worker。
 *
 * 创建失败时必须把 promise 置空，否则整个会话期间所有图片都只会拿到同一个 rejection。
 * oem=1 只用 LSTM 模型，配合只拷贝 *-lstm 核心文件，省下近一倍体积。
 */
async function getWorker() {
  if (!workerPromise) {
    const task = (async () => {
      const { createWorker } = await import('tesseract.js')
      const worker = await withTimeout(
        createWorker('chi_sim', 1, {
          workerPath: OCR_WORKER_PATH,
          corePath: OCR_CORE_PATH,
          langPath: OCR_LANG_PATH
        }),
        'boot'
      )
      resetIdleTimer(worker)
      return worker
    })()
    workerPromise = task
    task.catch(() => {
      if (workerPromise === task) {
        workerPromise = null
      }
    })
  } else {
    // 复用即在用，撤掉到期的回收定时器
    resetIdleTimer(await workerPromise)
  }
  return workerPromise
}

/**
 * 识别图片中的文字。
 *
 * @param {File|Blob} file 图片文件
 * @returns {Promise<string>} 识别出的文本；任何异常返回空串
 */
export async function ocrImageText(file) {
  if (typeof window === 'undefined' || !file || !file.type?.startsWith('image/')) {
    return ''
  }
  let worker
  try {
    worker = await getWorker()
  } catch {
    return ''
  }
  try {
    const { data } = await withTimeout(worker.recognize(file), 'recognize')
    resetIdleTimer(worker)
    // tesseract 中文识别会插入大量碎片空格与换行，压成连续文本再截断
    const text = (data?.text || '').replace(/[ \t]*\n[ \t]*/g, '\n').replace(/[ \t]{2,}/g, ' ').trim()
    return text.length > OCR_TEXT_MAX ? text.slice(0, OCR_TEXT_MAX) : text
  } catch {
    if (idleTimer) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
    worker.terminate().catch(() => {})
    workerPromise = null
    return ''
  }
}
