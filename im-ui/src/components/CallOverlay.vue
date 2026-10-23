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
        <!-- ★ 两路流各自固定绑一个 <video>，互换只切 CSS 位置（main / pip），绝不重挂 srcObject。
             移动端（尤其 iOS Safari）把一条正在播放的 MediaStream 换到新 video 会白屏 / 卡在第一帧
             （本端 getUserMedia 流尤其明显、远端常能重挂，所以表现为「我的画面卡住、对方正常」），
             谁在主画面完全由 class 决定。远端元素永不静音（声音出口）、本端元素恒静音（不回灌），与位置无关。 -->
        <div
          class="call-overlay__slot"
          :class="{
            'call-overlay__slot--pip': swappedView,
            'call-overlay__slot--main': !swappedView,
            'call-overlay__slot--expanded': swappedView && pipExpanded,
            'call-overlay__slot--clickable': swappedView,
            'call-overlay__slot--hidden': !isVideo
          }"
          @click="swappedView && onPipClick()"
        >
          <video ref="remoteVideoRef" class="call-overlay__slot-video" autoplay playsinline />
          <span v-if="isVideo && swappedView" class="call-overlay__slot-tag">对方</span>
          <button
            v-if="isVideo && swappedView && isDesktop"
            class="call-overlay__slot-zoom"
            :title="pipExpanded ? '缩小小窗' : '放大小窗'"
            @click.stop="pipExpanded = !pipExpanded"
          >
            <el-icon :size="12"><ZoomOut v-if="pipExpanded" /><ZoomIn v-else /></el-icon>
          </button>
        </div>

        <div
          class="call-overlay__slot"
          :class="{
            'call-overlay__slot--main': swappedView,
            'call-overlay__slot--pip': !swappedView,
            'call-overlay__slot--expanded': !swappedView && pipExpanded,
            'call-overlay__slot--clickable': !swappedView,
            'call-overlay__slot--hidden': !isVideo
          }"
          @click="!swappedView && onPipClick()"
        >
          <video ref="localVideoRef" class="call-overlay__slot-video" muted autoplay playsinline />
          <span v-if="isVideo && !swappedView" class="call-overlay__slot-tag">我</span>
          <button
            v-if="isVideo && !swappedView && isDesktop"
            class="call-overlay__slot-zoom"
            :title="pipExpanded ? '缩小小窗' : '放大小窗'"
            @click.stop="pipExpanded = !pipExpanded"
          >
            <el-icon :size="12"><ZoomOut v-if="pipExpanded" /><ZoomIn v-else /></el-icon>
          </button>
        </div>

        <!-- 语音通话的封面（两个视频槽都隐藏时叠一层头像） -->
        <div v-if="!isVideo" class="call-overlay__audio-face">
          <UserAvatar :src="peerAvatar" :name="peerName" :size="96" />
          <div class="call-overlay__peer-name">{{ peerName }}</div>
          <div class="call-overlay__timer">{{ elapsedText }}</div>
        </div>

        <!-- 顶部信息条（视频通话时叠在主画面上）：主画面是谁由 swappedView 决定 -->
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
 * 画面布局（用户拿截图逐轮定稿）：
 * - 两端同一套：中间铺满一格 + 右上角画中画，**默认都是中间＝我自己、右上＝对方**（他原话：
 *   「我需要显示的是第一张图片的格式，而不是第二张，在电脑端」），点右上角小窗两者互换。
 * - **手机端主画面用 object-fit: cover 铺满**：不管中间是自己还是对方，永远满屏、无上下黑边
 *   （他要的「显示格式不变」）。电脑端保持 contain（自己的横屏流在横屏窗口本来就铺满，
 *   而把对方的竖屏流 cover 到横屏会只剩下半张脸，比黑边更糟）。
 * - 缩放圆钮只给电脑端；手机端只留最小化悬浮窗。
 * 语音通话不参与互换（见 swappedView）。
 */
const call = useCallStore()
const auth = useAuthStore()
const friends = useFriendStore()

// 两个固定元素：各自永久绑一路流（远端 / 本端），互换只换 CSS 位置，从不重挂 srcObject
const remoteVideoRef = ref(null)
const localVideoRef = ref(null)
/** 画中画尺寸：默认缩小，点右下角按钮放大 / 缩回（仅电脑端有这颗按钮） */
const pipExpanded = ref(false)
/**
 * 是否电脑端（主指针是鼠标）。现在只管一件事：
 * 给不给小窗的放大/缩小圆钮（手机不给，只留最小化悬浮窗）。
 * 默认布局与能不能互换都不再看它——两端完全一样。
 */
const isDesktop =
  typeof window === 'undefined' || !window.matchMedia ? true : window.matchMedia('(pointer: fine)').matches
/**
 * 位置互换：false = 中间对方 + 右上我；true = 反过来。
 *
 * ★ 两端初值都是 false（中间＝对方、右上＝我自己）——用户本轮明确要求
 * 「视频通话主界面默认显示的是对方的视角而不是我的视角」，与之前几轮「默认中间＝自己」相反，
 * 以本条为准。点小窗仍可互换；手机端主画面 cover 保证换前后都满屏无黑边（见 @media）。
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
  // 两端都能换：上一轮把手机端禁掉是误读需求（他要的是换了之后显示格式也别变，不是不让换）
  swapped.value = !swapped.value
}

/**
 * 把两路 MediaStream 各自绑到自己的固定元素（一个元素只认一条流、永不变）。
 *
 * 为什么不能只 watch 流：通话中的两个 video 在 isPending 的 v-else 分支里，
 * 振铃/呼叫阶段根本还没这个元素，而本地流在 dialing 里 initMedia() 时就 set 好了——
 * 只 watch 流会错过这一次，所以连 isPending 一起 watch，元素一挂载就补绑。
 *
 * ★ 关键：这里只在「流对象本身变了」时赋一次 srcObject，且一条流永远只挂一个元素。
 * 互换（swappedView 变化）不重新调这个、也不碰 srcObject，只改 class——因为移动端把一条
 * 正在播放的流换到新 video 会白屏 / 卡第一帧。绑完不再动，从根上消除互换卡顿。
 */
async function bindStreams() {
  await nextTick()
  bindStream(remoteVideoRef.value, call.remoteStream)
  bindStream(localVideoRef.value, call.localStream)
}

function bindStream(el, stream) {
  if (!el || el.srcObject === stream) {
    return
  }
  el.srcObject = stream || null
  if (stream) {
    // 移动端 autoplay 属性不一定自动起，显式 play() 一次；被策略拒时静默（属性会兼）
    el.play().catch(() => {})
  }
}

watch([() => call.remoteStream, () => call.localStream, isPending], bindStreams, {
  immediate: true
})

// 每通新通话都回到默认：中间＝对方、右上＝自己；小窗都是缩小态
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

/* ------------------------- 通话画面槽（主画面 / 画中画共用一个基类）-------------------------
   两个 slot 元素永久各自绑一路流（远端 / 本端），谁铺满、谁在角落只由 --main / --pip
   这两个「位置类」决定，互换时只切类、绝不重挂 srcObject——移动端（尤其 iOS Safari）把一条
   正在播放的 MediaStream 换到新 <video> 会白屏 / 卡在第一帧（本端 getUserMedia 流尤其明显，
   远端常能重挂，所以旧写法表现为「我的画面卡住、对方正常」）。位置与流彻底解耦。 */
.call-overlay__slot {
  position: absolute;
  overflow: hidden;
  background: #000;
}

.call-overlay__slot--main {
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
}

/* 语音通话两路画面都不展示，但仍要留着元素（远端那颗是不静音的声音出口）：
   用 opacity 归零而非 display: none，避免部分移动端把不渲染的视频元素降速 / 暂停解码 */
.call-overlay__slot--hidden {
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

/* ------------------------- 画中画位置态 ------------------------- */
/* 默认（缩小）态；点右下角按钮放大一档（仅电脑端有圆钮），点整块小窗与主画面互换（只切类） */
.call-overlay__slot--pip {
  top: 24px;
  right: 24px;
  width: 270px;
  height: 192px;
  z-index: 2;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  background: #111;
  transition: width 0.18s ease, height 0.18s ease;
}

.call-overlay__slot--pip.call-overlay__slot--expanded {
  width: 405px;
  height: 288px;
}

/* 只有真能点（有互换目标）才给手型，否则看着可点其实没反应 */
.call-overlay__slot--clickable {
  cursor: pointer;
}

.call-overlay__slot-video {
  display: block;
  width: 100%;
  height: 100%;
  /* 主画面默认 contain：把对方竖屏流塞横屏窗口时 cover 会只剩下半张脸，比黑边更糟 */
  object-fit: contain;
  background: #111;
}

/* 小窗永远 cover：格子小，裁满比留边好看 */
.call-overlay__slot--pip .call-overlay__slot-video {
  object-fit: cover;
}

/* 角标：没有它时「中间是谁」全靠猜，而且互换后这个角标就是唯一提示 */
.call-overlay__slot-tag {
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

.call-overlay__slot-zoom {
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

.call-overlay__slot-zoom:hover {
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
  /* 主画面在手机上改成裁满：用户要「怎么点都是满屏那种格式」。contain 下横屏流放竖屏就是
     中间一条 + 上下大黑边（他不要的第二张）。代价是横屏流被裁左右只剩中间，这是满屏的必然取舍。 */
  .call-overlay__slot--main .call-overlay__slot-video {
    object-fit: cover;
  }

  .call-overlay__slot--pip {
    top: 12px;
    right: 12px;
    width: 150px;
    height: 111px;
  }

  .call-overlay__slot--pip.call-overlay__slot--expanded {
    width: 225px;
    height: 167px;
  }

  /* 22px 的圆钮在触屏上偏小，手指按不准 */
  .call-overlay__slot-zoom {
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
