<template>
  <transition name="call-fade">
    <!-- 最小化时只隐藏、不能卸载：视频元素一没，srcObject 要重挂、语音的声音出口也会断。
         用 class 而不是 v-show：同一个元素上叠 v-if + v-show 会让 transition 同时收到两套开关 -->
    <div v-if="call.visible" class="call-overlay" :class="{ 'call-overlay--minimized': call.minimized }">
      <!-- ==================== 振铃 / 呼叫中 ==================== -->
      <div v-if="isPending" class="call-overlay__pending">
        <UserAvatar :src="peerAvatar" :name="peerName" :size="84" />
        <div class="call-overlay__peer-name">{{ peerName }}</div>
        <div class="call-overlay__status-text">
          {{
            call.status === 'ringing'
              ? `邀请你${mediaLabel}`
              : `正在呼叫${mediaLabel}…`
          }}
        </div>
        <div v-if="call.hint && call.status !== 'dialing'" class="call-overlay__hint">{{ call.hint }}</div>

        <div class="call-overlay__actions">
          <template v-if="call.status === 'ringing'">
            <button class="call-btn call-btn--reject" title="拒绝" @click="call.reject()">
              <el-icon :size="24"><PhoneFilled /></el-icon>
            </button>
            <button class="call-btn call-btn--accept" title="接听" @click="call.accept()">
              <el-icon :size="24"><PhoneFilled /></el-icon>
            </button>
          </template>
          <template v-else>
            <div class="call-overlay__ringing-self">
              <UserAvatar :src="auth.avatarRaw" :name="auth.nickname" :size="56" />
              <span class="call-overlay__self-tag">我</span>
            </div>
            <button class="call-btn call-btn--reject" title="取消" @click="call.cancel()">
              <el-icon :size="24"><PhoneFilled /></el-icon>
            </button>
          </template>
        </div>
      </div>

      <!-- ==================== 通话中 ==================== -->
      <div v-else class="call-overlay__stage">
        <!-- 主画面（位置）：电脑上默认放对方、可互换；手机固定放自己。
             语音通话时它静默隐藏但仍存在——那是承接对端声音的出口；
             muted 跟着 swapped 走，保证对方的声音永远能播、自己的声音永远不回灌 -->
        <video
          ref="mainVideoRef"
          class="call-overlay__main"
          :class="{ 'call-overlay__main--hidden': !isVideo }"
          :muted="swappedView"
          autoplay
          playsinline
        />

        <!-- 语音通话的封面 -->
        <div v-if="!isVideo" class="call-overlay__audio-face">
          <UserAvatar :src="peerAvatar" :name="peerName" :size="96" />
          <div class="call-overlay__peer-name">{{ peerName }}</div>
          <div class="call-overlay__timer">{{ elapsedText }}</div>
        </div>

        <!-- 右上角画中画（位置）：两端都能点一下与主画面互换；放大/缩小圆钮只给电脑端 -->
        <div
          v-if="isVideo"
          class="call-overlay__pip call-overlay__pip--clickable"
          :class="{ 'call-overlay__pip--expanded': pipExpanded }"
          title="点击与对方画面互换"
          @click="onPipClick"
        >
          <video ref="pipVideoRef" class="call-overlay__pip-video" :muted="!swappedView" autoplay playsinline />
          <span class="call-overlay__pip-tag">{{ swappedView ? '对方' : '我' }}</span>
          <button
            v-if="isDesktop"
            class="call-overlay__pip-zoom"
            :title="pipExpanded ? '缩小小窗' : '放大小窗'"
            @click.stop="pipExpanded = !pipExpanded"
          >
            <el-icon :size="12"><ZoomOut v-if="pipExpanded" /><ZoomIn v-else /></el-icon>
          </button>
        </div>

        <!-- 顶部信息条（视频通话时叠在画面上） -->
        <div v-if="isVideo" class="call-overlay__topbar">
          <span class="call-overlay__topbar-name">{{ swappedView ? '我的画面' : peerName }}</span>
          <span class="call-overlay__timer">{{ elapsedText }}</span>
          <span v-if="call.recording" class="call-overlay__rec-dot">● 录制中</span>
          <span v-if="call.sharing" class="call-overlay__share-tag">共享中</span>
        </div>

        <div v-if="call.hint" class="call-overlay__hint call-overlay__hint--stage">{{ call.hint }}</div>

        <!-- 控制条 -->
        <div class="call-overlay__controls">
          <button
            class="call-btn call-btn--ghost"
            title="收起成悬浮小窗，点小红窗随时回来"
            @click="call.minimize()"
          >
            <el-icon :size="20"><Minus /></el-icon>
            <span>最小化</span>
          </button>

          <button
            class="call-btn call-btn--ghost"
            :class="{ 'call-btn--off': call.muted }"
            :title="call.muted ? '取消静音' : '静音'"
            @click="call.toggleMute()"
          >
            <el-icon :size="20"><Mute v-if="call.muted" /><Microphone v-else /></el-icon>
            <span>{{ call.muted ? '已静音' : '静音' }}</span>
          </button>

          <button
            v-if="isVideo"
            class="call-btn call-btn--ghost"
            :class="{ 'call-btn--off': call.cameraOff }"
            :title="call.cameraOff ? '开摄像头' : '关摄像头'"
            @click="call.toggleCamera()"
          >
            <el-icon :size="20"><VideoCamera /></el-icon>
            <span>{{ call.cameraOff ? '已关' : '摄像头' }}</span>
          </button>

          <button
            v-if="isVideo"
            class="call-btn call-btn--ghost"
            :class="{ 'call-btn--on': call.sharing }"
            title="共享屏幕"
            @click="call.toggleScreen()"
          >
            <el-icon :size="20"><Monitor /></el-icon>
            <span>{{ call.sharing ? '停止共享' : '共享' }}</span>
          </button>

          <button
            v-if="call.recordSupported"
            class="call-btn call-btn--ghost"
            :class="{ 'call-btn--on': call.recording }"
            title="录制通话"
            @click="call.toggleRecord()"
          >
            <el-icon :size="20"><VideoCameraFilled /></el-icon>
            <span>{{ call.recording ? '停止录制' : '录制' }}</span>
          </button>

          <button class="call-btn call-btn--hangup" title="挂断" @click="call.hangup()">
            <el-icon :size="22"><PhoneFilled /></el-icon>
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { Microphone, Minus, Monitor, Mute, PhoneFilled, VideoCamera, VideoCameraFilled, ZoomIn, ZoomOut } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { useCallStore } from '@/stores/call'
import { useAuthStore } from '@/stores/auth'
import { useFriendStore } from '@/stores/friend'
import { formatDuration } from '@/utils/format'

/**
 * 全局通话浮层。挂在 MainLayout，与 useCallStore 联动：
 * 切换会话、离开聊天页都不打断通话——通话状态由 store 持有，浮层只是它的一面镜子。
 *
 * 语音通话没有画面，但仍需要一个未静音的 <video>（隐藏）承接对端音频轨播放声音；
 * 视频通话则把它放大铺满，本端画面作右上角画中画。
 *
 * 画面布局两端同一套（用户最后定的格式）：默认中间＝对方、右上角＝我自己，点右上角小窗两者互换。
 * 端差异只剩一处：小窗的放大/缩小圆钮只在电脑端给，手机靠最小化悬浮窗。语音通话不参与互换（见 swappedView）。
 */
const call = useCallStore()
const auth = useAuthStore()
const friends = useFriendStore()

// 两个位置（主画面 / 画中画）的 DOM，装哪一路流由 swappedView 决定
const mainVideoRef = ref(null)
const pipVideoRef = ref(null)
/** 画中画尺寸：默认缩小，点右下角按钮放大 / 缩回（仅电脑端有这颗按钮） */
const pipExpanded = ref(false)
/**
 * 是否电脑端（主指针是鼠标）。现在只用来决定给不给小窗的放大/缩小圆钮——
 * 手机上那个圆钮按不准也没意义（小窗本来就占屏宽一半），用户要求手机只留最小化悬浮窗。
 */
const isDesktop =
  typeof window === 'undefined' || !window.matchMedia ? true : window.matchMedia('(pointer: fine)').matches
/**
 * 位置互换：false = 中间对方 + 右上我；true = 反过来。两端默认都是 false，都能点小窗互换。
 */
const swapped = ref(false)

const isVideo = computed(() => call.mediaType === 'video')
/**
 * 真正生效的互换。**语音通话必须强制不互换**：语音下 `isVideo` 为假，右上角小窗根本不渲染，
 * 只剩主画面这一个 video 元素在接对端声音；让 swapped 生效就会把远端流挂到不存在的元素上，
 * 手机语音通话直接听不到对方。（必须排在 isVideo 之后：attachStreams 的 watcher 是 immediate 的，
 * 提前声明会让 getter 读到 TDZ 里的 isVideo。）
 */
const swappedView = computed(() => swapped.value && isVideo.value)
const isPending = computed(() => call.status === 'ringing' || call.status === 'dialing')
const mediaLabel = computed(() => (isVideo.value ? '视频通话' : '语音通话'))
const elapsedText = computed(() => formatDuration(call.seconds))

const peer = computed(() => friends.friendOf(call.peerUserId))
const peerName = computed(
  () => (peer.value && (peer.value.remark || peer.value.displayName || peer.value.nickname)) || '对方'
)
const peerAvatar = computed(() => (peer.value && peer.value.avatar) || '')

function onPipClick() {
  swapped.value = !swapped.value
}

/**
 * 把两路 MediaStream 挂到两个位置。
 *
 * 为什么不能只 watch 流：通话中的 stage（两个 video）是 isPending 的 v-else 分支，
 * 振铃/呼叫阶段根本还没这个元素，而本地流在 dialing 里 initMedia() 时就 set 好了——
 * 只 watch 流会永远错过这一次，之后元素出现也没人再给它赋值，
 * 表现就是「右上角本端小窗整块黑」（远端流一般等接通后才到，反而撞上不了）。
 * 所以流、阶段、互换三个输入一起 watch，任一边变化都重挂；srcObject 相同则跳过，免得重新 play 把画面抖一下。
 */
async function attachStreams() {
  await nextTick()
  attachTo(mainVideoRef.value, swappedView.value ? call.localStream : call.remoteStream)
  attachTo(pipVideoRef.value, swappedView.value ? call.remoteStream : call.localStream)
}

function attachTo(el, stream) {
  if (!el || el.srcObject === stream) {
    return
  }
  el.srcObject = stream || null
  if (stream) {
    el.play().catch(() => {})
  }
}

watch([() => call.remoteStream, () => call.localStream, isPending, swappedView], attachStreams, {
  immediate: true
})

// 每通新通话回到默认：中间对方 + 右上我 + 小窗缩小（上一通手动改的不带进下一通）
watch(
  () => call.callId,
  () => {
    swapped.value = false
    pipExpanded.value = false
  }
)
</script>

<style scoped>
.call-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(20, 22, 26, 0.92);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.call-fade-enter-active,
.call-fade-leave-active {
  transition: opacity 0.24s ease;
}

.call-fade-enter-from,
.call-fade-leave-to {
  opacity: 0;
}

/* 收成悬浮小窗：整层不可见也不接事件，但 DOM 与两路流都还在跑（声音不能断）。
   用 opacity 而不是 display: none —— 部分移端浏览器会把 display: none 的视频元素降速/暂停解码，
   而 pointer-events: none 是继承的，控制条上的按钮也不会隔着屏幕偷吃点击 */
.call-overlay--minimized {
  opacity: 0;
  pointer-events: none;
}

/* ------------------------- 振铃 / 呼叫中 ------------------------- */
.call-overlay__pending {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.call-overlay__peer-name {
  font-size: 20px;
  font-weight: 600;
}

.call-overlay__status-text {
  font-size: 14px;
  color: #c9cdd4;
}

.call-overlay__hint {
  font-size: 13px;
  color: #e6a23c;
  min-height: 18px;
}

.call-overlay__actions {
  display: flex;
  align-items: center;
  gap: 48px;
  margin-top: 24px;
}

.call-overlay__ringing-self {
  position: absolute;
  top: 32px;
  right: 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.call-overlay__self-tag {
  font-size: 12px;
  color: #a8abb2;
}

/* ------------------------- 通话舞台 ------------------------- */
.call-overlay__stage {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.call-overlay__main {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.call-overlay__main--hidden {
  opacity: 0;
  pointer-events: none;
}

.call-overlay__audio-face {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  z-index: 1;
}

.call-overlay__timer {
  font-variant-numeric: tabular-nums;
  font-size: 16px;
  color: #c9cdd4;
}

/* ------------------------- 右上角画中画 ------------------------- */
/* 默认（缩小）态是旧尺寸的 1.5 倍；点右下角按钮再放大一档，点整块小窗与主画面互换 */
.call-overlay__pip {
  position: absolute;
  top: 24px;
  right: 24px;
  width: 270px;
  height: 192px;
  z-index: 2;
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  background: #111;
  transition: width 0.18s ease, height 0.18s ease;
}

/* 整块小窗都是点击区（两端都是：点一下与主画面互换） */
.call-overlay__pip--clickable {
  cursor: pointer;
}

.call-overlay__pip--expanded {
  width: 405px;
  height: 288px;
}

.call-overlay__pip-video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #111;
}

/* 小窗角标：没有它时「中间是谁」全靠猜，而且互换后这个角标就是唯一提示 */
.call-overlay__pip-tag {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  /* 不关掉就会吃掉整块小窗的点击，反而换不了 */
  pointer-events: none;
}

.call-overlay__pip-zoom {
  position: absolute;
  bottom: 6px;
  right: 6px;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 50%;
  color: #fff;
  cursor: pointer;
  background: rgba(0, 0, 0, 0.45);
}

.call-overlay__pip-zoom:hover {
  background: rgba(0, 0, 0, 0.7);
}

.call-overlay__topbar {
  position: absolute;
  top: 24px;
  left: 24px;
  display: flex;
  align-items: center;
  gap: 14px;
  z-index: 2;
}

.call-overlay__topbar-name {
  font-size: 16px;
  font-weight: 600;
}

.call-overlay__rec-dot {
  color: #f56c6c;
  font-size: 13px;
  animation: call-rec-blink 1.2s infinite;
}

.call-overlay__share-tag {
  color: #409eff;
  font-size: 13px;
}

@keyframes call-rec-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

.call-overlay__hint--stage {
  position: absolute;
  bottom: 120px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 2;
  /* ICE 诊断是一串候选统计，手机上不定长：不限宽就会顶到屏幕外，居中排版才能截图可读 */
  max-width: 92%;
  text-align: center;
  line-height: 1.6;
  word-break: break-all;
}

/* ------------------------- 控制条 ------------------------- */
.call-overlay__controls {
  position: absolute;
  bottom: 40px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 20px;
  z-index: 3;
}

.call-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: none;
  cursor: pointer;
  color: #fff;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.12);
  transition: background 0.15s ease;
}

.call-btn span {
  font-size: 11px;
}

.call-btn--ghost {
  width: 60px;
  height: 60px;
}

.call-btn--ghost:hover {
  background: rgba(255, 255, 255, 0.22);
}

.call-btn--off {
  background: rgba(245, 108, 108, 0.55);
}

.call-btn--on {
  background: rgba(64, 158, 255, 0.6);
}

.call-btn--accept,
.call-btn--reject,
.call-btn--hangup {
  width: 64px;
  height: 64px;
  border-radius: 50%;
}

.call-btn--accept {
  background: #21a675;
}

.call-btn--reject,
.call-btn--hangup {
  background: #e34d59;
}

.call-btn--reject .el-icon,
.call-btn--hangup .el-icon {
  transform: rotate(135deg);
}

.call-btn--accept:hover {
  background: #1c8f64;
}

.call-btn--reject:hover,
.call-btn--hangup:hover {
  background: #c93f4a;
}

/* ------------------------- 手机窄屏 ------------------------- */
/* 必须放在所有基础规则之后：同特异度下后写的规则赢，写在前面会被上面的基础值盖掉 */
@media (max-width: 640px) {
  .call-overlay__pip {
    top: 12px;
    right: 12px;
    width: 150px;
    height: 111px;
  }

  .call-overlay__pip--expanded {
    width: 225px;
    height: 167px;
  }

  /* 22px 的圆钮在触屏上偏小，手指按不准 */
  .call-overlay__pip-zoom {
    width: 28px;
    height: 28px;
  }

  .call-overlay__topbar {
    top: 12px;
    left: 12px;
    gap: 8px;
  }

  .call-overlay__controls {
    bottom: 24px;
    gap: 10px;
    /* 手机上最多能排到六个按钮（最小化/静音/摄像头/共享/录制/挂断），
       一行放不下就换行：宁可两行也别溢出到屏幕外按不到 */
    max-width: 94%;
    flex-wrap: wrap;
    row-gap: 10px;
  }

  .call-overlay__controls .call-btn--ghost {
    width: 50px;
    height: 50px;
  }

  .call-overlay__controls .call-btn span {
    font-size: 10px;
  }

  /* 控制条可能占两行，诊断行往上提，别叠上去 */
  .call-overlay__hint--stage {
    bottom: 156px;
  }
}
</style>
