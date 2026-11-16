import http from '@/api/request'
import SparkMD5 from 'spark-md5'

/**
 * 安装包上传工具（后台「版本管理」专用）。
 *
 * 逻辑从 im-ui/src/api/file.js 移植而不是共享一份代码：两个前端是独立打包的应用，
 * 为一个工具函数建共享包得不偿失；移植时把 bizType 写死成 client_release——
 * 后台这条通道只上传安装包，不给调用方留传错业务类型的口子（bizType 决定服务端的
 * 扩展名白名单与 2GB 分片通道，传成 chat_file 会被 100MB 直传上限掐死）。
 *
 * 流程与用户端一致：小文件（<= 5MB）走单请求 /file/upload；大文件走
 * 「init（报 MD5，可能秒传）→ 逐片上传（幂等可重试、断点续传）→ merge（服务端重算核对）」。
 * 上传通道要 file:upload 权限点，admin 角色种子里已含（im_role_permission 6006）。
 */

/** 安装包的业务类型，服务端 FileBizType.CLIENT_RELEASE 的白名单认 exe/msi/zip/apk/dmg 等 */
const BIZ_TYPE = 'client_release'

/** 小于此阈值直接走普通上传，不值得为它启动分片机制（与后端默认分片大小 5MB 对齐） */
const DIRECT_UPLOAD_MAX = 5 * 1024 * 1024

/** 分片并发数：太大容易把浏览器连接数与后端线程占满，3 是弱网下比较稳的折中 */
const CHUNK_CONCURRENCY = 3

/** 单片上传失败后的重试次数：分片落盘是幂等的，重试安全 */
const CHUNK_RETRIES = 2

/** 计算哈希时的读取分片大小，2MB 在进度粒度与内存占用之间取平衡 */
const HASH_CHUNK_SIZE = 2 * 1024 * 1024

/**
 * 增量计算整个文件的 MD5。
 *
 * 浏览器原生 SubtleCrypto 只支持 SHA 系列，而服务端 im_file.md5 与秒传判定用的都是 MD5，
 * 所以引 spark-md5。增量喂而不是一次性 readAsArrayBuffer：安装包动辄上 GB，
 * 一次性读进内存会直接把标签页撑爆。
 */
export async function computeFileMd5(file, onProgress) {
  const spark = new SparkMD5.ArrayBuffer()
  let offset = 0
  try {
    while (offset < file.size) {
      const slice = file.slice(offset, offset + HASH_CHUNK_SIZE)
      spark.append(await slice.arrayBuffer())
      offset += HASH_CHUNK_SIZE
      if (onProgress) {
        onProgress(Math.min(100, Math.round((Math.min(offset, file.size) / file.size) * 100)))
      }
    }
    return spark.end()
  } finally {
    // 释放 spark 内部缓冲，避免大文件计算后内存迟迟不回收
    spark.destroy()
  }
}

/**
 * 上传安装包，自动在「普通上传」与「秒传/分片续传」之间选择。
 *
 * @param {File} file 安装包文件
 * @param {(percent:number, stage:string, chunkInfo?:object)=>void} [onProgress]
 *        进度回调：percent 0-100；stage 为 hash/upload/merge/instant/done，
 *        供 UI 区分「计算中/上传中/合并中/秒传」；chunkInfo { loaded, total } 仅 upload 阶段非空
 * @returns {Promise<object>} FileVO（fileId / originalName / size / md5 ...）
 */
export async function uploadRelease(file, onProgress) {
  const report = (percent, stage, chunkInfo) => onProgress && onProgress(percent, stage, chunkInfo)

  if (file.size <= DIRECT_UPLOAD_MAX) {
    const form = new FormData()
    form.append('file', file)
    // 刻意不设 Content-Type：multipart 的 boundary 由浏览器生成，手写会让服务端切不开请求体
    return http.post('/file/upload', form, {
      params: { bizType: BIZ_TYPE },
      timeout: 120000,
      onUploadProgress: (event) => {
        if (event.total) {
          report(Math.round((event.loaded * 100) / event.total), 'upload')
        }
      }
    })
  }

  // 1) 算整文件 MD5（占进度 0-15%）
  const md5 = await computeFileMd5(file, (p) => report(Math.round(p * 0.15), 'hash'))

  // 2) init：命中秒传就直接拿文件走人
  report(15, 'hash')
  const init = await http.post('/file/upload/init', {
    md5,
    size: file.size,
    originalName: file.name,
    bizType: BIZ_TYPE
  }, { timeout: 30000 })
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
  const vo = await http.post('/file/upload/merge', null, { params: { uploadId }, timeout: 300000 })
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
      const form = new FormData()
      form.append('chunk', blob, `chunk-${index}`)
      return await http.post('/file/upload/chunk', form, {
        params: { uploadId, chunkIndex: index },
        timeout: 120000
      })
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
