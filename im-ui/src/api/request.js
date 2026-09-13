import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { getToken, getTokenName, clearToken } from '@/utils/token'

/**
 * 统一的 HTTP 客户端。
 *
 * 后端的全局异常处理器不带任何 @ResponseStatus，所有失败（含未登录、无权限、参数校验）
 * 都以 HTTP 200 + Result 体返回，真正的非 2xx 只可能来自传输层（代理挂了、超时、断网）。
 * 因此业务错误的判定全部落在响应拦截器的成功分支里，错误分支只处理网络问题。
 * 这一点如果搞反，会写出「401 时跳登录页」却永远不触发的代码。
 */

/** 未登录 / 登录态失效，需要清凭证回登录页 */
const CODE_UNAUTHORIZED = 1002
/** 被其他设备顶下线 */
const CODE_KICKED_OUT = 2010

/** 业务错误，携带后端 Result 里的 code 与 traceId */
export class ApiError extends Error {
  constructor(code, message, traceId) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId || ''
  }
}

/** 正在跳转登录页时抑制后续请求的重复提示与重复跳转 */
let redirecting = false

function newTraceId() {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** 判断响应体是否是后端的 Result 结构，二进制流与空响应都不是 */
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
    // 带上 redirect，登录成功后能回到用户原本在看的那个会话，而不是永远落到列表页
    router
      .replace({ name: 'login', query: current.fullPath === '/login' ? {} : { redirect: current.fullPath } })
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
  // 数组参数序列化成 userIds=1&userIds=2。
  // axios 默认会写成 userIds[]=1&userIds[]=2，Spring 的 @RequestParam List 认不了带方括号的参数名，
  // 结果是 /api/user/online 永远收到空列表、全员显示离线。
  paramsSerializer: { indexes: null }
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    // 头名取自后端下发的 tokenName，而不是写死 satoken
    config.headers[getTokenName()] = token
  }
  // 后端的 TraceIdFilter 认这个头并原样回写，浏览器控制台与服务器日志因此能用同一个 ID 对上
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
    } else if (body.code === CODE_KICKED_OUT) {
      toLogin(message || '账号已在其他设备登录')
    } else if (!response.config.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(new ApiError(body.code, message, body.traceId))
  },
  (error) => {
    const config = error.config || {}
    let message
    if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message || '')) {
      message = '请求超时，请检查网络后重试'
    } else if (error.response) {
      // 走到这里说明后端没按 Result 返回，通常是代理或容器层的问题
      message = `服务异常（HTTP ${error.response.status}）`
    } else {
      message = '无法连接服务器，请确认后端已启动'
    }
    if (!config.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(new ApiError(-1, message, ''))
  }
)

/**
 * 下载二进制。
 *
 * 后端的下载接口失败时同样返回 HTTP 200 + JSON 错误体，所以这里必须按 Content-Type
 * 判别：拿到 JSON 就说明没取到文件，把它解析出来当成业务错误抛，
 * 否则调用方会把一段 {"code":1002} 当成图片字节写进 blob，页面上表现为一张裂图且毫无提示。
 */
export async function fetchBlob(url, config = {}) {
  const response = await http.get(url, { ...config, responseType: 'blob', silent: true })
  const blob = response instanceof Blob ? response : response.data
  if (blob && blob.type && blob.type.includes('application/json')) {
    const text = await blob.text()
    let body = {}
    try {
      body = JSON.parse(text)
    } catch {
      // 非 JSON 内容被误标了类型，按原样当文件处理
      return blob
    }
    if (body.code === CODE_UNAUTHORIZED || body.code === CODE_KICKED_OUT) {
      toLogin(body.message)
    }
    throw new ApiError(body.code ?? -1, body.message || '文件获取失败', body.traceId)
  }
  return blob
}

export default http
