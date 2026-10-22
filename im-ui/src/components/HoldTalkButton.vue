<template>
  <button
    class="hold-talk"
    :class="{
      'hold-talk--recording': state === 'recording',
      'hold-talk--cancel': state === 'cancel',
      'hold-talk--busy': busy,
      'hold-talk--off': disabled && !busy && state === 'idle'
    }"
    :disabled="busy"
    @pointerdown.prevent="startPress"
    @pointermove="onMove"
    @pointerup="releasePress"
    @pointercancel="releasePress"
    @contextmenu.prevent
  >
    <slot name="icon">
      <el-icon :size="16"><Microphone /></el-icon>
    </slot>
    <span>{{ labelText }}</span>
  </button>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Microphone } from '@element-plus/icons-vue'
import { createSpeechRecorder } from '@/utils/audioRec'

/**
 * 通用「按住说话」：按下开麦、松开交出一段音频、上滑取消。
 *
 * 面试页（转成文字）与聊天页（发成语音消息）要的是同一段录音交互，
 * 差别只在「拿到音频之后干什么」，所以这里只管录、不碰识别也不碰上传：
 * 录好的 WAV 通过 result 事件交给调用方，各自去转写或上传。
 *
 * 几个不显眼但踩过的点：
 * - pointerdown 是用户手势，AudioContext.resume 必须在它里面发起（安卓自动播放策略）；
 * - setPointerCapture 让手指滑出按钮外也能收到 up/move，触屏必需；
 * - touch-action: none 防按住时页面滚动、contextmenu.prevent 防长按弹系统菜单；
 * - 不可用时不能用原生 disabled 拦：触屏上 disabled 按钮吞掉全部 pointer 事件、
 *   tooltip 也不弹，用户视角就是「按住没反应」，所以照常响应按下但把原因说出来。
 */
defineOptions({ name: 'HoldTalkButton' })

const props = defineProps({
  /** 不可用（如服务端没配语音、面试官正在说话）：按下只提示原因，不开麦 */
  disabled: { type: Boolean, default: false },
  /** 不可用时提示的具体原因 */
  disabledHint: { type: String, default: '' },
  /** 空闲态文案 */
  label: { type: String, default: '按住说话' },
  /** 调用方占用了这段音频（识别中/上传中）：期间不让再按 */
  busy: { type: Boolean, default: false },
  busyLabel: { type: String, default: '处理中…' }
})

const emit = defineEmits(['result', 'state', 'too-short', 'mic-denied'])

/** 上滑超过这个距离判定为取消 */
const SWIPE_CANCEL_PX = 80
/** 短于这个时长按「没说话」处理：误触一秒内的气泡对双方都是噪音 */
const MIN_DURATION_MS = 300
/**
 * 录音上限：再长就没有意义了。16k 单声道 16bit 的 WAV 60 秒约 1.9MB，
 * 正好卡在服务端 ASR 的 4MB 输入上限内（聊天侧存文件也没必要更大）。
 */
const MAX_DURATION_MS = 60_000

const state = ref('idle') // idle | recording | cancel
let startY = 0
let pressing = false
let startedAt = 0
let tickTimer = null
const recorder = createSpeechRecorder()

const labelText = computed(() => {
  if (props.busy) {
    return props.busyLabel
  }
  if (state.value === 'recording') {
    return '松开 发送 · 上滑 取消'
  }
  if (state.value === 'cancel') {
    return '松开 取消发送'
  }
  return props.label
})

/**
 * 把当前状态连同「已录几秒」抛给调用方：聊天页据此浮一层录音遮罩，
 * 面试页不接这个事件也无所谓。
 */
function announce() {
  emit('state', state.value, Math.round(elapsedMs() / 1000))
}

function elapsedMs() {
  return startedAt ? Math.min(MAX_DURATION_MS, Date.now() - startedAt) : 0
}

function startPress(event) {
  if (props.busy) {
    return
  }
  if (props.disabled) {
    ElMessage.info(props.disabledHint || '语音功能暂不可用')
    return
  }
  if (pressing) {
    return
  }
  pressing = true
  startY = event.clientY
  startedAt = Date.now()
  state.value = 'recording'
  announce()
  // 指针捕获：手指滑出按钮外也能收到 up/move（触屏必需）
  event.target.setPointerCapture?.(event.pointerId)
  // 每秒刷一次录音时长（聊天页的遮罩靠它读数）；到上限自动收尾，不等用户一直按着
  tickTimer = setInterval(() => {
    if (Date.now() - startedAt >= MAX_DURATION_MS) {
      releasePress(true)
      return
    }
    if (state.value !== 'idle') {
      announce()
    }
  }, 1000)
  recorder.start().catch(() => {
    stopTick()
    pressing = false
    startedAt = 0
    state.value = 'idle'
    announce()
    emit('mic-denied')
    ElMessage.error('无法访问麦克风，请在浏览器设置中允许录音权限')
  })
}

function onMove(event) {
  if (!pressing || props.busy) {
    return
  }
  const next = startY - event.clientY > SWIPE_CANCEL_PX ? 'cancel' : 'recording'
  if (next !== state.value) {
    state.value = next
    announce()
  }
}

/**
 * @param reachedMax 到达时长上限时的自动松开（与手指松开走同一段收尾，不再复制逻辑）
 */
async function releasePress(reachedMax = false) {
  stopTick()
  if (!pressing) {
    return
  }
  pressing = false
  const willCancel = state.value === 'cancel'
  state.value = 'idle'
  announce()
  startedAt = 0
  if (!recorder.running) {
    recorder.cancel()
    // 权限弹窗或开轨还没完成就松手：以前这里静默 return，用户按了个寂寞。
    // 首次授权必然撞上，所以要说出来——不是失灵，是还没开始录。
    if (!willCancel) {
      ElMessage.info('麦克风还没准备好，请按住说完再松开')
    }
    return
  }
  if (willCancel) {
    recorder.cancel() // 上滑取消：直接丢帧，不再白白编码一段 WAV
    return
  }
  const result = await recorder.stop()
  if (!result) {
    return // 开轨还没完成就松手的竞态
  }
  if (result.durationMs < MIN_DURATION_MS) {
    emit('too-short')
    ElMessage.warning('说话时间太短，请按住按钮说完再松开')
    return
  }
  if (reachedMax) {
    ElMessage.info('已达单条录音上限，自动发送')
  }
  emit('result', { blob: result.blob, durationMs: result.durationMs })
}

function stopTick() {
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}

onBeforeUnmount(() => {
  stopTick()
  recorder.cancel()
})
</script>

<style scoped>
.hold-talk {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex: none;
  min-height: 44px;
  padding: 0 16px;
  border-radius: 22px;
  border: 1px solid var(--im-border, #e4e7ed);
  background: #ffffff;
  color: #303133;
  font-size: 14px;
  cursor: pointer;
  user-select: none;
  touch-action: none; /* 按住说话时不触发页面滚动/双击缩放 */
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.hold-talk:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 不可用态：不用原生 disabled 拦（触屏会吞事件、无反馈），视觉上仍置灰 */
.hold-talk--off {
  opacity: 0.5;
}

.hold-talk--recording {
  background: var(--im-primary, #409eff);
  border-color: var(--im-primary, #409eff);
  color: #ffffff;
}

.hold-talk--cancel {
  background: #f56c6c;
  border-color: #f56c6c;
  color: #ffffff;
}

.hold-talk--busy {
  opacity: 0.75;
  cursor: progress;
}

/* 聊天输入区里的语音条要占满那一行，别缩成一颗按钮 */
.hold-talk--wide {
  flex: 1;
  width: 100%;
}
</style>
