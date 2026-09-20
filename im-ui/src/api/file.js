import http from './request'
import { computeFileMd5 } from '@/utils/fileHash'

/**
 * 文件相关接口。
 *
 * 后端存进数据库和消息 extra 里的地址是「受控地址」/api/file/download/{fileId}，
 * 长期有效但不带任何凭证；浏览器直接拿它当 img src 会因为没有 satoken 头而 401。
 * 因此展示层统一走 utils/media.js，用带登录头的 XHR 把内容取成 blob 再交给 img。
 */

/**
 * 上传文件。
 *
 * @param file     File 对象
 * @param bizType  avatar / chat_image / chat_file / chat_voice，缺省后端按 chat_file 处理
 * @param duration 语音时长（秒），仅语音需要
 * @param onProgress 上传进度回调，参数是 0-100 的整数
 */
export function uploadFile(file, bizType, duration, onProgress) {
  const form = new FormData()
  // 后端用 @RequestPart("file") 接收，字段名必须是 file
  form.append('file', file)
  return http.post('/file/upload', form, {
    params: { bizType, duration },
    // 刻意不设 Content-Type：multipart 的 boundary 由浏览器生成，
    // 手写一个不带 boundary 的 'multipart/form-data' 会让服务端无法切分请求体。
    // axios 在浏览器环境下遇到 FormData 会主动清掉该头，不写反而最稳。
    timeout: 120000,
    // silent：上传链路的失败由 ChatWindow 的占位气泡/重发机制呈现，
    // 断网时不再弹「无法连接服务器」的全局 toast
    silent: true,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded * 100) / event.total))
      }
    }
  })
}

/** 上传头像，后端会顺手写进当前用户资料，不必再调一次修改资料接口 */
export function uploadAvatar(file) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/file/avatar', form, { timeout: 120000 })
}

/** 文件元数据，需要对该文件有访问权 */
export function fetchFileMeta(fileId) {
  return http.get(`/file/${fileId}`)
}

/** 换取带短时票据的直链，票据有效期由后端 im.jwt.file-ticket-ttl-seconds 决定（默认 1800 秒） */
export function fetchSignedUrl(fileId) {
  return http.get(`/file/${fileId}/url`)
}

/* ============================================================================
 * 分片上传：秒传 + 断点续传
 *
 * 小文件（<= DIRECT_UPLOAD_MAX）仍走原来的单请求 /file/upload，简单、少往返；
 * 大文件才走「init -> 逐片上传 -> merge」：init 阶段命中秒传就一个字节的上传都省掉，
 * 没命中则按服务端下发的分片大小切片、跳过已传分片（断点续传）、并发补传、最后合并。
 * ========================================================================== */

/** 小于此阈值直接走普通上传，不值得为它启动分片机制（与后端默认分片大小 5MB 对齐） */
const DIRECT_UPLOAD_MAX = 5 * 1024 * 1024

/** 分片并发数：太大容易把浏览器连接数与后端线程占满，3 是弱网下比较稳的折中 */
const CHUNK_CONCURRENCY = 3

/** 单片上传失败后的重试次数：分片落盘是幂等的，重试安全 */
const CHUNK_RETRIES = 2

/** 初始化一次分片上传：上报整文件 MD5，可能直接秒传命中 */
export function initChunkUpload(payload) {
  return http.post('/file/upload/init', payload, { timeout: 30000, silent: true })
}

/** 上传单个分片。uploadId / chunkIndex 走 query，分片体走 multipart 的 chunk 字段 */
export function uploadChunk(uploadId, chunkIndex, blob) {
  const form = new FormData()
  form.append('chunk', blob, `chunk-${chunkIndex}`)
  return http.post('/file/upload/chunk', form, {
    params: { uploadId, chunkIndex },
    // 同 uploadFile：不手写 Content-Type，boundary 交给浏览器；silent 同理交给占位气泡呈现
    timeout: 120000,
    silent: true
  })
}

/** 通知服务端合并分片并落库，返回最终的 FileVO */
export function mergeChunkUpload(uploadId) {
  return http.post('/file/upload/merge', null, { params: { uploadId }, timeout: 300000, silent: true })
}

/**
 * 智能上传：自动在「普通上传」与「秒传/分片续传」之间选择，对调用方透明。
 *
 * @param file     File 对象
 * @param bizType  chat_image / chat_file / chat_voice
 * @param duration 语音时长（秒），仅语音需要
 * @param onProgress 进度回调 (percent, stage, chunkInfo)：percent 0-100；
 *                   stage 为 hash/upload/merge/instant/done，供 UI 区分「计算中/上传中/合并中/秒传」；
 *                   chunkInfo 为 { loaded, total } 分片计数，仅 upload 阶段非空
 * @returns {Promise<object>} FileVO
 */
export async function uploadFileSmart(file, bizType, duration, onProgress) {
  // 进度回调统一带上阶段标识，UI 才能区分「计算文件中 / 上传中 / 合并中 / 秒传」
  const report = (percent, stage, chunkInfo) => onProgress && onProgress(percent, stage, chunkInfo)

  if (file.size <= DIRECT_UPLOAD_MAX) {
    // 小文件走单请求上传，只有一个「上传中」阶段
    return uploadFile(file, bizType, duration, (p) => report(p, 'upload'))
  }

  // 1) 算整文件 MD5（占进度 0-15%）
  const md5 = await computeFileMd5(file, (p) => report(Math.round(p * 0.15), 'hash'))

  // 2) init：命中秒传就直接拿文件走人
  report(15, 'hash')
  const init = await initChunkUpload({
    md5,
    size: file.size,
    originalName: file.name,
    bizType,
    duration
  })
  if (init.uploaded) {
    report(100, 'instant')
    return init.file
  }

  // 3) 补传缺失分片（占进度 15-95%）。uploadedChunks 里的分片是断点续传时已传过的，跳过
  const { uploadId, chunkSize, totalChunks } = init
  const done = new Set(init.uploadedChunks || [])
  const pending = []
  for (let i = 0; i < totalChunks; i++) {
    if (!done.has(i)) {
      pending.push(i)
    }
  }
  let completed = done.size
  const advance = () => report(
    15 + Math.round((completed / totalChunks) * 80),
    'upload',
    { loaded: completed, total: totalChunks }
  )
  advance()

  await runPool(pending, CHUNK_CONCURRENCY, async (index) => {
    const start = index * chunkSize
    const blob = file.slice(start, Math.min(start + chunkSize, file.size))
    await uploadChunkWithRetry(uploadId, index, blob)
    completed++
    advance()
  })

  // 4) 合并落库（占进度 95-100%）
  report(96, 'merge')
  const vo = await mergeChunkUpload(uploadId)
  report(100, 'done')
  return vo
}

/**
 * 带重试的单片上传。分片以「临时文件 + 原子改名」落盘，重复上传同一分片幂等，
 * 因此任何失败都可以安全重试；重试仍失败才把错误抛出去中断整体上传。
 */
async function uploadChunkWithRetry(uploadId, index, blob) {
  let lastError
  for (let attempt = 0; attempt <= CHUNK_RETRIES; attempt++) {
    try {
      return await uploadChunk(uploadId, index, blob)
    } catch (error) {
      lastError = error
      if (attempt < CHUNK_RETRIES) {
        await sleep(300 * (attempt + 1))
      }
    }
  }
  throw lastError
}

/**
 * 固定并发地消费任务队列。用共享游标而不是把数组预先切块：
 * 快的分片传完立刻领下一个，不会因为等同一批里最慢的那个而空转。
 */
async function runPool(items, concurrency, worker) {
  if (!items.length) {
    return
  }
  let cursor = 0
  const size = Math.min(concurrency, items.length)
  const runners = Array.from({ length: size }, async () => {
    while (cursor < items.length) {
      const current = items[cursor++]
      await worker(current)
    }
  })
  await Promise.all(runners)
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
