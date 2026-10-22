/**
 * 能量门限 VAD（语音活动检测）：把连续帧流切成「一段完整说话」。
 *
 * 为什么不用模型：浏览器里跑得动的静音检测方案里，能量 RMS 是唯一
 * 零依赖、零冷启动、国产内核也不挑的；电话面试的断句容忍 0.5s 级的
 * 误差，不需要音素级精度。嘈杂环境靠「底噪自适应」顶大部分场景，
 * 真的不行是模式选择问题（按住说话永远可用），不是加模型能解决的。
 *
 * 判据（都在构造参数里，可按实测调）：
 *   阈值 = max(底噪 EMA × thresholdFactor, minThreshold)
 *   起音：连续 startFrames 帧过阈值；
 *   断句：段内静音连续超 silenceMs 且段长 ≥ minVoiceMs；
 *   段长超 maxSegmentMs 强制切（防止有人长时间背景噪音不过阈值也不停）。
 */

const FRAME_MS = 30

export function createEnergyVad({
  startFrames = 3,
  silenceMs = 850,
  minVoiceMs = 300,
  maxSegmentMs = 60000,
  thresholdFactor = 3.2,
  minThreshold = 0.004,
  onSegment = null,
  onStateChange = null
} = {}) {
  let gridBuf = [] // 拼帧到 30ms 网格的暂存
  let gridSampleRate = 0
  let speaking = false
  let voiceFrames = 0 // 当前段累计帧数
  let overCount = 0 // 连续过阈值帧数（起音判定）
  let silentMs = 0 // 段内累计静音时长（断句判定）
  let segmentFrames = [] // 当前段的全部帧
  let floor = 0 // 底噪 RMS 的 EMA
  let emaInit = 0 // 底噪样本数（前 20 帧快速收敛）

  function frameSize() {
    return Math.max(1, Math.round((gridSampleRate * FRAME_MS) / 1000))
  }

  function resetSegment() {
    speaking = false
    voiceFrames = 0
    overCount = 0
    silentMs = 0
    segmentFrames = []
  }

  function emitSegment() {
    const len = segmentFrames.reduce((acc, f) => acc + f.length, 0)
    const merged = new Float32Array(len)
    let off = 0
    for (const f of segmentFrames) {
      merged.set(f, off)
      off += f.length
    }
    const durationMs = Math.round((len / gridSampleRate) * 1000)
    resetSegment()
    if (onSegment) {
      onSegment({ samples: merged, sampleRate: gridSampleRate, durationMs })
    }
  }

  function setSpeaking(next) {
    if (speaking !== next) {
      speaking = next
      if (onStateChange) {
        onStateChange(next)
      }
    }
  }

  return {
    /**
     * 喂一帧任意长度的单声道 Float32（麦克风回调直接进来）。
     * @param {Float32Array} frame
     * @param {number} sampleRate
     */
    feed(frame, sampleRate) {
      if (gridSampleRate && gridSampleRate !== sampleRate) {
        // 采样率中途变了（设备切换等）：旧网格作废，段也放弃
        gridBuf = []
        resetSegment()
      }
      gridSampleRate = sampleRate
      gridBuf.push(frame)
      let size = frameSize()
      while (gridBuf.reduce((acc, f) => acc + f.length, 0) >= size) {
        const chunk = new Float32Array(size)
        let off = 0
        while (off < size) {
          const head = gridBuf[0]
          const take = Math.min(head.length, size - off)
          chunk.set(head.subarray(0, take), off)
          off += take
          if (take === head.length) {
            gridBuf.shift()
          } else {
            gridBuf[0] = head.subarray(take)
          }
        }
        processChunk(chunk)
        size = frameSize()
      }
    },
    /** 丢弃当前未完成段（挂断/静音/模式切换时用） */
    abort() {
      gridBuf = []
      resetSegment()
    },
    get speaking() {
      return speaking
    }
  }

  function processChunk(chunk) {
    let sum = 0
    for (let i = 0; i < chunk.length; i++) {
      sum += chunk[i] * chunk[i]
    }
    const rms = Math.sqrt(sum / chunk.length)

    // 底噪 EMA：只在「未起音」的帧里学习，说话期间的能量不污染地板
    if (!speaking) {
      emaInit = Math.min(emaInit + 1, 20)
      const alpha = emaInit < 20 ? 0.2 : 0.02
      floor = floor + alpha * (rms - floor)
    }
    const threshold = Math.max(floor * thresholdFactor, minThreshold)

    if (rms >= threshold) {
      overCount += 1
      silentMs = 0
      if (!speaking && overCount >= startFrames) {
        setSpeaking(true)
        voiceFrames = 0
        segmentFrames = []
      }
    } else {
      overCount = 0
      if (speaking) {
        silentMs += FRAME_MS
      }
    }

    if (speaking) {
      voiceFrames += 1
      segmentFrames.push(chunk)
      const voicedMs = voiceFrames * FRAME_MS
      if (silentMs >= silenceMs) {
        if (voicedMs - silentMs >= minVoiceMs) {
          emitSegment() // 尾静音达标且有效语音够长：交付整段
        } else {
          resetSegment() // 咳嗽一声这种太短的，整段丢弃
          setSpeaking(false)
        }
      } else if (voicedMs >= maxSegmentMs) {
        emitSegment() // 超长强制切，防止无限累积内存
        setSpeaking(true) // 仍在说话，新段继续
      }
    }
  }
}
