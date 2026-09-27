/**
 * 直连协议 DXP（Direct eXchange Protocol）报文编解码。
 *
 * 与被控端 com.im.remote.agent.Dxp 逐字段对齐，任何一处不一致都会表现为
 * 「握手失败」或「花屏」而不是明确报错，所以格式必须在两边写成同一段文字：
 *
 *   固定头 12B（大端）
 *    0  u8   ver = 1
 *    1  u8   type    1=HELLO 2=HELLO_ACK 3=DATA 4=ACK 6=PING 7=PONG 8=CLOSE
 *    2  u8   channel 0=控制(握手/心跳，不进 ARQ) 1=画面(不重传) 2=可靠(指令+信封+文件)
 *    3  u8   flags   bit0=该消息的最后一个分片
 *    4  u32  msgId   消息序号（分片归属）
 *    8  u32  seq     包序号（仅可靠通道有意义，其余恒为 0）
 *   12  ...  body
 *
 *   DATA 的 body 再带 6B：[u16 fragIdx][u16 fragCnt][u8 kind][u8 保留] + 数据
 *   kind 0=文本信封（JSON UTF-8）1=二进制帧（remoteCodec 的那份 13B 头 + meta + 载荷）
 *
 * ACK 的 body 只有一个 u32：对端已确认到的最大连续 seq（Go-Back-N 的累积确认）。
 */

export const DXP_VER = 1
export const DXP_HEADER = 12
export const DATA_HEADER = 6
/** 单包总长上限的兜底值，协商值大于它时按它执行（对应 Agent 侧 Dxp.MAX_MTU） */
export const MAX_MTU = 1048576

export const TYPE = { HELLO: 1, HELLO_ACK: 2, DATA: 3, ACK: 4, PING: 6, PONG: 7, CLOSE: 8 }
export const CH = { CONTROL: 0, SCREEN: 1, RELIABLE: 2 }
export const KIND = { TEXT: 0, BINARY: 1 }
export const FLAG_LAST = 1

const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** 组一个报文：body 传 Uint8Array 或字符串 */
export function encode(type, channel, flags, msgId, seq, body) {
  const payload = typeof body === 'string' ? encoder.encode(body) : body || new Uint8Array(0)
  const packet = new Uint8Array(DXP_HEADER + payload.length)
  const view = new DataView(packet.buffer)
  view.setUint8(0, DXP_VER)
  view.setUint8(1, type)
  view.setUint8(2, channel)
  view.setUint8(3, flags)
  view.setUint32(4, msgId >>> 0)
  view.setUint32(8, seq >>> 0)
  packet.set(payload, DXP_HEADER)
  return packet
}

/** 解报文；版本不符或长度不足返回 null（UDP 上畸形包是常态，丢弃即可，别抛异常） */
export function decode(bytes) {
  const raw = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  if (raw.length < DXP_HEADER || raw[0] !== DXP_VER) {
    return null
  }
  const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength)
  return {
    type: view.getUint8(1),
    channel: view.getUint8(2),
    flags: view.getUint8(3),
    msgId: view.getUint32(4),
    seq: view.getUint32(8),
    body: raw.subarray(DXP_HEADER)
  }
}

/** 单分片可携带的载荷字节数（与 Agent 侧 Dxp.payloadCapacity 同式） */
export function payloadCapacity(mtu) {
  return Math.max(64, Math.min(mtu, MAX_MTU) - DXP_HEADER - DATA_HEADER)
}

export function fragmentCount(mtu, length) {
  const capacity = payloadCapacity(mtu)
  return Math.max(1, Math.ceil(length / capacity))
}

/**
 * 把一条完整消息切成若干 DATA 报文。
 *
 * TCP/WebSocket 档协商 mtu=1MB，结果恒为一个分片；UDP 档才真按 1200 切。
 * seq 一律写 0，进 ARQ 窗口时再补写——与 Agent 侧同一手法，避免为了编号把整条消息重切一遍。
 */
export function splitData(channel, kind, message, mtu, msgId) {
  const bytes = typeof message === 'string' ? encoder.encode(message) : message
  const capacity = payloadCapacity(mtu)
  const count = fragmentCount(mtu, bytes.length)
  const packets = []
  for (let index = 0; index < count; index++) {
    const offset = index * capacity
    const slice = bytes.subarray(offset, Math.min(offset + capacity, bytes.length))
    const body = new Uint8Array(DATA_HEADER + slice.length)
    const view = new DataView(body.buffer)
    view.setUint16(0, index)
    view.setUint16(2, count)
    body[4] = kind
    body[5] = 0
    body.set(slice, DATA_HEADER)
    packets.push(encode(TYPE.DATA, channel, index === count - 1 ? FLAG_LAST : 0, msgId, 0, body))
  }
  return packets
}

/** 从一个 DATA 报文里取分片信息 */
export function readFragment(body) {
  const bytes = body instanceof Uint8Array ? body : new Uint8Array(body)
  if (bytes.length < DATA_HEADER) {
    return null
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  return {
    index: view.getUint16(0),
    count: view.getUint16(2),
    kind: bytes[4],
    data: bytes.subarray(DATA_HEADER)
  }
}

export function makeAck(seq) {
  return encode(TYPE.ACK, CH.CONTROL, 0, 0, 0, (() => {
    const body = new Uint8Array(4)
    new DataView(body.buffer).setUint32(0, seq >>> 0)
    return body
  })())
}

export function readAckSeq(body) {
  if (!body || body.length < 4) {
    return -1
  }
  return new DataView(body.buffer, body.byteOffset, body.byteLength).getUint32(0)
}

export function encodeText(text) {
  return encoder.encode(text)
}

export function decodeText(bytes) {
  return bytes && bytes.length ? decoder.decode(bytes) : ''
}

/**
 * 分片重组槽位（对应 Agent 侧 DirectArq.Slot）。
 *
 * 要求 index 严格连续，一旦跳号整条作废：可靠通道由 ARQ 保证有序，
 * 画面通道跳号说明中间丢了，拼一半的画面没有价值，等下一个关键帧。
 */
export class Reassembler {
  constructor() {
    this.reset()
  }

  reset() {
    this.msgId = -1
    this.kind = 0
    this.expected = 0
    this.count = 0
    this.poisoned = false
    this.parts = []
    this.size = 0
    this.updatedAt = 0
  }

  /**
   * 喂一个分片。
   * @returns {{kind:number, message:Uint8Array}|null} 拼齐时返回完整消息
   */
  accept(msgId, fragment) {
    const now = Date.now()
    if (this.msgId !== msgId) {
      this.reset()
      this.msgId = msgId
      if (fragment.index !== 0) {
        this.poisoned = true
      }
    }
    if (this.poisoned) {
      return null
    }
    this.updatedAt = now
    if (!this.count) {
      this.count = fragment.count
      this.kind = fragment.kind
    }
    if (fragment.index !== this.expected || this.count !== fragment.count) {
      this.poisoned = true
      return null
    }
    this.parts.push(fragment.data)
    this.size += fragment.data.length
    this.expected++
    if (this.expected < this.count) {
      return null
    }
    const message = new Uint8Array(this.size)
    let offset = 0
    for (const part of this.parts) {
      message.set(part, offset)
      offset += part.length
    }
    const done = { kind: this.kind, message }
    this.reset()
    return done
  }

  /** 画面槽位超时清理：一帧最多几百片，正常几十毫秒收完，卡住就是残帧 */
  expireIfStale(staleMs = 400) {
    if (this.msgId >= 0 && Date.now() - this.updatedAt > staleMs) {
      this.reset()
    }
  }
}

/**
 * 握手密钥证明：Base64(HMAC-SHA256(aesKey, prefix|sid|nonce|token))。
 *
 * 没有 32B 密钥（dev 环境 im.remote.aes=false）时退化为 SHA-256 同一串——
 * 两种算法都掺了 token，所以退化版仍要求攻击者知道一次性票据。
 * sid 一律以字符串参与拼接：雪花 ID 走 JS Number 会掉末几位精度，
 * 与 Agent 侧 String.valueOf(long) 对不上，表现就是永远的 bad-mac。
 */
export async function proofB64(keyBytes, token, sid, nonce, prefix) {
  const material = encoder.encode(`${prefix}|${sid}|${nonce}|${token}`)
  let digest
  if (keyBytes && keyBytes.length === 32 && globalThis.crypto?.subtle) {
    const key = await globalThis.crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, [
      'sign'
    ])
    digest = await globalThis.crypto.subtle.sign('HMAC', key, material)
  } else {
    digest = await globalThis.crypto.subtle.digest('SHA-256', material)
  }
  const bytes = new Uint8Array(digest)
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
}

export function randomHex(bytes = 12) {
  const buffer = new Uint8Array(bytes)
  crypto.getRandomValues(buffer)
  return Array.from(buffer, (b) => b.toString(16).padStart(2, '0')).join('')
}
