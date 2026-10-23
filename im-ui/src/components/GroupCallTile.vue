<template>
  <div class="gctile">
    <!-- 语音通话也保留这个 video：它是远端音频轨的载体，藏起来即可（display:none 会让部分浏览器暂停解码，用 opacity 隐藏） -->
    <video
      ref="videoRef"
      class="gctile__media"
      :class="{ 'gctile__media--hidden': !showVideo }"
      autoplay
      playsinline
      :muted="self"
    />

    <div v-if="!showVideo" class="gctile__face">
      <UserAvatar :src="avatar" :name="name" :size="56" />
      <span class="gctile__state">{{ stateText }}</span>
    </div>

    <div class="gctile__label">
      <span class="im-ellipsis">{{ self ? `${name}（我）` : name }}</span>
      <el-icon v-if="muted" :size="13" class="gctile__mute"><Mute /></el-icon>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { Mute } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'

/**
 * 群通话的一格画面。
 *
 * 单独成组件而不是在浮层里 v-for 一堆 <video> 再管 refs 数组：每一格要各自绑一条 MediaStream，
 * 用 ref 数组收集时「谁在哪一格」会随成员进出重排，流就挂错人。组件实例与成员一一对应（:key），
 * Vue 复用实例时 watch 只在本格 stream 变化时重新挂，错不了。
 *
 * 也正因为这个对应关系，MediaStream 必须走 props 传进来（store 里用的是 shallowRef，
 * 流对象不会被 Vue 深度代理，srcObject 才拿得到原始对象）。
 */

const props = defineProps({
  /** MediaStream 或 null：本端那格传自己采集的流 */
  stream: { type: Object, default: null },
  name: { type: String, default: '' },
  avatar: { type: String, default: '' },
  /** 是否显示画面：视频通话 + 对方没关摄像头 + 已经有流 */
  video: { type: Boolean, default: false },
  muted: { type: Boolean, default: false },
  cameraOff: { type: Boolean, default: false },
  /** 那一路 PeerConnection 的状态：connecting | connected | failed | closed */
  state: { type: String, default: 'connecting' },
  self: { type: Boolean, default: false }
})

const videoRef = ref(null)

const showVideo = computed(() => props.video && !props.cameraOff && !!props.stream)
const stateText = computed(() => {
  if (props.cameraOff && props.video) {
    return '摄像头已关'
  }
  if (props.self) {
    return ''
  }
  return { connecting: '连接中…', connected: '', failed: '连不上', closed: '已断开' }[props.state] || ''
})

watch(
  () => props.stream,
  async (stream) => {
    await nextTick()
    const el = videoRef.value
    if (!el) {
      return
    }
    if (el.srcObject !== stream) {
      el.srcObject = stream || null
    }
    if (stream) {
      // 自动播放策略：静音的本端预览不会被拦；远端那一路由浮层在用户点「接听」的手势里建流，
      // 个别浏览器仍可能拒绝，这里静默失败并让画面停在头像态，不能抛未捕获的 Promise
      el.play().catch(() => {})
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.gctile {
  position: relative;
  background: #1b1d22;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  min-height: 120px;
  aspect-ratio: 4 / 3;
}

.gctile__media {
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #000;
}

.gctile__media--hidden {
  position: absolute;
  inset: 0;
  opacity: 0;
  pointer-events: none;
}

.gctile__face {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.gctile__state {
  font-size: 12px;
  color: #a8abb2;
}

.gctile__label {
  position: absolute;
  left: 8px;
  bottom: 6px;
  right: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #fff;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.6);
  z-index: 2;
}

.gctile__mute {
  color: #f56c6c;
}
</style>
