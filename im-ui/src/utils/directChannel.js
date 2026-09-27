/**
 * 直连通道（控制端侧）：局域网 WebSocket 档 + Electron UDP 打洞档。
 *
 * 对外接口与 RemoteControlSocket 完全同名同签名（connect/sendEnvelope/request/
 * sendBinaryFrame/stats/close/ready），页面层只是换个实现，业务代码一行不改。
 *
 * <h2>为什么必须两级尝试</h2>
 *
 * 浏览器开不了原始 UDP，也发不了裸 TCP，所以同网段只能走 WebSocket（被控端
 * DirectTcpServer 自己实现了 RFC6455）；跨公网要直连就必须借 Electron 主进程的
 * dgram，UDP 的打洞、分片、ARQ 全部放在主进程做（electron/direct.js），渲染进程
 * 只处理「完整消息」，这样协议状态不会被两处代码各持一半。
 *
 * <h2>两档都失败不是错误</h2>
 *
 * 防火墙拒连、对称 NAT、被控端没开直连开关都是常态，中继 WS 全程另开一条，
 * 所以这里任何一步失败只是回调 onFallback(reason) 让页面继续用中继，
 * 用户视角最坏就是「和没做直连之前一样」。
 *
 * <h2>与 Agent 侧对齐的三条硬规定</h2>
 *
 *  1. 一条 WS 消息 = 一个完整 DXP 报文（不是纯文本信封），首包必须是 HELLO；
 *  2. TCP 档协商 mtu=1MB，因此永远单分片——收到多分片直接忽略（与 Agent 同口径），
 *     半截消息绝不去拼，花屏比丢一帧更难查；
 *  3. 信封里的 sid 一律用字符串：雪花 ID 超过 2^53，JSON.stringify(Number) 会掉末位，
 *     而 Agent 的 receiveDirectText 恰恰拿 sid 做等值校验，掉精度等于每帧都被丢。
 */
import { decodeFrame, encodeFrame, importAesKey, base64ToBytes, parseEnvelope } from '@/utils/remoteCodec'
import {
  CH,
  KIND,
  TYPE,
  decode,
  encode,
  encodeText,
  proofB64,
  randomHex,
  readFragment,
  splitData
} from '@/utils/dxp'

/** DXP 单报文头 + DATA 分片头，UDP 档估流量时按它补上封装开销 */
const DXP_OVERHEAD = 18
/** LAN 档握手与读帧的兜底超时：Agent 的 handshakeTimeoutMs 只用于 UDP 侧提示 */
const HANDSHAKE_TIMEOUT_MS = 3000
/** 心跳间隔与容忍次数：3 次无 PONG 即判链路死，回到中继 */
const PING_INTERVAL_MS = 15000
const PING_MAX_MISS = 3
/** DXP 单报文上限，TCP 档直接按它协商，等于告诉对端「不要切片」 */
const TCP_MTU = 1048576

export class DirectChannel {
  /**
   * @param {object} options
   * @param {string} options.sessionId 会话 ID（雪花字符串）
   * @param {string} [options.aesKeyB64] 会话 AES-256 密钥，同时用于密钥证明
   * @param {object} options.direct 服务端 detail() 下发的 direct 节点（token/enabled/lan/udp/mtu）
   * @param {object} options.candidates 被控端上报的可连地址（lan/tcpPort/udpPort/publicHost）
   * @param {(env:object)=>void} options.onEnvelope 收到文本信封
   * @param {(frame:{frameType:number,meta:object,payload:Uint8Array})=>void} options.onBinaryFrame 收到二进制帧
   * @param {(path:string|null)=>void} [options.onPath] 链路变化：'tcp'/'udp'/null
   * @param {(reason:string)=>void} [options.onFallback] 全部尝试失败，页面该继续用中继
   */
  constructor(options) {
    this.sessionId = options.sessionId
    this.aesKeyB64 = options.aesKeyB64 || ''
    this.direct = options.direct || {}
    this.candidates = options.candidates || {}
    this.onEnvelope = options.onEnvelope
    this.onBinaryFrame = options.onBinaryFrame
    this.onPath = options.onPath
    this.onFallback = options.onFallback

    this.seq = 0
    this.path = null
    this.ws = null
    this.key = null
    this.keyBytes = null
    this.pending = new Map()
    this.heartbeatTimer = null
    this.pingMiss = 0
    this.closedByUser = false
    this.outMsgId = 0
    this.bridgeBound = false
    this.rxBytes = 0
    this.txBytes = 0
    this.startedAt = 0
  }

  /**
   * 按阶梯尝试：LAN WebSocket → Electron UDP 打洞 → 放弃。
   * @returns {Promise<string|null>} 成功的档位（'tcp'/'udp'），失败为 null
   */
  async connect() {
    this.key = await importAesKey(this.aesKeyB64)
    this.keyBytes = this.aesKeyB64 ? base64ToBytes(this.aesKeyB64) : null
    const token = this.direct.token
    if (!this.direct.enabled || !token) {
      // 服务端没开 im.remote.direct.enabled 或会话未激活，不必再试
      this.fallback('direct-disabled')
      return null
    }
    if (this.direct.lan && this.canOpenLan()) {
      for (const host of this.lanHosts()) {
        try {
          await this.openLan(host)
          this.startedAt = Date.now()
          this.startHeartbeat()
          this.onPath && this.onPath(this.path)
          return this.path
        } catch (e) {
          this.note('局域网直连失败', host, e)
        }
      }
    }
    const bridge = globalThis.window?.__IM_DIRECT__
    if (this.direct.udp && bridge && this.udpEndpoint()) {
      try {
        const result = await this.openUdp(bridge)
        if (result) {
          this.startedAt = Date.now()
          this.onPath && this.onPath(this.path)
          return this.path
        }
        this.note('UDP 打洞直连未通', this.udpEndpoint())
      } catch (e) {
        this.note('UDP 打洞直连失败', '', e)
      }
    }
    this.fallback('no-direct-path')
    return null
  }

  /* ==================== LAN（WebSocket 档） ==================== */

  /**
   * 混合内容硬限制：https 页面连 ws:// 会被浏览器直接拦掉（Chrome 甚至按页面策略
   * 阻断任何 ws://），这种失败和防火墙无关，重试每个地址都只是刷日志。
   */
  canOpenLan() {
    return globalThis.location ? globalThis.location.protocol !== 'https:' : true
  }

  /** 被控端候选地址：优先局域网，公网映射留给 UDP 档 */
  lanHosts() {
    const hosts = []
    for (const list of [this.candidates.lan, this.candidates.publicHost]) {
      for (const host of Array.isArray(list) ? list : list ? [list] : []) {
        if (host && !hosts.includes(host)) {
          hosts.push(host)
        }
      }
    }
    return hosts
  }

  openLan(host) {
    const port = Number(this.candidates.tcpPort || 0)
    if (!port) {
      return Promise.reject(new Error('无直连 TCP 端口'))
    }
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(`ws://${host}:${port}`)
      socket.binaryType = 'arraybuffer'
      let settled = false
      const fail = (err) => {
        if (!settled) {
          // 握手阶段失败：只让 connect() 换下一档，不能当作「已有链路掉线」去回调
          settled = true
          clearTimeout(timer)
          try {
            socket.close()
          } catch {
            // 已经死了
          }
          reject(err)
          return
        }
        if (this.ws === socket) {
          // 已就绪后对端断开：拆链路并告诉页面「回到中继」，否则画面会静默停住
          this.teardown(err && err.message ? err.message : String(err))
        }
      }
      const timer = setTimeout(() => fail(new Error('握手超时')), HANDSHAKE_TIMEOUT_MS)
      socket.onopen = () => {
        // 浏览器不能自定义请求头，身份全在 HELLO 报文体里：token + 密钥证明
        this.helloPacket('tcp')
          .then((packet) => this.writeLan(packet))
          .catch(fail)
      }
      socket.onmessage = (event) => {
        if (typeof event.data === 'string') {
          return
        }
        this.rxBytes += event.data.byteLength
        this.onLanPacket(new Uint8Array(event.data), () => {
          if (settled) {
            return
          }
          settled = true
          clearTimeout(timer)
          this.ws = socket
          this.path = 'tcp'
          resolve()
        }, fail)
      }
      socket.onclose = () => fail(new Error('连接已断开'))
      socket.onerror = () => fail(new Error('无法连接'))
    })
  }

  /** 解一个 LAN 报文；onReady 只在 HELLO_ACK 校验通过时回调一次 */
  onLanPacket(packet, onReady, fail) {
    const parsed = decode(packet)
    if (!parsed) {
      return
    }
    if (parsed.type === TYPE.HELLO_ACK) {
      this.handleHelloAck(parsed).then(onReady).catch(fail)
      return
    }
    if (parsed.type === TYPE.PONG) {
      this.pingMiss = 0
      return
    }
    if (parsed.type === TYPE.CLOSE) {
      fail(new Error('对端关闭直连'))
      return
    }
    if (!this.ws) {
      // 握手还没完成就来数据：说明对端把我们当成了上一条连接的残留，忽略
      return
    }
    this.onDataPacket(parsed)
  }

  async handleHelloAck(parsed) {
    const ack = parseEnvelope(textOf(parsed.body))
    if (!ack || ack.kind !== 'helloAck') {
      throw new Error('ACK 格式不符')
    }
    if (!ack.ok) {
      throw new Error(`被控端拒绝: ${ack.reason || 'unknown'}`)
    }
    if (String(ack.sid) !== String(this.sessionId)) {
      // 打洞后可能撞上别人家的会话，宁可放弃直连也不能串会话
      throw new Error('会话号不符')
    }
    const expected = await proofB64(this.keyBytes, this.direct.token, this.sessionId, ack.anonce, 'dxp-ack')
    if (expected !== ack.mac) {
      throw new Error('密钥证明校验失败')
    }
    this.peer = ack.deviceName || ''
  }

  /** 组 HELLO：sid 传字符串，mac 与 Agent 的 proof() 同串同序 */
  async helloPacket(mode) {
    const cnonce = randomHex(12)
    this.cnonce = cnonce
    const body = {
      v: 1,
      kind: 'hello',
      sid: String(this.sessionId),
      cnonce,
      token: this.direct.token,
      mtu: mode === 'tcp' ? TCP_MTU : Number(this.direct.mtu || 1200)
    }
    body.mac = await proofB64(this.keyBytes, this.direct.token, this.sessionId, cnonce, 'dxp-hello')
    return encode(TYPE.HELLO, CH.CONTROL, 0, 0, 0, JSON.stringify(body))
  }

  onDataPacket(parsed) {
    if (parsed.type !== TYPE.DATA) {
      // TCP 档的 ACK 无意义，PING 由对端主动回 PONG
      return
    }
    const fragment = readFragment(parsed.body)
    if (!fragment || fragment.count !== 1) {
      // 与 Agent 侧一致：TCP 协商了 1MB 还收到多分片，是协议实现分裂，宁丢不拼
      return
    }
    this.dispatch(fragment.kind, fragment.data)
  }

  async dispatch(kind, message) {
    if (kind === KIND.TEXT) {
      const env = parseEnvelope(textOf(message))
      if (!env) {
        return
      }
      this.settlePending(env)
      this.onEnvelope && this.onEnvelope(env)
      return
    }
    const frame = await decodeFrame(message.buffer.slice(message.byteOffset, message.byteOffset + message.byteLength), this.key)
    if (frame) {
      this.onBinaryFrame && this.onBinaryFrame(frame)
    }
  }

  /** reqSeq 对齐：与中继档同一套，页面层的 await 不关心帧从哪条链路回来 */
  settlePending(env) {
    const data = env.data || {}
    if (!data.reqSeq || !this.pending.has(data.reqSeq)) {
      return
    }
    const { resolve, reject } = this.pending.get(data.reqSeq)
    this.pending.delete(data.reqSeq)
    if (env.type === 'error') {
      reject(new Error(data.message || '被控端拒绝了该操作'))
    } else {
      resolve(data)
    }
  }

  writeLan(packet) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('直连未就绪'))
    }
    // 计数含 14B WS 帧头开销估算，与 Agent 侧 accountSent 同一口径
    this.txBytes += packet.length + 14
    this.ws.send(packet)
    return Promise.resolve()
  }

  /* ==================== UDP（Electron 主进程档） ==================== */

  udpEndpoint() {
    // 公网映射端口以反射服务回显的 publicPort 为准：网关不一定把 18925 原样映射出去
    const publicHost = this.candidates.publicHost
    if (publicHost) {
      const port = Number(this.candidates.publicPort || this.candidates.udpPort || 0)
      if (port) {
        return { host: publicHost, port }
      }
    }
    const lan = Array.isArray(this.candidates.lan) ? this.candidates.lan[0] : ''
    const udpPort = Number(this.candidates.udpPort || 0)
    return lan && udpPort ? { host: lan, port: udpPort } : null
  }

  /**
   * UDP 档整条链路交给主进程：打洞、DXP 分片、GBN 重传都在那边，
   * 这里只负责把「完整消息」接回页面，避免同一份 ARQ 状态两端各持一半。
   */
  async openUdp(bridge) {
    const endpoint = this.udpEndpoint()
    if (!bridge.open || !bridge.write) {
      return false
    }
    if (!this.bridgeBound) {
      this.bridgeBound = true
      // 事件监听只挂一次：一个页面生命周期内最多一条 UDP 直连，重连也复用同一批回调
      if (bridge.onData) {
        bridge.onData((message) => this.onUdpMessage(message))
      }
      if (bridge.onState) {
        bridge.onState((state) => this.onUdpState(state))
      }
    }
    const result = await bridge.open({
      host: endpoint.host,
      udpPort: endpoint.port,
      token: this.direct.token,
      aesKeyB64: this.aesKeyB64,
      sessionId: String(this.sessionId),
      mtu: Number(this.direct.mtu || 1200),
      punchHost: this.direct.punchHost || '',
      punchPort: Number(this.direct.punchPort || 0)
    })
    this.path = 'udp'
    this.peer = (result && result.peer) || ''
    return true
  }

  /** 主进程侧链路状态：'punched' 只是打洞线索，'closed' 才需要回落中继 */
  onUdpState(state) {
    if (!state) {
      return
    }
    if (state.status === 'closed') {
      if (this.path === 'udp' && !this.closedByUser) {
        this.teardown(state.reason || 'udp-link-lost')
      }
      return
    }
    this.note('UDP 打洞', state.status, state.publicHost, state.publicPort)
  }

  onUdpMessage(message) {
    if (!message) {
      return
    }
    this.rxBytes += message.bytes || 0
    if (message.t === 'text') {
      this.dispatch(KIND.TEXT, typeof message.data === 'string' ? encodeText(message.data) : toBytes(message.data))
      return
    }
    if (message.t === 'bin') {
      this.dispatch(KIND.BINARY, toBytes(message.data))
    }
  }

  /**
   * 投递给主进程：不等它回执就返回 true。
   *
   * 页面层（鼠标移动等）按中继档的同步语义调用 sendEnvelope，改成 await 会把
   * 输入事件堵在渲染线程上；IPC 本身保序，主进程那边还有一条 promise 链串行入队，
   * 所以「先投出去、失败再异步拆链路」既不丢序也不卡界面。真失败了下一帧就走中继。
   */
  sendUdp(kind, bytes) {
    const bridge = globalThis.window?.__IM_DIRECT__
    if (!bridge || !bridge.write) {
      return false
    }
    let pending
    try {
      pending = bridge.write({ channel: 'reliable', kind, data: bytes })
    } catch (e) {
      this.note('UDP 投递失败', e && e.message)
      return false
    }
    if (!pending || typeof pending.then !== 'function') {
      this.txBytes += bytes.length + DXP_OVERHEAD
      return true
    }
    pending
      .then((result) => {
        this.txBytes += (result && result.bytes) || bytes.length + DXP_OVERHEAD
      })
      .catch((e) => {
        this.note('UDP 发送失败', e && e.message)
        if (this.path === 'udp' && !this.closedByUser) {
          this.teardown('udp-write-failed')
        }
      })
    return true
  }

  /* ==================== 与中继档同名的对外接口 ==================== */

  sendEnvelope(type, data) {
    if (!this.ready) {
      return null
    }
    const packet = { v: 1, type, sid: String(this.sessionId), seq: ++this.seq, ts: Date.now() }
    if (data && Object.keys(data).length) {
      packet.data = data
    }
    const json = JSON.stringify(packet)
    if (!this.sendReliable(KIND.TEXT, encodeText(json))) {
      return null
    }
    return packet.seq
  }

  /** 请求-响应：非就绪时返回 null，由页面层回落中继（不静默丢命令） */
  request(type, data, timeoutMs = 20000) {
    return new Promise((resolve, reject) => {
      const seq = this.sendEnvelope(type, data)
      if (!seq) {
        reject(new Error('直连未就绪'))
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

  async sendBinaryFrame(frameType, meta, payload) {
    if (!this.ready) {
      return false
    }
    const packet = await encodeFrame({ frameType, sessionId: this.sessionId, meta, payload, key: this.key })
    return this.sendReliable(KIND.BINARY, packet)
  }

  /** 统一走可靠通道：TCP 直接写 WS，UDP 交给主进程分片 + ARQ */
  sendReliable(kind, bytes) {
    if (this.path === 'tcp') {
      const msgId = ++this.outMsgId
      const packets = splitData(CH.RELIABLE, kind, bytes, TCP_MTU, msgId)
      for (const packet of packets) {
        this.writeLan(packet).catch(() => this.teardown('write-failed'))
      }
      return true
    }
    if (this.path === 'udp') {
      // 主进程要求「一条消息 + 通道名」，分片与重传在它那边做
      return this.sendUdp(kind, bytes)
    }
    return false
  }

  stats() {
    return {
      rx: this.rxBytes,
      tx: this.txBytes,
      seconds: this.startedAt ? Math.max(0, Math.round((Date.now() - this.startedAt) / 1000)) : 0
    }
  }

  close() {
    this.closedByUser = true
    this.teardown('local-close')
  }

  get ready() {
    if (this.path === 'tcp') {
      return !!this.ws && this.ws.readyState === WebSocket.OPEN
    }
    return this.path === 'udp'
  }

  get currentPath() {
    return this.path
  }

  startHeartbeat() {
    this.stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      if (!this.ready) {
        this.teardown('not-ready')
        return
      }
      if (this.path !== 'tcp') {
        // UDP 档的保活由主进程自己发 DXP PING，浏览器这边插不进
        return
      }
      if (this.pingMiss >= PING_MAX_MISS) {
        this.teardown('heartbeat-timeout')
        return
      }
      this.pingMiss++
      this.writeLan(encode(TYPE.PING, CH.CONTROL, 0, 0, 0, null)).catch(() => this.teardown('ping-failed'))
    }, PING_INTERVAL_MS)
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  /** 拆链路并交回中继：调用方负责把后续消息发给 relay */
  teardown(reason) {
    this.stopHeartbeat()
    const hadPath = !!this.path
    if (this.ws) {
      try {
        this.ws.send(encode(TYPE.CLOSE, CH.CONTROL, 0, 0, 0, null))
      } catch {
        // 已断
      }
      try {
        this.ws.close()
      } catch {
        // 已断
      }
      this.ws = null
    }
    const bridge = globalThis.window?.__IM_DIRECT__
    if (this.path === 'udp' && bridge && bridge.close) {
      try {
        const pending = bridge.close()
        if (pending && typeof pending.catch === 'function') {
          pending.catch(() => {
            // 主进程自己会收尾
          })
        }
      } catch {
        // 已断
      }
    }
    this.path = null
    for (const { reject } of this.pending.values()) {
      reject(new Error('直连已断开'))
    }
    this.pending.clear()
    if (hadPath && !this.closedByUser) {
      this.onPath && this.onPath(null)
    }
    if (reason) {
      this.note('直连已回落中继', reason)
    }
  }

  fallback(reason) {
    this.teardown(null)
    this.note('直连不可用', reason)
    this.onFallback && this.onFallback(reason)
  }

  note(...parts) {
    if (globalThis.console) {
      console.debug('[direct]', ...parts)
    }
  }
}

function toBytes(data) {
  if (data instanceof Uint8Array) {
    return data
  }
  if (data instanceof ArrayBuffer) {
    return new Uint8Array(data)
  }
  if (Array.isArray(data)) {
    return new Uint8Array(data)
  }
  if (typeof data === 'string') {
    return base64ToBytes(data)
  }
  return new Uint8Array(0)
}

function textOf(bytes) {
  return bytes && bytes.length ? new TextDecoder().decode(bytes) : ''
}

/** 便于页面层判断「这个环境有没有 UDP 能力」：只有 Electron 主进程注册了桥才有 */
export function directUdpSupported() {
  return !!globalThis.window?.__IM_DIRECT__
}
