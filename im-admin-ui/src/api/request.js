import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { getToken, getTokenName, clearToken } from '@/utils/token'

/**
 * 后台统一的 HTTP 客户端。
 *
 * 后端的全局异常处理器不带任何 @ResponseStatus，所有失败（含未登录、无权限、参数校验）
 * 都以 HTTP 200 + Result 体返回，所以业务错误的判定全在响应拦截器的成功分支里。
 * 这一点抄 im-ui 而不改：如果按「401 才跳登录」写，后台会遇到每一个 1002 都只弹红条、
 * 永远不回登录页的现象，而根因是判定层数搞反了。
 *
 * 与 im-ui 那份的差异只有两处，且都是有意的：
 *  1. 没有 WebSocket / Electron 地址推导，后台只打同源 /api；
 *  2. 多拦一道 1003（无权限）：管理接口一旦被非管理员账号摸到，
 *     必须把「这个账号没有后台权限」说清并退回登录页，而不是让页面停在原地刷红色 toast。
 */

/** 未登录 / 登录态失效 */
const CODE_UNAUTHORIZED = 1002
/** 已登录但没有该接口的权限（缺 system:manage） */
const CODE_FORBIDDEN = 1003

export class ApiError extends Error {
  constructor(code, message, traceId) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId || ''
  }
}

/** 正在跳转登录页时抑制后续请求的重复提示与重复跳转（一屏并发请求会同时收到 1002） */
let redirecting = false

function newTraceId() {
  return `admin-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function isResultBody(body) {
  return !!body && typeof body === 'object' && !(body instanceof Blob) && 'code' in body && 'success' in body
}

function toLogin(reason) {
  if (redirecting) {
    return
  }
  redirecting = true
  clearToken()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router
      .replace({ name: 'login', query: { redirect: current.fullPath } })
      .finally(() => {
        redirecting = false
      })
  } else {
    redirecting = false
  }
  if (reason) {
    ElMessage.warning(reason)
  }
}

/** 供 auth store 在登录成功后复位，避免一次登出把后续提示永久关掉 */
export function resetRedirectFlag() {
  redirecting = false
}

const http = axios.create({
  baseURL: '/api',
  timeout: 20000,
  // 数组参数序列化成 ids=1&ids=2：axios 默认的 ids[]=1 后端 @RequestParam List 认不出
  paramsSerializer: { indexes: null }
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers[getTokenName()] = token
  }
  // 后端 TraceIdFilter 认这个头并原样回写，浏览器与服务器日志因此能对上同一个 ID。
  // 后台排查「谁在什么时候处置了哪张工单」全靠这条线，省掉它是给自己埋坑
  config.headers['X-Trace-Id'] = newTraceId()
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (!isResultBody(body)) {
      return body
    }
    if (body.code === 200) {
      return body.data
    }
    const message = body.message || '请求失败'
    if (body.code === CODE_UNAUTHORIZED) {
      toLogin(message)
    } else if (body.code === CODE_FORBIDDEN) {
      // 有登录态却没权限：这个账号不该进后台。回登录页比留在一个每格都 403 的看板上强
      toLogin('该账号没有管理后台权限')
    } else if (!response.config.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(new ApiError(body.code, message, body.traceId))
  },
  (error) => {
    const config = error.config || {}
    let message
    if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message || '')) {
      message = '请求超时，请确认后端已启动'
    } else if (error.response) {
      message = `服务异常（HTTP ${error.response.status}）`
    } else {
      message = '无法连接服务器，请确认后端已在 8080 启动'
    }
    if (!config.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(new ApiError(-1, message, ''))
  }
)

/**
 * 取受登录态保护的图片字节（头像）。
 *
 * 后端 /api/file/** 要带头的 token，<img src> 带不了，直接塞地址只会得到一张裂图。
 * 这里拉回 blob 换成 object URL；不做持久缓存与 ETag 复核 —— 后台的头像出现频次低，
 * 加一层 IndexedDB 只是多一处要维护的失效逻辑。
 */
const blobUrls = new Map()
const inflight = new Map()

export async function fetchAvatar(url) {
  if (!url) {
    return ''
  }
  if (/^(https?:|data:|blob:)/i.test(url)) {
    return url
  }
  if (blobUrls.has(url)) {
    return blobUrls.get(url)
  }
  if (inflight.has(url)) {
    return inflight.get(url)
  }
  const task = (async () => {
    try {
      const blob = await http.get(url, { baseURL: '', responseType: 'blob', silent: true })
      const objectUrl = URL.createObjectURL(blob instanceof Blob ? blob : blob.data)
      blobUrls.set(url, objectUrl)
      return objectUrl
    } catch {
      // 取不到就留空串，由组件回退到昵称首字母；失败不缓存，下次渲染还会重试
      blobUrls.set(url, '')
      return ''
    } finally {
      inflight.delete(url)
    }
  })()
  inflight.set(url, task)
  return task
}

export default http
