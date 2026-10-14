<template>
  <transition name="call-fade">
    <div v-if="call.visible" class="call-overlay">
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
        <!-- 远端画面：视频通话显示；语音通话用一个静默隐藏的 video 承接对端声音 -->
        <video
          ref="remoteVideoRef"
          class="call-overlay__remote"
          :class="{ 'call-overlay__remote--hidden': !isVideo }"
          autoplay
          playsinline
        />

        <!-- 语音通话的封面 -->
        <div v-if="!isVideo" class="call-overlay__audio-face">
          <UserAvatar :src="peerAvatar" :name="peerName" :size="96" />
          <div class="call-overlay__peer-name">{{ peerName }}</div>
          <div class="call-overlay__timer">{{ elapsedText }}</div>
        </div>

        <!-- 本地小窗 -->
        <video
          v-if="isVideo"
          ref="localVideoRef"
          class="call-overlay__local"
          autoplay
          playsinline
          muted
        />

        <!-- 顶部信息条（视频通话时叠在画面上） -->
        <div v-if="isVideo" class="call-overlay__topbar">
          <span class="call-overlay__topbar-name">{{ peerName }}</span>
          <span class="call-overlay__timer">{{ elapsedText }}</span>
          <span v-if="call.recording" class="call-overlay__rec-dot">● 录制中</span>
          <span v-if="call.sharing" class="call-overlay__share-tag">共享中</span>
        </div>

        <div v-if="call.hint" class="call-overlay__hint call-overlay__hint--stage">{{ call.hint }}</div>

        <!-- 控制条 -->
        <div class="call-overlay__controls">
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
import { Microphone, Monitor, Mute, PhoneFilled, VideoCamera, VideoCameraFilled } from '@element-plus/icons-vue'
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
 */
const call = useCallStore()
const auth = useAuthStore()
const friends = useFriendStore()

const remoteVideoRef = ref(null)
const localVideoRef = ref(null)

const isVideo = computed(() => call.mediaType === 'video')
const isPending = computed(() => call.status === 'ringing' || call.status === 'dialing')
const mediaLabel = computed(() => (isVideo.value ? '视频通话' : '语音通话'))
const elapsedText = computed(() => formatDuration(call.seconds))

const peer = computed(() => friends.friendOf(call.peerUserId))
const peerName = computed(
  () => (peer.value && (peer.value.remark || peer.value.displayName || peer.value.nickname)) || '对方'
)
const peerAvatar = computed(() => (peer.value && peer.value.avatar) || '')

/** 流就绪时把 MediaStream 挂到对应 video（shallowRef 变更触发） */
watch(
  () => call.remoteStream,
  async (stream) => {
    await nextTick()
    if (remoteVideoRef.value && remoteVideoRef.value.srcObject !== stream) {
      remoteVideoRef.value.srcObject = stream || null
      if (stream) {
        remoteVideoRef.value.play().catch(() => {})
      }
    }
  },
  { immediate: true }
)

watch(
  () => call.localStream,
  async (stream) => {
    await nextTick()
    if (localVideoRef.value && localVideoRef.value.srcObject !== stream) {
      localVideoRef.value.srcObject = stream || null
      if (stream) {
        localVideoRef.value.play().catch(() => {})
      }
    }
  },
  { immediate: true }
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

.call-overlay__remote {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.call-overlay__remote--hidden {
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

.call-overlay__local {
  position: absolute;
  top: 24px;
  right: 24px;
  width: 180px;
  height: 128px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  background: #111;
  z-index: 2;
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
</style>
