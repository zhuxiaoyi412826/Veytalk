/**
 * 远程控制数据面 WS 客户端（控制端侧，中继档）。
 *
 * 报文格式与加解密都抽到了 utils/remoteCodec.js，与直连档共用一份；
 * 本类只负责「怎么把帧搬过去」这一件事。
 *
 * 握手 URL 同时带 ticket（一次性会话凭证）与 satoken（登录态），后者是
 * 浏览器 WS 无法自定义请求头条件下唯一的身份来源；两者缺一服务端都拒绝。
 *
 * 注意：WebCrypto（crypto.subtle）只在 secure context 可用——localhost、
 * https、Electron 都满足；用局域网 IP + http 访问会拿不到 subtle，
 * 此时若会话启用了 AES 会直接报错提示，而不是静默丢帧。
 */
import { wsBaseURL } from '@/utils/env'
import {
  buildEnvelope,
  decodeFrame,
  encodeFrame,
  importAesKey,
  parseEnvelope,
  textBytes
} from '@/utils/remoteCodec'

// 协议常量从共用模块转出一道出口，页面层的 import 不必跟着改
export { FRAME_SCREEN, FRAME_FILE } from '@/utils/remoteCodec'

export class RemoteControlSocket {
  /**
   * @param {object} options
   * @param {string} options.sessionId 远程会话 ID（雪花字符串）
   * @param {string} options.ticket 一次性控制端票据
   * @param {string} options.satoken 登录 token
   * @param {string} [options.aesKeyB64] 会话 AES-256 密钥（标准 Base64）
   * @param {(env:object)=>void} options.onEnvelope 收到文本帧
   * @param {(frame:{frameType:number,meta:object,payload:Uint8Array})=>void} options.onBinaryFrame 收到已解密的二进制帧
   * @param {()=>void} [options.onOpen]
   * @param {(evt:CloseEvent)=>void} [options.onClose]
   */
  constructor(options) {
    this.sessionId = options.sessionId
    this.ticket = options.ticket
    this.satoken = options.satoken
    this.aesKeyB64 = options.aesKeyB64 || ''
    this.onEnvelope = options.onEnvelope
    this.onBinaryFrame = options.onBinaryFrame
    this.onOpen = options.onOpen
    this.onClose = options.onClose

    this.seq = 0
    this.ws = null
    this.key = null
    this.pending = new Map()
    this.heartbeatTimer = null
    this.closedByUser = false
    this.opened = false
    this.openResolve = null
    this.openReject = null
    /* 流量计数：按「实际上链路的字节」累加，与后端中继 binding.bytes 同一口径
       （含帧头与密文开销），所以两端数字应当对得上，能相互印证。 */
    this.rxBytes = 0
    this.txBytes = 0
    this.startedAt = 0
  }

  /**
   * 建立数据通道，**等真正 open 后才返回**。
   *
   * 浏览器里 `new WebSocket()` 不抛错，握手被 TLS / 代理拦下时只在稍后发一个 close 事件。
   * 早先这里没等，于是手机上的现象是：页面先打一行「已连接中继」，半秒后被 onclose 踢回
   * 设备列表，一条错误提示都没有——即「画面闪一下就退出」，用户与日志两头都查不出原因。
   * 现在把 open/close 收敛成一个 promise，失败直接带原因抛出，由页面层给可读提示。
   */
  async connect() {
    this.key = await importAesKey(this.aesKeyB64)
    const url =
      `${wsBaseURL()}/ws/remote/control` +
      `?ticket=${encodeURIComponent(this.ticket)}` +
      `&satoken=${encodeURIComponent(this.satoken)}`
    this.ws = new WebSocket(url)
    this.ws.binaryType = 'arraybuffer'
    const ready = new Promise((resolve, reject) => {
      this.openResolve = resolve
      this.openReject = reject
    })
    // 8 秒：比一次正常的 TLS + 代理转发慢得多，又比用户的耐心短
    const watchdog = setTimeout(() => {
      this.rejectOpen(new Error('数据通道 8 秒内未建立（常见原因：手机未完全信任 certs/ca.pem，wss 被静默拒绝）'))
    }, 8000)
    this.ws.onopen = () => {
      clearTimeout(watchdog)
      this.opened = true
      this.startedAt = Date.now()
      // 就绪帧是票据的真正消费点：连接活着 + 客户端明确就绪，才绑定中继
      this.sendEnvelope('control-ready', {})
      this.heartbeatTimer = setInterval(() => {
        try {
          this.sendEnvelope('ping', { ts: Date.now() })
        } catch {
          // 心跳失败由 onclose 兜底
        }
      }, 30000)
      this.onOpen && this.onOpen()
      this.resolveOpen()
    }
    this.ws.onmessage = (event) => {
      if (typeof event.data === 'string') {
        // 文本帧按 UTF-8 字节数计，中文日志/目录列表 JSON 按字符数会低估一大截
        this.rxBytes += textBytes(event.data)
        this.handleText(event.data)
      } else {
        this.rxBytes += event.data.byteLength
        this.handleBinary(event.data).catch((e) => console.warn('[remote] 二进制帧处理失败', e))
      }
    }
    this.ws.onclose = (event) => {
      clearTimeout(watchdog)
      this.stopHeartbeat()
      this.rejectAllPending(new Error('连接已断开'))
      // 还没 open 就断了 => 握手根本没成功（证书、ticket、代理三种成因之一），
      // 把 close code 带上，至少能把「手机端静默拒绝」与服务端主动踢连分开
      if (!this.opened) {
        this.rejectOpen(new Error(`数据通道握手失败（code=${event.code || '无'}）`))
      }
      // 从未建立成功过的连接不走页面层的「连接断开」收尾：那条路径会记一条 0 字节的
      // 会话结束审计，而真正的原因已经由上面的 reject 交给页面层提示了
      if (this.opened || this.closedByUser) {
        this.onClose && this.onClose(event)
      }
    }
    this.ws.onerror = () => {
      // 规范保证 error 之后必有 close 事件，这里不重复处理
    }
    await ready
  }

  resolveOpen() {
    const resolve = this.openResolve
    this.openResolve = null
    this.openReject = null
    resolve && resolve()
  }

  rejectOpen(error) {
    const reject = this.openReject
    this.openResolve = null
    this.openReject = null
    reject && reject(error)
  }

  handleText(raw) {
    const env = parseEnvelope(raw)
    if (!env) {
      return
    }
    const data = env.data || {}
    if (data.reqSeq && this.pending.has(data.reqSeq)) {
      const { resolve, reject } = this.pending.get(data.reqSeq)
      this.pending.delete(data.reqSeq)
      if (env.type === 'error') {
        reject(new Error(data.message || '被控端拒绝了该操作'))
      } else {
        resolve(data)
      }
      // result/error 同时交给页面层（如文件下载完成的副作用），不吞掉
    }
    this.onEnvelope && this.onEnvelope(env)
  }

  async handleBinary(buffer) {
    const frame = await decodeFrame(buffer, this.key)
    if (!frame) {
      return
    }
    this.onBinaryFrame && this.onBinaryFrame(frame)
  }

  sendEnvelope(type, data) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return null
    }
    const packet = buildEnvelope(type, this.sessionId, ++this.seq, data)
    const json = JSON.stringify(packet)
    this.txBytes += textBytes(json)
    this.ws.send(json)
    return packet.seq
  }

  /** 发一条请求帧并等待被控端 result/error 回执（按 reqSeq 对齐） */
  request(type, data, timeoutMs = 20000) {
    return new Promise((resolve, reject) => {
      const seq = this.sendEnvelope(type, data)
      if (!seq) {
        reject(new Error('连接未就绪'))
        return
      }
      const timer = setTimeout(() => {
        this.pending.delete(seq)
        reject(new Error('被控端响应超时'))
      }, timeoutMs)
      this.pending.set(seq, {
        resolve: (value) => {
          clearTimeout(timer)
          resolve(value)
        },
        reject: (err) => {
          clearTimeout(timer)
          reject(err)
        }
      })
    })
  }

  /** 发送二进制文件块（上传路径，载荷加密后拼接 12B IV） */
  async sendBinaryFrame(frameType, meta, payload) {
    const packet = await encodeFrame({ frameType, sessionId: this.sessionId, meta, payload, key: this.key })
    this.txBytes += packet.byteLength
    this.ws.send(packet.buffer)
  }

  /** 本会话累计流量与时长，供页面实时展示与结束时汇总（单位：字节 / 秒） */
  stats() {
    return {
      rx: this.rxBytes,
      tx: this.txBytes,
      seconds: this.startedAt ? Math.max(0, Math.round((Date.now() - this.startedAt) / 1000)) : 0
    }
  }

  close() {
    this.closedByUser = true
    this.stopHeartbeat()
    if (this.ws && this.ws.readyState <= WebSocket.OPEN) {
      try {
        this.ws.send(JSON.stringify(buildEnvelope('session-end', this.sessionId, 0, null)))
      } catch {
        // 已经断了就无所谓
      }
      this.ws.close()
    }
    this.ws = null
  }

  get ready() {
    return this.ws && this.ws.readyState === WebSocket.OPEN
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  rejectAllPending(err) {
    for (const { reject } of this.pending.values()) {
      reject(err)
    }
    this.pending.clear()
  }
}
