'use strict'
/**
 * 后端地址的运行期配置（桌面端专属）。
 *
 * 为什么要有这个文件：后端地址以前只写在 electron/main.js 的常量里，编译进 app.asar，
 * 于是「换一台机器部署后端」= 改源码 + 重打一个 260MB 的安装包。更糟的是分发包一旦
 * 烤死 `http://localhost:8080`，装到任何**没有本机后端**的电脑上都必然连不上，
 * 用户看到的只是「无法连接服务器，请确认后端已启动」——界面正常、代码正常、打包也没坏，
 * 纯粹是地址不对。这个模块把地址变成运行期可改的三层回落：
 *
 *   ① userData/server-config.json 里的 baseUrl（实测路径 = %APPDATA%\im-desktop\server-config.json；
 *      目录名取的是 package.json 的 name「im-desktop」而不是 productName「IM通讯」，
 *      安装版与免安装版共用同一个 userData，所以两边互相能看到对方的配置）
 *   ② 环境变量 IM_SERVER_BASE（无人值守 / 多环境切换）
 *   ③ 内置默认 BUILT_IN_BASE（开发机自测用，保持和老包一致的行为）
 *
 * 三层都在主进程解析，**渲染进程只读结果**：地址是「页面能不能活」级别的前提，
 * 不能让前端各存一份偏好，否则连不上后端时界面本身就是唯一出路，而它需要先知道地址。
 *
 * 改完不需要重启应用：preload 每次页面加载都用 sendSync 现取（见 preload.js），
 * 前端调 location.reload() 重载页面即可拿到新注入值。注意 additionalArguments 是
 * 建窗口时定死的、reload 不会变，所以那个值只当兜底，实时值一律走 im:server-resolve。
 */
const fs = require('fs')
const path = require('path')
const { app, shell } = require('electron')

/** 内置默认值：与老安装包一致，开发机本机起后端时零配置可用 */
const BUILT_IN_BASE = 'http://localhost:8080'
const CONFIG_FILE = 'server-config.json'
const MAX_LEN = 200

function configPath() {
  return path.join(app.getPath('userData'), CONFIG_FILE)
}

/** 形如 `localhost:8080`、`10.0.0.5:8080/im`：冒号后必须是纯数字端口，才允许当成裸 host:port */
const HOST_PORT_RE = /^[A-Za-z0-9._~%-]+:\d+(\/.*$)?/
/** 语法上带协议头（`file:`、`javascript:`、`http:` …），但 `localhost:8080` 也会命中它 */
const SCHEME_RE = /^[A-Za-z][A-Za-z0-9+.-]*:/

function tryParse(url) {
  try {
    return new URL(url)
  } catch {
    return null
  }
}

/**
 * 规整用户输入的后端地址，合法则返回可用的根地址，否则返回 null。
 *
 * 规则（前端界面里的提示要跟这里一致，别再在渲染进程复制一份校验）：
 * - 允许省略协议：`192.168.1.10:8080`、`localhost:8080` 都按 http 补全
 *   （`new URL('localhost:8080')` 会把 localhost 当协议名，所以要补前缀再判协议）；
 *   但只对「冒号后全是数字端口」或压根没有协议头的输入补前缀——
 *   `file:///etc/passwd`、`javascript:alert(1)` 这种自带别的协议的是非法输入，
 *   必须直接拒掉；早先无脑补前缀会把 `file:///etc/passwd` 拼成 `http://file///etc/passwd`
 *   并当合法值存进配置，用户拿到的是一个莫名其妙的地址和一句看不懂的失败；
 * - 只接受 http/https：这个值最终会进 fetch 与 WebSocket；
 * - 丢弃用户名密码：`http://u:p@host` 的凭据不该明文落进配置文件；
 * - 允许子路径（后端挂在 Nginx 的 `/im/` 下面），但剥掉尾部 `/api`——
 *   前端拼的是 `{base}/api`（utils/env.js），用户手滑把 /api 也填进来就必须削掉，
 *   否则会请求成 /api/api；
 * - 去掉尾部斜杠，与 env.js 的 `replace(/\/+$/,'')` 同口径。
 */
function normalizeBase(raw) {
  const s = String(raw == null ? '' : raw).trim()
  if (!s || /\s/.test(s) || s.length > MAX_LEN) return null

  let u = tryParse(s)
  if (!u || (u.protocol !== 'http:' && u.protocol !== 'https:')) {
    if (SCHEME_RE.test(s) && !HOST_PORT_RE.test(s)) return null
    u = tryParse('http://' + s.replace(/^\/+/, ''))
  }
  if (!u || (u.protocol !== 'http:' && u.protocol !== 'https:')) return null
  if (!u.hostname || u.username || u.password) return null

  let p = String(u.pathname || '').replace(/\/+$/, '')
  if (/\/api$/i.test(p)) p = p.slice(0, -4)
  p = p.replace(/\/+$/, '')
  return `${u.protocol}//${u.host}${p}`
}

function readOverride() {
  try {
    const file = configPath()
    if (!fs.existsSync(file)) return ''
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'))
    return normalizeBase(parsed && parsed.baseUrl) || ''
  } catch {
    // 配置文件被手改坏（非法 JSON / 半截写入）时不能让整个应用起不来：
    // 当作没配过，回落到环境变量或内置默认，界面里仍能看到并改掉它
    return ''
  }
}

function writeOverride(baseUrl) {
  const file = configPath()
  fs.mkdirSync(path.dirname(file), { recursive: true })
  // 先写 .part 再原子 rename：写一半时崩溃会留下截断的 JSON，下次启动就落到上面那个 catch 里
  const tmp = `${file}.part`
  fs.writeFileSync(tmp, JSON.stringify({ baseUrl, updatedAt: Date.now() }, null, 2), 'utf8')
  fs.renameSync(tmp, file)
}

/** 当前生效的地址与它的来源，界面据此告诉用户「这个值是从哪层来的」 */
function resolve() {
  const fromFile = readOverride()
  if (fromFile) return { baseUrl: fromFile, source: 'file' }
  const fromEnv = normalizeBase(process.env.IM_SERVER_BASE || '')
  if (fromEnv) return { baseUrl: fromEnv, source: 'env' }
  return { baseUrl: BUILT_IN_BASE, source: 'default' }
}

const ok = (data) => ({ ok: true, data })
const fail = (error) => ({ ok: false, error })

/**
 * 注册 IPC。必须在 createWindow 之前调用：preload 在页面脚本之前执行，
 * 那时就要能 sendSync 拿到解析结果。
 */
function setupServerConfig(ipcMain) {
  // preload 用同步通道：异步 invoke 在 contextBridge 暴露时机上来不及
  ipcMain.on('im:server-resolve', (event) => {
    event.returnValue = ok(resolve())
  })

  ipcMain.handle('im:server-get', () => {
    try {
      const current = resolve()
      return ok({
        ...current,
        builtIn: BUILT_IN_BASE,
        env: normalizeBase(process.env.IM_SERVER_BASE || ''),
        configPath: configPath(),
        hasConfig: fs.existsSync(configPath())
      })
    } catch (e) {
      return fail(String((e && e.message) || e))
    }
  })

  /**
   * 只规整不落盘：弹窗里「测试连接」要先拿到同一个口径的地址再去探，
   * 否则前端就得自己再写一份校验规则，两份规则迟早漂移（界面说合法、保存被拒）。
   */
  ipcMain.handle('im:server-normalize', (event, payload) => {
    const baseUrl = normalizeBase(payload && payload.baseUrl)
    return baseUrl ? ok({ baseUrl }) : fail('地址不合法：需要 http:// 或 https:// 开头（也可直接填 IP:端口），且不能带空格与账号密码')
  })

  ipcMain.handle('im:server-set', (event, payload) => {
    const baseUrl = normalizeBase(payload && payload.baseUrl)
    if (!baseUrl) {
      return fail('地址不合法：需要 http:// 或 https:// 开头（也可直接填 IP:端口），且不能带空格与账号密码')
    }
    try {
      writeOverride(baseUrl)
      return ok({ baseUrl, source: 'file', configPath: configPath() })
    } catch (e) {
      // 写不进去通常是目录权限问题（比如装到了 Program Files 后又以受限身份运行）：
      // 报原话给用户，别只说「失败」，否则没人知道要去改安装目录
      return fail(`写入配置文件失败：${String((e && e.message) || e)}`)
    }
  })

  ipcMain.handle('im:server-reset', () => {
    try {
      const file = configPath()
      if (fs.existsSync(file)) fs.rmSync(file, { force: true })
      return ok(resolve())
    } catch (e) {
      return fail(`清除配置失败：${String((e && e.message) || e)}`)
    }
  })

  /** 在资源管理器里定位配置文件：改完界面连不上时，人第一反应是直接去看那个文件 */
  ipcMain.handle('im:server-reveal', () => {
    try {
      const file = configPath()
      if (fs.existsSync(file)) {
        shell.showItemInFolder(file)
      } else {
        shell.openPath(path.dirname(file))
      }
      return ok({ configPath: file })
    } catch (e) {
      return fail(String((e && e.message) || e))
    }
  })
}

module.exports = {
  BUILT_IN_BASE,
  normalizeBase,
  resolve,
  configPath,
  setupServerConfig
}
