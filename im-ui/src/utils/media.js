import { reactive } from 'vue'
import { fetchBlob } from '@/api/request'

/**
 * 受控文件地址 -> 可渲染的 blob 地址。
 *
 * 后端存下来的是 /api/file/download/{fileId}，这个地址不带任何凭证，
 * 而 <img src> 与浏览器的原生图片加载都无法附带 satoken 头（is-read-cookie 也是关的），
 * 直接用必然 401。另一条路是先调 /api/file/{id}/url 换带票据的直链，
 * 但那要两次往返且票据 1800 秒后失效，长会话里翻旧图会集体裂掉。
 * 所以这里用带登录头的 XHR 把内容取成 blob，一次往返、无有效期问题。
 *
 * objectUrls 用 reactive(Map) 而不是普通 Map：模板里读它会建立依赖，
 * 取回内容后 set 一次就能让所有引用同一地址的 <img> 自动刷新，不需要手动通知组件。
 */
const objectUrls = reactive(new Map())
const inflight = new Map()

/** 已经是浏览器可直接加载的地址，不需要再走鉴权 */
const DIRECT_PATTERN = /^(https?:|data:|blob:)/i

/**
 * 取一个可用于 src 的地址。
 *
 * 同步返回：没取到时返回空串，调用方据此显示占位；取回后响应式更新。
 * 副作用（发起请求）刻意放在这里而不是放在组件的 onMounted —— 同一条消息里的图片
 * 会因为滚动反复挂载卸载，集中在这层去重才不会每次都重新拉一遍。
 */
export function mediaUrl(rawUrl) {
  if (!rawUrl) {
    return ''
  }
  if (DIRECT_PATTERN.test(rawUrl)) {
    return rawUrl
  }
  if (objectUrls.has(rawUrl)) {
    return objectUrls.get(rawUrl)
  }
  objectUrls.set(rawUrl, '')
  loadMedia(rawUrl)
  return ''
}

async function loadMedia(rawUrl) {
  if (inflight.has(rawUrl)) {
    return inflight.get(rawUrl)
  }
  const task = (async () => {
    try {
      // baseURL 置空：rawUrl 本身就是以 /api 开头的完整路径，不能再被实例的 /api 前缀拼一次
      const blob = await fetchBlob(rawUrl, { baseURL: '' })
      objectUrls.set(rawUrl, URL.createObjectURL(blob))
    } catch {
      // 失败不写缓存，下次渲染还会重试；错误提示已由 fetchBlob 内部的登录态判断处理，
      // 这里再弹一次会在列表页刷出一屏重复的「文件获取失败」
      objectUrls.set(rawUrl, '')
    } finally {
      inflight.delete(rawUrl)
    }
  })()
  inflight.set(rawUrl, task)
  return task
}

/**
 * 判断文件扩展名是否属于可预览的文本类型。
 *
 * 这些格式在浏览器里用等宽字体原样展示就有可读性，
 * 不需要额外的渲染器。命中时文件卡片点击走预览弹窗而不是直接下载。
 */
const VIEWABLE_TEXT_EXTS = new Set([
  'txt', 'md', 'markdown',
  'json', 'xml', 'yml', 'yaml', 'toml', 'ini', 'cfg', 'conf',
  'py', 'js', 'ts', 'jsx', 'tsx', 'java', 'c', 'cpp', 'h', 'cs', 'go', 'rs', 'rb', 'php', 'sh', 'bash', 'zsh',
  'ps1', 'psm1', 'psd1', 'bat', 'cmd',
  'html', 'htm', 'css', 'scss', 'less', 'sql',
  'log', 'env', 'gitignore', 'dockerfile', 'makefile',
  'vue', 'svelte'
])

export function isViewableText(fileName) {
  if (!fileName) {
    return false
  }
  const dot = fileName.lastIndexOf('.')
  if (dot < 0) {
    return false
  }
  const ext = fileName.slice(dot + 1).toLowerCase()
  return VIEWABLE_TEXT_EXTS.has(ext)
}

/**
 * 判断文件名是否属于常见视频类型。
 *
 * 与 isViewableText 互斥：视频文件走内联播放器而不是文本预览弹窗。
 * 用于 MessageBubble 区分「视频气泡」和「普通文件卡片」。
 */
const VIDEO_EXTS = new Set([
  'mp4', 'webm', 'ogg', 'mov', 'avi', 'mkv', 'flv', 'wmv'
])

export function isVideo(fileName) {
  if (!fileName) {
    return false
  }
  const dot = fileName.lastIndexOf('.')
  if (dot < 0) {
    return false
  }
  const ext = fileName.slice(dot + 1).toLowerCase()
  return VIDEO_EXTS.has(ext)
}

/**
 * 读取视频的时长（秒）和宽高。
 *
 * 与 readImageSize / readAudioDuration 同理：后端不解析视频元数据，
 * 只有客户端在上传前能拿到这些信息。读不到就返回 null，视频照常发，
 * 只是播放器里缺少尺寸信息，只能靠加载后自然撑开。
 */
export function readVideoMetadata(file) {
  return new Promise((resolve) => {
    if (!file) {
      resolve(null)
      return
    }
    const isVideoFile = file.type
      ? file.type.startsWith('video/')
      : isVideo(file.name)
    if (!isVideoFile) {
      resolve(null)
      return
    }
    const url = URL.createObjectURL(file)
    const video = document.createElement('video')
    video.preload = 'metadata'
    const finish = (value) => {
      URL.revokeObjectURL(url)
      resolve(value)
    }
    video.onloadedmetadata = () => {
      const duration = Number(video.duration)
      finish({
        duration: Number.isFinite(duration) && duration > 0 ? Math.ceil(duration) : null,
        width: video.videoWidth || null,
        height: video.videoHeight || null
      })
    }
    video.onerror = () => finish(null)
    video.src = url
  })
}

/**
 * 下载文件到本地。
 *
 * 走同一条鉴权通道取 blob，再用一个不落 DOM 的 <a download> 触发保存。
 * 直接 window.open 受控地址会因为拿不到登录头而变成一段 JSON 错误文本。
 */
export async function downloadFile(rawUrl, fileName) {
  const blob = await fetchBlob(rawUrl, { baseURL: '' })
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = fileName || 'download'
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  // 立即 revoke 会让部分浏览器取消尚未开始的下载，交给下一轮任务循环
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}

/**
 * 退出登录时清空缓存。
 *
 * 不只是省内存：object URL 是全局有效的，不清的话切换账号后
 * 新账号的界面里可能直接显示上一个账号的头像（缓存键是同一个文件地址）。
 */
export function clearMediaCache() {
  objectUrls.forEach((value) => {
    if (value) {
      URL.revokeObjectURL(value)
    }
  })
  objectUrls.clear()
  inflight.clear()
}

/**
 * 读取图片的宽高。
 *
 * 后端的附件元数据回填不覆盖 width / height（文件服务不解析图片内容），
 * 只有客户端在上传前能拿到，缺了它气泡里的缩略图会在加载完成的瞬间跳一下。
 * 读取失败时返回 null，让消息照常发出，只是不带尺寸。
 */
export function readImageSize(file) {
  return new Promise((resolve) => {
    if (!file || !file.type || !file.type.startsWith('image/')) {
      resolve(null)
      return
    }
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      resolve({ width: image.naturalWidth, height: image.naturalHeight })
      URL.revokeObjectURL(url)
    }
    image.onerror = () => {
      resolve(null)
      URL.revokeObjectURL(url)
    }
    image.src = url
  })
}

/**
 * 读取音频时长（秒，向上取整）。
 *
 * 与宽高同理：后端只存 duration 字段而不解析音频内容，
 * 上传接口虽然有 duration 参数，但那个值只能由客户端读出来后传上去。
 * 读不到就返回 null，语音消息照常发，只是气泡上不显示时长。
 */
export function readAudioDuration(file) {
  return new Promise((resolve) => {
    if (!file || !file.type || !file.type.startsWith('audio/')) {
      resolve(null)
      return
    }
    const url = URL.createObjectURL(file)
    const audio = document.createElement('audio')
    // preload 必须给 metadata，否则部分浏览器不会去读时长，loadedmetadata 永远不触发
    audio.preload = 'metadata'
    const finish = (value) => {
      URL.revokeObjectURL(url)
      resolve(value)
    }
    audio.onloadedmetadata = () => {
      const seconds = Number(audio.duration)
      finish(Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds) : null)
    }
    audio.onerror = () => finish(null)
    audio.src = url
  })
}
