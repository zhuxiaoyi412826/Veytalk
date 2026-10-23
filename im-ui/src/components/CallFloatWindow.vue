<template>
  <div
    v-if="call.visible && call.minimized"
    ref="rootRef"
    class="call-float"
    :style="floatStyle"
    title="拖动可移到任意位置，点一下回到通话界面"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
  >
    <video v-if="isVideo" ref="videoRef" class="call-float__video" muted autoplay playsinline />
    <UserAvatar v-else :src="peerAvatar" :name="peerName" :size="44" />

    <div class="call-float__info">
      <span class="call-float__name">{{ peerName }}</span>
      <span class="call-float__timer">{{ elapsedText }}</span>
    </div>

    <button
      class="call-float__hangup"
      title="挂断"
      @click.stop="call.hangup()"
    >
      <el-icon :size="14"><PhoneFilled /></el-icon>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { PhoneFilled } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { useCallStore } from '@/stores/call'
import { useFriendStore } from '@/stores/friend'
import { formatDuration } from '@/utils/format'

/**
 * 通话最小化后的悬浮小窗：可自由拖动，点一下回到通话浮层。
 *
 * 它和 CallOverlay 是两个组件（都挂 MainLayout），靠 store 里的 `minimized` 交接：
 * 浮层只是被 opacity 抹掉（DOM 与两路流还在），这里再拿远端流画一份缩略图，
 * 所以本窗口的 <video> **必须 muted**——同一路 MediaStream 挂两个元素会出双份声音。
 */
const call = useCallStore()
const friends = useFriendStore()

const rootRef = ref(null)
const videoRef = ref(null)
/** null = 还没动过，用右上角默认定位；一旦拖动就换成 left/top（继续用 right 的话鼠标一动窗口朝反方向跑） */
const pos = ref(null)

const isVideo = computed(() => call.mediaType === 'video')
const elapsedText = computed(() => formatDuration(call.seconds))
const peer = computed(() => friends.friendOf(call.peerUserId))
const peerName = computed(
  () => (peer.value && (peer.value.remark || peer.value.displayName || peer.value.nickname)) || '对方'
)
const peerAvatar = computed(() => (peer.value && peer.value.avatar) || '')

const floatStyle = computed(() =>
  pos.value ? { left: `${pos.value.x}px`, top: `${pos.value.y}px` } : { top: '16px', right: '16px' }
)

/**
 * 拖动与点击共用一套 pointer 事件，靠 4px 位移阈值区分：
 * 没越过阈值就松手算「点击」→ 恢复浮层；越过就算拖动，不会误把拖动当成点击弹回全屏。
 */
let dragging = false
let moved = false
let startClient = { x: 0, y: 0 }
let startRect = { x: 0, y: 0 }

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), Math.max(min, max))
}

function onPointerDown(event) {
  if (event.button != null && event.button !== 0) {
    return
  }
  const el = rootRef.value
  if (!el) {
    return
  }
  // 落在挂断钮上的按下不开拖动（不这么拦会一下拖又一下点，挂断与移动同时发生）
  if (event.target instanceof Element && event.target.closest('.call-float__hangup')) {
    return
  }
  const rect = el.getBoundingClientRect()
  pos.value = { x: rect.left, y: rect.top }
  startRect = { x: rect.left, y: rect.top }
  startClient = { x: event.clientX, y: event.clientY }
  dragging = true
  moved = false
  if (el.setPointerCapture && event.pointerId != null) {
    el.setPointerCapture(event.pointerId)
  }
}

function onPointerMove(event) {
  if (!dragging) {
    return
  }
  const dx = event.clientX - startClient.x
  const dy = event.clientY - startClient.y
  if (!moved && Math.abs(dx) <= 4 && Math.abs(dy) <= 4) {
    return
  }
  moved = true
  const el = rootRef.value
  // 留 8px 边距并夹在视口内：拖出屏幕外就只剩一条边能抓，等于把通话关进小黑屋
  pos.value = {
    x: clamp(startRect.x + dx, 8, window.innerWidth - el.offsetWidth - 8),
    y: clamp(startRect.y + dy, 8, window.innerHeight - el.offsetHeight - 8)
  }
}

function onPointerUp() {
  if (!dragging) {
    return
  }
  dragging = false
  if (!moved) {
    call.restore()
  }
}

/**
 * 挂流。和 CallOverlay 里同一课的第二个坑：这里的 <video> 是 `v-if="call.minimized"` 才存在的，
 * 只 watch 流就会在「流先到、小窗后开」时错过赋值（小窗全黑），所以把 minimized 也放进依赖。
 */
async function attachStream() {
  await nextTick()
  const el = videoRef.value
  if (!el || el.srcObject === call.remoteStream) {
    return
  }
  el.srcObject = call.remoteStream || null
  if (call.remoteStream) {
    el.play().catch(() => {})
  }
}

watch([() => call.remoteStream, () => call.minimized, isVideo], attachStream, { immediate: true })

// 换一通电话时把小窗放回右上角默认位置，别沿用上通拖到的角落
watch(
  () => call.callId,
  () => {
    pos.value = null
  }
)
</script>

<style scoped>
.call-float {
  position: fixed;
  z-index: 2990;
  width: 208px;
  height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: #111;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  /* 触屏上不禁用默认手势，拖动会被浏览器当成页面滚动吃掉 */
  touch-action: none;
  user-select: none;
  cursor: move;
}

.call-float__video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.call-float__info {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 3px 7px;
  font-size: 11px;
  color: #fff;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.55), rgba(0, 0, 0, 0));
  pointer-events: none;
}

.call-float__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.call-float__timer {
  flex: none;
  font-variant-numeric: tabular-nums;
}

.call-float__hangup {
  position: absolute;
  right: 6px;
  bottom: 6px;
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 50%;
  color: #fff;
  cursor: pointer;
  background: #e34d59;
}

.call-float__hangup .el-icon {
  transform: rotate(135deg);
}

.call-float__hangup:hover {
  background: #c93f4a;
}

@media (max-width: 640px) {
  .call-float {
    width: 140px;
    height: 104px;
  }
}
</style>
