/**
 * 面试语音输入：麦克风采集 + 16k 单声道 WAV 编码。
 *
 * 为什么不用 MediaRecorder：它输出的容器/编码随内核（webm/opus/mp4…），
 * 国产浏览器行为不齐，而后端 ASR 需要我们声明格式；自己采 PCM、
 * 自己写 44 字节 WAV 头，模型侧 `format:"wav"` 才是永远成立的。
 *
 * 三层结构，各层都能单独测：
 *   createMicSource   麦克风 → 帧流（Float32Array 单声道 + 采样率）
 *   encodeWav16k      帧流列表 → 16k/mono/16bit WAV Blob
 *   createSpeechRecorder  按住说话/一段录音的启停封装（前两层拼起来）
 */

const TARGET_RATE = 16000

/** AudioWorklet 透传模块：只把首声道输入帧原样发回，处理都在主线程做 */
const WORKLET_SRC = `class PcmPassthrough extends AudioWorkletProcessor {
  process(inputs) {
    const ch = inputs[0] && inputs[0][0]
    if (ch) { this.port.postMessage(ch.slice(0)) }
    return true
  }
}
registerProcessor('pcm-passthrough', PcmPassthrough)`

/**
 * 打开麦克风并输出单声道 Float32 帧流。
 *
 * @param {(frame:Float32Array, sampleRate:number)=>void} options.onFrame 每帧回调
 * @returns {Promise<{stop:()=>void, sampleRate:number}>}
 */
export async function createMicSource({ onFrame }) {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true
    }
  })
  // 优先让浏览器把采集直接落到 16k（省一次重采样）；不支持的目标采样率会抛，退回默认
  let ctx
  try {
    ctx = new AudioContext({ sampleRate: TARGET_RATE })
  } catch {
    ctx = new AudioContext()
  }
  // 自动播放策略：不论调用方是不是手势内进来的，resume 一次没有副作用
  if (ctx.state === 'suspended') {
    await ctx.resume().catch(() => {})
  }
  const sourceNode = ctx.createMediaStreamSource(stream)

  const attach = await attachProcessor(ctx, sourceNode, onFrame)

  return {
    sampleRate: ctx.sampleRate,
    stop() {
      attach.detach()
      stream.getTracks().forEach((t) => t.stop())
      ctx.close().catch(() => {})
    }
  }
}

/** worklet 优先，注册/构建失败退 ScriptProcessor（老内核最后的路） */
async function attachProcessor(ctx, sourceNode, onFrame) {
  if (ctx.audioWorklet) {
    try {
      const url = URL.createObjectURL(new Blob([WORKLET_SRC], { type: 'application/javascript' }))
      await ctx.audioWorklet.addModule(url)
      URL.revokeObjectURL(url)
      const node = new AudioWorkletNode(ctx, 'pcm-passthrough')
      node.port.onmessage = (e) => onFrame(e.data, ctx.sampleRate)
      sourceNode.connect(node)
      node.connect(ctx.destination)
      return {
        detach() {
          node.port.onmessage = null
          try { sourceNode.disconnect(node) } catch { /* 已断开 */ }
          node.disconnect()
        }
      }
    } catch {
      // 落到下面的 ScriptProcessor
    }
  }
  const proc = ctx.createScriptProcessor(4096, 1, 1)
  proc.onaudioprocess = (e) => onFrame(new Float32Array(e.inputBuffer.getChannelData(0)), ctx.sampleRate)
  sourceNode.connect(proc)
  // ScriptProcessor 不挂输出节点不会运转，接回 destination 但不播内容
  proc.connect(ctx.destination)
  return {
    detach() {
      proc.onaudioprocess = null
      try { sourceNode.disconnect(proc) } catch { /* 已断开 */ }
      proc.disconnect()
    }
  }
}

/**
 * Float32 帧列表 → 16k 单声道 16bit WAV Blob。
 *
 * 源采样率不等于 16k 时线性插值降采样——语音转写对插值误差完全不敏感，
 * 不值得引多相滤波。
 */
export function encodeWav16k(frames, srcSampleRate) {
  const total = frames.reduce((acc, f) => acc + f.length, 0)
  const merged = new Float32Array(total)
  let off = 0
  for (const f of frames) {
    merged.set(f, off)
    off += f.length
  }
  const pcm = srcSampleRate === TARGET_RATE ? merged : resampleLinear(merged, srcSampleRate, TARGET_RATE)

  const dataBytes = pcm.length * 2
  const buf = new ArrayBuffer(44 + dataBytes)
  const view = new DataView(buf)
  writeAscii(view, 0, 'RIFF')
  view.setUint32(4, 36 + dataBytes, true)
  writeAscii(view, 8, 'WAVE')
  writeAscii(view, 12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true) // PCM
  view.setUint16(22, 1, true) // mono
  view.setUint32(24, TARGET_RATE, true)
  view.setUint32(28, TARGET_RATE * 2, true) // byteRate
  view.setUint16(32, 2, true) // blockAlign
  view.setUint16(34, 16, true) // bits
  writeAscii(view, 36, 'data')
  view.setUint32(40, dataBytes, true)
  let pos = 44
  for (let i = 0; i < pcm.length; i++) {
    const s = Math.max(-1, Math.min(1, pcm[i]))
    view.setInt16(pos, s < 0 ? s * 0x8000 : s * 0x7FFF, true)
    pos += 2
  }
  return new Blob([buf], { type: 'audio/wav' })
}

function resampleLinear(input, fromRate, toRate) {
  const ratio = fromRate / toRate
  const outLen = Math.floor(input.length / ratio)
  const out = new Float32Array(outLen)
  for (let i = 0; i < outLen; i++) {
    const src = i * ratio
    const i0 = Math.floor(src)
    const i1 = Math.min(i0 + 1, input.length - 1)
    const frac = src - i0
    out[i] = input[i0] * (1 - frac) + input[i1] * frac
  }
  return out
}

function writeAscii(view, offset, str) {
  for (let i = 0; i < str.length; i++) {
    view.setUint8(offset + i, str.charCodeAt(i))
  }
}

/**
 * 「按住说话」级录音器：start 开麦、stop 出 WAV、cancel 丢弃。
 *
 * 帧同时喂给可选的 onFrame 钩子——电话模式的 VAD 就靠它旁路取帧，
 * 同一路麦克风两用，不再开第二个 getUserMedia。
 */
export function createSpeechRecorder({ onFrame } = {}) {
  let chunks = []
  let srcRate = 0
  let source = null
  let startedAt = 0
  let startSeq = 0 // 防竞态：start 还在 await 麦克风时被 stop/cancel，开好的轨道要当场关掉

  function teardown() {
    startSeq += 1
    if (source) {
      source.stop()
      source = null
    }
    chunks = []
  }

  return {
    get running() {
      return !!source
    },
    async start() {
      if (source) {
        return
      }
      chunks = []
      const seq = ++startSeq
      const mic = await createMicSource({
        onFrame: (frame, rate) => {
          srcRate = rate
          chunks.push(frame)
          if (onFrame) {
            onFrame(frame, rate)
          }
        }
      })
      if (seq !== startSeq) {
        // 等权限/开轨期间用户已经松手或取消
        mic.stop()
        return
      }
      source = mic
      srcRate = source.sampleRate
      startedAt = Date.now()
    },
    /** @returns {Promise<{blob:Blob, durationMs:number}|null>} */
    async stop() {
      startSeq += 1
      const hadSource = !!source
      if (source) {
        source.stop()
        source = null
      }
      const frames = chunks
      chunks = []
      if (!hadSource || !frames.length) {
        return null
      }
      const durationMs = Date.now() - startedAt
      return { blob: encodeWav16k(frames, srcRate), durationMs }
    },
    cancel() {
      teardown()
    }
  }
}
