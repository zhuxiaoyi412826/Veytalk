/**
 * 语音朗读（TTS）双引擎抽象：系统音色 / 神经音色。
 *
 * 两档引擎对上层暴露完全相同的形状（speak/stop/onEnd），电话面板与
 * 文字页只认抽象，不感知底下是 speechSynthesis 还是 /tts 回来的 WAV：
 *   system：window.speechSynthesis（免流量、零延迟，但安卓国产壳
 *           经常没有中文音色 —— canSystemSpeak() 检测，调用方决定回退）
 *   remote：api/ai.js#synthesizeSpeech 拿 wav Blob → <audio> 播放
 *
 * Chrome 的长文本 utterance 有吞字/卡住的已知 bug，系统引擎按 ~200 字
 * 分句排队播报；「朗读完成」以最后一句的 onend 为准。
 */
import { synthesizeSpeech } from '@/api/ai'

export const TTS_ENGINE_KEY = 'im-interview-tts-engine'

/** 引擎偏好持久化：默认系统音色（免费），用户切过则记住 */
export function loadEngine() {
  const saved = localStorage.getItem(TTS_ENGINE_KEY)
  return saved === 'remote' ? 'remote' : 'system'
}

export function saveEngine(engine) {
  localStorage.setItem(TTS_ENGINE_KEY, engine === 'remote' ? 'remote' : 'system')
}

/**
 * 系统引擎是否真的可用：接口存在 ≠ 有点中文音色。
 * getVoices() 可能异步晚到（首次为空），所以这里宽松判定：
 * 有 voice 就查 zh，voice 列表暂时为空只查接口存在（调用方播的时候
 * 若 0 音色会走 onError，由上层提示并建议切神经音色）。
 */
export function canSystemSpeak() {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
    return false
  }
  const voices = window.speechSynthesis.getVoices()
  if (!voices.length) {
    return true
  }
  return voices.some((v) => (v.lang || '').toLowerCase().startsWith('zh'))
}

/** 找一条中文音色，找不到返回 null（用浏览器默认） */
function pickZhVoice() {
  const voices = window.speechSynthesis.getVoices()
  return voices.find((v) => (v.lang || '').toLowerCase().startsWith('zh')) || null
}

/** 长文按标点切句，单句仍超 chunkChars 的硬切，防 Chrome 长 utterance bug */
function splitSentences(text, chunkChars = 200) {
  const parts = text.split(/(?<=[。！？!?；;，,、\n])/)
  const out = []
  for (let part of parts) {
    if (!part.trim()) {
      continue
    }
    while (part.length > chunkChars) {
      out.push(part.slice(0, chunkChars))
      part = part.slice(chunkChars)
    }
    out.push(part)
  }
  return out.length ? out : [text]
}

/**
 * 创建朗读控制器。
 *
 * @param {object} options
 * @param {'system'|'remote'} options.engine 初始引擎（可 setEngine 切换）
 * @param {()=>void} [options.onEnd] 一次 speak 完整播完（或被 stop 掐断后不触发）
 * @param {(err:Error)=>void} [options.onError]
 * @returns {{speak:(text:string)=>void, stop:()=>void, setEngine:(e:string)=>void, getEngine:()=>string, speaking:boolean}}
 */
export function createSpeech({ engine = 'system', onEnd = null, onError = null } = {}) {
  let currentEngine = engine === 'remote' ? 'remote' : 'system'
  let speakingFlag = false
  let sessionId = 0 // 每次 speak/stop 自增，旧回调凭 id 作废（防迟到 onend）

  // ---- system 引擎状态 ----
  let sysQueueActive = false

  // ---- remote 引擎状态 ----
  let remoteAbort = null
  let remoteAudio = null
  let remoteUrl = null

  function finish(id) {
    if (id !== sessionId) {
      return // 已被新的 speak/stop 取代
    }
    speakingFlag = false
    if (onEnd) {
      onEnd()
    }
  }

  function fail(err) {
    speakingFlag = false
    if (onError) {
      onError(err)
    }
  }

  function stopSystem() {
    sysQueueActive = false
    try {
      window.speechSynthesis.cancel()
    } catch { /* 部分壳 cancel 会抛 */ }
  }

  function stopRemote() {
    if (remoteAbort) {
      remoteAbort.abort()
      remoteAbort = null
    }
    if (remoteAudio) {
      remoteAudio.pause()
      remoteAudio.src = ''
      remoteAudio = null
    }
    if (remoteUrl) {
      URL.revokeObjectURL(remoteUrl)
      remoteUrl = null
    }
  }

  function speakSystem(text, id) {
    stopSystem()
    sysQueueActive = true
    const sentences = splitSentences(text)
    const voice = pickZhVoice()
    sentences.forEach((sentence, index) => {
      const u = new SpeechSynthesisUtterance(sentence)
      u.lang = 'zh-CN'
      if (voice) {
        u.voice = voice
      }
      u.onend = () => {
        if (index === sentences.length - 1) {
          sysQueueActive = false
          finish(id)
        }
      }
      u.onerror = (e) => {
        // 用户主动 cancel 也会进 onerror（error='canceled'/'interrupted'），不算失败
        if (['canceled', 'interrupted', 'cancelled'].includes(e.error)) {
          return
        }
        sysQueueActive = false
        fail(new Error(e.error === 'no-voice'
          ? '系统没有可用的中文音色，请切换神经音色'
          : `系统朗读失败（${e.error}）`))
      }
      window.speechSynthesis.speak(u)
    })
  }

  async function speakRemote(text, id) {
    try {
      remoteAbort = new AbortController()
      const blob = await synthesizeSpeech(text)
      if (id !== sessionId) {
        return // 等合成期间被 stop/新 speak 取代
      }
      remoteUrl = URL.createObjectURL(blob)
      remoteAudio = new Audio(remoteUrl)
      remoteAudio.onended = () => {
        stopRemote()
        finish(id)
      }
      remoteAudio.onerror = () => {
        stopRemote()
        fail(new Error('音频播放失败，请重试或切换系统音色'))
      }
      await remoteAudio.play()
    } catch (err) {
      if (err && err.name === 'AbortError') {
        return // 主动 stop
      }
      stopRemote()
      fail(err)
    }
  }

  return {
    /** 朗读一段文本；再次调用 = 打断当前、播新的 */
    speak(text) {
      const content = (text || '').trim()
      if (!content) {
        return
      }
      const id = ++sessionId
      stopSystem()
      stopRemote()
      speakingFlag = true
      if (currentEngine === 'remote') {
        speakRemote(content, id)
      } else {
        speakSystem(content, id)
      }
    },
    /** 立即停止播报（不算「播完」，不触发 onEnd） */
    stop() {
      sessionId += 1
      speakingFlag = false
      stopSystem()
      stopRemote()
    },
    setEngine(next) {
      const normalized = next === 'remote' ? 'remote' : 'system'
      if (normalized !== currentEngine) {
        this.stop()
        currentEngine = normalized
      }
    },
    getEngine() {
      return currentEngine
    },
    get speaking() {
      // speechSynthesis 的 paused/pending 也视为在说（分句排队间隙）
      if (currentEngine === 'system' && speakingFlag) {
        return sysQueueActive || window.speechSynthesis.speaking
      }
      return speakingFlag
    }
  }
}
