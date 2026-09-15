<template>
  <!-- 系统消息与被撤回的消息不占气泡，居中一行灰字 -->
  <div v-if="isSystem || message.recalled" class="bubble bubble--plain">
    <span class="bubble__plain-text">{{ plainText }}</span>
  </div>

  <div v-else class="bubble" :class="{ 'bubble--self': message.self }" @contextmenu.prevent="emit('menu', $event)">
    <UserAvatar
      :src="message.self ? auth.avatarRaw : message.fromAvatar"
      :name="message.self ? auth.nickname : message.fromNickname"
      :size="36"
    />

    <div class="bubble__main">
      <!-- 群聊里需要区分是谁说的；单聊显示昵称纯属噪音 -->
      <div v-if="!message.self && showSender" class="bubble__sender im-ellipsis">{{ message.fromNickname }}</div>

      <div class="bubble__row">
        <!-- 自己的消息：状态显示在气泡左侧 -->
        <span v-if="message.self" class="bubble__status" :class="statusClass">
          <el-icon v-if="Number(message.status) === 0" class="is-loading"><Loading /></el-icon>
          <el-icon v-else-if="Number(message.status) === 5" class="bubble__status-fail"><WarningFilled /></el-icon>
          <template v-else>{{ readInfoText }}</template>
        </span>

        <div class="bubble__box" :class="boxClass">
          <!-- 文本 -->
          <div v-if="msgType === 1" class="bubble__text">{{ textContent }}</div>

          <!-- 图片 -->
          <el-image
            v-else-if="msgType === 2"
            class="im-msg-image"
            :src="imageUrl"
            :preview-src-list="settings.imagePreview && imageUrl ? [imageUrl] : []"
            :preview-teleported="true"
            :style="imageStyle"
            fit="cover"
            hide-on-click-modal
          >
            <template #placeholder>
              <div class="bubble__media-loading">
                <el-icon class="is-loading"><Loading /></el-icon>
              </div>
            </template>
            <template #error>
              <div class="bubble__media-loading">图片加载失败</div>
            </template>
          </el-image>

          <!-- 视频 -->
          <div v-else-if="isVideoFile" class="bubble__video">
            <video
              v-if="videoSrc"
              :src="videoSrc"
              controls
              :preload="settings.autoPlay ? 'metadata' : 'none'"
              :style="videoStyle"
            />
            <div v-else class="bubble__video-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span>视频加载中</span>
            </div>
          </div>

          <!-- 文件 -->
          <div v-else-if="msgType === 3" class="bubble__file" @click="onFileClick">
            <el-icon class="bubble__file-icon" :size="30"><Document v-if="!canPreview" /><View v-else /></el-icon>
            <div class="bubble__file-meta">
              <div class="bubble__file-name im-ellipsis">{{ fileName }}</div>
              <div class="bubble__file-size">{{ canPreview ? '点击预览' : fileSize }}</div>
            </div>
          </div>

          <!-- 语音 -->
          <div
            v-else-if="msgType === 4"
            class="bubble__voice"
            :class="{ 'bubble__voice--playing': playing }"
            :style="{ width: voiceWidth }"
            @click="toggleVoice"
          >
            <el-icon :size="16"><component :is="playing ? VideoPause : VideoPlay" /></el-icon>
            <span class="bubble__voice-duration">{{ voiceDuration }}</span>
          </div>

          <!-- 兜底：后端将来加了新类型而前端还没跟上时，至少把原文露出来 -->
          <div v-else class="bubble__text">{{ textContent }}</div>
        </div>

        <!-- 对方的消息：留白，让气泡靠左 -->
        <span v-if="!message.self" class="bubble__status"></span>
      </div>

      <!-- 发送失败的补救入口直接摊在气泡下面，藏进右键菜单会让人找不到 -->
      <div v-if="Number(message.status) === 5" class="bubble__retry">
        <el-button link type="primary" size="small" @click="emit('resend', message)">重发</el-button>
        <el-button link type="danger" size="small" @click="emit('discard', message)">删除</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Loading, View, VideoPause, VideoPlay, WarningFilled } from '@element-plus/icons-vue'
import UserAvatar from './UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { mediaUrl, downloadFile, isViewableText, isVideo } from '@/utils/media'
import { formatDuration, formatFileSize } from '@/utils/format'

/**
 * 单条消息的气泡。
 *
 * 只负责渲染与抛出意图（menu / resend / discard），撤回、删除、重发的实际调用
 * 由父组件统一处理 —— 那些操作要弹确认框、要维护右键菜单的可用性，
 * 放在这里会让每个气泡都各自持有一份菜单状态。
 */
const props = defineProps({
  message: { type: Object, required: true },
  /** 是否显示发送者昵称，群聊里为 true */
  showSender: { type: Boolean, default: false },
  /** 是否为群聊消息，用于展示已读人数 */
  isGroup: { type: Boolean, default: false }
})

const emit = defineEmits(['menu', 'resend', 'discard', 'view-file'])

const auth = useAuthStore()
const settings = useSettingsStore()

const msgType = computed(() => Number(props.message.msgType))
const isSystem = computed(() => msgType.value === 5)
const extra = computed(() => props.message.extra || {})

const plainText = computed(() => {
  if (props.message.recalled) {
    // 后端推来的 recall-notify 带 summary（「XX撤回了一条消息」），
    // 本地点撤回时 store 里存的是空串，这里兜一句通用文案
    return props.message.recallSummary || (props.message.self ? '你撤回了一条消息' : '对方撤回了一条消息')
  }
  return props.message.content || ''
})

/**
 * 文本内容。
 *
 * 附件类消息的 content 存的是文件 ID（服务端按文件记录回填 extra），
 * 万一渲染走到了文本分支，直接显示会在气泡里露出一串雪花数字，所以按类型改写。
 */
const textContent = computed(() => {
  if (msgType.value === 2) {
    return '[图片]'
  }
  if (msgType.value === 4) {
    return '[语音]'
  }
  if (msgType.value === 3) {
    return `[文件] ${extra.value.fileName || ''}`.trim()
  }
  const at = extra.value.atAll ? '@所有人 ' : ''
  return at + (props.message.content || '')
})

const boxClass = computed(() => ({
  'bubble__box--self': props.message.self,
  // 附件类气泡自带白底卡片，再套一层气泡底色会出现双重边框
  'bubble__box--bare': msgType.value === 2 || msgType.value === 3 || msgType.value === 4
}))

const statusClass = computed(() => ({
  'bubble__status--pending': Number(props.message.status) === 0,
  'bubble__status--fail': Number(props.message.status) === 5,
  'bubble__status--read': Number(props.message.status) === 3
}))

/**
 * 已读/未读状态文案。
 *
 * 单聊只展示状态文字（已发送 / 已送达 / 已读）；
 * 群聊在「已送达」与「已读」状态下额外显示已读人数，
 * 让用户直观看到有多少群成员看过了这条消息。
 */
const readInfoText = computed(() => {
  const status = Number(props.message.status)
  const readCount = Number(props.message.readCount) || 0
  if (status === 0) return ''
  if (status === 5) return ''
  if (status === 1) return '已发送'
  // 单聊关闭「已读回执」后，隐藏已送达 / 已读标记；群聊的已读人数不受此开关影响
  if (!props.isGroup && !settings.showReadReceipt) return ''
  if (status === 2) {
    if (props.isGroup && readCount > 0) return `${readCount} 人已读`
    return '已送达'
  }
  if (status === 3) {
    if (props.isGroup && readCount > 0) return `${readCount} 人已读`
    return '已读'
  }
  return props.message.statusDesc || ''
})

/* -------------------------------- 图片 -------------------------------- */

const imageUrl = computed(() => mediaUrl(extra.value.fileUrl))

/**
 * 先按 extra 里的原始尺寸占位。
 *
 * 后端不回填 width / height，这两个值是发送方上传前自己读出来塞进 extra 的。
 * 有就按比例缩到 240px 宽以内，没有就不设尺寸，让图片加载完自然撑开。
 */
const imageStyle = computed(() => {
  const width = Number(extra.value.width)
  const height = Number(extra.value.height)
  if (!width || !height) {
    return {}
  }
  const scale = Math.min(1, 240 / width)
  return { width: Math.round(width * scale) + 'px', height: Math.round(height * scale) + 'px' }
})

/* -------------------------------- 文件 -------------------------------- */

const fileName = computed(() => extra.value.fileName || '未命名文件')
const fileSize = computed(() => formatFileSize(extra.value.fileSize))

/** 文件是否属于可预览的文本类型，命中时点击打开预览弹窗而不是直接下载 */
const canPreview = computed(() => isViewableText(extra.value.fileName))

/** 文件是否属于视频类型，命中时渲染内联播放器而不是文件卡片 */
const isVideoFile = computed(() => msgType.value === 3 && isVideo(extra.value.fileName))

/** 视频 blob URL，由 mediaUrl 异步取回后响应式更新 */
const videoSrc = computed(() => (isVideoFile.value && extra.value.fileUrl) ? mediaUrl(extra.value.fileUrl) : '')

/**
 * 视频播放器尺寸。
 *
 * 优先使用发送方上传前读到的原始宽高按比例缩放到 320px 宽以内；
 * 没有元数据时不设尺寸，让视频加载完后自然撑开。
 */
const videoStyle = computed(() => {
  const width = Number(extra.value.width)
  const height = Number(extra.value.height)
  if (!width || !height) {
    return {}
  }
  const scale = Math.min(1, 320 / width)
  return { width: Math.round(width * scale) + 'px', height: Math.round(height * scale) + 'px' }
})

function onFileClick() {
  if (canPreview.value && extra.value.fileUrl) {
    emit('view-file', { fileUrl: extra.value.fileUrl, fileName: fileName.value })
  } else {
    onDownload()
  }
}

async function onDownload() {
  const url = extra.value.fileUrl
  if (!url) {
    ElMessage.warning('文件地址缺失，无法下载')
    return
  }
  try {
    await downloadFile(url, fileName.value)
  } catch {
    // downloadFile 内部走的是 request.js，失败提示已经弹过
  }
}

/* -------------------------------- 语音 -------------------------------- */

const playing = ref(false)
let audio = null

const voiceDuration = computed(() => formatDuration(extra.value.duration))

/**
 * 语音条宽度随时长变化，给出直观的长短差异。
 * 最短 72px 保证时长文字放得下，最长 200px 免得一条 60 秒的语音横穿整个窗口。
 */
const voiceWidth = computed(() => {
  const seconds = Number(extra.value.duration) || 0
  return Math.min(200, Math.max(72, 72 + seconds * 3)) + 'px'
})

async function toggleVoice() {
  if (playing.value) {
    audio?.pause()
    playing.value = false
    return
  }
  if (!audio) {
    // 语音地址是受控地址，必须带登录头取回 blob 才能交给 Audio 播放，
    // 直接塞 <audio src> 会因为拿不到凭证而失败
    const url = mediaUrl(extra.value.fileUrl)
    if (!url) {
      // mediaUrl 是懒加载的：第一次调用触发拉取并返回空串，稍后再点就有了
      ElMessage.info('语音加载中，请稍候再点一次')
      return
    }
    audio = new Audio(url)
    audio.addEventListener('ended', () => {
      playing.value = false
    })
    audio.addEventListener('error', () => {
      playing.value = false
      ElMessage.error('语音播放失败')
    })
  }
  try {
    await audio.play()
    playing.value = true
  } catch {
    // 浏览器可能因为缺少用户手势或格式不支持而拒绝播放
    playing.value = false
    ElMessage.error('语音播放失败，可能是不支持的音频格式')
  }
}

onBeforeUnmount(() => {
  if (audio) {
    audio.pause()
    audio = null
  }
})
</script>

<style scoped>
.bubble {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 16px;
}

.bubble--self {
  flex-direction: row-reverse;
}

.bubble--plain {
  justify-content: center;
  padding: 4px 16px;
}

.bubble__plain-text {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.bubble__main {
  max-width: min(560px, 62%);
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.bubble--self .bubble__main {
  align-items: flex-end;
}

.bubble__sender {
  max-width: 100%;
  margin-bottom: 3px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.bubble__row {
  display: flex;
  align-items: flex-end;
  gap: 6px;
}

.bubble--self .bubble__row {
  flex-direction: row-reverse;
}

.bubble__status {
  flex: none;
  display: flex;
  align-items: center;
  min-width: 44px;
  height: 20px;
  font-size: 11px;
  color: var(--im-text-secondary);
}

.bubble--self .bubble__status {
  justify-content: flex-end;
}

.bubble__status--fail {
  color: #f56c6c;
}

.bubble__status--read {
  color: #67c23a;
}

.bubble__status-fail {
  color: #f56c6c;
  font-size: 15px;
}

.bubble__box {
  position: relative;
  padding: 8px 12px;
  border-radius: var(--im-bubble-radius, var(--im-radius));
  background: var(--im-bubble-other);
  word-break: break-word;
}

.bubble__box--self {
  background: var(--im-bubble-self);
}

/* 附件类内容自带卡片外观，去掉气泡底色与内边距 */
.bubble__box--bare {
  padding: 0;
  background: transparent;
}

/* 气泡尖角：用伪元素画一个小三角，比引入图片资源省事 */
.bubble__box::before {
  content: '';
  position: absolute;
  top: 12px;
  left: -5px;
  border: 5px solid transparent;
  border-right-color: var(--im-bubble-other);
  border-left: 0;
}

.bubble__box--self::before {
  left: auto;
  right: -5px;
  border: 5px solid transparent;
  border-left-color: var(--im-bubble-self);
  border-right: 0;
}

.bubble__box--bare::before {
  display: none;
}

.bubble__text {
  font-size: calc(14px * var(--im-font-scale, 1));
  line-height: calc(22px * var(--im-font-scale, 1));
  /* 保留用户敲的换行与连续空格，但不保留 HTML —— 用的是插值而不是 v-html，天然免疫 XSS */
  white-space: pre-wrap;
}

.bubble__media-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 160px;
  height: 100px;
  font-size: 12px;
  color: var(--im-text-secondary);
  background: var(--im-bg);
  border-radius: 4px;
}

.bubble__file {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 200px;
  max-width: 280px;
  padding: 10px 12px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: 6px;
  cursor: pointer;
}

.bubble__file:hover {
  border-color: var(--im-primary);
}

.bubble__file-icon {
  flex: none;
  color: var(--im-primary);
}

.bubble__file-meta {
  min-width: 0;
}

.bubble__file-name {
  font-size: 13px;
  color: var(--im-text);
}

.bubble__file-size {
  margin-top: 2px;
  font-size: 11px;
  color: var(--im-text-secondary);
}

.bubble__voice {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: 16px;
  cursor: pointer;
}

.bubble__voice--playing {
  border-color: var(--im-primary);
  color: var(--im-primary);
}

.bubble__voice-duration {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.bubble__retry {
  display: flex;
  gap: 4px;
  margin-top: 2px;
}

.bubble__video {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 200px;
  min-height: 120px;
  border-radius: 6px;
  overflow: hidden;
  background: #000;
}

.bubble__video video {
  display: block;
  max-width: 320px;
  border-radius: 6px;
}

.bubble__video-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 200px;
  min-height: 120px;
  font-size: 12px;
  color: var(--im-text-secondary);
}
</style>
