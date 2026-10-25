<template>
  <div class="call">
    <div class="call__top">
      <button class="call__chip" :class="{ 'call__chip--active': textMode }" @click="textMode = !textMode">
        {{ textMode ? '语音模式' : '文字模式' }}
      </button>
      <div class="call__voices">
        <button
          class="call__chip"
          :class="{ 'call__chip--active': engine === 'system' }"
          @click="$emit('switch-engine', 'system')"
        >
          系统音色
        </button>
        <button
          class="call__chip"
          :class="{ 'call__chip--active': engine === 'remote' }"
          @click="$emit('switch-engine', 'remote')"
        >
          神经音色
        </button>
      </div>
    </div>
    <p v-if="!sysAvailable && engine !== 'system'" class="call__voice-tip">浏览器没有可用中文音色，已切到神经音色</p>

    <div v-if="!textMode" class="call__stage">
      <div class="call__avatar" :class="avatarClass">
        <el-icon :size="42"><Microphone /></el-icon>
      </div>
      <p class="call__who">AI 面试官 · 通话中</p>
      <p class="call__duration">{{ durationText }}</p>
      <p class="call__phase">{{ statusText }}</p>
    </div>
    <!-- 文字态不挂舞台，但通话还在跑：用一行状态条把时长与提示补回来 -->
    <p v-else class="call__status-line">{{ durationText }} · {{ statusText }}</p>

    <div ref="scrollEl" class="call__captions" :class="{ 'call__captions--full': textMode }">
      <div
        v-for="(line, index) in visibleCaptions"
        :key="index"
        class="call__caption"
        :class="[`call__caption--${line.role}`, { 'call__caption--draft': line.draft }]"
      >
        <span class="call__caption-role">{{ captionLabel(line) }}</span>
        <span class="call__caption-text">{{ line.content }}</span>
      </div>
      <p v-if="!visibleCaptions.length" class="call__caption-empty">接通后面试官会先提问，直接回答即可</p>
    </div>

    <div class="call__actions">
      <button class="call__btn" :class="{ 'call__btn--on': muted }" @click="$emit('toggle-mute')">
        <el-icon :size="22"><Mute /></el-icon>
        <span>{{ muted ? '取消静音' : '静音' }}</span>
      </button>
      <button class="call__btn" :disabled="phase !== 'speaking'" @click="$emit('interrupt')">
        <el-icon :size="22"><Pointer /></el-icon>
        <span>打断播报</span>
      </button>
      <button class="call__btn call__btn--hangup" @click="$emit('hangup')">
        <el-icon :size="22" class="call__hangup-icon"><Phone /></el-icon>
        <span>挂断</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Microphone, Mute, Pointer, Phone } from '@element-plus/icons-vue'

/**
 * 电话面试全屏浮层：纯展示 + 事件上抛，音频环路状态机全部在主页面。
 *
 * phase 决定舞台动画与提示文案：connecting/listening/thinking/speaking/muted；
 * 「文字模式」只在浮层内收起/展开头像舞台，通话不断；两种模式都常驻完整问答史（带序号）可上滑查看，
 * 退出通话只由「挂断」触发。
 * 移动端竖屏 + safe-area 适配，底部按钮统一 ≥44px 触控高度。
 */
defineOptions({ name: 'CallPanel' })

const props = defineProps({
  phase: { type: String, default: 'connecting' },
  captions: { type: Array, default: () => [] },
  durationText: { type: String, default: '00:00' },
  muted: { type: Boolean, default: false },
  engine: { type: String, default: 'system' },
  sysAvailable: { type: Boolean, default: true }
})
defineEmits(['toggle-mute', 'interrupt', 'hangup', 'switch-engine'])

/** 文字态只收起头像舞台；问答史两种模式都给全量（含序号），长回答也不会被截成尾巴 */
const textMode = ref(false)
const scrollEl = ref(null)
const visibleCaptions = computed(() => props.captions)

/** 序号标签：面试官按出题顺序计「第 N 问」，我的回答与正在说的草稿都归属当前这题 */
function captionLabel(line) {
  if (line.draft) {
    return '我（正在说…）'
  }
  if (line.role === 'ai') {
    return line.no ? `面试官 · 第 ${line.no} 问` : '面试官'
  }
  return line.no ? `我的回答 · 第 ${line.no} 问` : '我'
}

// 跟随最新消息滚到底，但只要用户上滑翻看历史就不再强行拽回底部（否则长回答的开头永远看不到）。
// 切到文字态时滚到底是为了立刻看到当前这题。watch 数组本身而非 length：逐 token 追加条数不变、只有引用变。
watch([() => props.captions, textMode], ([, toText], [, fromText]) => {
  nextTick(() => {
    const el = scrollEl.value
    if (!el) {
      return
    }
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
    if (nearBottom || (toText && toText !== fromText)) {
      el.scrollTop = el.scrollHeight
    }
  })
})

const STATUS_TEXT = {
  connecting: '正在接通…',
  listening: '请讲话，停顿 5 秒自动送出',
  thinking: '面试官思考中…',
  speaking: '正在播报，可点「打断播报」',
  muted: '已静音（不退出通话）'
}
const statusText = computed(() => STATUS_TEXT[props.phase] || '')
// 属性双引号里的模板字符串容易被解析器咬到，普通拼接最稳
const avatarClass = computed(() => 'call__avatar--' + props.phase)
</script>

<style scoped>
.call {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  padding: calc(12px + env(safe-area-inset-top)) calc(16px + env(safe-area-inset-right))
    calc(16px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-left));
  background: linear-gradient(180deg, #1d2b3a 0%, #16212c 100%);
  color: #e8edf2;
}

.call__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.call__voices {
  display: flex;
  gap: 6px;
}

.call__chip {
  min-height: 32px;
  padding: 4px 12px;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: transparent;
  color: #cfd8e1;
  font-size: 12px;
  cursor: pointer;
}

.call__chip--active {
  background: rgba(64, 158, 255, 0.85);
  border-color: transparent;
  color: #ffffff;
}

.call__voice-tip {
  margin: 8px 0 0;
  font-size: 12px;
  color: #e6a23c;
  text-align: right;
}

.call__stage {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 24px 0 12px;
}

.call__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 92px;
  height: 92px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  color: #9ecbff;
}

/* listening 呼吸、speaking 声波脉冲；其余状态静止 */
.call__avatar--listening {
  animation: call-breathe 2.4s ease-in-out infinite;
}

.call__avatar--speaking {
  animation: call-pulse 1.1s ease-out infinite;
  background: rgba(64, 158, 255, 0.28);
}

@keyframes call-breathe {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(109, 212, 255, 0.35);
  }
  50% {
    box-shadow: 0 0 0 18px rgba(109, 212, 255, 0);
  }
}

@keyframes call-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(64, 158, 255, 0.5);
  }
  100% {
    box-shadow: 0 0 0 26px rgba(64, 158, 255, 0);
  }
}

.call__who {
  margin: 8px 0 0;
  font-size: 15px;
  font-weight: 600;
}

.call__duration {
  margin: 2px 0 0;
  font-size: 13px;
  color: #9aa8b6;
  font-variant-numeric: tabular-nums;
}

.call__phase {
  margin: 4px 0 0;
  font-size: 13px;
  color: #79c0ff;
}

.call__status-line {
  flex: none;
  margin: 6px 0 0;
  text-align: center;
  font-size: 12px;
  color: #79c0ff;
  font-variant-numeric: tabular-nums;
}

.call__captions {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  /* 从顶部排起、整体可滚动：用 flex-end 钉底时，单条长回答超高会把开头顶到滚动区上方且划不回去（“只剩最后几个字”的根因） */
  justify-content: flex-start;
  gap: 10px;
  padding: 10px 0;
}

/* 兼容旧类名：文字态与语音态现在都是完整转写，此处与基类保持一致 */
.call__captions--full {
  justify-content: flex-start;
}

.call__caption {
  align-self: flex-start;
  max-width: 88%;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.08);
  font-size: 14px;
  line-height: 1.6;
}

.call__caption--user {
  align-self: flex-end;
  background: rgba(64, 158, 255, 0.25);
}

/* 正在说的草稿泡：虚线框 + 半透明，与已送出的正式回答区分开 */
.call__caption--draft {
  border: 1px dashed rgba(64, 158, 255, 0.6);
  background: rgba(64, 158, 255, 0.12);
  opacity: 0.85;
}

.call__caption-role {
  display: block;
  margin-bottom: 2px;
  font-size: 11px;
  color: #9aa8b6;
}

.call__caption-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.call__caption-empty {
  margin: auto 0;
  text-align: center;
  font-size: 13px;
  color: #677482;
}

.call__actions {
  flex: none;
  display: flex;
  align-items: stretch;
  justify-content: space-around;
  padding-top: 8px;
}

.call__btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 84px;
  min-height: 76px;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.06);
  color: #dbe3ea;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.call__btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.call__btn--on {
  background: rgba(230, 162, 60, 0.35);
  border-color: transparent;
  color: #ffffff;
}

.call__btn--hangup {
  background: #d64a4a;
  border-color: transparent;
  color: #ffffff;
}

/* 电话图标旋转 135° 模拟「挂断」手势语义 */
.call__hangup-icon {
  transform: rotate(135deg);
}
</style>
