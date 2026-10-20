<template>
  <!-- 系统消息与被撤回的消息不占气泡，居中一行灰字 -->
  <div v-if="isSystem || message.recalled" class="bubble bubble--plain">
    <span class="bubble__plain-text">{{ plainText }}</span>
  </div>

  <div
    v-else
    class="bubble"
    :class="{ 'bubble--self': message.self, 'bubble--selecting': selectable }"
    @click.capture="onBubbleClick"
    @contextmenu.prevent="emit('menu', $event)"
  >
    <span v-if="selectable" class="bubble__check" :class="{ 'is-checked': selected }">
      <el-icon v-if="selected"><Select /></el-icon>
    </span>
    <UserAvatar
      :src="message.self ? auth.avatarRaw : message.fromAvatar"
      :name="message.self ? auth.nickname : message.fromNickname"
      :size="36"
    />

    <div class="bubble__main">
      <!-- 群聊里需要区分是谁说的；单聊显示昵称纯属噪音。备注优先：别人在我这里的显示名由我定 -->
      <div v-if="!message.self && showSender" class="bubble__sender im-ellipsis">
        {{ senderDisplayName }}
        <span v-if="isRobot" class="bubble__robot-tag">机器人</span>
      </div>

      <!-- 引用预览块：显示在气泡上方，点击可跳转到原消息 -->
      <div
        v-if="message.quote"
        class="bubble__quote"
        @click.stop="emit('jump-quote', message.quote.messageId)"
      >
        <span class="bubble__quote-nick">回复 {{ quoteDisplayName }}</span>
        <span class="bubble__quote-text im-ellipsis">{{ quoteContent }}</span>
      </div>

      <!-- 消息角标行：群精华（群主/管理员标记）与收藏（本人私有标记）可能同时存在，共用一行左右排 -->
      <div v-if="message.essence || message.favorite" class="bubble__flags">
        <div v-if="message.essence" class="bubble__essence">★ 群精华</div>
        <!-- 收藏是自己给的私人书签，只在本端可见（后端按 user_id 回填），不会推给聊天对象 -->
        <div v-if="message.favorite" class="bubble__favorite">★ 已收藏</div>
      </div>

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
          <div v-else-if="msgType === 2" class="bubble__image-wrap" @click="onImageClick">
            <!-- 闪照：拆封前不露任何像素（连预取都不做），销毁后只剩占位块 -->
            <div v-if="isFlash" class="bubble__flash" :class="{ 'bubble__flash--viewed': flashViewed }">
              <el-icon :size="20"><Delete v-if="flashViewed" /><Picture v-else /></el-icon>
              <span>{{ flashViewed ? '闪照已销毁' : '闪照 · 点击查看（仅限一次）' }}</span>
            </div>
            <template v-else>
              <el-image
                class="im-msg-image"
                :src="imageUrl"
                :preview-src-list="imagePreviewList"
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
              <WatermarkOverlay v-if="attachWatermark && senderWatermarkText" :text="senderWatermarkText" />
            </template>
          </div>

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
            <WatermarkOverlay v-if="attachWatermark && senderWatermarkText" :text="senderWatermarkText" />
          </div>

          <!-- 文件 -->
          <div v-else-if="msgType === 3" class="bubble__file" @click="onFileClick">
            <el-icon class="bubble__file-icon" :size="30"><Document v-if="!canPreview" /><View v-else /></el-icon>
            <div class="bubble__file-meta">
              <div class="bubble__file-name im-ellipsis">{{ fileName }}</div>
              <div class="bubble__file-size">
                {{ canPreview ? '点击预览' : fileSize }}
                <span v-if="attachWatermark" class="bubble__file-wm">· 带水印</span>
              </div>
              <!-- 高风险格式常驻警示（需求 2）：传输不拦，但接收方得知道自己拿到的是什么 -->
              <div v-if="riskCategory" class="bubble__file-risk">
                <el-icon :size="12"><WarningFilled /></el-icon>
                <span>{{ riskCategory.label }}，请确认来源可信</span>
              </div>
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

          <!-- 通话记录：类型图标 + 时长；已录制则可回放 -->
          <div v-else-if="msgType === 6" class="bubble__call">
            <el-icon :size="16"><component :is="callIsVideo ? VideoCameraFilled : PhoneFilled" /></el-icon>
            <span class="bubble__call-label">{{ callLabel }}</span>
            <span class="bubble__call-duration">{{ callDurationText }}</span>
            <!-- 已录制：视频内联播放器 / 音频点击播放 -->
            <video
              v-if="callPlayableVideo"
              :src="callVideoSrc"
              controls
              preload="metadata"
              class="bubble__call-video"
            />
            <button
              v-else-if="callHasRecording"
              class="bubble__call-play"
              :class="{ 'is-playing': callPlaying }"
              title="回放通话录音"
              @click="toggleCallAudio"
            >
              <el-icon :size="14"><component :is="callPlaying ? VideoPause : VideoPlay" /></el-icon>
            </button>
          </div>

          <!-- 名片：头像 + 昵称快照，点击打开对方资料页 -->
          <div
            v-else-if="msgType === 7"
            class="bubble__card"
            :class="{ 'is-disabled': !extra.cardUserId }"
            @click="onCardClick"
          >
            <UserAvatar :src="extra.cardAvatar" :name="cardName" :size="40" />
            <div class="bubble__card-meta">
              <div class="bubble__card-name im-ellipsis">{{ cardName }}</div>
              <div class="bubble__card-hint">个人名片 · 点击查看</div>
            </div>
          </div>

          <!-- 位置：地图瓦片缩略图 + 文字地址，点击在外部地图打开 -->
          <div v-else-if="msgType === 8" class="bubble__location" @click="onLocationClick">
            <div class="bubble__location-map">
              <img v-if="mapTileSrc" :src="mapTileSrc" class="bubble__location-tile" alt="地图" />
              <div v-else class="bubble__location-tile bubble__location-tile--empty">地图不可用</div>
              <div class="bubble__location-pin">📍</div>
            </div>
            <div class="bubble__location-meta">
              <div class="bubble__location-name im-ellipsis">{{ locationName }}</div>
              <div class="bubble__location-coord">{{ locationCoord }}</div>
            </div>
          </div>

          <!-- 合并转发（聊天记录）：卡片，点击展开查看器 -->
          <div v-else-if="msgType === 9" class="bubble__merged" @click="onMergedClick">
            <el-icon class="bubble__merged-icon" :size="22"><ChatLineSquare /></el-icon>
            <div class="bubble__merged-meta">
              <div class="bubble__merged-title im-ellipsis">{{ mergedTitle }}</div>
              <div class="bubble__merged-hint">聊天记录 · 共 {{ mergedCount }} 条 · 点击查看</div>
            </div>
          </div>

          <!-- 投票：标题 + 选项（票数条），单选即时提交 / 多选勾选后提交 -->
          <div v-else-if="msgType === 10" class="bubble__vote">
            <div class="bubble__vote-head">
              <span class="bubble__vote-title">{{ voteTitle }}</span>
              <span class="bubble__vote-tag">{{ voteMultiple ? '多选' : '单选' }}{{ voteAnonymous ? ' · 匿名' : '' }}</span>
            </div>
            <div class="bubble__vote-options">
              <div
                v-for="o in voteOptions"
                :key="o.index"
                class="bubble__vote-option"
                :class="{ 'is-picked': voteMultiple ? votePick.has(o.index) : o.selfVoted, 'is-disabled': voteClosed }"
                @click="onOptionClick(o)"
              >
                <div class="bubble__vote-bar" :style="{ width: votePercent(o) + '%' }"></div>
                <div class="bubble__vote-line">
                  <span class="bubble__vote-mark">{{ (voteMultiple ? votePick.has(o.index) : o.selfVoted) ? (voteMultiple ? '☑' : '●') : (voteMultiple ? '☐' : '○') }}</span>
                  <span class="bubble__vote-text im-ellipsis">{{ o.text }}</span>
                  <span class="bubble__vote-count">{{ o.count }}</span>
                </div>
              </div>
            </div>
            <div class="bubble__vote-foot">
              <span class="bubble__vote-total">共 {{ voteTotal }} 人参与{{ voteClosed ? ' · 已截止' : '' }}</span>
              <span class="bubble__vote-spacer"></span>
              <el-button v-if="voteMultiple && !voteClosed" link type="primary" size="small" :disabled="!votePick.size" @click="submitMultiVote">提交</el-button>
              <el-button v-if="message.self && !voteClosed" link size="small" @click="onCloseVote">截止</el-button>
            </div>
          </div>

          <!-- 接龙（14）：有序条目列表，成员点「参与接龙」把自己那条追到末尾（序号=列表位置，服务端定） -->
          <div v-else-if="msgType === 14" class="bubble__chain">
            <div class="bubble__chain-head">
              <span class="bubble__chain-title">{{ chainTitle }}</span>
              <span class="bubble__chain-tag">{{ chainItems.length }} 条{{ chainClosed ? ' · 已截止' : '' }}</span>
            </div>
            <ol v-if="chainItems.length" class="bubble__chain-list">
              <li v-for="(it, idx) in chainItems" :key="idx" class="bubble__chain-item">
                <span class="bubble__chain-no">{{ idx + 1 }}.</span>
                <span class="bubble__chain-name">{{ it.nickname || '匿名' }}</span>
                <span class="bubble__chain-text">{{ it.text }}</span>
              </li>
            </ol>
            <div v-else class="bubble__chain-empty">还没有人接龙，来当第 1 个</div>
            <div class="bubble__chain-foot">
              <span class="bubble__chain-spacer"></span>
              <el-button v-if="!chainClosed" link type="primary" size="small" @click="onChainJoin">参与接龙</el-button>
              <el-button v-if="message.self && !chainClosed" link size="small" @click="onCloseChain">结束接龙</el-button>
            </div>
          </div>

          <!-- 转账（12）：金额与附言。即时到账，所以没有「点一下领取」这一步，只把结论写清楚 -->
          <div v-else-if="msgType === 12" class="bubble__money">
            <el-icon class="bubble__money-icon" :size="26"><Coin /></el-icon>
            <div class="bubble__money-body">
              <div class="bubble__money-amount">{{ transferAmount }} 积分</div>
              <div class="bubble__money-memo im-ellipsis">{{ transferMemo }}</div>
              <div class="bubble__money-hint">{{ transferHint }}</div>
            </div>
          </div>

          <!-- 红包（13）：点开才去取领取态，气泡本身只带 packetId 这个寻址钩子 -->
          <div
            v-else-if="msgType === 13"
            class="bubble__packet"
            :class="{ 'is-opened': packetOpened }"
            @click="onPacketClick"
          >
            <el-icon class="bubble__packet-icon" :size="26"><Present /></el-icon>
            <div class="bubble__packet-body">
              <div class="bubble__packet-greeting im-ellipsis">{{ packetGreeting }}</div>
              <div class="bubble__packet-hint">{{ packetHint }}</div>
            </div>
          </div>

          <!-- 兜底：后端将来加了新类型而前端还没跟上时，至少把原文露出来 -->
          <div v-else class="bubble__text">{{ textContent }}</div>

          <!-- 已编辑标记：放在整条 v-if/v-else 链之后当独立节点，不能插在链中间否则会打断条件渲染 -->
          <span v-if="msgType === 1 && message.extra && message.extra.edited" class="bubble__edited">已编辑</span>
        </div>

        <!-- 对方的消息：留白，让气泡靠左 -->
        <span v-if="!message.self" class="bubble__status"></span>
      </div>

      <!-- 发送失败的补救入口直接摊在气泡下面，藏进右键菜单会让人找不到 -->
      <div v-if="Number(message.status) === 5" class="bubble__retry">
        <el-button link type="primary" size="small" @click="emit('resend', message)">重发</el-button>
        <el-button link type="danger" size="small" @click="emit('discard', message)">删除</el-button>
      </div>

      <!-- 表情回应栏：有回应才占位；点 chip——自己贴过的再点取消，否则追加同款 -->
      <div v-if="reactions.length" class="bubble__reactions">
        <span
          v-for="r in reactions"
          :key="r.emoji"
          class="bubble__reaction"
          :class="{ 'is-self': r.self }"
          @click.stop="toggleReaction(r)"
        >{{ r.emoji }} <b>{{ r.count }}</b></span>
      </div>

      <!-- 悬停浮现的快捷回应栏（仅可回应时）：纯 CSS hover，桌面端主用 -->
      <div v-if="canReact" class="bubble__quick">
        <span
          v-for="e in quickEmojis"
          :key="e"
          class="bubble__quick-emoji"
          @click.stop="pickReaction(e)"
        >{{ e }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, Loading, Picture, View, VideoPause, VideoPlay, VideoCameraFilled, WarningFilled, PhoneFilled, Select, ChatLineSquare, Coin, Present } from '@element-plus/icons-vue'
import UserAvatar from './UserAvatar.vue'
import WatermarkOverlay from './WatermarkOverlay.vue'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { useFriendStore } from '@/stores/friend'
import { mediaUrl, downloadFile, isPreviewableFile, isVideo } from '@/utils/media'
import { riskCategoryOf } from '@/utils/riskFile'
import { formatDuration, formatFileSize } from '@/utils/format'
import { tileUrl, formatCoord } from '@/utils/geo'

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
  isGroup: { type: Boolean, default: false },
  /** 多选模式：为每条消息渲染勾选框、点气泡即切换选中（不再触发预览/菜单） */
  selectable: { type: Boolean, default: false },
  /** 多选模式下本条是否已选中 */
  selected: { type: Boolean, default: false }
})

const emit = defineEmits(['menu', 'resend', 'discard', 'view-file', 'view-image', 'view-flash', 'jump-quote', 'react', 'view-card', 'open-location', 'view-merged', 'toggle-select', 'vote', 'close-vote', 'chain', 'close-chain', 'open-packet'])

const auth = useAuthStore()
const settings = useSettingsStore()
const friends = useFriendStore()

const msgType = computed(() => Number(props.message.msgType))
const isSystem = computed(() => msgType.value === 5)
const extra = computed(() => props.message.extra || {})
// 机器人消息：类型 11，或 extra 里带机器人名称快照（接收端据此把发送者标为「机器人」）
const isRobot = computed(() => msgType.value === 11 || !!extra.value.robotName)

/* -------------------------------- 名片 -------------------------------- */
const cardName = computed(() => extra.value.cardNickname || '联系人')
function onCardClick() {
  if (extra.value.cardUserId) {
    emit('view-card', extra.value.cardUserId)
  }
}

/* -------------------------------- 位置 -------------------------------- */
const hasCoord = computed(() =>
  Number.isFinite(Number(extra.value.latitude)) && Number.isFinite(Number(extra.value.longitude))
)
const mapTileSrc = computed(() => (hasCoord.value ? tileUrl(extra.value.latitude, extra.value.longitude) : ''))
const locationName = computed(() => extra.value.locationName || '位置')
const locationCoord = computed(() =>
  hasCoord.value ? formatCoord(extra.value.latitude, extra.value.longitude) : ''
)
function onLocationClick() {
  if (hasCoord.value) {
    emit('open-location', { latitude: Number(extra.value.latitude), longitude: Number(extra.value.longitude) })
  }
}

/* ------------------------------ 合并转发 ------------------------------ */
const mergedTitle = computed(() => extra.value.mergedTitle || '聊天记录')
const mergedCount = computed(() => (Array.isArray(extra.value.mergedMessages) ? extra.value.mergedMessages.length : 0))
function onMergedClick() {
  emit('view-merged', { title: mergedTitle.value, messages: extra.value.mergedMessages || [] })
}

/* -------------------------------- 转账 / 红包 -------------------------------- */
// 金额与份数都是发出时定格的快照（服务端在扣款成功后写进 extra），前端不参与任何资金计算
const transferAmount = computed(() => Number(extra.value.transferAmount || 0))
const transferMemo = computed(() => extra.value.transferMemo || '转账给好友')
// 付款方与收款方看到的结论不同，但都是「已到」：单聊转账没有待领取环节
const transferHint = computed(() => {
  if (props.message.recalled) return '已撤回'
  return props.message.self ? `已转出给 ${extra.value.transferToNickname || '对方'}` : '已到账'
})

const packetId = computed(() => extra.value.packetId || '')
const packetCount = computed(() => Number(extra.value.packetCount || 1))
const packetGreeting = computed(() => extra.value.packetGreeting || '恭喜发财，大吉大利')
// 本端是否已经点开过：只是本地视觉状态，领取结论一律以服务端返回的 status 为准
const packetOpened = ref(false)
const packetHint = computed(() => {
  if (props.message.recalled) return '已撤回'
  if (packetOpened.value) return '已查看，可再看一次'
  return packetCount.value > 1 ? `拼手气红包 · 共 ${packetCount.value} 份` : '专属红包 · 点击拆开'
})
function onPacketClick() {
  if (!packetId.value || props.message.recalled) return
  packetOpened.value = true
  // 把整条消息一起交给外层：详情弹窗需要「来自谁」，而 sender 在消息上而不在 packetId 里
  emit('open-packet', { packetId: packetId.value, message: props.message })
}

/* -------------------------------- 投票 -------------------------------- */
const voteView = computed(() => props.message.vote || null)
const voteTitle = computed(() => voteView.value?.title || extra.value.voteTitle || '投票')
const voteMultiple = computed(() => (voteView.value ? voteView.value.multiple === true : extra.value.voteMultiple === true))
const voteAnonymous = computed(() => (voteView.value ? voteView.value.anonymous === true : extra.value.voteAnonymous === true))
const voteClosed = computed(() => (voteView.value ? voteView.value.closed === true : extra.value.voteClosed === true))
// 优先用服务端聚合的 vote（含票数）；新推送的投票只有 extra 定义时，票数从 0 起算
const voteOptions = computed(() => {
  if (voteView.value && Array.isArray(voteView.value.options)) {
    return voteView.value.options
  }
  const texts = Array.isArray(extra.value.voteOptions) ? extra.value.voteOptions : []
  return texts.map((text, index) => ({ index, text, count: 0, selfVoted: false }))
})
const voteTotal = computed(() => {
  if (voteView.value && Number.isFinite(Number(voteView.value.totalVoters))) {
    return Number(voteView.value.totalVoters)
  }
  return voteOptions.value.reduce((sum, o) => sum + (Number(o.count) || 0), 0)
})
/** 多选模式下的本地待提交选择；单选直接即时提交 */
const votePick = ref(new Set())
// 服务端刷新 vote 后同步本地选择（取本人已选）
watch(
  () => voteOptions.value,
  (opts) => {
    const next = new Set()
    opts.forEach((o) => {
      if (o.selfVoted) next.add(o.index)
    })
    votePick.value = next
  },
  { immediate: true }
)
function votePercent(o) {
  const total = voteTotal.value
  if (!total) return 0
  return Math.round(((Number(o.count) || 0) / total) * 100)
}
function onOptionClick(o) {
  if (voteClosed.value) {
    return
  }
  if (voteMultiple.value) {
    const next = new Set(votePick.value)
    if (next.has(o.index)) {
      next.delete(o.index)
    } else {
      next.add(o.index)
    }
    votePick.value = next
  } else {
    // 单选：点即提交（再点同一项不取消，避免误操作；要改选直接点另一项）
    if (!o.selfVoted) {
      emit('vote', { message: props.message, options: [o.index] })
    }
  }
}
function submitMultiVote() {
  if (!votePick.value.size) {
    return
  }
  emit('vote', { message: props.message, options: [...votePick.value] })
}
function onCloseVote() {
  emit('close-vote', props.message)
}

/* -------------------------------- 接龙 -------------------------------- */
// 接龙的条目就住在 extra.chainItems 里（服务端 write 后整份推回），无需像投票那样另往 message 上挂一个聚合视图
const chainTitle = computed(() => extra.value.chainTitle || '接龙')
const chainItems = computed(() => (Array.isArray(extra.value.chainItems) ? extra.value.chainItems : []))
const chainClosed = computed(() => extra.value.chainClosed === true)
function onChainJoin() {
  emit('chain', props.message)
}
function onCloseChain() {
  emit('close-chain', props.message)
}

/**
 * 多选模式下的点选拦截：在捕获阶段抢在内部媒体/菜单处理器之前接管，
 * 点整行即切换选中，避免选中时误触图片预览、文件下载。
 */
function onBubbleClick(event) {
  if (props.selectable) {
    event.stopPropagation()
    event.preventDefault()
    emit('toggle-select', props.message)
  }
}

/** 本条附件是否带发送者水印（发送时选择，随 message.extra 传递到接收端） */
const attachWatermark = computed(() => extra.value.watermark === true)
/**
 * 附件预览水印文字：直接用消息自带的发送者昵称——它是服务端权威数据，
 * 无需客户端自填一段可被伪造的水印文案。
 */
const senderWatermarkText = computed(
  () => props.message.fromNickname || (props.message.self ? auth.nickname : '')
)

/** 本条图片是否为闪照（拆封前不渲染像素，看过一次即销毁） */
const isFlash = computed(() => msgType.value === 2 && extra.value.flash === true)
const flashViewed = computed(() => isFlash.value && extra.value.flashViewed === true)

/* -------------------------------- 表情回应 -------------------------------- */

/** 快捷回应表情集（悬停气泡时浮现）；与右键无关，点一下即贴 */
const quickEmojis = ['👍', '❤️', '😄', '😢', '🎉', '🔥']
/** 已撤回与系统消息不提供回应入口 */
const canReact = computed(() => !props.message.recalled && !isSystem.value && !!props.message.messageId)
const reactions = computed(() => (Array.isArray(props.message.reactions) ? props.message.reactions : []))

/** 点已有表情 chip：自己贴过的再点为取消，否则追加同款 */
function toggleReaction(item) {
  if (!canReact.value) {
    return
  }
  emit('react', { message: props.message, emoji: item.emoji, remove: item.self === true })
}

/** 从快捷栏选一个表情贴上 */
function pickReaction(emoji) {
  if (!canReact.value) {
    return
  }
  emit('react', { message: props.message, emoji, remove: false })
}

/**
 * 群聊发送者显示名：好友备注优先，其次服务端给的昵称。
 *
 * 备注是「别人在我这里的显示名」，存在好友关系里、只影响本机展示；
 * 非好友（群里的陌生人）friendOf 查不到，自然回退到昵称。
 */
const senderDisplayName = computed(() => {
  const friend = props.message.fromUserId != null ? friends.friendOf(props.message.fromUserId) : null
  return (friend && friend.remark) || props.message.fromNickname
})

/** 引用块的发送者名字同样吃备注覆盖 */
const quoteDisplayName = computed(() => {
  const q = props.message.quote
  if (!q) {
    return ''
  }
  const friend = q.fromUserId != null ? friends.friendOf(q.fromUserId) : null
  return (friend && friend.remark) || q.fromNickname
})

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
    return isFlash.value ? '[闪照]' : '[图片]'
  }
  if (msgType.value === 4) {
    return '[语音]'
  }
  if (msgType.value === 6) {
    return callLabel.value
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
  'bubble__box--bare': msgType.value === 2 || msgType.value === 3 || msgType.value === 4 || msgType.value === 6 || msgType.value === 7 || msgType.value === 8 || msgType.value === 9 || msgType.value === 10
}))

/**
 * 引用块的内容摘要。
 *
 * 附件类消息的 content 是文件 ID，直接显示会露出一串雪花数字；
 * 已撤回的消息用占位文字，否则撤回功能形同虚设。
 */
const quoteContent = computed(() => {
  const q = props.message.quote
  if (!q) return ''
  if (q.recalled) return '[引用内容已撤回]'
  const type = Number(q.msgType)
  if (type === 2) return '[图片]'
  if (type === 3) return '[文件]'
  if (type === 4) return '[语音]'
  if (type === 6) return '[通话]'
  return q.content || ''
})

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
 * 图片放大预览列表。
 *
 * 带水印时故意置空，禁用 el-image 内置灯箱（它只显示 <img>，盖不上水印层），
 * 改由 onImageClick 走自绘弹窗；不带水印时维持原有内置预览。
 */
const imagePreviewList = computed(() => {
  if (attachWatermark.value) {
    return []
  }
  return settings.imagePreview && imageUrl.value ? [imageUrl.value] : []
})

function onImageClick() {
  // 闪照不在气泡里拆：把意图抛给父组件走「取回字节 -> 上报销毁 -> 倒计时预览」的完整流程
  if (isFlash.value) {
    if (!flashViewed.value) {
      emit('view-flash', props.message)
    }
    return
  }
  if (attachWatermark.value && imageUrl.value) {
    emit('view-image', { url: imageUrl.value, watermark: senderWatermarkText.value })
  }
}

/**
 * 先按 extra 里的原始尺寸占位。
 *
 * 后端不回填 width / height，这两个值是发送方上传前自己读出来塞进 extra 的。
 * 有就按比例缩到 320px 宽以内，没有就不设尺寸，让图片加载完自然撑开。
 */
const imageStyle = computed(() => {
  const width = Number(extra.value.width)
  const height = Number(extra.value.height)
  if (!width || !height) {
    return {}
  }
  const scale = Math.min(1, 320 / width)
  return { width: Math.round(width * scale) + 'px', height: Math.round(height * scale) + 'px' }
})

/* -------------------------------- 文件 -------------------------------- */

const fileName = computed(() => extra.value.fileName || '未命名文件')
const fileSize = computed(() => formatFileSize(extra.value.fileSize))

/** 文件是否属于可预览的文本类型，命中时点击打开预览弹窗而不是直接下载 */
const canPreview = computed(() => isPreviewableFile(extra.value.fileName))

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
    emit('view-file', {
      fileUrl: extra.value.fileUrl,
      fileName: fileName.value,
      fileSize: extra.value.fileSize,
      watermark: attachWatermark.value ? senderWatermarkText.value : ''
    })
  } else {
    onDownload()
  }
}

/**
 * 下载前对高风险格式再确认一次（需求 2）。
 *
 * 发送方的确认发生在选完文件时，接收方却没有这道工序：气泡上那行小字容易被忽略，
 * 而「双击一个来路不明的 apk」才是真正的事故现场。菜单里的「下载」也走这里，
 * 两个入口共用一份拦截。
 */
async function confirmRiskDownload() {
  if (!riskCategory.value) {
    return true
  }
  try {
    await ElMessageBox.confirm(
      `${fileName.value} 属于「${riskCategory.value.label}」。${riskCategory.value.hint}。仍要下载？`,
      '高风险文件提醒',
      { confirmButtonText: '仍要下载', cancelButtonText: '取消', type: 'warning' }
    )
    return true
  } catch {
    return false
  }
}

/** 命中的风险分类，普通文件为 null */
const riskCategory = computed(() => riskCategoryOf(extra.value.fileName))

async function onDownload() {
  const url = extra.value.fileUrl
  if (!url) {
    ElMessage.warning('文件地址缺失，无法下载')
    return
  }
  if (!(await confirmRiskDownload())) {
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

/* -------------------------------- 通话记录 -------------------------------- */

const callIsVideo = computed(() => extra.value.callMediaType === 'video')
/**
 * 参与人数：只有群通话会写 extra.callParticipantCount（1:1 没这个字段），
 * 所以「有值就是群通话」，不用再另加一个类型标记。旧记录无此字段，自然回落成 1:1 文案。
 */
const callParticipantCount = computed(() => Number(extra.value.callParticipantCount) || 0)
const callLabel = computed(() => {
  const base = callIsVideo.value ? '视频通话' : '语音通话'
  return callParticipantCount.value > 0 ? `群${base} · ${callParticipantCount.value} 人` : base
})
const callDurationText = computed(() => {
  const sec = Number(extra.value.duration) || 0
  return sec > 0 ? formatDuration(sec) : '未接通'
})
/** 本次通话是否留有录制文件（fileUrl 存在才能回放） */
const callHasRecording = computed(() => msgType.value === 6 && !!extra.value.fileUrl)
/** 录制是否为可内联播放的视频（webm/mp4 等视频容器） */
const callPlayableVideo = computed(() => callHasRecording.value && isVideo(extra.value.fileName || 'x.webm'))
const callVideoSrc = computed(() => (callPlayableVideo.value ? mediaUrl(extra.value.fileUrl) : ''))

const callPlaying = ref(false)
let callAudio = null

async function toggleCallAudio() {
  if (callPlaying.value) {
    callAudio?.pause()
    callPlaying.value = false
    return
  }
  if (!callAudio) {
    const url = mediaUrl(extra.value.fileUrl)
    if (!url) {
      ElMessage.info('录音加载中，请稍候再点一次')
      return
    }
    callAudio = new Audio(url)
    callAudio.addEventListener('ended', () => {
      callPlaying.value = false
    })
    callAudio.addEventListener('error', () => {
      callPlaying.value = false
      ElMessage.error('录音播放失败')
    })
  }
  try {
    await callAudio.play()
    callPlaying.value = true
  } catch {
    callPlaying.value = false
    ElMessage.error('录音播放失败，可能是不支持的音频格式')
  }
}

onBeforeUnmount(() => {
  if (audio) {
    audio.pause()
    audio = null
  }
  if (callAudio) {
    callAudio.pause()
    callAudio = null
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
  /* 需求 7：气泡宽度增大 1/3（560→745，62%→83%），长文本少折几行 */
  max-width: min(745px, 83%);
  min-width: 0;
  display: flex;
  flex-direction: column;
}

/* 手机端放宽气泡：窄屏下比例上限会把一句话说成三行，可读性太差 */
@media (max-width: 768px) {
  .bubble__main {
    max-width: min(745px, 92%);
  }
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

.bubble__robot-tag {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  font-size: 10px;
  line-height: 16px;
  color: #13a86b;
  background: #e8f8f1;
  border: 1px solid #b7ecd4;
  border-radius: 8px;
  vertical-align: middle;
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

/* 名片：头像 + 昵称的横排卡片，整块可点 */
.bubble__card {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 180px;
  max-width: 240px;
  padding: 4px 2px;
  cursor: pointer;
}

.bubble__card.is-disabled {
  cursor: default;
}

.bubble__card-meta {
  min-width: 0;
  flex: 1;
}

.bubble__card-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.bubble__card-hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/* 位置：地图缩略图 + 地址的竖排卡片 */
.bubble__location {
  width: 220px;
  cursor: pointer;
}

.bubble__location-map {
  position: relative;
  width: 100%;
  height: 110px;
  border-radius: 6px;
  overflow: hidden;
  background: #e8ecf0;
}

.bubble__location-tile {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.bubble__location-tile--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.bubble__location-pin {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -100%);
  font-size: 22px;
  line-height: 1;
}

.bubble__location-meta {
  margin-top: 6px;
}

.bubble__location-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.bubble__location-coord {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.bubble__edited {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.2;
  opacity: 0.55;
  text-align: right;
}

.bubble__reactions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.bubble__reaction {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 1px 8px;
  font-size: 12px;
  line-height: 18px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 10px;
  background: var(--el-fill-color-blank, #fff);
  cursor: pointer;
  user-select: none;
}

.bubble__reaction.is-self {
  border-color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.bubble__reaction b {
  font-weight: 600;
}

.bubble__quick {
  display: flex;
  gap: 2px;
  margin-top: 4px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}

.bubble:hover .bubble__quick {
  opacity: 1;
  pointer-events: auto;
}

.bubble__quick-emoji {
  padding: 0 4px;
  font-size: 16px;
  line-height: 22px;
  border-radius: 6px;
  cursor: pointer;
  user-select: none;
}

.bubble__quick-emoji:hover {
  background: var(--el-fill-color-light, #f0f2f5);
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
  /* 与图片显示框 1/3 增幅同步（160×100 → 213×133） */
  width: 213px;
  height: 133px;
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

.bubble__file-risk {
  display: flex;
  align-items: center;
  gap: 3px;
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.4;
  color: #e6a23c;
}

.bubble__image-wrap {
  position: relative;
  display: inline-block;
  line-height: 0;
  border-radius: 6px;
  overflow: hidden;
}

/* 闪照占位块：尺寸与普通图片加载框一致，未销毁前可点、销毁后置灰 */
.bubble__flash {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 213px;
  height: 133px;
  padding: 0 12px;
  font-size: 12px;
  line-height: 1.4;
  text-align: center;
  color: var(--im-text-secondary);
  background: var(--im-panel);
  border: 1px dashed var(--im-border);
  border-radius: 6px;
  cursor: pointer;
}

.bubble__flash--viewed {
  cursor: default;
  opacity: 0.7;
}

.bubble__file-wm {
  color: var(--im-primary);
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

/* 通话记录：图标 + 类型 + 时长；若留有录制则附带回放入口 */
.bubble__call {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: 12px;
  color: var(--im-primary);
}

.bubble__call-label {
  font-size: 13px;
  color: var(--im-text-primary);
}

.bubble__call-duration {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.bubble__call-video {
  width: 100%;
  max-width: 260px;
  margin-top: 6px;
  border-radius: 8px;
  background: #000;
}

.bubble__call-play {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--im-primary);
  color: #fff;
  cursor: pointer;
}

.bubble__call-play.is-playing {
  background: var(--im-text-secondary);
}

.bubble__retry {
  display: flex;
  gap: 4px;
  margin-top: 2px;
}

.bubble__quote {
  display: flex;
  flex-direction: column;
  gap: 1px;
  max-width: 100%;
  margin-bottom: 4px;
  padding: 4px 8px;
  border-left: 3px solid var(--im-primary, #409eff);
  border-radius: 0 4px 4px 0;
  background: rgba(0, 0, 0, 0.04);
  cursor: pointer;
  font-size: 12px;
  line-height: 1.4;
}

.bubble__quote:hover {
  background: rgba(0, 0, 0, 0.08);
}

.bubble__quote-nick {
  color: var(--im-primary, #409eff);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bubble__quote-text {
  color: var(--im-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bubble__flags {
  display: flex;
  gap: 6px;
  align-self: flex-start;
  margin-bottom: 3px;
  width: fit-content;
}

/* 自己的消息靠右，角标行跟着右对齐（原 .bubble__essence 的 margin-left:auto 由这里统一承担） */
.bubble--self .bubble__flags {
  align-self: flex-end;
}

.bubble__essence {
  padding: 1px 8px;
  font-size: 11px;
  line-height: 1.5;
  color: #d48806;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 10px;
  width: fit-content;
}

.bubble__favorite {
  padding: 1px 8px;
  font-size: 11px;
  line-height: 1.5;
  color: #d4380d;
  background: #fff2e8;
  border: 1px solid #ffbb96;
  border-radius: 10px;
  width: fit-content;
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

/* 合并转发卡片：聊天气泡式白底块 + 徒章图标 + 标题/条数 */
.bubble__merged {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 180px;
  max-width: 240px;
  padding: 10px 12px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: 8px;
  cursor: pointer;
}

.bubble__merged:hover {
  border-color: var(--im-primary);
}

.bubble__merged-icon {
  flex: none;
  color: var(--im-primary);
}

.bubble__merged-meta {
  min-width: 0;
  flex: 1;
}

.bubble__merged-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.bubble__merged-hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/* 投票卡片 */
/* 转账与红包：共用一套「资金卡片」骨架，只靠配色区分（转账偏金、红包偏红） */
.bubble__money,
.bubble__packet {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 240px;
  padding: 10px 12px;
  background: var(--im-panel, #fff);
  border: 1px solid var(--im-border, #ebeef5);
  border-radius: 8px;
}

.bubble__money {
  background: linear-gradient(135deg, #fffaf0, #fff);
}

/* 红包必须点一下才去取领取态，所以它才是可点的；转账即时到账，不需要任何交互 */
.bubble__packet {
  background: linear-gradient(135deg, #fff1f0, #fff);
  cursor: pointer;
}

.bubble__packet.is-opened {
  opacity: 0.78;
}

.bubble__money-icon {
  flex: none;
  color: #d4a017;
}

.bubble__packet-icon {
  flex: none;
  color: #e6544a;
}

.bubble__money-body,
.bubble__packet-body {
  flex: 1;
  min-width: 0;
}

.bubble__money-amount,
.bubble__packet-greeting {
  font-size: 15px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.bubble__money-memo {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.bubble__money-hint,
.bubble__packet-hint {
  margin-top: 4px;
  font-size: 11px;
  color: var(--im-text-secondary, #909399);
}

.bubble__vote {
  width: 260px;
  padding: 10px 12px;
  background: var(--im-panel, #fff);
  border: 1px solid var(--im-border, #ebeef5);
  border-radius: 8px;
}

.bubble__vote-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.bubble__vote-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.bubble__vote-tag {
  flex: none;
  font-size: 11px;
  color: var(--im-text-secondary, #909399);
}

.bubble__vote-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.bubble__vote-option {
  position: relative;
  overflow: hidden;
  border-radius: 6px;
  cursor: pointer;
}

.bubble__vote-option.is-disabled {
  cursor: default;
  opacity: 0.8;
}

.bubble__vote-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: var(--el-color-primary-light-8, #d9ecff);
  transition: width 0.2s ease;
}

.bubble__vote-option.is-picked .bubble__vote-bar {
  background: var(--el-color-primary-light-7, #c6e2ff);
}

.bubble__vote-line {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
}

.bubble__vote-mark {
  flex: none;
  font-size: 14px;
  line-height: 1;
  color: var(--im-primary, #409eff);
}

.bubble__vote-text {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--im-text, #303133);
}

.bubble__vote-count {
  flex: none;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.bubble__vote-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.bubble__vote-total {
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.bubble__vote-spacer {
  flex: 1;
}

/* 多选勾选圈：选中时实心主题色 + 白勾 */
.bubble__check {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-top: 8px;
  border: 1.5px solid var(--im-border, #dcdfe6);
  border-radius: 50%;
  background: var(--im-panel, #fff);
  color: #fff;
  font-size: 13px;
}

.bubble__check.is-checked {
  background: var(--el-color-primary, #409eff);
  border-color: var(--el-color-primary, #409eff);
}

.bubble--selecting {
  cursor: pointer;
  user-select: none;
}

/* 接龙卡片：与投票同宽同容器，内容是一条条编号行，多时内部滚动不吃掉整个聊天区高度 */
.bubble__chain {
  width: 260px;
  padding: 10px 12px;
  background: var(--im-panel, #fff);
  border: 1px solid var(--im-border, #ebeef5);
  border-radius: 8px;
}

.bubble__chain-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.bubble__chain-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.bubble__chain-tag {
  flex: none;
  font-size: 11px;
  color: var(--im-text-secondary, #909399);
}

.bubble__chain-list {
  margin: 0;
  padding: 0 0 0 4px;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 180px;
  overflow-y: auto;
}

.bubble__chain-item {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 13px;
  line-height: 1.5;
}

.bubble__chain-no {
  flex: none;
  color: var(--im-primary, #409eff);
  font-variant-numeric: tabular-nums;
}

.bubble__chain-name {
  flex: none;
  max-width: 84px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--im-text, #303133);
  font-weight: 500;
}

.bubble__chain-text {
  flex: 1;
  min-width: 0;
  color: var(--im-text-regular, #606266);
  word-break: break-word;
}

.bubble__chain-empty {
  padding: 6px 0;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.bubble__chain-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.bubble__chain-spacer {
  flex: 1;
}
</style>
