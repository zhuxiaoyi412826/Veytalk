/**
 * 远控帧编解码：中继 WebSocket 与直连通道共用的唯一一份协议实现。
 *
 * 协议与后端 com.im.remote.protocol / 被控端 AgentClient 严格对应：
 *  - 文本帧：JSON 信封 {v:1, type, sid, seq, ts, data}
 *  - 二进制帧：[1B 帧类型][8B 会话ID][4B 元数据长度][元数据JSON][载荷]
 *  - 载荷加密：12B IV + AES-256-GCM 密文（Tag 拼在密文尾部，与 Java Cipher 输出一致）
 *
 * 抽出来的理由很直接：直连档位（局域网 WebSocket、UDP）要把同一份二进制帧塞进
 * 自己的报文里传，若在这两处各写一遍解析，改协议时必然只改到一边，
 * 表现为「中继正常、直连花屏」这种最难查的分裂。
 */

export const FRAME_SCREEN = 1
export const FRAME_FILE = 2

/** 帧头固定长度：1B 类型 + 8B 会话ID + 4B 元数据长度 */
export const FRAME_HEADER = 13

export function base64ToBytes(b64) {
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

export function bytesToBase64(bytes) {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
}

export function concatBytes(a, b) {
  const out = new Uint8Array(a.length + b.length)
  out.set(a)
  out.set(b, a.length)
  return out
}

/**
 * 导入会话 AES 密钥。
 *
 * 没有密钥（服务端 aes=false 或本端拿不到 aesKey）时返回 null，编解码退成明文——
 * 与 Agent 侧 cipher==null 的处理一致，两端必须同一口径，否则会出现
 * 「一端加密一端不解密」的静默丢帧。
 */
export async function importAesKey(aesKeyB64) {
  if (!aesKeyB64) {
    return null
  }
  if (!globalThis.crypto?.subtle) {
    throw new Error('当前环境不支持 WebCrypto（请用 localhost / HTTPS / Electron 打开）')
  }
  return await globalThis.crypto.subtle.importKey('raw', base64ToBytes(aesKeyB64), { name: 'AES-GCM' }, false, [
    'encrypt',
    'decrypt'
  ])
}

/** 明文载荷 → [12B IV][密文+Tag]；无密钥时原样返回 */
export async function encryptPayload(key, payload) {
  if (!key) {
    return payload
  }
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12))
  const encrypted = new Uint8Array(await globalThis.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, payload))
  return concatBytes(iv, encrypted)
}

/** [12B IV][密文+Tag] → 明文载荷；长度不足按明文返回（对应 dev 环境不加密） */
export async function decryptPayload(key, payload) {
  if (!key || payload.length <= 12) {
    return payload
  }
  return new Uint8Array(
    await globalThis.crypto.subtle.decrypt({ name: 'AES-GCM', iv: payload.slice(0, 12) }, key, payload.slice(12))
  )
}

/**
 * 组一个二进制帧。
 *
 * sid 用 BigUint64 写：雪花 ID 超过 2^53 时 JS Number 会掉精度，所以调用方一律传字符串，
 * 这里 BigInt(字符串) 才能保住那 19 位。
 */
export async function encodeFrame({ frameType, sessionId, meta, payload, key }) {
  const body = await encryptPayload(key, payload)
  const metaBytes = new TextEncoder().encode(JSON.stringify(meta || {}))
  const packet = new Uint8Array(FRAME_HEADER + metaBytes.length + body.length)
  const view = new DataView(packet.buffer)
  view.setUint8(0, frameType)
  view.setBigUint64(1, BigInt(String(sessionId)))
  view.setInt32(9, metaBytes.length)
  packet.set(metaBytes, FRAME_HEADER)
  packet.set(body, FRAME_HEADER + metaBytes.length)
  return packet
}

/** 解一个二进制帧并解密载荷；长度不合法返回 null，由调用方丢弃 */
export async function decodeFrame(buffer, key) {
  if (!buffer || buffer.byteLength < FRAME_HEADER) {
    return null
  }
  const view = new DataView(buffer)
  const frameType = view.getUint8(0)
  const metaLen = view.getInt32(9)
  if (metaLen < 0 || buffer.byteLength < FRAME_HEADER + metaLen) {
    return null
  }
  const meta = JSON.parse(new TextDecoder().decode(new Uint8Array(buffer, FRAME_HEADER, metaLen)))
  const payload = await decryptPayload(key, new Uint8Array(buffer, FRAME_HEADER + metaLen))
  return { frameType, meta, payload }
}

/** 文本帧解析：坏 JSON 在数据面上是常态（探测包、半包），不抛异常打断回调 */
export function parseEnvelope(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/** 组一个信封对象（调用方自行 stringify；单独抽出来是为了两端 seq/ts 口径一致） */
export function buildEnvelope(type, sessionId, seq, data) {
  const packet = { v: 1, type, sid: Number(sessionId), seq, ts: Date.now() }
  if (data && Object.keys(data).length) {
    packet.data = data
  }
  return packet
}

/** 链路字节数估算：文本按 UTF-8 实际字节，中文目录列表按字符数会低估一大截 */
export function textBytes(text) {
  return new TextEncoder().encode(text).length
}
