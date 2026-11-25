/**
 * 浏览器定位：把「还没点授权」和「真的取不到位置」这两件事分开。
 *
 * ★ 这里修的是一个很具体的假失败：navigator.geolocation 的 timeout 是从**调用那一刻**开始计时的，
 *   它把「用户在授权弹窗上思考的那几十秒」也算进去了。原来的 8 秒几乎必然先超时，于是界面报
 *   「定位失败」，而用户点了允许、位置其实已经到手——再点一次按钮，因为权限已授出且
 *   maximumAge 命中缓存，这次立刻成功。表现就是「失败了但其实成功，必须再点一次」。
 *   做法：先用 Permissions API 看权限态，已授权才用短超时，还在询问中就给足反应时间。
 *
 * ★ 另外两件事必须说清楚，否则用户会以为是代码坏了：
 *   1. 定位本身不发任何网络请求（那是操作系统/浏览器的位置服务，网页只是接一个回调），
 *      「我看不到请求」是正常的；真要有请求是坐标换算成行政区划那一步（utils/regionLookup.js）。
 *   2. 非安全上下文（http:// 且不是 localhost）下浏览器直接禁定位，回的是 PERMISSION_DENIED，
 *      这时再怎么重试都没用，只能换 https 或本机 localhost 打开。
 */

/** 已经授权过：只剩「设备取不到固定解」这一段，给 15 秒足够 */
const GRANTED_TIMEOUT_MS = 15000

/** 还在弹窗前：留出用户读提示、点允许的时间，超时只说明他没点，不代表定位坏了 */
const PROMPT_TIMEOUT_MS = 75000

/** 60 秒内允许复用系统缓存的位置：重开页面不必再催一次 GPS */
const DEFAULT_MAX_AGE_MS = 60000

/** 浏览器压根没有这个 API（老 Safari、部分 WebView） */
export function locationUnsupported() {
  return !navigator.geolocation
}

/** 非安全上下文：注意 localhost 与 127.0.0.1 是安全的，所以这里只认 isSecureContext === false */
export function insecureContext() {
  return typeof window !== 'undefined' && window.isSecureContext === false
}

/** 权限态查不到时（部分浏览器不支持查 geolocation 这一项）一律按「还在问」处理，宁可多等不要误报 */
async function permissionState() {
  try {
    if (!navigator.permissions || !navigator.permissions.query) {
      return 'unknown'
    }
    const status = await navigator.permissions.query({ name: 'geolocation' })
    return status.state
  } catch {
    return 'unknown'
  }
}

function messageOf(code, state) {
  if (code === 1) {
    // 拒授权与「浏览器策略直接拦掉」都是这个码，内网与无痕模式常见
    return '定位权限被拒，请在浏览器地址栏的站点设置里允许定位后重试'
  }
  if (code === 2) {
    return '设备暂时取不到位置（室内 GPS 弱或未开定位服务），可稍后重试或直接填写城市'
  }
  if (state === 'prompt' || state === 'unknown') {
    return '还在等你在浏览器弹窗里点「允许」，点完会自动继续；也可以先手动填城市'
  }
  return '定位超时，多半是室内信号弱，可稍后重试或直接填写城市'
}

/**
 * 取一次当前位置。
 *
 * <p>永远 resolve，不抛异常：调用点是「顺手带出坐标」这种增强，失败要在原地出一句能看懂的话，
 * 而不是把一次保存资料整个打断。
 *
 * @param {{highAccuracy?: boolean, maxAgeMs?: number}} options 高精度会更快超时（室内尤其），默认关
 * @returns {Promise<{ok: boolean, lng?: number, lat?: number, accuracy?: number, code?: string, message?: string}>}
 */
export async function requestLocation(options = {}) {
  if (locationUnsupported()) {
    return { ok: false, code: 'unsupported', message: '当前浏览器不支持定位，可先只填城市' }
  }
  if (insecureContext()) {
    return {
      ok: false,
      code: 'insecure',
      message: '当前页面不是 HTTPS（localhost 除外），浏览器已禁用定位，请换 https 地址打开'
    }
  }
  const state = await permissionState()
  const timeout = state === 'granted' ? GRANTED_TIMEOUT_MS : PROMPT_TIMEOUT_MS
  return new Promise((resolve) => {
    // 回调可能一次都不回来（部分 WebView 在页面隐藏时挂起），所以自己兜一个计时器收尾
    let settled = false
    const timer = setTimeout(() => {
      if (settled) {
        return
      }
      settled = true
      resolve({ ok: false, code: 'timeout', message: messageOf(3, state) })
    }, timeout + 2000)
    const finish = (result) => {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      resolve(result)
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => finish({
        ok: true,
        // 与后端 DECIMAL(10,6) 同口径，多出来的小数位会在存库时被截掉，不如一开始就别传
        lng: Number(pos.coords.longitude.toFixed(6)),
        lat: Number(pos.coords.latitude.toFixed(6)),
        accuracy: Math.round(pos.coords.accuracy || 0)
      }),
      (err) => finish({
        ok: false,
        code: String((err && err.code) || 'unknown'),
        message: messageOf(err && err.code, state)
      }),
      {
        enableHighAccuracy: options.highAccuracy === true,
        timeout,
        maximumAge: options.maxAgeMs == null ? DEFAULT_MAX_AGE_MS : options.maxAgeMs
      }
    )
  })
}
