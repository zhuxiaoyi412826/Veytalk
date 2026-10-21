/**
 * 后端地址调试工具的纯函数层（桌面端「开发调试」弹窗专用）。
 *
 * 为什么单开一个文件：这个弹窗的价值全在「判定说得准不准」上——连不上时到底
 * 是后端没起、地址写错、还是端口上跑着别的服务，三种结论要分别给不同的下一步动作，
 * 混成一句「网络错误」就等于没做。这些判定挂在 SFC 里就只能靠真装一次 Electron
 * 才能验（沿用 utils/quality.js 那条经验），抽出来就能用 `node tmp/xxx.mjs` 直接断言。
 *
 * 这里不碰 window、不碰 ipc、不碰 fetch：调用方把结果喂进来，本模块只负责翻译成人话。
 */

/** 探测结论级别：绿=后端确实活着，黄=有响应但不对劲，红=压根没通 */
export const PROBE_LEVEL = {
  OK: 'ok',
  SOFT: 'soft',
  FAIL: 'fail'
}

/** 后端地址的三种来源，与 electron/server-config.js 的 resolve() 一一对应 */
export const SOURCE = {
  FILE: 'file',
  ENV: 'env',
  DEFAULT: 'default',
  ARGV: 'argv'
}

/** 未登录码，见后端 ResultCode.UNAUTHORIZED / api/request.js 的 CODE_UNAUTHORIZED */
const CODE_UNAUTHORIZED = 1002

/** 探测用的路径：带 @SaCheckLogin 的匿名可达 GET 接口，未登录也回 HTTP 200 + code 1002 */
const PROBE_ENDPOINT = '/api/auth/me'

export function probeUrl(base) {
  const trimmed = String(base || '').replace(/\/+$/, '')
  return `${trimmed}${PROBE_ENDPOINT}`
}

/** 是不是后端的 Result 结构（与 api/request.js 的 isResultBody 同口径，不引它以免拖进 axios） */
function isResultBody(body) {
  return !!body && typeof body === 'object' && !(body instanceof Blob) && 'code' in body && 'success' in body
}

/**
 * 把一次探测的结果翻译成人话。
 *
 * @param {{ threw?: boolean, message?: string, status?: number, body?: any, timedOut?: boolean }} res
 * @returns {{ level: string, title: string, detail: string }}
 */
export function classifyProbe(res) {
  const input = res || {}

  if (input.timedOut) {
    return {
      level: PROBE_LEVEL.FAIL,
      title: '连接超时',
      detail: '地址可能被防火墙拦下，或端口上根本没有服务在等。先确认后端进程与端口。'
    }
  }

  if (input.threw) {
    const raw = String(input.message || '')
    // ERR_NAME_NOT_RESOLVED / ERR_CONNECTION_REFUSED 这类 Chromium 网络错误码留着原样，
    // 排查的人要看的是它，而不是被润色成一句「网络异常」
    return {
      level: PROBE_LEVEL.FAIL,
      title: '连不上这个地址',
      detail:
        `请求根本没发出去${raw ? `（${raw}）` : ''}。` +
        '若这是刚装好的安装包，先想一件事：内置默认是 http://localhost:8080，' +
        '它指向的是「运行本程序的这台电脑」——这台机器上没起后端就必然连不上，' +
        '把地址改成真正部署了后端的机器 IP 或域名即可。'
    }
  }

  const status = Number(input.status) || 0
  const body = input.body
  const reachable = status >= 200 && status < 300

  if (reachable && isResultBody(body)) {
    if (Number(body.code) === CODE_UNAUTHORIZED) {
      // 这是最常见也最容易被误读成「失败」的一种：没登录当然拿不到资料，
      // 能拿到 1002 恰恰证明后端活着、路由对、且就是本项目的后端
      return {
        level: PROBE_LEVEL.OK,
        title: '后端连通',
        detail: '返回 1002（未登录）——这正是预期结果：探测接口本来就要求登录态。可以保存了。'
      }
    }
    if (body.success === true) {
      return { level: PROBE_LEVEL.OK, title: '后端连通', detail: '接口正常返回（当前登录态仍有效）' }
    }
    return {
      level: PROBE_LEVEL.OK,
      title: '后端连通',
      detail: `业务码 ${body.code}${body.message ? `：${body.message}` : ''}（已经是后端自己的答复，链路通）`
    }
  }

  if (reachable) {
    return {
      level: PROBE_LEVEL.SOFT,
      title: '端口有服务，但不像这个后端',
      detail: `HTTP ${status} 且响应体不是后端的 Result 结构。多半是端口上跑着别的东西，或地址写到了静态站/代理层。`
    }
  }

  if (status > 0) {
    return {
      level: PROBE_LEVEL.SOFT,
      title: `HTTP ${status}`,
      detail:
        '有服务应答，但没按后端的约定返回。' +
        '后端的全局异常处理是「HTTP 200 + Result 体」，出现非 2xx 通常是中间的代理/容器层拦了一道（401/403/502 都属这类）。'
    }
  }

  return { level: PROBE_LEVEL.FAIL, title: '没有拿到任何 HTTP 应答', detail: '连接在半路就断了，检查地址、端口与防火墙。' }
}

/** 来源标签：界面上必须说清当前值从哪层来，否则用户不知道改了会不会被环境变量盖掉 */
export function sourceLabel(source) {
  switch (source) {
    case SOURCE.FILE:
      return '配置文件（界面改的就是它）'
    case SOURCE.ENV:
      return '环境变量 IM_SERVER_BASE'
    case SOURCE.DEFAULT:
      return '安装包内置默认'
    case SOURCE.ARGV:
      return '窗口启动参数'
    default:
      return '未知来源'
  }
}

/** 主进程拿不到地址时（老包 / preload 异常）的显示占位 */
export function effectiveBase(serverInfo) {
  return (serverInfo && serverInfo.baseUrl) || ''
}

/** El 标签配色：绿=配置文件生效，灰=内置默认，蓝=环境变量 */
export function sourceTagType(source) {
  if (source === SOURCE.FILE) return 'success'
  if (source === SOURCE.ENV) return 'primary'
  return 'info'
}

/**
 * 桌面端才有的能力探测：Web 部署既没有 preload 也没有这个桥，
 * 入口必须整体隐藏——给用户一个点了必弹「不支持」的按钮比没有更糟。
 */
export function canDebug(bridge) {
  return !!bridge && typeof bridge.get === 'function' && typeof bridge.set === 'function'
}
