'use strict'

/**
 * 远程桌面 UDP 直连（Electron 主进程侧）。
 *
 * <h2>为什么只能在主进程做</h2>
 *
 * 浏览器和渲染进程都开不了原始 UDP socket，跨公网打洞这一档必须借主进程的 dgram。
 * 打洞、DXP 分片、Go-Back-N 重传、组包重组全部放在这里，渲染进程只收「完整消息」——
 * 同一份 ARQ 状态如果被两处各持一半，丢包重传的行为就无法推理，出问题也查不清。
 *
 * <h2>与 Agent 侧的关系</h2>
 *
 * 报文格式、ARQ 参数、握手密钥证明全部对齐 com.im.remote.agent 的
 * Dxp / DirectArq / DirectChannel 三个类，字段顺序与常量值一改必须两边同改，
 * 否则表现是「握手永远不成」或「花屏」，而不是明确报错。
 *
 * <h2>失败不是异常</h2>
 *
 * 对称 NAT 打不通、对方防火墙拒 UDP、被控端没开直连开关都是常态。这里任何一步失败
 * 只是把 open() 这个 promise reject 掉（或把链路判死后发一次 state 事件），
 * 渲染侧据此继续用中继，用户视角最坏就是「和没做直连之前一样」。
 */

const dgram = require('dgram')
const crypto = require('crypto')
const os = require('os')

/* ==================== DXP 报文（对齐 Agent 侧 Dxp.java） ==================== */

const VER = 1
const HEADER = 12
const DATA_HEADER = 6
const MAX_MTU = 1048576

const TYPE = { HELLO: 1, HELLO_ACK: 2, DATA: 3, ACK: 4, PING: 6, PONG: 7, CLOSE: 8 }
const CH = { CONTROL: 0, SCREEN: 1, RELIABLE: 2 }
const KIND = { TEXT: 0, BINARY: 1 }
const FLAG_LAST = 1

/** 单报文可携带的载荷字节数 */
function payloadCapacity(mtu) {
  return Math.max(64, Math.min(mtu, MAX_MTU) - HEADER - DATA_HEADER)
}

function fragmentCount(mtu, length) {
  return Math.max(1, Math.ceil(length / payloadCapacity(mtu)))
}

function encode(type, channel, flags, msgId, seq, body) {
  const payload = toBuffer(body)
  const packet = Buffer.alloc(HEADER + payload.length)
  packet.writeUInt8(VER, 0)
  packet.writeUInt8(type, 1)
  packet.writeUInt8(channel, 2)
  packet.writeUInt8(flags, 3)
  packet.writeUInt32BE((msgId || 0) >>> 0, 4)
  packet.writeUInt32BE((seq || 0) >>> 0, 8)
  if (payload.length) {
    payload.copy(packet, HEADER)
  }
  return packet
}

/** 解一个报文；畸形包返回 null 由调用方静默丢弃（打洞期收到杂包是常态） */
function decode(buf) {
  if (!buf || buf.length < HEADER || buf.readUInt8(0) !== VER) {
    return null
  }
  return {
    type: buf.readUInt8(1),
    channel: buf.readUInt8(2),
    flags: buf.readUInt8(3),
    msgId: buf.readUInt32BE(4),
    seq: buf.readUInt32BE(8),
    body: buf.subarray(HEADER)
  }
}

function fragmentBody(index, count, kind, data) {
  const body = Buffer.alloc(DATA_HEADER + data.length)
  body.writeUInt16BE(index, 0)
  body.writeUInt16BE(count, 2)
  body.writeUInt8(kind, 4)
  body.writeUInt8(0, 5)
  data.copy(body, DATA_HEADER)
  return body
}

function readFragment(body) {
  if (!body || body.length < DATA_HEADER) {
    return null
  }
  return {
    index: body.readUInt16BE(0),
    count: body.readUInt16BE(2),
    kind: body.readUInt8(4),
    data: body.subarray(DATA_HEADER)
  }
}

function ackBody(lastContiguousSeq) {
  return Buffer.from(UInt32(lastContiguousSeq))
}

function UInt32(value) {
  const buf = Buffer.alloc(4)
  buf.writeUInt32BE((value || 0) >>> 0, 0)
  return buf
}

function readAckSeq(body) {
  return body && body.length >= 4 ? body.readUInt32BE(0) : -1
}

/** 改写报文头里的 seq 字段（第 8..11 字节），避免为了编号把整条消息重切一遍 */
function withSeq(packet, seq) {
  const copy = Buffer.from(packet)
  copy.writeUInt32BE((seq || 0) >>> 0, 8)
  return copy
}

function toBuffer(body) {
  if (body === null || body === undefined) {
    return Buffer.alloc(0)
  }
  if (Buffer.isBuffer(body)) {
    return body
  }
  if (body instanceof Uint8Array) {
    return Buffer.from(body.buffer, body.byteOffset, body.byteLength)
  }
  if (body instanceof ArrayBuffer) {
    return Buffer.from(body)
  }
  if (typeof body === 'string') {
    return Buffer.from(body, 'utf8')
  }
  return Buffer.alloc(0)
}

/* ==================== 握手密钥证明（对齐 DirectChannel.proof） ==================== */

/**
 * Base64(HMAC-SHA256(aesKey, "prefix|sid|nonce|token"))；无 32B 密钥时退化为 SHA-256 同一串。
 * sid 一律按字符串参与拼接：雪花 ID 走 JS Number 会掉末位，与 Agent 的 String.valueOf(long) 对不上。
 */
function proofB64(keyBytes, token, sid, nonce, prefix) {
  const material = `${prefix}|${sid}|${nonce}|${token}`
  if (keyBytes && keyBytes.length === 32) {
    return crypto.createHmac('sha256', keyBytes).update(material, 'utf8').digest('base64')
  }
  return crypto.createHash('sha256').update(material, 'utf8').digest('base64')
}

function randomHex(bytes) {
  return crypto.randomBytes(bytes).toString('hex')
}

/* ==================== 分片重组槽位（对齐 DirectArq.Slot） ==================== */

class Slot {
  constructor() {
    this.reset()
  }

  reset() {
    this.msgId = -1
    this.kind = 0
    this.expectedIndex = 0
    this.count = 0
    this.poisoned = false
    this.parts = []
    this.size = 0
    this.updatedAt = 0
  }

  flush() {
    return Buffer.concat(this.parts, this.size)
  }
}

/* ==================== 可靠通道 ARQ（对齐 DirectArq.java） ==================== */

const WINDOW = 64
const RELIABLE_QUEUE = WINDOW * 2
const SCREEN_QUEUE = 4
const MAX_TIMEOUT_STREAK = 4
const RTO_BASE_MS = 250
const RTO_MAX_MS = 1000
const SEND_BLOCK_MS = 2000
const SCREEN_SLOT_STALE_MS = 400
const TICK_MS = 50
const PING_INTERVAL_MS = 3000

/**
 * Go-Back-N + 分片重组。
 *
 * Java 侧靠 synchronized + wait(20) 做「队列满最多等 2 秒」；Node 是单线程，不能同步阻塞，
 * 于是把每条发送排进一个 promise 链（tail），既保序又能等——乱序入队会让对端的
 * seq 校验直接判成丢包，代价比多等 20 毫秒大得多。
 */
class DirectArqNode {
  /**
   * @param {number} mtu 协商后的单报文上限
   * @param {(packet:Buffer)=>void} send 落到 socket 的出口
   * @param {(kind:number, message:Buffer)=>void} onMessage 重组完成的一条消息
   */
  constructor(mtu, send, onMessage) {
    this.mtu = Math.max(512, Math.min(mtu, MAX_MTU))
    this.send = send
    this.onMessage = onMessage
    this.reliableWaiting = []
    this.screenWaiting = []
    this.unacked = []
    this.nextSeq = 1
    this.lastContiguous = 0
    this.expectedSeq = 1
    this.rtoMs = RTO_BASE_MS
    this.timeoutStreak = 0
    this.ackDue = false
    this.outMsgId = 0
    this.healthy = true
    this.reliableSlot = new Slot()
    this.screenSlot = new Slot()
    /** 发送串行化：并发 write 也不能交错入队 */
    this.tail = Promise.resolve()
  }

  split(channel, kind, message) {
    const capacity = payloadCapacity(this.mtu)
    const count = fragmentCount(this.mtu, message.length)
    const msgId = ++this.outMsgId
    const packets = []
    for (let index = 0; index < count; index++) {
      const offset = index * capacity
      const slice = message.subarray(offset, Math.min(offset + capacity, message.length))
      const flags = index === count - 1 ? FLAG_LAST : 0
      packets.push(encode(TYPE.DATA, channel, flags, msgId, 0, fragmentBody(index, count, kind, slice)))
    }
    return packets
  }

  /** 可靠通道：信封 + 文件块共用同一条有序流，队满最多等 SEND_BLOCK_MS */
  sendReliable(kind, message) {
    const task = this.tail.then(() => this.doSendReliable(kind, message))
    this.tail = task.catch(() => {})
    return task
  }

  async doSendReliable(kind, message) {
    const packets = this.split(CH.RELIABLE, kind, message)
    const deadline = Date.now() + SEND_BLOCK_MS
    while (this.reliableWaiting.length + this.unacked.length > RELIABLE_QUEUE) {
      if (!this.healthy || Date.now() > deadline) {
        return false
      }
      await sleep(20)
    }
    this.reliableWaiting.push(...packets)
    this.pump()
    return true
  }

  /** 画面通道：永不重传永不等待，积压超过 8 包直接丢（丢帧远好过画面延迟累积） */
  sendScreen(kind, message) {
    const packets = this.split(CH.SCREEN, kind, message)
    if (this.screenWaiting.length > SCREEN_QUEUE * 2) {
      return false
    }
    this.screenWaiting.push(...packets)
    this.pump()
    return true
  }

  sendControl(type, body) {
    this.send(encode(type, CH.CONTROL, 0, 0, 0, toBuffer(body)))
  }

  /** 出队：画面包优先（新鲜数据，不受可靠窗口牵制），再填可靠窗口 */
  pump() {
    while (this.screenWaiting.length) {
      this.send(this.screenWaiting.shift())
    }
    while (this.unacked.length < WINDOW && this.reliableWaiting.length) {
      const packet = this.reliableWaiting.shift()
      const seq = this.nextSeq++
      const numbered = withSeq(packet, seq)
      this.unacked.push({ seq, packet: numbered, sentAt: Date.now() })
      this.send(numbered)
    }
  }

  /** @returns {boolean} true 表示本报文已由 ARQ 消化（ACK/DATA），false 交给上层识别控制类 */
  onPacket(packet) {
    if (packet.type === TYPE.ACK) {
      this.acknowledge(readAckSeq(packet.body))
      return true
    }
    if (packet.type === TYPE.DATA) {
      this.deliver(packet)
      return true
    }
    return false
  }

  acknowledge(highestContiguous) {
    if (highestContiguous < this.lastContiguous) {
      return
    }
    while (this.unacked.length && this.unacked[0].seq <= highestContiguous) {
      this.unacked.shift()
    }
    this.lastContiguous = highestContiguous
    this.rtoMs = RTO_BASE_MS
    this.timeoutStreak = 0
    this.pump()
  }

  deliver(packet) {
    const fragment = readFragment(packet.body)
    if (!fragment) {
      return
    }
    if (packet.channel === CH.SCREEN) {
      this.deliverScreen(packet, fragment)
      return
    }
    if (packet.channel !== CH.RELIABLE) {
      return
    }
    if (packet.seq !== this.expectedSeq) {
      // 乱序或重复：GBN 直接丢，并立刻回 ACK 触发对端整窗重发
      this.ackDue = true
      return
    }
    this.expectedSeq++
    const complete = this.accept(this.reliableSlot, fragment)
    const ackValue = this.expectedSeq - 1
    // 每条消息确认一次；长传输期间每 8 包确认一次，兼顾反馈及时与开销
    const sendAck = complete || (this.expectedSeq & 7) === 0
    let done = null
    if (complete) {
      done = { kind: this.reliableSlot.kind, message: this.reliableSlot.flush() }
      this.reliableSlot.reset()
    }
    if (sendAck) {
      this.sendControl(TYPE.ACK, ackBody(ackValue))
    } else {
      this.ackDue = true
    }
    if (done) {
      this.onMessage(done.kind, done.message)
    }
  }

  deliverScreen(packet, fragment) {
    const slot = this.screenSlot
    if (slot.msgId !== packet.msgId) {
      slot.reset()
      slot.msgId = packet.msgId
      if (fragment.index !== 0) {
        // 从半路开始的消息永远拼不齐，整条作废等下一帧
        slot.poisoned = true
      }
    }
    if (slot.poisoned) {
      return
    }
    const complete = this.accept(slot, fragment)
    if (!complete) {
      return
    }
    const done = { kind: slot.kind, message: slot.flush() }
    slot.reset()
    this.onMessage(done.kind, done.message)
  }

  /** 顺序追加分片：index 必须连续，跳号即整条作废 */
  accept(slot, fragment) {
    slot.updatedAt = Date.now()
    if (!slot.count) {
      slot.count = fragment.count
      slot.kind = fragment.kind
    }
    if (fragment.index !== slot.expectedIndex || slot.count !== fragment.count) {
      slot.poisoned = true
      return false
    }
    slot.parts.push(fragment.data)
    slot.size += fragment.data.length
    slot.expectedIndex++
    return slot.expectedIndex >= slot.count
  }

  /** 由定时器每 TICK_MS 调一次：超时重传、补发 ACK、清理卡死的画面槽位 */
  tick() {
    if (!this.healthy) {
      return
    }
    const now = Date.now()
    if (this.unacked.length) {
      const oldest = this.unacked[0]
      if (now - oldest.sentAt > this.rtoMs) {
        if (++this.timeoutStreak >= MAX_TIMEOUT_STREAK) {
          this.healthy = false
          return
        }
        this.rtoMs = Math.min(this.rtoMs * 2, RTO_MAX_MS)
        for (const item of this.unacked) {
          item.sentAt = now
          this.send(item.packet)
        }
      }
    } else {
      this.rtoMs = RTO_BASE_MS
      this.timeoutStreak = 0
    }
    if (this.ackDue) {
      this.ackDue = false
      this.sendControl(TYPE.ACK, ackBody(this.expectedSeq - 1))
    }
    if (this.screenSlot.msgId >= 0 && now - this.screenSlot.updatedAt > SCREEN_SLOT_STALE_MS) {
      this.screenSlot.reset()
    }
    this.pump()
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/* ==================== 一条 UDP 直连链路 ==================== */

const MAGIC = Buffer.from('IMP1', 'ascii')
const HELLO_RETRY_MS = 400
const HELLO_TRIES = 6
const PUNCH_TIMEOUT_MS = 3000
/** 这些拒绝原因是「再来一次也不会成」，立即失败；其余（如 Agent 还没建会话）按周期重发 HELLO */
const FATAL_REJECTS = ['bad-token', 'bad-mac', 'sid-mismatch', 'busy', 'rate-limited', 'disabled']

class DirectLink {
  /**
   * @param {object} params host/udpPort/token/aesKeyB64/sessionId/mtu/punchHost/punchPort
   * @param {(channel:string, payload:object)=>void} emit 向渲染进程投递事件
   */
  constructor(params, emit) {
    this.params = params
    this.emit = emit
    this.token = params.token || ''
    this.sid = String(params.sessionId || '')
    this.keyBytes = params.aesKeyB64 ? Buffer.from(params.aesKeyB64, 'base64') : null
    this.mtu = Math.max(512, Math.min(Number(params.mtu) || 1200, MAX_MTU))
    this.socket = dgram.createSocket('udp4')
    this.arq = null
    this.state = 'init'
    this.peer = ''
    this.closed = false
    this.recvBytes = 0
    this.sentBytes = 0
    this.lastPingAt = 0
    this.helloTries = 0
    this.helloTimer = null
    this.handshakeTimer = null
    this.tickTimer = null
    this.wireBytes = 0
    this.punchWaiter = null
    this.settle = null
    this.cnonce = randomHex(12)
  }

  /** 建链：反射问公网地址 → 连发 HELLO 打洞 → 等 ACK */
  async open() {
    await new Promise((resolve, reject) => {
      this.socket.once('error', reject)
      // 不指定端口：让 OS 选临时端口，映射出来后由反射服务回显
      this.socket.bind(0, '0.0.0.0', resolve)
    })
    this.socket.on('error', (err) => this.fail(err && err.message ? err.message : 'socket 错误'))
    this.socket.on('message', (msg, rinfo) => this.onDatagram(msg, rinfo))
    try {
      this.socket.setRecvBufferSize(2 * 1024 * 1024)
      this.socket.setSendBufferSize(1024 * 1024)
    } catch {
      // 缓冲区调不上只影响吞吐上限，不影响可用性
    }
    const publicAddress = await this.queryPublicAddress()
    this.emit('im:direct-state', { status: 'punched', publicHost: publicAddress.host, publicPort: publicAddress.port })
    this.state = 'handshake'
    return new Promise((resolve) => {
      this.settle = resolve
      this.sendHello()
      this.helloTimer = setInterval(() => this.sendHello(), HELLO_RETRY_MS)
      // 全部尝试用尽仍无 ACK：判洞打不通，交给渲染侧回落中继
      this.handshakeTimer = setTimeout(() => {
        if (this.state === 'handshake') {
          this.fail('打洞握手超时')
        }
      }, HELLO_RETRY_MS * HELLO_TRIES + 1500)
    })
  }

  /**
   * 用「收数据的同一个 socket」问反射服务自己的公网映射。
   * 换个端口去问，拿到的地址就不是别人能打进来的那个口——这是打洞最容易踩错的点。
   */
  queryPublicAddress() {
    const host = this.params.punchHost
    const port = Number(this.params.punchPort) || 0
    if (!host || !port) {
      return Promise.resolve({ host: '', port: 0 })
    }
    const request = Buffer.alloc(9)
    MAGIC.copy(request, 0)
    request.writeUInt8(0, 4)
    request.writeInt32BE((Date.now() & 0x7fffffff) | 0, 5)
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.punchWaiter = null
        resolve({ host: '', port: 0 })
      }, PUNCH_TIMEOUT_MS)
      this.punchWaiter = (address, mapped) => {
        clearTimeout(timer)
        this.punchWaiter = null
        resolve({ host: address, port: mapped })
      }
      this.socket.send(request, 0, request.length, port, host, (err) => {
        if (err) {
          clearTimeout(timer)
          this.punchWaiter = null
          resolve({ host: '', port: 0 })
        }
      })
    })
  }

  sendHello() {
    if (this.state !== 'handshake') {
      return
    }
    if (++this.helloTries > HELLO_TRIES) {
      if (this.state === 'handshake') {
        this.fail('打洞握手超时')
      }
      return
    }
    const body = {
      v: 1,
      kind: 'hello',
      sid: this.sid,
      cnonce: this.cnonce,
      token: this.token,
      mtu: this.mtu
    }
    body.mac = proofB64(this.keyBytes, this.token, this.sid, this.cnonce, 'dxp-hello')
    this.rawSend(encode(TYPE.HELLO, CH.CONTROL, 0, 0, 0, Buffer.from(JSON.stringify(body), 'utf8')))
  }

  targetAddress() {
    return { host: this.params.host, port: Number(this.params.udpPort) || 0 }
  }

  onDatagram(buf, rinfo) {
    if (!buf || !buf.length) {
      return
    }
    // 反射应答不是 DXP 报文，先单独路由（握手前也会到）
    if (buf.length >= 13 && buf.subarray(0, 4).equals(MAGIC) && this.punchWaiter) {
      const ipLength = buf.readUInt8(8)
      if (ipLength === 4 || ipLength === 16) {
        const raw = buf.subarray(9, 9 + ipLength)
        const address =
          ipLength === 4 ? Array.from(raw, (b) => b.toString(10)).join('.') : this.ipv6Text(raw)
        this.punchWaiter(address, buf.readUInt16BE(9 + ipLength))
        return
      }
    }
    const packet = decode(buf)
    if (!packet) {
      return
    }
    if (this.state === 'handshake') {
      if (packet.type === TYPE.HELLO_ACK) {
        this.handleHelloAck(packet)
      }
      return
    }
    if (this.state !== 'open') {
      return
    }
    this.recvBytes += buf.length
    this.wireBytes = buf.length
    if (!this.arq.onPacket(packet)) {
      this.handleControl(packet)
    }
    if (!this.arq.healthy) {
      // 连续多轮重传无进展：洞已失效，别再往里堆数据
      this.fail('链路失活')
    }
  }

  ipv6Text(raw) {
    const parts = []
    for (let i = 0; i < 16; i += 2) {
      parts.push(raw.readUInt16BE(i).toString(16))
    }
    return parts.join(':')
  }

  handleHelloAck(packet) {
    let ack = null
    try {
      ack = JSON.parse(packet.body.toString('utf8'))
    } catch {
      this.fail('ACK 格式不符')
      return
    }
    if (!ack || ack.kind !== 'helloAck') {
      this.fail('ACK 格式不符')
      return
    }
    if (!ack.ok) {
      const reason = ack.reason || '被控端拒绝'
      if (FATAL_REJECTS.indexOf(reason) >= 0) {
        this.fail(reason)
      }
      // no-session 一类是「Agent 还没建会话」的抢跑，保持重发 HELLO 等它就绪
      return
    }
    if (String(ack.sid) !== this.sid) {
      // 打洞后可能撞上别人家的会话，宁可放弃直连也不能串会话
      this.fail('会话号不符')
      return
    }
    const expected = proofB64(this.keyBytes, this.token, this.sid, ack.anonce, 'dxp-ack')
    if (expected !== ack.mac) {
      this.fail('密钥证明校验失败')
      return
    }
    this.state = 'open'
    this.peer = ack.deviceName || rinfoText(this.targetAddress())
    this.clearHello()
    const negotiated = Number(ack.mtu) > 0 ? Number(ack.mtu) : this.mtu
    this.arq = new DirectArqNode(
      negotiated,
      (packetBytes) => this.rawSend(packetBytes),
      (kind, message) => this.onMessage(kind, message)
    )
    this.tickTimer = setInterval(() => this.tick(), TICK_MS)
    if (this.settle) {
      const resolve = this.settle
      this.settle = null
      resolve({ ok: true, peer: this.peer, mtu: negotiated })
    }
  }

  tick() {
    if (this.closed || !this.arq) {
      return
    }
    this.arq.tick()
    const now = Date.now()
    if (now - this.lastPingAt > PING_INTERVAL_MS) {
      this.lastPingAt = now
      // 心跳同时兼作 NAT 映射续期：不发包的映射会在 30~60s 后被网关静默回收
      this.arq.sendControl(TYPE.PING, null)
    }
  }

  handleControl(packet) {
    if (packet.type === TYPE.PING) {
      this.arq.sendControl(TYPE.PONG, null)
      return
    }
    if (packet.type === TYPE.PONG) {
      return
    }
    if (packet.type === TYPE.CLOSE) {
      this.fail('对端关闭直连')
    }
  }

  onMessage(kind, message) {
    const bytes = this.wireBytes || 0
    if (kind === KIND.TEXT) {
      this.emit('im:direct-data', { t: 'text', data: message.toString('utf8'), bytes })
      return
    }
    // ArrayBuffer 走结构化克隆，渲染进程零拷贝拿到二进制帧
    this.emit('im:direct-data', {
      t: 'bin',
      data: message.buffer.slice(message.byteOffset, message.byteOffset + message.length),
      bytes
    })
  }

  rawSend(packet) {
    if (this.closed || !this.socket || this.socket.isClosed()) {
      return
    }
    const target = this.targetAddress()
    this.socket.send(packet, 0, packet.length, target.port, target.host, (err) => {
      if (err) {
        // 端口不可写（多为对端已关会话），下一跳 ARQ 会判死
        this.fail(err.message || '发送失败')
        return
      }
      this.sentBytes += packet.length
    })
  }

  /** @returns {Promise<boolean>} false 表示没进窗（拥堵或链路已死），上层应回落中继 */
  async write(channel, kind, data) {
    if (this.state !== 'open' || !this.arq) {
      return false
    }
    const message = toBuffer(data)
    if (channel === 'screen') {
      return this.arq.sendScreen(kind, message)
    }
    return this.arq.sendReliable(kind, message)
  }

  clearHello() {
    if (this.helloTimer) {
      clearInterval(this.helloTimer)
      this.helloTimer = null
    }
    if (this.handshakeTimer) {
      clearTimeout(this.handshakeTimer)
      this.handshakeTimer = null
    }
  }

  fail(reason) {
    if (this.closed) {
      return
    }
    const opening = this.state !== 'open'
    const message = reason || '直连已断开'
    this.closeLink(message)
    if (opening && this.settle) {
      const resolve = this.settle
      this.settle = null
      resolve({ ok: false, error: message })
      return
    }
    this.emit('im:direct-state', { status: 'closed', reason: message })
  }

  /** 正常收尾：给对端一个 CLOSE，让被控端立刻释放链路而不是等 ARQ 判死 */
  closeLink(reason, notifyPeer) {
    this.clearHello()
    if (this.tickTimer) {
      clearInterval(this.tickTimer)
      this.tickTimer = null
    }
    if (notifyPeer && this.arq && this.state === 'open') {
      try {
        this.arq.sendControl(TYPE.CLOSE, Buffer.from(reason || '', 'utf8'))
      } catch {
        // 已经发不出去也无妨，对端会自行判死
      }
    }
    this.state = 'closed'
    this.closed = true
    this.arq = null
    this.punchWaiter = null
    try {
      this.socket.close()
    } catch {
      // 已关
    }
  }

  stats() {
    return { rx: this.recvBytes, tx: this.sentBytes }
  }
}

function rinfoText(target) {
  return `${target.host}:${target.port}`
}

/* ==================== 本机候选地址 ==================== */

/** 本机的可连通 IPv4（排除回环与 APIPA），供页面层展示与局域网档兜底 */
function localCandidates() {
  const interfaces = os.networkInterfaces()
  const lan = []
  for (const name of Object.keys(interfaces)) {
    for (const item of (interfaces[name] || [])) {
      const address = item && item.address ? String(item.address) : ''
      if (item && !item.internal && item.family === 'IPv4' && address && address.indexOf('169.254.') !== 0) {
        lan.push(address)
      }
    }
  }
  return { lan, hostname: os.hostname() }
}

/* ==================== IPC 装配 ==================== */

let link = null

/**
 * 注册直连相关的 IPC 通道。
 *
 * @param {import('electron').IpcMain} ipcMain
 * @param {(channel:string, payload:object)=>void} send 向渲染进程投递事件的函数
 */
function setupDirectBridge(ipcMain, send) {
  ipcMain.handle('im:direct-open', async (event, params) => {
    try {
      if (!params || !params.host || !Number(params.udpPort)) {
        return { ok: false, error: '缺少直连地址或端口' }
      }
      if (!params.token) {
        return { ok: false, error: '缺少一次性直连票据' }
      }
      if (link) {
        // 一个桌面端同一时刻只服务一个远程会话，旧的先干净收掉
        link.closeLink('replaced', true)
        link = null
      }
      const created = new DirectLink(params, send)
      link = created
      const result = await created.open()
      if (!result || !result.ok) {
        created.closeLink('handshake-failed', false)
        if (link === created) {
          link = null
        }
        return { ok: false, error: (result && result.error) || '打洞未通' }
      }
      return { ok: true, data: { peer: result.peer, mtu: result.mtu, local: localCandidates() } }
    } catch (e) {
      if (link) {
        link.closeLink('error', false)
        link = null
      }
      return { ok: false, error: e && e.message ? e.message : String(e) }
    }
  })

  ipcMain.handle('im:direct-write', async (event, packet) => {
    if (!link || !packet) {
      return { ok: false, error: '直连未就绪' }
    }
    try {
      const before = link.sentBytes
      const sent = await link.write(packet.channel || 'reliable', Number(packet.kind) || 0, packet.data)
      if (!sent) {
        return { ok: false, error: '直连拥堵' }
      }
      return { ok: true, data: { bytes: link.sentBytes - before } }
    } catch (e) {
      return { ok: false, error: e && e.message ? e.message : String(e) }
    }
  })

  ipcMain.handle('im:direct-close', async () => {
    if (link) {
      link.closeLink('local-close', true)
      link = null
    }
    return { ok: true, data: true }
  })

  ipcMain.handle('im:direct-stats', async () => {
    return { ok: true, data: link ? link.stats() : { rx: 0, tx: 0 } }
  })

  ipcMain.handle('im:direct-local-candidates', async () => {
    return { ok: true, data: localCandidates() }
  })
}

/** 主进程退出前收尾：给对端一个 CLOSE，让被控端立刻释放链路而不是等 ARQ 判死 */
function closeDirectLink() {
  if (link) {
    link.closeLink('app-quit', true)
    link = null
  }
}

module.exports = { setupDirectBridge, localCandidates, closeDirectLink }
