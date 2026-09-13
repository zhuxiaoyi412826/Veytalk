/**
 * ========================================================================
 * TODO: 视频压缩功能 —— 待后续开发
 * ========================================================================
 * 当前状态：已暂停使用，视频直接上传原始文件（限制 100MB）。
 * 后续开发时启用：客户端 ffmpeg.wasm 压缩后上传，减少带宽消耗。
 *
 * 待解决的问题：
 *   1. ffmpeg.wasm 核心需从 CDN 下载 ~30MB，首次体验较差
 *   2. COOP/COEP 跨域隔离头可能影响其他第三方资源加载
 *   3. wasm 单线程压缩速度较慢（约为实时的 1-3 倍）
 *   4. 部分浏览器/设备不支持 SharedArrayBuffer
 *
 * 原始设计文档保留如下，供后续开发参考。
 * ========================================================================
 *
 * 客户端视频压缩 —— 基于 ffmpeg.wasm。
 *
 * 用户在聊天里选完视频后，先在本浏览器内用 WebAssembly 版 ffmpeg 转码成
 * H.264 + AAC 的 MP4，再上传压缩结果。好处：
 *   1. 上行带宽省一大截（原始 4K 手机视频动辄上百 MB，压完通常只剩几 MB）；
 *   2. 接收方拿到的就是浏览器原生可播的 MP4，无需任何解码插件；
 *   3. 服务端不必装 ffmpeg，零额外依赖。
 *
 * 代价是首次压缩时需要从 CDN 下载约 30 MB 的 wasm 核心，之后会被浏览器缓存，
 * 后续压缩不再重复下载。如果浏览器不支持 SharedArrayBuffer（比如 COOP/COEP 头
 * 没配好），ffmpeg 初始化会失败，此时返回 compressed: false 让调用方提示用户。
 */

import { FFmpeg } from '@ffmpeg/ffmpeg'
import { fetchFile, toBlobURL } from '@ffmpeg/util'

/** ffmpeg 实例全局唯一，初始化成功后复用 */
let ffmpeg = null
/** 初始化 Promise，防止并发调用时重复加载 */
let loading = null

/**
 * 加载 ffmpeg.wasm 核心。
 *
 * 从 CDN 拉取 wasm 与 worker 脚本，转成 blob URL 后交给 ffmpeg 加载。
 * 走 CDN 而不是本地打包，是因为 wasm 核心有 ~30 MB，打进主 bundle 会让首屏
 * 加载时间不可接受；按需加载 + 浏览器缓存是更合理的策略。
 *
 * 优先用 jsDelivr（CORS 支持更完善），失败后回退到 unpkg。
 * 加载失败时会重置 loading 状态，允许下次重试。
 */
async function loadFFmpeg() {
  if (ffmpeg) {
    return ffmpeg
  }
  if (loading) {
    await loading
    return ffmpeg
  }

  loading = (async () => {
    ffmpeg = new FFmpeg()

    ffmpeg.on('log', ({ message }) => {
      console.debug('[ffmpeg]', message)
    })

    // 多个 CDN 地址，按优先级尝试：jsDelivr 的 CORS 支持比 unpkg 更稳定，
    // 在 COEP credentialless 模式下 unpkg 的 fetch 可能被浏览器拦截。
    const cdnList = [
      'https://cdn.jsdelivr.net/npm/@ffmpeg/core@0.12.6/dist/umd',
      'https://unpkg.com/@ffmpeg/core@0.12.6/dist/umd'
    ]

    let lastError = null
    for (const baseURL of cdnList) {
      try {
        console.log('[视频压缩] 尝试从 CDN 加载 ffmpeg 核心:', baseURL)
        const coreURL = await toBlobURL(`${baseURL}/ffmpeg-core.js`, 'text/javascript')
        const wasmURL = await toBlobURL(`${baseURL}/ffmpeg-core.wasm`, 'application/wasm')

        await ffmpeg.load({ coreURL, wasmURL })
        console.log('[视频压缩] ffmpeg 核心加载成功, CDN:', baseURL)
        return
      } catch (err) {
        lastError = err
        console.warn('[视频压缩] CDN 加载失败:', baseURL, err.message)
        // 重置 ffmpeg，下次循环用新的实例重试
        ffmpeg = null
      }
    }

    // 所有 CDN 都失败了，重置状态让下次调用可以重试
    loading = null
    ffmpeg = null
    throw lastError || new Error('所有 CDN 均无法加载 ffmpeg 核心')
  })()

  try {
    await loading
  } catch {
    // 加载失败，重置 loading 以便下次重试
    loading = null
    throw new Error('ffmpeg 核心加载失败，请检查网络连接后重试')
  }
  return ffmpeg
}

/**
 * 压缩视频文件为 H.264 + AAC 的 MP4。
 *
 * @param {File|Blob} file  原始视频文件
 * @param {Function} onProgress  压缩进度回调，参数为 0-100 的整数（可选）
 * @returns {Promise<{file: File, compressed: boolean}>}
 *   - file: 压缩后的 File（成功时）或原始 File（失败时）
 *   - compressed: 是否成功压缩；false 表示 ffmpeg 不可用或压缩出错
 *
 * 编码参数说明：
 *   -c:v libx264    H.264 视频编码，浏览器兼容性最好
 *   -crf 28         质量因子，数值越大文件越小、画质越差；28 是「够用」的平衡点
 *   -preset fast    编码速度，fast 在 wasm 环境里不会太慢
 *   -vf scale=-2:720  高度缩到 720p，宽度按比例自动对齐到偶数
 *   -c:a aac        AAC 音频编码
 *   -b:a 128k       音频码率 128kbps
 *   -movflags +faststart  把 moov atom 移到文件头部，浏览器可以边下边播
 */
export async function compressVideo(file, onProgress) {
  try {
    const instance = await loadFFmpeg()

    const inputName = 'input_video'
    const outputName = 'output.mp4'

    console.log('[视频压缩] 开始压缩:', file.name, '原始大小:', (file.size / 1024 / 1024).toFixed(1) + 'MB')

    await instance.writeFile(inputName, await fetchFile(file))

    // 用命名函数注册，exec 结束后移除，避免多次调用时监听器堆积
    // ffmpeg.wasm 0.12.x 的 progress 事件触发不稳定，可能长时间不回调，
    // 所以额外启动一个模拟进度定时器，保证进度条始终在动
    let lastRealProgress = 0
    const progressHandler = ({ progress }) => {
      const pct = Math.min(99, Math.round(progress * 100))
      if (pct > lastRealProgress) {
        lastRealProgress = pct
        onProgress?.(pct)
      }
    }
    instance.on('progress', progressHandler)

    // 模拟进度：每 200ms 递增 1%，上限 95%，确保进度条一直在走
    const simulatedTimer = setInterval(() => {
      if (lastRealProgress < 95) {
        lastRealProgress++
        onProgress?.(lastRealProgress)
      }
    }, 200)

    try {
      await instance.exec([
        '-i', inputName,
        '-c:v', 'libx264',
        '-crf', '28',
        '-preset', 'fast',
        '-vf', 'scale=-2:720',
        '-c:a', 'aac',
        '-b:a', '128k',
        '-movflags', '+faststart',
        '-y',
        outputName
      ])
    } finally {
      clearInterval(simulatedTimer)
      instance.off('progress', progressHandler)
    }

    const data = await instance.readFile(outputName)
    const blob = new Blob([data.buffer], { type: 'video/mp4' })

    // 清理临时文件，避免 wasm 虚拟文件系统持续膨胀
    await instance.deleteFile(inputName)
    await instance.deleteFile(outputName)

    const originalName = file.name || 'video.mp4'
    const dot = originalName.lastIndexOf('.')
    const baseName = dot > 0 ? originalName.substring(0, dot) : originalName
    const compressedFile = new File([blob], `${baseName}.mp4`, { type: 'video/mp4' })

    console.log('[视频压缩] 压缩完成:',
      '原始:', (file.size / 1024 / 1024).toFixed(1) + 'MB',
      '→ 压缩后:', (compressedFile.size / 1024 / 1024).toFixed(1) + 'MB',
      '压缩比:', ((1 - compressedFile.size / file.size) * 100).toFixed(0) + '%')

    return { file: compressedFile, compressed: true }
  } catch (error) {
    console.error('[视频压缩] 失败:', error)
    // 返回原始文件并标记压缩失败，让调用方决定是否继续
    return { file, compressed: false }
  }
}

/**
 * 判断 ffmpeg.wasm 是否可用。
 *
 * 核心检测 SharedArrayBuffer —— 没有它 ffmpeg 根本无法初始化。
 * 在用户点击上传之前就调用这个函数，决定是否要走压缩路径。
 */
export function isFFmpegAvailable() {
  return typeof SharedArrayBuffer !== 'undefined'
}
