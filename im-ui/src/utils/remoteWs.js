/**
 * 远程控制数据面 WS 客户端（控制端侧）。
 *
 * 协议与后端 com.im.remote.protocol 严格对应：
 *  - 文本帧：JSON 信封 {v:1, type, sid, seq, ts, data}
 *  - 二进制帧：[1B 帧类型][8B 会话ID][4B 元数据长度][元数据JSON][载荷]
 *  - 载荷加密：12B IV + AES-256-GCM 密文（Tag 拼在密文尾部，与 Java Cipher 输出一致）
 *
 * 握手 URL 同时带 ticket（一次性会话凭证）与 satoken（登录态），后者是
 * 浏览器 WS 无法自定义请求头条件下唯一的身份来源；两者缺一服务端都拒绝。
 *
 * 注意：WebCrypto（crypto.subtle）只在 secure context 可用——localhost、
 * https、Electron 都满足；用局域网 IP + http 访问会拿不到 subtle，
 * 此时若会话启用了 AES 会直接报错提示，而不是静默丢帧。
 */
import { wsBaseURL } from '@/utils/env'

export const FRAME_SCREEN = 1
export const FRAME_FILE = 2

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
  }

  async connect() {
    if (this.aesKeyB64) {
      if (!globalThis.crypto?.subtle) {
        throw new Error('当前环境不支持 WebCrypto（请用 localhost / HTTPS / Electron 打开）')
      }
      const raw = base64ToBytes(this.aesKeyB64)
      this.key = await globalThis.crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, [
        'encrypt',
        'decrypt'
      ])
    }
    const url =
      `${wsBaseURL()}/ws/remote/control` +
      `?ticket=${encodeURIComponent(this.ticket)}` +
      `&satoken=${encodeURIComponent(this.satoken)}`
    this.ws = new WebSocket(url)
    this.ws.binaryType = 'arraybuffer'
    this.ws.onopen = () => {
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
    }
    this.ws.onmessage = (event) => {
      if (typeof event.data === 'string') {
        this.handleText(event.data)
      } else {
        this.handleBinary(event.data).catch((e) => console.warn('[remote] 二进制帧处理失败', e))
      }
    }
    this.ws.onclose = (event) => {
      this.stopHeartbeat()
      this.rejectAllPending(new Error('连接已断开'))
      this.onClose && this.onClose(event)
    }
    this.ws.onerror = () => {
      // 规范保证 error 之后必有 close 事件，这里不重复处理
    }
  }

  handleText(raw) {
    let env
    try {
      env = JSON.parse(raw)
    } catch {
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
    const view = new DataView(buffer)
    if (buffer.byteLength < 13) {
      return
    }
    const frameType = view.getUint8(0)
    // 8B sid 用 BigUint64 读，超过 MAX_SAFE_INTEGER 的精度丢失无碍——我们只按 frameType 分发
    const metaLen = view.getInt32(9)
    const metaBytes = new Uint8Array(buffer, 13, metaLen)
    const meta = JSON.parse(new TextDecoder().decode(metaBytes))
    let payload = new Uint8Array(buffer, 13 + metaLen)
    if (this.key && payload.length > 12) {
      payload = new Uint8Array(
        await globalThis.crypto.subtle.decrypt(
          { name: 'AES-GCM', iv: payload.slice(0, 12) },
          this.key,
          payload.slice(12)
        )
      )
    }
    this.onBinaryFrame && this.onBinaryFrame({ frameType, meta, payload })
  }

  sendEnvelope(type, data) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return null
    }
    const packet = { v: 1, type, sid: Number(this.sessionId), seq: ++this.seq, ts: Date.now() }
    if (data && Object.keys(data).length) {
      packet.data = data
    }
    this.ws.send(JSON.stringify(packet))
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
    let body = payload
    if (this.key) {
      const iv = globalThis.crypto.getRandomValues(new Uint8Array(12))
      const encrypted = new Uint8Array(
        await globalThis.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, this.key, payload)
      )
      body = concat(iv, encrypted)
    }
    const metaBytes = new TextEncoder().encode(JSON.stringify(meta))
    const packet = new Uint8Array(13 + metaBytes.length + body.length)
    const view = new DataView(packet.buffer)
    view.setUint8(0, frameType)
    view.setBigUint64(1, BigInt(this.sessionId))
    view.setInt32(9, metaBytes.length)
    packet.set(metaBytes, 13)
    packet.set(body, 13 + metaBytes.length)
    this.ws.send(packet.buffer)
  }

  close() {
    this.closedByUser = true
    this.stopHeartbeat()
    if (this.ws && this.ws.readyState <= WebSocket.OPEN) {
      try {
        this.ws.send(JSON.stringify({ v: 1, type: 'session-end', sid: Number(this.sessionId), ts: Date.now() }))
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

function base64ToBytes(b64) {
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length)
  out.set(a)
  out.set(b, a.length)
  return out
}
