/**
 * H.264 解码（WebCodecs）：把被控端 ffmpeg 硬编出来的 Annex-B 流解成可画的帧。
 *
 * <h2>为什么不在浏览器里自己拼 Canvas</h2>
 *
 * 中继档原来是 JPEG：一帧一解码，无状态，丢一帧无所谓。H.264 是帧间预测编码，
 * 必须按序喂给同一个解码器实例，还得把参数集（SPS/PPS）转成 avcC 交给它，
 * 所以单开一个有状态的类，Remote.vue 只管「拿到帧就画」。
 *
 * <h2>三处最容易错的地方</h2>
 *
 *  1. Annex-B 起始码后的 NAL 含防竞争字节 0x03（00 00 03 转义），而 avcC 里存的
 *     是去掉转义的 RBSP——不去掉，Chrome 会报 "configuration changed" 或直接不出帧；
 *  2. 喂 VideoDecoder 的 chunk 用长度前缀（AVCC）而不是起始码：description 已给出
 *     参数集，chunk 里再带起始码会被当成非法 NAL；
 *  3. 一个访问单元必须整包一次 decode()：SPS+PPS+IDR 拆开喂会让关键帧被丢。
 *
 * <h2>失败一律回落 JPEG</h2>
 *
 * 老卡没有硬件 H.264 解码、Chrome 禁用了软解、或连续丢包导致解不动，都不该把画面整条搞死：
 * 连续 {@link MAX_FAILURES} 次失败就回调 onFail，由页面层发 screen-start{codec:'jpeg'}
 * 让被控端换档——与 Agent 侧「不具备条件一律回落 jpeg」同一思路。
 */

/** NAL 类型：7=SPS 8=PPS 5=IDR 9=AUD 6=SEI */
const NAL_SPS = 7
const NAL_PPS = 8
const NAL_IDR = 5

/** 连续解码失败多少次就换回 JPEG：3 次足够区分「偶发丢帧」和「这台机器解不动」 */
const MAX_FAILURES = 3
/** 解码队列积压上限：超了就丢非关键帧，宁可掉帧也不让延迟一路涨到几秒 */
const MAX_QUEUE = 6

/**
 * Annex-B → NAL 列表。
 *
 * 只按「00 00 01 / 00 00 00 01」找边界，不校验类型，畸形流交给上层按失败处理。
 */
export function splitNals(bytes) {
  const nals = []
  let index = 0
  let start = -1
  while (index + 2 < bytes.length) {
    if (bytes[index] === 0 && bytes[index + 1] === 0 && (bytes[index + 2] === 1 || bytes[index + 3] === 1)) {
      if (start >= 0) {
        nals.push(bytes.subarray(start, index))
      }
      index += bytes[index + 2] === 1 ? 3 : 4
      start = index
      continue
    }
    index++
  }
  if (start >= 0 && start < bytes.length) {
    nals.push(bytes.subarray(start, bytes.length))
  }
  return nals.filter((nal) => nal.length > 1)
}

/** 去掉防竞争仿真字节（00 00 03 → 00 00），avcC 与长度前缀流都要干净数据 */
function stripEmulation(bytes) {
  const out = new Uint8Array(bytes.length)
  let size = 0
  let zeros = 0
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i]
    if (zeros >= 2 && b === 3) {
      // 吃掉这个 0x03，后面的字节重新按原始值参与零计数
      zeros = 0
      continue
    }
    out[size++] = b
    zeros = b === 0 ? zeros + 1 : 0
  }
  return out.subarray(0, size)
}

function nalType(nal) {
  return nal[0] & 0x1f
}

/**
 * 由 SPS/PPS 组 AVCDecoderConfigurationRecord（即 avcC box 的内容，不含 box 头）。
 *
 * profile/compat/level 取自 SPS 头三个字节，用于拼 codec 串 avc1.PPCCLL：
 * VideoDecoder 靠这个串挑解码器实例，description 靠这份记录初始化参数集。
 */
export function buildDescription(sps, pps) {
  const cleanSps = stripEmulation(sps)
  const cleanPps = stripEmulation(pps)
  const record = new Uint8Array(11 + cleanSps.length + cleanPps.length)
  let i = 0
  record[i++] = 1 // configurationVersion
  record[i++] = cleanSps[1] // profile_idc
  record[i++] = cleanSps[2] // profile_compatibility
  record[i++] = cleanSps[3] // level_idc
  record[i++] = 0xff // 6 bits reserved + lengthSizeMinusOne=3（4 字节长度前缀）
  record[i++] = 0xe1 // 3 bits reserved + numOfSPS=1
  record[i++] = (cleanSps.length >> 8) & 0xff
  record[i++] = cleanSps.length & 0xff
  record.set(cleanSps, i)
  i += cleanSps.length
  record[i++] = 1 // numOfPPS
  record[i++] = (cleanPps.length >> 8) & 0xff
  record[i++] = cleanPps.length & 0xff
  record.set(cleanPps, i)
  return {
    record,
    codec: `avc1.${hex(cleanSps[1])}${hex(cleanSps[2])}${hex(cleanSps[3])}`
  }
}

function hex(value) {
  return (value & 0xff).toString(16).padStart(2, '0')
}

/** 本环境能不能用 WebCodecs 解码：与 WebCrypto 同样的 secure context 限制 */
export function avcSupported() {
  return typeof globalThis.VideoDecoder === 'function'
}

export class AvcDecoder {
  /**
   * @param {object} options
   * @param {(frame:VideoFrame)=>void} options.onFrame 解出的帧，调用方负责 close()
   * @param {(reason:string)=>void} [options.onFail] 判定为解不动，页面该换回 JPEG
   */
  constructor(options = {}) {
    this.onFrame = options.onFrame
    this.onFail = options.onFail
    this.decoder = null
    this.lastSps = null
    this.lastPps = null
    this.codec = ''
    this.failures = 0
    this.failed = false
    this.sequence = 0
    this.supported = avcSupported()
    if (!this.supported) {
      this.failed = true
      // 不支持也要让页面知道原因，否则表现是「开了 H.264 却没画面」
      queueMicrotask(() => options.onFail && options.onFail('当前浏览器不支持 WebCodecs'))
    }
  }

  /**
   * 喂一个访问单元（一段完整 Annex-B）。
   * @param {object} meta 帧元数据，取 key（是否关键帧）
   * @returns {boolean} 是否已提交解码
   */
  push(meta, annexB) {
    if (this.failed || !this.supported || !annexB || !annexB.length) {
      return false
    }
    const nals = splitNals(annexB)
    if (!nals.length) {
      return false
    }
    let sps = null
    let pps = null
    let idr = false
    for (const nal of nals) {
      const type = nalType(nal)
      if (type === NAL_SPS) {
        sps = nal
      } else if (type === NAL_PPS) {
        pps = nal
      } else if (type === NAL_IDR) {
        idr = true
      }
    }
    // 参数集变了必须重配：分辨率变了（切显示器/窗口化抓屏）沿用旧配置会持续错位花屏
    if (sps && pps && (!this.lastSps || !sameNal(sps, this.lastSps) || !sameNal(pps, this.lastPps))) {
      this.lastSps = sps
      this.lastPps = pps
      this.configure(buildDescription(sps, pps))
    }
    if (!this.decoder) {
      // 参数集还没来（中途加入流）：等下一个关键帧，别用无配置解码器硬喂
      return false
    }
    if (this.decoder.decodeQueueSize > MAX_QUEUE && !idr && !meta?.key) {
      // 积压时丢非关键帧：解码器状态不依赖被丢的帧以外的一切，等 IDR 恢复
      return false
    }
    const data = toLengthPrefixed(nals)
    const key = idr || !!meta?.key
    try {
      this.decoder.decode({
        type: key ? 'key' : 'delta',
        timestamp: this.sequence++,
        data
      })
      this.failures = 0
      return true
    } catch (e) {
      this.noteFailure(e && e.message ? e.message : String(e))
      return false
    }
  }

  configure(built) {
    try {
      if (this.decoder && this.decoder.state !== 'closed') {
        this.decoder.close()
      }
    } catch {
      // 已在错误状态，重建即可
    }
    this.codec = built.codec
    this.decoder = new globalThis.VideoDecoder({
      output: (frame) => this.emitFrame(frame),
      error: (error) => this.noteFailure(error && error.message ? error.message : String(error))
    })
    this.decoder.configure({
      codec: built.codec,
      description: built.record
    })
  }

  emitFrame(frame) {
    this.failures = 0
    this.onFrame && this.onFrame(frame)
  }

  noteFailure(reason) {
    this.failures++
    if (this.failures < MAX_FAILURES) {
      // 单次失败大概率是丢了参考帧，重置解码器等下一个关键帧就能续上
      try {
        this.decoder && this.decoder.reset()
      } catch {
        // reset 失败说明实例已废，靠下一次重配救回来
      }
      return
    }
    this.failed = true
    this.onFail && this.onFail(reason)
  }

  /** 换源（重新推流/换显示器）时清状态：解码器不复位会拿旧参数集解新流 */
  reset() {
    this.codec = ''
    this.lastSps = null
    this.lastPps = null
    this.failures = 0
    this.sequence = 0
    try {
      if (this.decoder && this.decoder.state !== 'closed') {
        this.decoder.flush().catch(() => {})
        this.decoder.reset()
      }
    } catch {
      // 状态不对就直接重建，下次 configure 会新建实例
      this.decoder = null
    }
  }

  close() {
    try {
      if (this.decoder && this.decoder.state !== 'closed') {
        this.decoder.close()
      }
    } catch {
      // 关闭失败无副作用
    }
    this.decoder = null
    this.lastSps = null
    this.lastPps = null
  }
}

/** 参数集是否同一个：SPS/PPS 只有几十字节，逐字节比最可靠也最不贵 */
function sameNal(a, b) {
  if (!a || !b || a.length !== b.length) {
    return false
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      return false
    }
  }
  return true
}

/** NAL 列表 → 4 字节长度前缀（AVCC）格式 */
function toLengthPrefixed(nals) {
  let total = 0
  for (const nal of nals) {
    total += 4 + nal.length
  }
  const out = new Uint8Array(total)
  const view = new DataView(out.buffer)
  let offset = 0
  for (const nal of nals) {
    view.setUint32(offset, nal.length)
    out.set(nal, offset + 4)
    offset += 4 + nal.length
  }
  return out
}
