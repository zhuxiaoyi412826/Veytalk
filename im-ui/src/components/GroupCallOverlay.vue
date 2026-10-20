<template>
  <transition name="gc-fade">
    <div v-if="gc.visible" class="gc-overlay">
      <!-- ==================== 收到邀约 ==================== -->
      <div v-if="gc.status === 'incoming'" class="gc-overlay__pending">
        <UserAvatar :src="inviter?.avatar" :name="inviterName" :size="80" />
        <div class="gc-overlay__title">{{ inviterName }} 邀请你加入群通话</div>
        <div class="gc-overlay__sub">{{ groupName }} · {{ mediaLabel }}</div>
        <div v-if="gc.hint" class="gc-overlay__hint">{{ gc.hint }}</div>
        <div class="gc-overlay__actions">
          <button class="gc-btn gc-btn--reject" title="拒绝" @click="gc.rejectInvite()">
            <el-icon :size="22"><PhoneFilled /></el-icon>
          </button>
          <button class="gc-btn gc-btn--accept" title="接听" @click="gc.join()">
            <el-icon :size="22"><PhoneFilled /></el-icon>
          </button>
        </div>
      </div>

      <!-- ==================== 通话中 / 呼叫中 ==================== -->
      <div v-else class="gc-overlay__stage">
        <div class="gc-overlay__topbar">
          <span class="gc-overlay__room im-ellipsis">{{ groupName }}</span>
          <span class="gc-overlay__count">{{ gc.participantCount }} 人</span>
          <span class="gc-overlay__timer">{{ elapsedText }}</span>
        </div>

        <div class="gc-grid" :data-count="tiles.length">
          <GroupCallTile
            v-for="tile in tiles"
            :key="tile.userId"
            :stream="tile.stream"
            :name="tile.name"
            :avatar="tile.avatar"
            :video="isVideo"
            :muted="tile.muted"
            :camera-off="tile.cameraOff"
            :state="tile.state"
            :self="tile.self"
          />
        </div>

        <!-- 发起了但没人接：这一列只在主叫那端出现 -->
        <div v-if="waitingNames.length" class="gc-overlay__waiting">
          等待接听：{{ waitingNames.join('、') }}
        </div>

        <div v-if="gc.hint" class="gc-overlay__hint gc-overlay__hint--stage">{{ gc.hint }}</div>

        <div class="gc-overlay__controls">
          <button
            class="gc-btn gc-btn--ghost"
            :class="{ 'gc-btn--off': gc.muted }"
            :title="gc.muted ? '取消静音' : '静音'"
            @click="gc.toggleMute()"
          >
            <el-icon :size="20"><Mute v-if="gc.muted" /><Microphone v-else /></el-icon>
            <span>{{ gc.muted ? '已静音' : '静音' }}</span>
          </button>

          <button
            v-if="isVideo"
            class="gc-btn gc-btn--ghost"
            :class="{ 'gc-btn--off': gc.cameraOff }"
            :title="gc.cameraOff ? '开摄像头' : '关摄像头'"
            @click="gc.toggleCamera()"
          >
            <el-icon :size="20"><VideoCamera /></el-icon>
            <span>{{ gc.cameraOff ? '已关' : '摄像头' }}</span>
          </button>

          <button class="gc-btn gc-btn--ghost" title="邀请群成员" @click="inviteVisible = true">
            <el-icon :size="20"><UserFilled /></el-icon>
            <span>邀请</span>
          </button>

          <button class="gc-btn gc-btn--hangup" title="离开通话" @click="gc.hangup()">
            <el-icon :size="22"><PhoneFilled /></el-icon>
            <span>离开</span>
          </button>
        </div>
      </div>

      <GroupCallInviteDialog
        v-model="inviteVisible"
        :group-id="gc.groupId"
        :exclude-ids="excludeIds"
        :max-slots="callStore.groupMaxSlots"
        title="邀请群成员加入通话"
        confirm-text="邀请"
        @confirm="onInviteConfirm"
      />
    </div>
  </transition>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Microphone, Mute, PhoneFilled, UserFilled, VideoCamera } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import GroupCallTile from '@/components/GroupCallTile.vue'
import GroupCallInviteDialog from '@/components/GroupCallInviteDialog.vue'
import { useGroupcallStore } from '@/stores/groupcall'
import { useCallStore } from '@/stores/call'
import { useGroupStore } from '@/stores/group'
import { useAuthStore } from '@/stores/auth'
import { asId } from '@/utils/id'
import { formatDuration } from '@/utils/format'

/**
 * 群通话浮层。挂在 MainLayout：切换会话、离开聊天页都不打断通话——状态在 store 里，浮层只是镜子。
 *
 * 网格列数由 data-count 决定（1/2/3/4 人各一套），人多时自动缩小格子：
 * mesh 下第 5 路已经接近家用宽带的上行极限，界面先把话说清楚，不要做得像还能再加。
 *
 * 一格都没画面是正常状态：语音通话本来就没有视频轨，视频通话要等对方摄像头开且流到达。
 * 每格都保留一个隐藏的 <video>，它是远端音频轨的载体，没有它语音通话就没有声音。
 */
const gc = useGroupcallStore()
const callStore = useCallStore()
const group = useGroupStore()
const auth = useAuthStore()

const inviteVisible = ref(false)

const isVideo = computed(() => gc.mediaType === 'video')
const mediaLabel = computed(() => (isVideo.value ? '视频通话' : '语音通话'))
const elapsedText = computed(() => formatDuration(gc.seconds))
const groupName = computed(() => group.groupOf(gc.groupId)?.name || '群聊')

/** 成员 ID → 群名片：花名册只带 ID，昵称与头像在本机的群成员列表里查（服务端不重复搬运） */
const memberInfo = computed(() => {
  const map = new Map()
  ;(group.members || []).forEach((m) => map.set(asId(m.userId), m))
  return map
})

function nameOf(uid) {
  const m = memberInfo.value.get(uid)
  return (m && (m.nicknameInGroup || m.displayName || m.nickname)) || '群成员'
}

function avatarOf(uid) {
  const m = memberInfo.value.get(uid)
  return (m && m.avatar) || ''
}

const inviter = computed(() => memberInfo.value.get(asId(gc.inviterUserId)))
const inviterName = computed(() => (asId(gc.inviterUserId) === asId(auth.userId) ? auth.nickname : nameOf(asId(gc.inviterUserId))))

/** 自己那一格 + 每个已到成员一格 */
const tiles = computed(() => {
  const list = [
    {
      userId: asId(auth.userId),
      name: auth.nickname || '我',
      avatar: auth.avatarRaw || '',
      stream: gc.localStream,
      muted: gc.muted,
      cameraOff: gc.cameraOff,
      state: 'connected',
      self: true
    }
  ]
  gc.members.forEach((m) => {
    list.push({
      userId: m.userId,
      name: nameOf(m.userId),
      avatar: avatarOf(m.userId),
      stream: gc.remoteStreams[m.userId] || null,
      muted: m.muted,
      cameraOff: m.cameraOff,
      // 语音通话没有画面，格子上的「连接中」全靠这一路 pc 的状态
      state: gc.peerStates[m.userId] || 'connecting',
      self: false
    })
  })
  return list
})

const waitingNames = computed(() => gc.pending.map((p) => nameOf(p.userId)))

const excludeIds = computed(() => [
  ...gc.members.map((m) => m.userId),
  ...gc.pending.map((p) => p.userId)
])

function onInviteConfirm(ids) {
  gc.invite(ids)
}

/**
 * 浮层可能在成员列表还没加载过时打开（比如从别的会话被邀）。
 * 昵称/头像查不到只会显示「群成员」，不影响通话本身，所以这里补一次拉取就够了，不做重试。
 */
watch(
  () => gc.visible,
  (val) => {
    if (val && gc.groupId && !group.members.length) {
      group.fetchMembers(gc.groupId, 1, 200).catch(() => {})
    }
  }
)
</script>

<style scoped>
.gc-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(20, 22, 26, 0.94);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.gc-fade-enter-active,
.gc-fade-leave-active {
  transition: opacity 0.24s ease;
}

.gc-fade-enter-from,
.gc-fade-leave-to {
  opacity: 0;
}

.gc-overlay__pending {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.gc-overlay__title {
  font-size: 18px;
  font-weight: 600;
}

.gc-overlay__sub {
  font-size: 13px;
  color: #c9cdd4;
}

.gc-overlay__actions {
  display: flex;
  align-items: center;
  gap: 48px;
  margin-top: 22px;
}

.gc-overlay__stage {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 72px 24px 130px;
  box-sizing: border-box;
}

.gc-overlay__topbar {
  position: absolute;
  top: 20px;
  left: 24px;
  right: 24px;
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 14px;
}

.gc-overlay__room {
  font-weight: 600;
  max-width: 40%;
}

.gc-overlay__count,
.gc-overlay__timer {
  color: #c9cdd4;
  font-variant-numeric: tabular-nums;
}

.gc-grid {
  display: grid;
  gap: 12px;
  width: 100%;
  max-width: 900px;
  margin: auto;
  grid-template-columns: repeat(1, minmax(0, 1fr));
}

/* 按人数换列数：2 人并排、3~4 人两列、5~6 人三列（6 是服务端硬上限） */
.gc-grid[data-count='2'] {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-width: 720px;
}

.gc-grid[data-count='3'],
.gc-grid[data-count='4'] {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.gc-grid[data-count='5'],
.gc-grid[data-count='6'] {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.gc-overlay__waiting {
  position: absolute;
  bottom: 148px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 13px;
  color: #e6a23c;
}

.gc-overlay__hint {
  font-size: 13px;
  color: #e6a23c;
  min-height: 18px;
  margin-top: 10px;
}

.gc-overlay__hint--stage {
  position: absolute;
  bottom: 116px;
  left: 50%;
  transform: translateX(-50%);
}

.gc-overlay__controls {
  position: absolute;
  bottom: 32px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 18px;
}

.gc-btn {
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

.gc-btn span {
  font-size: 11px;
}

.gc-btn--ghost {
  width: 60px;
  height: 60px;
}

.gc-btn--ghost:hover {
  background: rgba(255, 255, 255, 0.22);
}

.gc-btn--off {
  background: rgba(245, 108, 108, 0.55);
}

.gc-btn--accept,
.gc-btn--reject {
  width: 64px;
  height: 64px;
  border-radius: 50%;
}

/* 挂断那一格带文字，做成圆圈会把字挤出去，所以与上面的接听/拒绝不同形 */
.gc-btn--hangup {
  width: 60px;
  height: 60px;
  background: #e34d59;
}

.gc-btn--accept .el-icon,
.gc-btn--reject .el-icon {
  transform: rotate(135deg);
}

.gc-btn--accept:hover {
  background: #1c8f64;
}

.gc-btn--accept {
  background: #21a675;
}

.gc-btn--reject {
  background: #e34d59;
}

.gc-btn--reject:hover,
.gc-btn--hangup:hover {
  background: #c93f4a;
}
</style>
