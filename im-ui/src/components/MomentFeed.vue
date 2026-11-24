<template>
  <div class="moment-feed" :class="{ 'moment-feed--compact': compact }">
    <!-- 紧凑模式（资料卡里嵌一块）不出工具条：那一屏已经有「操作」区，再摆一排按钮就是两个入口做同一件事 -->
    <div v-if="!compact" class="peerbar">
      <el-button v-if="isMineFeed" size="small" type="primary" @click="openPublish">
        <el-icon><EditPen /></el-icon>
        <span>发动态</span>
      </el-button>
      <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
      <!-- 「公开」这个词最容易被读成全站公开，所以在工具条上就把受众说清，不等用户刷不到人再来问 -->
      <span class="peerbar__hint">只显示你的好友与配对对象的动态，「公开」不是全站公开</span>
    </div>

    <div ref="bodyRef" v-loading="loading" class="moment-feed__body im-scroll" @scroll="onScroll">
      <article
        v-for="item in list"
        :key="asId(item.momentId)"
        class="moment"
        :class="{ 'moment--offline': item.status === STATUS_OFFLINE }"
      >
        <header class="moment__head">
          <UserAvatar
            :src="item.author && item.author.avatar"
            :name="item.author && item.author.nickname"
            :size="40"
            :online="!!(item.author && item.author.online)"
          />
          <div class="moment__headbox">
            <div class="moment__name im-ellipsis">
              <a class="moment__link" @click="openProfile(item.author)">{{ authorName(item) }}</a>
              <!-- visibility / status 只对本人下发，别人的动态这两个标恒为空，不需要判权限 -->
              <el-tag v-if="item.visibilityDesc" size="small" type="info" effect="plain">
                {{ item.visibilityDesc }}
              </el-tag>
              <el-tag v-if="item.status === STATUS_OFFLINE" size="small" type="danger" effect="plain">
                {{ item.statusDesc || '已下架' }}
              </el-tag>
            </div>
            <div class="moment__time">{{ formatConvTime(item.createTime) }}</div>
          </div>
          <el-button
            v-if="item.mine"
            link
            type="danger"
            size="small"
            :loading="acting === asId(item.momentId)"
            @click="removeMoment(item)"
          >
            删除
          </el-button>
        </header>

        <div v-if="item.content" class="moment__content">{{ item.content }}</div>

        <!-- 九宫格：一张时给大图，多张时按格数自适应；视频恒为一条（后端不允许图视频混排） -->
        <div v-if="(item.media || []).length" class="moment__media" :class="gridClass(item.media)">
          <template v-for="m in item.media" :key="asId(m.mediaId)">
            <video
              v-if="m.type === MEDIA_VIDEO"
              class="moment__video"
              :src="mediaUrl(m.url)"
              controls
              preload="metadata"
            />
            <el-image
              v-else
              class="moment__img"
              :src="mediaUrl(m.url)"
              fit="cover"
              :preview-src-list="previewList(item)"
              :initial-index="previewIndex(item, m)"
              preview-teleported
              hide-on-click-modal
            >
              <template #placeholder>
                <div class="moment__img-loading"><el-icon><Picture /></el-icon></div>
              </template>
              <template #error>
                <div class="moment__img-loading"><el-icon><PictureFilled /></el-icon></div>
              </template>
            </el-image>
          </template>
        </div>

        <footer class="moment__actions">
          <el-button
            v-if="canInteract"
            link
            :type="item.liked ? 'danger' : 'default'"
            :loading="liking === asId(item.momentId)"
            @click="toggleLike(item)"
          >
            <el-icon><Star /></el-icon>
            <span>{{ item.liked ? '已赞' : '赞' }} {{ item.likeCount || 0 }}</span>
          </el-button>
          <span v-else class="moment__count">赞 {{ item.likeCount || 0 }}</span>
          <el-button link @click="openDetail(item)">
            <el-icon><ChatDotRound /></el-icon>
            <span>评论 {{ item.commentCount || 0 }}</span>
          </el-button>
          <el-button v-if="canShare" link @click="openShare(item)">
            <el-icon><Promotion /></el-icon>
            <span>转发</span>
          </el-button>
        </footer>

        <!-- 列表里只摊前两条：Feed 上把十几条评论全展开会把下一条动态推到很远 -->
        <div v-if="(item.comments || []).length" class="moment__comments">
          <div v-for="c in item.comments.slice(0, 2)" :key="asId(c.commentId)" class="moment__comment">
            <span class="moment__comment-name">{{ (c.user && c.user.nickname) || '该用户已注销' }}</span>
            <span class="moment__comment-text">{{ c.content }}</span>
          </div>
          <a v-if="(item.commentCount || 0) > 2" class="moment__more-link" @click="openDetail(item)">
            查看全部 {{ item.commentCount }} 条评论
          </a>
        </div>
      </article>

      <el-empty
        v-if="!loading && list.length === 0"
        :image-size="72"
        :description="isMineFeed ? '还没有动态' : 'TA 还没有可见的动态'"
      >
        <div v-if="isMineFeed" class="moment-feed__tip">
          发一条动态，你的好友与配对对象就能在这里看到；也可以在资料页把「动态可见天数」调宽。
        </div>
        <el-button v-if="isMineFeed" type="primary" plain @click="openPublish">发第一条</el-button>
      </el-empty>

      <div v-if="list.length" class="moment-feed__more">
        <el-button v-if="!exhausted" link :loading="loadingMore" @click="loadMore">加载更多</el-button>
        <span v-else class="moment-feed__end">没有更多了</span>
      </div>
    </div>

    <!-- ==================== 发布 ==================== -->
    <el-dialog
      v-model="publish.visible"
      title="发动态"
      width="540px"
      append-to-body
      :close-on-click-modal="false"
      @closed="resetPublish"
    >
      <el-input
        v-model="publish.content"
        type="textarea"
        :rows="4"
        :maxlength="CONTENT_MAX"
        show-word-limit
        placeholder="说点什么…（纯图动态可以不写）"
      />

      <div class="publish__media">
        <div v-for="(m, index) in publish.media" :key="m.key" class="publish__item">
          <video v-if="m.type === MEDIA_VIDEO" class="publish__thumb" :src="m.localUrl" preload="metadata" />
          <img v-else class="publish__thumb" :src="m.localUrl" alt="" />
          <span v-if="m.uploading" class="publish__mask">
            <el-icon class="is-loading"><Loading /></el-icon>
            {{ m.percent }}%
          </span>
          <el-icon class="publish__remove" @click="publish.media.splice(index, 1)"><CircleCloseFilled /></el-icon>
        </div>

        <div v-if="!videoPicked && publish.media.length < MAX_IMAGE" class="publish__add" @click="imageInput.click()">
          <el-icon :size="20"><Plus /></el-icon>
          <span>图片</span>
        </div>
        <div v-if="!publish.media.length" class="publish__add" @click="videoInput.click()">
          <el-icon :size="20"><VideoCamera /></el-icon>
          <span>视频</span>
        </div>
      </div>
      <!-- 后端「9 图 或 1 视频，不混排」是硬规则：与其让人选完再吃一条错误，不如在选的时候就不给这个选项 -->
      <div class="publish__hint">
        最多 9 张图，或 1 个视频（两者不能混在一条里）{{ publishing ? ' · 正在上传…' : '' }}
      </div>

      <input ref="imageInput" type="file" accept="image/*" multiple hidden @change="onPickImages" />
      <input ref="videoInput" type="file" accept="video/*" hidden @change="onPickVideo" />

      <div class="publish__field">
        <span class="publish__label">谁可以看</span>
        <el-radio-group v-model="publish.visibility" size="small">
          <el-radio-button v-for="opt in VISIBILITY_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </el-radio-button>
        </el-radio-group>
        <span class="publish__hint">{{ visibilityHint }}</span>
      </div>

      <div v-if="publish.visibility === VISIBILITY_PARTIAL" class="publish__picker">
        <el-input v-model="pickerKeyword" size="small" placeholder="搜索昵称 / 备注" clearable :prefix-icon="Search" />
        <div class="publish__picker-list im-scroll">
          <label v-for="opt in pickerOptions" :key="opt.id" class="publish__picker-item">
            <el-checkbox
              :model-value="publish.visibleUserIds.includes(opt.id)"
              @change="toggleWhitelist(opt.id)"
            />
            <UserAvatar :src="opt.avatar" :name="opt.name" :size="28" />
            <span class="im-ellipsis">{{ opt.name }}</span>
            <el-tag size="small" type="info" effect="plain">{{ opt.from }}</el-tag>
          </label>
          <el-empty v-if="!pickerOptions.length" :image-size="56" description="没有匹配的人" />
        </div>
        <div class="publish__hint">已选 {{ publish.visibleUserIds.length }} 人（最多 {{ WHITELIST_MAX }} 人）</div>
      </div>

      <template #footer>
        <el-button @click="publish.visible = false">取消</el-button>
        <el-button type="primary" :loading="publishing" :disabled="!canPublish" @click="submitPublish">
          发布
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 详情与评论 ==================== -->
    <!-- 详情屏与聊天气泡（msgType=15）共用一个组件：可见范围文案、2050 的提示、两层评论的回复口径
         各长一套的话，改一处漏一处的永远是「多给出去」的那一边 -->
    <MomentDetailDialog v-model:visible="detail.visible" :moment-id="detail.momentId" @changed="onDetailChanged" />

    <!-- ==================== 动态转聊天 ==================== -->
    <el-dialog v-model="share.visible" title="发到聊天" width="420px" append-to-body @closed="resetShare">
      <el-input
        v-model="share.keyword"
        placeholder="搜索会话名称"
        clearable
        :prefix-icon="Search"
        style="margin-bottom: 10px"
      />
      <div class="share__list im-scroll">
        <div
          v-for="conv in shareTargets"
          :key="asId(conv.conversationId)"
          class="share__item"
          :class="{ 'share__item--selected': sameId(share.conversationId, conv.conversationId) }"
          @click="share.conversationId = asId(conv.conversationId)"
        >
          <UserAvatar :src="conv.avatar" :name="conv.name" :size="34" />
          <div class="share__info">
            <div class="share__name im-ellipsis">{{ conv.name }}</div>
            <div class="share__type">{{ Number(conv.type) === 2 ? '群聊' : '单聊' }}</div>
          </div>
          <el-icon v-if="sameId(share.conversationId, conv.conversationId)" class="share__check"><Select /></el-icon>
        </div>
        <el-empty v-if="!shareTargets.length" :image-size="56" description="暂无匹配的会话" />
      </div>
      <el-input
        v-model="share.message"
        type="textarea"
        :rows="2"
        :maxlength="SHARE_MESSAGE_MAX"
        show-word-limit
        placeholder="附言（可空）"
        style="margin-top: 10px"
      />
      <!-- 这一句必须在发送前看到：转发之后才发现对方打不开，比一开始就说清更让人恼火 -->
      <div class="publish__hint">
        卡片只带摘要不带图，收到的人点开时按 TA 自己与作者的关系重算可见范围，不在受众里就是「这条动态不在了」。
      </div>
      <template #footer>
        <el-button @click="share.visible = false">取消</el-button>
        <el-button type="primary" :loading="sharing" :disabled="!share.conversationId" @click="submitShare">
          发送
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, CircleCloseFilled, EditPen, Loading, Picture, PictureFilled, Plus, Promotion,
  Search, Select, Star, VideoCamera
} from '@element-plus/icons-vue'
import UserAvatar from './UserAvatar.vue'
import MomentDetailDialog from './MomentDetailDialog.vue'
import {
  deleteMoment, fetchMatchPairs, fetchMomentFeed, fetchUserMoments, likeMoment, publishMoment,
  shareMoment, unlikeMoment
} from '@/api/dating'
import { uploadFileSmart } from '@/api/file'
import { guardImage } from '@/utils/nsfw'
import { mediaUrl, readImageSize, readVideoMetadata } from '@/utils/media'
import { formatConvTime } from '@/utils/format'
import { asId, sameId } from '@/utils/id'
import { useAuthStore } from '@/stores/auth'
import { useConversationStore } from '@/stores/conversation'
import { useFriendStore } from '@/stores/friend'

/**
 * 动态 Feed：发布、列表、点赞、评论、详情与转聊天。
 *
 * <p>抽成组件而不是直接写进 Discover.vue：交友页已经有五个 Tab，而同一份列表还要在
 * 「某人主页」上再出现一次（{@code userId} 传谁就看谁）。两处各写一份，
 * 可见范围那几档文案与 2050 的提示很快就会长得不一样。
 *
 * <p>三条与后端对齐、但看代码看不出来的口径：
 * <ul>
 *   <li><b>「返回不足一页」不等于到底</b>：可见范围是服务端在内存里逐条过滤的，被滤掉的也算扫过，
 *       所以 {@link #exhausted} 只在拿到空列表时置位，滚动加载不会提前停；</li>
 *   <li><b>visibility / status 只对本人下发</b>：别人的动态这两个字段恒为空，
 *       模板里因此不判权限只判空 —— 别人看到「仅好友」等于从侧面知道了自己与作者的好友关系；</li>
 *   <li><b>点赞与评论要 friend:apply、转发要 message:send</b>：没权限就把按钮藏掉，
 *       留一个点了吃 403 的按钮只会让人反复去点。</li>
 * </ul>
 */
defineOptions({ name: 'MomentFeed' })

const props = defineProps({
  /** 看谁的动态：为空表示「我的动态流」（受众 = 好友 ∪ 配对 ∪ 自己） */
  userId: { type: [String, Number], default: null },
  /** 紧凑模式：不出工具条、不自动撑满高度，用于嵌在资料卡里 */
  compact: { type: Boolean, default: false }
})

const emit = defineEmits(['loaded'])

const router = useRouter()
const auth = useAuthStore()
const conversationStore = useConversationStore()
const friendStore = useFriendStore()

const PAGE_SIZE = 10
/** 与后端 Moment.CONTENT_MAX / MomentComment.CONTENT_MAX / MessageExtra 的附言上限一致 */
const CONTENT_MAX = 500
const SHARE_MESSAGE_MAX = 500
const MAX_IMAGE = 9
const WHITELIST_MAX = 100

const MEDIA_IMAGE = 1
const MEDIA_VIDEO = 2
const STATUS_OFFLINE = 2

const VISIBILITY_PUBLIC = 1
const VISIBILITY_PARTIAL = 3
const VISIBILITY_OPTIONS = [
  { value: 1, label: '公开', hint: '你的好友与配对对象能看到；陌生人看不到' },
  { value: 2, label: '仅好友', hint: '只有好友能看到，配对但没加好友的人看不到' },
  { value: 3, label: '部分人', hint: '只有名单里的人能看到，名单至少要有一个人' },
  { value: 4, label: '仅自己', hint: '只有你自己能看到，相当于草稿' }
]

/** 上传业务类型，必须与后端 FileBizType.MOMENT_IMAGE / MOMENT_VIDEO 一致，传错会被判成「文件不属于这条动态」 */
const BIZ_IMAGE = 'moment_image'
const BIZ_VIDEO = 'moment_video'

const bodyRef = ref(null)
const list = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const exhausted = ref(false)
/** 行内操作转圈，按动态 ID 区分是哪一行 */
const acting = ref(null)
const liking = ref(null)

const canInteract = computed(() => auth.hasPermission('friend:apply'))
const canShare = computed(() => auth.hasPermission('message:send'))
/** 传了别人的 userId 就是主页视角：不出发布入口，也不把新发的动态往这一列里插 */
const isMineFeed = computed(() => !props.userId || sameId(props.userId, auth.userId))

/* ------------------------------ 列表 ------------------------------ */

/**
 * 拉一页。
 *
 * 游标推进用「本页最后一条」而不是「挑中的最后一条」——后端已经这么做了，
 * 前端只要把返回数组的尾巴交回去即可，不要自己按时间排序后另取一个游标。
 */
async function fetchPage(reset) {
  const busy = reset ? loading : loadingMore
  if (busy.value) {
    return
  }
  busy.value = true
  try {
    const cursorId = reset || !list.value.length ? null : asId(list.value[list.value.length - 1].momentId)
    // 不传 userId 才走 /feed（我的动态流）；传了自己的 id 也要走 /user/{id}，
    // 因为只有那一条会把已下架的动态一起给 —— 主页要能看到自己被下架的那几条
    const page = (props.userId
      ? await fetchUserMoments(asId(props.userId), { cursorId, size: PAGE_SIZE }, { silent: true })
      : await fetchMomentFeed({ cursorId, size: PAGE_SIZE })) || []
    if (reset) {
      list.value = page
    } else {
      // 按 momentId 去重：两次请求之间同一条可能同时落在两批里，重复卡片会让人删两次
      const known = new Set(list.value.map((item) => asId(item.momentId)))
      list.value = list.value.concat(page.filter((item) => !known.has(asId(item.momentId))))
    }
    exhausted.value = page.length === 0
    // 只在首页报一次总数：嵌在资料卡里的那一份靠它决定整张卡要不要显示
    //（陌生人恒为空列表，为它留一张空卡就是噪音）
    if (reset) {
      emit('loaded', list.value.length)
    }
  } catch {
    // 主页视角已经 silent，这里补一条；Feed 视角的提示由 request.js 弹过
    if (props.userId) {
      exhausted.value = list.value.length === 0
    }
  } finally {
    busy.value = false
  }
}

function reload() {
  exhausted.value = false
  fetchPage(true)
}

function loadMore() {
  fetchPage(false)
}

/** 滚到离底 120px 以内就自动续一页：这一列是无限流，让人每屏都去找「加载更多」按钮很蠢 */
function onScroll() {
  const el = bodyRef.value
  if (!el || exhausted.value || loading.value || loadingMore.value) {
    return
  }
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 120) {
    loadMore()
  }
}

function authorName(item) {
  return (item.author && item.author.nickname) || '该用户已注销'
}

/** 一张图给大图，2 或 4 张给两列，其余给三列：九宫格的意义就是让格数看起来是齐的 */
function gridClass(media) {
  const images = (media || []).filter((m) => m.type !== MEDIA_VIDEO)
  if (images.length === 1) {
    return 'moment__media--single'
  }
  if (images.length === 2 || images.length === 4) {
    return 'moment__media--double'
  }
  return 'moment__media--triple'
}

/** 预览列表只收图片：把视频地址塞进 el-image 的 preview-src-list 会得到一个放不出来的黑框 */
function previewList(item) {
  return (item.media || []).filter((m) => m.type !== MEDIA_VIDEO).map((m) => mediaUrl(m.url))
}

function previewIndex(item, media) {
  const images = (item.media || []).filter((m) => m.type !== MEDIA_VIDEO)
  const index = images.findIndex((m) => sameId(m.mediaId, media.mediaId))
  return index < 0 ? 0 : index
}

function openProfile(author) {
  if (!author || !author.userId || sameId(author.userId, auth.userId)) {
    return
  }
  router.push({ name: 'user-profile', params: { id: asId(author.userId) } })
}

/* ------------------------------ 点赞 / 删除 ------------------------------ */

/**
 * 点赞与取消。
 *
 * 后端两个方向都是幂等的且都返回权威总数，所以这里不做乐观更新：
 * 乐观更新要在失败时把 liked 与 likeCount 一起回滚，而漏回滚任何一个都会让按钮状态和数字对不上。
 * 详情弹层里点的那一次不走这里：它自己算完权威值后经 changed 事件回来，由 {@link #onDetailChanged} 打补丁。
 */
async function toggleLike(item) {
  const id = asId(item.momentId)
  if (liking.value) {
    return
  }
  liking.value = id
  try {
    const total = item.liked ? await unlikeMoment(id) : await likeMoment(id)
    applyLike(item, !item.liked, Number(total) || 0)
  } catch {
    // 提示已弹出，按钮留在原状态方便再点一次
  } finally {
    liking.value = null
  }
}

function applyLike(item, liked, total) {
  item.liked = liked
  item.likeCount = total
}

async function removeMoment(item) {
  try {
    await ElMessageBox.confirm('删除这条动态？附件、评论与点赞会一起清掉，且不能恢复。', '删除动态', {
      confirmButtonText: '删除',
      cancelButtonText: '再想想',
      type: 'warning'
    })
  } catch {
    return
  }
  const id = asId(item.momentId)
  acting.value = id
  try {
    await deleteMoment(id)
    list.value = list.value.filter((row) => !sameId(row.momentId, id))
    ElMessage.success('已删除')
  } catch {
    // 提示已弹出
  } finally {
    acting.value = null
  }
}

/* ------------------------------ 发布 ------------------------------ */

const imageInput = ref(null)
const videoInput = ref(null)
const publishing = ref(false)
const publish = reactive({
  visible: false,
  content: '',
  visibility: VISIBILITY_PUBLIC,
  media: [],
  visibleUserIds: []
})

const videoPicked = computed(() => publish.media.some((m) => m.type === MEDIA_VIDEO))
const uploading = computed(() => publish.media.some((m) => m.uploading))
const canPublish = computed(() => {
  if (uploading.value) {
    return false
  }
  if (publish.visibility === VISIBILITY_PARTIAL && !publish.visibleUserIds.length) {
    return false
  }
  return !!publish.content.trim() || publish.media.length > 0
})
const visibilityHint = computed(
  () => (VISIBILITY_OPTIONS.find((opt) => opt.value === publish.visibility) || {}).hint || ''
)

function openPublish() {
  publish.visible = true
  loadPickerOptions()
}

function resetPublish() {
  publish.content = ''
  publish.visibility = VISIBILITY_PUBLIC
  // 本地预览用的是 createObjectURL，不清就会一直占着内存
  publish.media.forEach((m) => m.localUrl && URL.revokeObjectURL(m.localUrl))
  publish.media = []
  publish.visibleUserIds = []
  pickerKeyword.value = ''
}

async function onPickImages(event) {
  const files = Array.from(event.target.files || [])
  // 清 value：同一个文件选第二次也要能触发 change，否则「删掉重选同一张」会没反应
  event.target.value = ''
  const room = MAX_IMAGE - publish.media.length
  if (room <= 0) {
    ElMessage.warning(`一条动态最多 ${MAX_IMAGE} 张图`)
    return
  }
  for (const file of files.slice(0, room)) {
    await uploadMedia(file, MEDIA_IMAGE)
  }
  if (files.length > room) {
    ElMessage.warning(`只保留了前 ${room} 张，一条动态最多 ${MAX_IMAGE} 张图`)
  }
}

async function onPickVideo(event) {
  const file = (event.target.files || [])[0]
  event.target.value = ''
  if (!file) {
    return
  }
  if (videoPicked.value) {
    ElMessage.warning('一条动态只能有一个视频')
    return
  }
  await uploadMedia(file, MEDIA_VIDEO)
}

/**
 * 上传一个附件并挂进草稿。
 *
 * 逐张串行而不是并发：九宫格的顺序就是数组顺序，并发完成会让「第 3 张」变成随机的那一张。
 * 失败只摘掉这一项，其余照常留着 —— 已经传上去的字节没必要因为一张图违规就全丢。
 */
async function uploadMedia(file, type) {
  const draft = reactive({
    key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    type,
    fileId: null,
    width: 0,
    height: 0,
    duration: null,
    percent: 0,
    uploading: true,
    localUrl: URL.createObjectURL(file)
  })
  publish.media.push(draft)
  try {
    if (type === MEDIA_IMAGE) {
      // 本地那道 NSFW 闸门与照片墙同一口径：命中就压根不上传，省一次带宽也省一条待审记录
      const guard = await guardImage(file, { scene: BIZ_IMAGE })
      if (!guard.pass) {
        ElMessage.error(guard.message)
        throw new Error(guard.message)
      }
      const size = await readImageSize(file)
      if (size) {
        draft.width = size.width || 0
        draft.height = size.height || 0
      }
    } else {
      const meta = await readVideoMetadata(file)
      if (meta) {
        draft.width = meta.width || 0
        draft.height = meta.height || 0
        draft.duration = meta.duration || null
      }
    }
    const vo = await uploadFileSmart(file, type === MEDIA_VIDEO ? BIZ_VIDEO : BIZ_IMAGE, draft.duration, (percent) => {
      draft.percent = percent
    })
    // 文件主键字段是 fileId 不是 id，写成 vo.id 会得到 undefined 且不报错
    draft.fileId = vo && vo.fileId
    if (!draft.fileId) {
      throw new Error('上传未返回文件 ID')
    }
  } catch {
    const index = publish.media.findIndex((m) => m.key === draft.key)
    if (index >= 0) {
      publish.media.splice(index, 1)
    }
    URL.revokeObjectURL(draft.localUrl)
  } finally {
    draft.uploading = false
  }
}

async function submitPublish() {
  if (!canPublish.value || publishing.value) {
    return
  }
  publishing.value = true
  try {
    const vo = await publishMoment({
      content: publish.content.trim() || null,
      visibility: publish.visibility,
      media: publish.media.map((m) => ({
        fileId: asId(m.fileId),
        type: m.type,
        width: m.width || 0,
        height: m.height || 0,
        duration: m.duration
      })),
      // 一律传字符串：雪花 ID 经 Number() 会掉精度，后端 Long 同时接受字符串与数字
      visibleUserIds: publish.visibility === VISIBILITY_PARTIAL ? publish.visibleUserIds : []
    })
    ElMessage.success('已发布')
    publish.visible = false
    if (isMineFeed.value && vo && vo.momentId) {
      list.value = [vo].concat(list.value.filter((row) => !sameId(row.momentId, vo.momentId)))
    }
  } catch {
    // 提示已弹出，草稿留在原地方便改完再发
  } finally {
    publishing.value = false
  }
}

/* --------------------- 部分人可见：选人 --------------------- */

const pickerKeyword = ref('')
const pickerAll = ref([])

/**
 * 可选的人 = 好友 ∪ 配对对象，与后端 Feed 的受众同源。
 *
 * 刻意不收「全站搜索出来的人」：受众之外的人即使被点名，也不会在他自己的动态流里看到这一条
 * （候选是按受众取的），只能从我的主页看到 —— 给一个看上去能选、实际半失效的名单比不给更糟。
 */
async function loadPickerOptions() {
  if (pickerAll.value.length) {
    return
  }
  try {
    if (!friendStore.friends.length) {
      await friendStore.fetchFriends()
    }
    // 拉黑的人不进名单：后端会把「我拉黑的人」从白名单里剔掉，
    // 这里再列出来就是给一个选了也不生效的选项（「我不想再看到这个人」不该被一次点名覆盖）
    const merged = friendStore.friends
      .filter((item) => Number(item.status) !== 2)
      .map((item) => ({
        id: asId(item.friendId),
        name: item.displayName || item.nickname || '未命名',
        avatar: item.avatar,
        from: '好友'
      }))
    const known = new Set(merged.map((opt) => opt.id))
    try {
      const page = await fetchMatchPairs({ current: 1, size: 50 })
      ;((page && page.records) || []).forEach((item) => {
        const id = asId(item.peerUserId)
        if (known.has(id)) {
          return
        }
        known.add(id)
        const card = item.card || {}
        merged.push({ id, name: card.nickname || '未命名', avatar: card.avatar, from: '配对' })
      })
    } catch {
      // 配对列表拉不到就只从好友里挑：少一半候选比整个弹层打不开好
    }
    pickerAll.value = merged
  } catch {
    // 提示已弹出
  }
}

const pickerOptions = computed(() => {
  const kw = pickerKeyword.value.trim().toLowerCase()
  if (!kw) {
    return pickerAll.value
  }
  return pickerAll.value.filter((opt) => opt.name.toLowerCase().includes(kw))
})

function toggleWhitelist(id) {
  const index = publish.visibleUserIds.findIndex((item) => sameId(item, id))
  if (index >= 0) {
    publish.visibleUserIds.splice(index, 1)
    return
  }
  if (publish.visibleUserIds.length >= WHITELIST_MAX) {
    ElMessage.warning(`最多挑 ${WHITELIST_MAX} 个人`)
    return
  }
  publish.visibleUserIds.push(id)
}

/* ------------------------------ 详情 ------------------------------ */

const detail = reactive({ visible: false, momentId: null })

function openDetail(item) {
  detail.momentId = asId(item.momentId)
  detail.visible = true
}

/**
 * 详情弹层里改完的东西就地打补丁，不重拉一页。
 *
 * 重拉会把用户正在看的位置冲掉，而弹层交回来的就是服务端的权威值；
 * 同一条动态在主页与动态流里各有一份时，两份都要改，否则关掉弹层就看到旧数字。
 * gone / removed 两种情况下直接把这一行摘掉：留一个点不开的卡片，用户会一直去点。
 */
function onDetailChanged(payload) {
  if (!payload || !payload.momentId) {
    return
  }
  if (payload.gone || payload.removed) {
    list.value = list.value.filter((row) => !sameId(row.momentId, payload.momentId))
    return
  }
  const twin = list.value.find((row) => sameId(row.momentId, payload.momentId))
  if (twin) {
    twin.liked = payload.liked
    twin.likeCount = payload.likeCount
    twin.commentCount = payload.commentCount
  }
}

/* ------------------------------ 转聊天 ------------------------------ */

const share = reactive({ visible: false, momentId: null, conversationId: null, keyword: '', message: '' })
const sharing = ref(false)

const shareTargets = computed(() => {
  const all = conversationStore.list || []
  const kw = share.keyword.trim().toLowerCase()
  return kw ? all.filter((conv) => (conv.name || '').toLowerCase().includes(kw)) : all
})

async function openShare(item) {
  share.momentId = asId(item.momentId)
  share.visible = true
  if (!conversationStore.list.length) {
    try {
      await conversationStore.fetchList()
    } catch {
      // 提示已弹出，弹层里是一个空列表，用户可以关掉重开
    }
  }
}

function resetShare() {
  share.momentId = null
  share.conversationId = null
  share.keyword = ''
  share.message = ''
}

async function submitShare() {
  if (!share.momentId || !share.conversationId || sharing.value) {
    return
  }
  sharing.value = true
  const target = conversationStore.find(share.conversationId)
  try {
    const vo = await shareMoment(share.momentId, {
      conversationId: asId(share.conversationId),
      message: share.message.trim() || null
    })
    share.visible = false
    // 会话可能是后端这次才建出来的（转发给一个还没聊过的人），本地列表里没有这一条，
    // 不刷新就跳过去会因为找不到对应项而显示空白面板
    await conversationStore.fetchList().catch(() => undefined)
    const conversationId = asId((vo && vo.conversationId) || share.conversationId)
    try {
      await ElMessageBox.confirm(`已发到「${(target && target.name) || '会话'}」，现在去看看？`, '已分享', {
        confirmButtonText: '去会话',
        cancelButtonText: '留在这里',
        type: 'success'
      })
      router.push({ name: 'chat', params: { conversationId } })
    } catch {
      // 选了「留在这里」
    }
  } catch {
    // 提示已弹出
  } finally {
    sharing.value = false
  }
}

/* ------------------------------ 生命周期 ------------------------------ */

watch(
  () => props.userId,
  () => {
    list.value = []
    exhausted.value = false
    fetchPage(true)
  }
)

onMounted(() => {
  fetchPage(true)
})

defineExpose({ reload })
</script>

<style scoped>
.moment-feed {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

/* 紧凑模式嵌在一张卡里，卡片的高度由内容决定：再写 height:100% 会塌成一个 0 高的滚动区 */
.moment-feed--compact {
  height: auto;
}

.moment-feed--compact .moment-feed__body {
  max-height: 520px;
}

.peerbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 0;
}

.peerbar__hint {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.moment-feed__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: 12px;
}

.moment-feed__tip {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-bottom: 8px;
}

.moment-feed__more {
  display: flex;
  justify-content: center;
  padding: 8px 0 4px;
}

.moment-feed__end {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.moment {
  padding: 12px;
  border-radius: 8px;
  background: var(--im-panel);
  border: 1px solid var(--im-border, rgba(0, 0, 0, 0.06));
  margin-bottom: 10px;
}

.moment--offline {
  opacity: 0.65;
}

.moment__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.moment__headbox {
  flex: 1;
  min-width: 0;
}

.moment__name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text);
}

.moment__link {
  cursor: pointer;
}

.moment__link:hover {
  color: var(--el-color-primary);
}

.moment__time {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-top: 2px;
}

.moment__content {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--im-text);
  /* 正文按原样换行：动态里的换行是排版的一部分，压成一行会让人以为发出去的字被吞了 */
  white-space: pre-wrap;
  word-break: break-word;
}

.moment__media {
  display: grid;
  gap: 4px;
  margin-top: 8px;
}

.moment__media--single {
  grid-template-columns: minmax(0, 320px);
}

.moment__media--double {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-width: 420px;
}

.moment__media--triple {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  max-width: 480px;
}

.moment__img {
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: 4px;
  overflow: hidden;
  background: var(--im-bg-hover, rgba(0, 0, 0, 0.04));
}

.moment__media--single .moment__img {
  aspect-ratio: auto;
  max-height: 420px;
}

.moment__video {
  width: 100%;
  max-height: 420px;
  border-radius: 4px;
  background: #000;
}

.moment__img-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--im-text-secondary);
}

.moment__actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 8px;
}

.moment__count {
  font-size: 13px;
  color: var(--im-text-secondary);
}

.moment__comments {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--im-bg-hover, rgba(0, 0, 0, 0.03));
}

.moment__comment {
  font-size: 13px;
  line-height: 1.7;
  color: var(--im-text);
  word-break: break-word;
}

.moment__comment-name {
  color: var(--el-color-primary);
  margin-right: 4px;
}

.moment__more-link {
  font-size: 12px;
  color: var(--im-text-secondary);
  cursor: pointer;
}

.moment__more-link:hover {
  color: var(--el-color-primary);
}

/* ------------------------------ 发布 ------------------------------ */

.publish__media {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.publish__item {
  position: relative;
  width: 84px;
  height: 84px;
}

.publish__thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 6px;
  background: #000;
}

.publish__mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.5);
  border-radius: 6px;
}

.publish__remove {
  position: absolute;
  top: -6px;
  right: -6px;
  font-size: 18px;
  color: var(--im-text-secondary);
  cursor: pointer;
  background: var(--im-panel);
  border-radius: 50%;
}

.publish__remove:hover {
  color: var(--el-color-danger);
}

.publish__add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 84px;
  height: 84px;
  font-size: 12px;
  color: var(--im-text-secondary);
  border: 1px dashed var(--im-border, rgba(0, 0, 0, 0.2));
  border-radius: 6px;
  cursor: pointer;
}

.publish__add:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.publish__field {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.publish__label {
  font-size: 13px;
  color: var(--im-text);
}

.publish__hint {
  font-size: 12px;
  color: var(--im-text-secondary);
  line-height: 1.6;
  margin-top: 4px;
}

.publish__picker {
  margin-top: 8px;
}

.publish__picker-list {
  max-height: 200px;
  overflow-y: auto;
  margin-top: 6px;
  border: 1px solid var(--im-border, rgba(0, 0, 0, 0.06));
  border-radius: 6px;
}

.publish__picker-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  font-size: 13px;
  cursor: pointer;
}

.publish__picker-item:hover {
  background: var(--im-bg-hover, rgba(0, 0, 0, 0.04));
}

/* ------------------------------ 转聊天 ------------------------------ */

.share__list {
  max-height: 280px;
  overflow-y: auto;
}

.share__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.share__item:hover {
  background: var(--im-bg-hover, rgba(0, 0, 0, 0.04));
}

.share__item--selected {
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.share__info {
  flex: 1;
  min-width: 0;
}

.share__name {
  font-size: 14px;
  color: var(--im-text);
}

.share__type {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.share__check {
  flex: none;
  font-size: 18px;
  color: var(--el-color-primary);
}
</style>
