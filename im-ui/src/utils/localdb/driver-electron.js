/**
 * Electron 端驱动：把 SQL 转发给主进程执行。
 *
 * 架构约束（对应规范「DB 访问统一放在主进程，渲染进程走 IPC」）：
 * 渲染进程即使有 node 集成也不直接碰 sqlite 文件 —— 多窗口/多进程共写
 * 同一个文件必然撞锁，统一收敛到主进程后天然串行。
 *
 * 接口形状与 driver-web 的 WebDriver 完全一致（open/all/write/info/close/destroy），
 * 上层 localdb 门面不感知自己面对的是 WASM 还是 IPC。
 * 桥由 electron/preload.js 挂在 window.__IM_NATIVE__.db 上。
 */
export class ElectronDriver {
  constructor() {
    this.bridge = window.__IM_NATIVE__.db
    this.userId = null
  }

  async open(userId) {
    this.userId = userId
    await this.bridge.open(userId)
  }

  async all(sql, params = []) {
    return this.bridge.all(sql, params)
  }

  async write(statements) {
    return this.bridge.write(statements)
  }

  async info() {
    return this.bridge.info()
  }

  /** 切换账号 / 登出：主进程会关掉连接并释放文件句柄（规范要求先关旧再开新） */
  async close() {
    if (this.userId !== null) {
      await this.bridge.close()
      this.userId = null
    }
  }

  async destroy() {
    await this.bridge.destroy()
  }
}
