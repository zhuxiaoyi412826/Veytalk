<template>
  <!-- 带视频的详情把弹层撑宽：固定在 580px 里，稍大一点的竖屏视频也只能看到一小块画面 -->
  <el-dialog
    v-model="dialogVisible"
    title="动态详情"
    :width="dialogWidth"
    append-to-body
    @closed="onClosed"
  >
    <div v-loading="loading" class="mdetail">
      <template v-if="data">
        <header class="mdetail__head">
          <UserAvatar
            :src="data.author && data.author.avatar"
            :name="data.author && data.author.nickname"
            :size="40"
            :online="!!(data.author && data.author.online)"
          />
          <div class="mdetail__headbox">
            <div class="mdetail__name im-ellipsis">
              <a class="mdetail__link" @click="openProfile">{{ authorName }}</a>
              <!-- visibility / status 只对作者本人下发，别人的动态这两个标恒为空 -->
              <el-tag v-if="data.visibilityDesc" size="small" type="info" effect="plain">
                {{ data.visibilityDesc }}
              </el-tag>
              <el-tag v-if="data.status === STATUS_OFFLINE" size="small" type="danger" effect="plain">
                {{ data.statusDesc || '已下架' }}
              </el-tag>
            </div>
            <div class="mdetail__time">{{ formatConvTime(data.createTime) }}</div>
          </div>
        </header>

        <div v-if="data.content" class="mdetail__content">{{ data.content }}</div>

        <div v-if="(data.media || []).length" class="mdetail__media" :class="gridClass">
          <template v-for="m in data.media" :key="asId(m.mediaId)">
            <video
              v-if="m.type === MEDIA_VIDEO"
              class="mdetail__video"
              :src="mediaUrl(m.url)"
              controls
              preload="metadata"
            />
            <el-image
              v-else
              class="mdetail__img"
              :src="mediaUrl(m.url)"
              fit="cover"
              :preview-src-list="previewList"
              :initial-index="previewIndex(m)"
              preview-teleported
              hide-on-click-modal
            />
          </template>
        </div>

        <div class="mdetail__actions">
          <el-button
            v-if="canInteract"
            link
            :type="data.liked ? 'danger' : 'default'"
            :loading="liking"
            @click="toggleLike"
          >
            <el-icon><Star /></el-icon>
            <span>{{ data.liked ? '已赞' : '赞' }} {{ data.likeCount || 0 }}</span>
          </el-button>
          <span v-else class="mdetail__count">赞 {{ data.likeCount || 0 }}</span>
          <span class="mdetail__count">评论 {{ data.commentCount || 0 }}</span>
          <!-- 浏览量只对作者下发，判的是字段在不在而不是有没有权限 -->
          <span v-if="data.viewCount !== null && data.viewCount !== undefined" class="mdetail__count">
            浏览 {{ data.viewCount || 0 }}
          </span>
          <span class="mdetail__spacer"></span>
          <el-button v-if="data.mine" link type="danger" size="small" @click="remove">删除这条动态</el-button>
        </div>

        <div class="mdetail__comments im-scroll">
          <div v-for="c in data.comments || []" :key="asId(c.commentId)" class="comment">
            <UserAvatar :src="c.user && c.user.avatar" :name="c.user && c.user.nickname" :size="28" />
            <div class="comment__body">
              <div class="comment__head">
                <span class="comment__name">{{ (c.user && c.user.nickname) || '该用户已注销' }}</span>
                <span class="comment__time">{{ formatConvTime(c.createTime) }}</span>
              </div>
              <div class="comment__text">{{ c.content }}</div>
              <div class="comment__ops">
                <a v-if="canInteract" @click="beginReply(c, c)">回复</a>
                <a v-if="c.mine || data.mine" class="comment__danger" @click="removeComment(c)">删除</a>
              </div>

              <div v-for="r in c.replies || []" :key="asId(r.commentId)" class="comment comment--reply">
                <UserAvatar :src="r.user && r.user.avatar" :name="r.user && r.user.nickname" :size="24" />
                <div class="comment__body">
                  <div class="comment__head">
                    <span class="comment__name">{{ (r.user && r.user.nickname) || '该用户已注销' }}</span>
                    <span v-if="r.replyUser" class="comment__reply-to">回复 {{ r.replyUser.nickname }}</span>
                    <span class="comment__time">{{ formatConvTime(r.createTime) }}</span>
                  </div>
                  <div class="comment__text">{{ r.content }}</div>
                  <div class="comment__ops">
                    <a v-if="canInteract" @click="beginReply(c, r)">回复</a>
                    <a v-if="r.mine || data.mine" class="comment__danger" @click="removeComment(r)">删除</a>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <el-empty v-if="!(data.comments || []).length" :image-size="56" description="还没有评论" />
        </div>

        <div v-if="canInteract" class="mdetail__editor">
          <el-tag v-if="replyTarget" size="small" closable @close="replyTarget = null">
            回复 {{ replyTarget.name }}
          </el-tag>
          <el-input
            v-model="commentText"
            type="textarea"
            :rows="2"
            :maxlength="COMMENT_MAX"
            show-word-limit
            :placeholder="replyTarget ? `回复 ${replyTarget.name}` : '写条评论…'"
          />
          <div class="mdetail__editor-ops">
            <el-button
              type="primary"
              size="small"
              :loading="commenting"
              :disabled="!commentText.trim()"
              @click="submitComment"
            >
              发送
            </el-button>
          </div>
        </div>
      </template>

      <!-- 「不在了」与「没权限看」是同一个码：分开就等于把动态 ID 的存在性交给外部逐个试 -->
      <el-empty v-else-if="!loading && gone" :image-size="64" description="这条动态不在了" />
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Star } from '@element-plus/icons-vue'
import UserAvatar from './UserAvatar.vue'
import {
  commentMoment, deleteMoment, deleteMomentComment, fetchMomentDetail, likeMoment, unlikeMoment
} from '@/api/dating'
import { mediaUrl } from '@/utils/media'
import { formatConvTime } from '@/utils/format'
import { asId, sameId } from '@/utils/id'
import { useAuthStore } from '@/stores/auth'

/**
 * 动态详情弹层：正文 + 附件 + 两层评论 + 点赞 + 删除。
 *
 * <p>抽出来而不是留在 {@code MomentFeed} 里：从聊天卡片（msgType=15）点开一条动态时也要这一屏，
 * 而气泡长在 ChatWindow 里、Feed 长在交友页里，两个宿主共用一份详情才不会让
 * 「可见范围文案」「2050 的提示」「两层评论的回复口径」这三处各长一套。
 *
 * <p>与宿主的通信只有一个 {@code changed} 事件，带上改完后的权威计数：
 * 宿主列表里那一份是旧的，而重拉整页会把用户正在看的位置冲掉，所以由宿主自己按 id 打补丁。
 */
defineOptions({ name: 'MomentDetailDialog' })

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 要看哪一条；为空时弹层不取数 */
  momentId: { type: [String, Number], default: null }
})

const emit = defineEmits(['update:visible', 'changed'])

const router = useRouter()
const auth = useAuthStore()

const COMMENT_MAX = 200
const MEDIA_VIDEO = 2
const STATUS_OFFLINE = 2
/** ResultCode.MOMENT_GONE */
const CODE_MOMENT_GONE = 2050

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const loading = ref(false)
const gone = ref(false)
const data = ref(null)
const liking = ref(false)
const commenting = ref(false)
const commentText = ref('')
/** { parentId, replyUserId, name }：parentId 恒为那条一级评论，两层封顶 */
const replyTarget = ref(null)

const canInteract = computed(() => auth.hasPermission('friend:apply'))
const authorName = computed(() => (data.value && data.value.author && data.value.author.nickname) || '该用户已注销')

/**
 * 一张图给大图，2 或 4 张给两列，其余给三列：九宫格的意义就是让格数看起来是齐的。
 *
 * 视频先单独判：它永远只有一条，落到下面会被归成「0 张图」而拿到 triple，
 * 于是一个附件只分到网格的三分之一，这就是「播放窗口太小」的根源。
 */
const gridClass = computed(() => {
  const media = (data.value && data.value.media) || []
  const images = media.filter((m) => m.type !== MEDIA_VIDEO)
  if (images.length !== media.length) {
    return 'mdetail__media--video'
  }
  if (images.length === 1) {
    return 'mdetail__media--single'
  }
  if (images.length === 2 || images.length === 4) {
    return 'mdetail__media--double'
  }
  return 'mdetail__media--triple'
})

const hasVideo = computed(() =>
  ((data.value && data.value.media) || []).some((m) => m.type === MEDIA_VIDEO)
)
const dialogWidth = computed(() => (hasVideo.value ? 'min(880px, 94vw)' : '580px'))

/** 预览列表只收图片：把视频地址塞进 el-image 的 preview-src-list 会得到一个放不出来的黑框 */
const previewList = computed(() => {
  const media = (data.value && data.value.media) || []
  return media.filter((m) => m.type !== MEDIA_VIDEO).map((m) => mediaUrl(m.url))
})

function previewIndex(media) {
  const images = ((data.value && data.value.media) || []).filter((m) => m.type !== MEDIA_VIDEO)
  const index = images.findIndex((m) => sameId(m.mediaId, media.mediaId))
  return index < 0 ? 0 : index
}

function openProfile() {
  const author = data.value && data.value.author
  if (!author || !author.userId || sameId(author.userId, auth.userId)) {
    return
  }
  dialogVisible.value = false
  router.push({ name: 'user-profile', params: { id: asId(author.userId) } })
}

async function load() {
  if (!props.momentId) {
    return
  }
  loading.value = true
  gone.value = false
  data.value = null
  replyTarget.value = null
  commentText.value = ''
  try {
    // silent + 自己认 2050：这条动态被删或被挡住是内容层面的正常结果，
    // 让全局拦截器弹一条红字、这里再摆一个空态，就是同一个问题说两遍
    data.value = await fetchMomentDetail(asId(props.momentId), { silent: true })
  } catch (err) {
    if (err && err.code === CODE_MOMENT_GONE) {
      gone.value = true
      emit('changed', { momentId: asId(props.momentId), gone: true })
    } else {
      dialogVisible.value = false
      ElMessage.error((err && err.message) || '动态加载失败')
    }
  } finally {
    loading.value = false
  }
}

/** 把改完后的权威计数交给宿主，让它就地打补丁而不是重拉一页 */
function notify() {
  if (!data.value) {
    return
  }
  emit('changed', {
    momentId: asId(data.value.momentId),
    liked: !!data.value.liked,
    likeCount: data.value.likeCount || 0,
    commentCount: data.value.commentCount || 0
  })
}

/**
 * 点赞与取消。
 *
 * 后端两个方向都幂等且都返回权威总数，所以不做乐观更新：乐观更新要在失败时把 liked 与
 * likeCount 一起回滚，漏掉任何一个都会让按钮状态和数字对不上。
 */
async function toggleLike() {
  if (liking.value || !data.value) {
    return
  }
  liking.value = true
  try {
    const total = data.value.liked
      ? await unlikeMoment(asId(data.value.momentId))
      : await likeMoment(asId(data.value.momentId))
    data.value.liked = !data.value.liked
    data.value.likeCount = Number(total) || 0
    notify()
  } catch {
    // 提示已弹出，按钮留在原状态方便再点一次
  } finally {
    liking.value = false
  }
}

/**
 * 进入回复态。
 *
 * root 与 target 分开传：回复一条回复时 parentId 要用它所在的那条一级评论，
 * 而「回复 @谁」显示的是被点的那个人。传成同一个就会出现「回复自己的回复」这种套娃。
 */
function beginReply(root, target) {
  replyTarget.value = {
    parentId: asId(root.commentId),
    replyUserId: target.user ? asId(target.user.userId) : null,
    name: (target.user && target.user.nickname) || '该用户'
  }
}

async function submitComment() {
  const text = commentText.value.trim()
  if (!text || commenting.value || !data.value) {
    return
  }
  commenting.value = true
  try {
    const vo = await commentMoment(asId(data.value.momentId), {
      content: text,
      parentId: replyTarget.value ? replyTarget.value.parentId : null,
      replyUserId: replyTarget.value ? replyTarget.value.replyUserId : null
    })
    insertComment(vo)
    commentText.value = ''
    replyTarget.value = null
    notify()
  } catch {
    // 提示已弹出，输入框里的字留着方便改完再发
  } finally {
    commenting.value = false
  }
}

/**
 * 把新评论就地插进树，而不是重拉详情。
 *
 * 重拉会重置滚动位置，也会把用户刚展开的那一串回复收回去；而服务端返回的就是权威行，插进去即可。
 */
function insertComment(vo) {
  if (!vo || !data.value) {
    return
  }
  data.value.commentCount = (data.value.commentCount || 0) + 1
  if (vo.parentId) {
    const root = (data.value.comments || []).find((c) => sameId(c.commentId, vo.parentId))
    if (root) {
      root.replies = (root.replies || []).concat(vo)
      return
    }
  }
  data.value.comments = (data.value.comments || []).concat(vo)
}

async function removeComment(comment) {
  try {
    await ElMessageBox.confirm('删除这条评论？它下面的回复也会一起删掉。', '删除评论', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteMomentComment(asId(comment.commentId))
    // 删一级评论会连带整棵子树，减几行只有服务端算得准，所以这里重拉一次详情
    data.value = await fetchMomentDetail(asId(data.value.momentId), { silent: true })
    notify()
    ElMessage.success('已删除')
  } catch {
    // 提示已弹出
  }
}

async function remove() {
  try {
    await ElMessageBox.confirm('删除这条动态？附件、评论与点赞会一起清掉，且不能恢复。', '删除动态', {
      confirmButtonText: '删除',
      cancelButtonText: '再想想',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteMoment(asId(data.value.momentId))
    dialogVisible.value = false
    ElMessage.success('已删除')
    emit('changed', { momentId: asId(data.value.momentId), removed: true })
  } catch {
    // 提示已弹出
  }
}

function onClosed() {
  data.value = null
  gone.value = false
  commentText.value = ''
  replyTarget.value = null
}

watch(
  () => [props.visible, props.momentId],
  ([visible]) => {
    if (visible && props.momentId) {
      load()
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.mdetail {
  min-height: 160px;
}

.mdetail__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.mdetail__headbox {
  flex: 1;
  min-width: 0;
}

.mdetail__name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text);
}

.mdetail__link {
  cursor: pointer;
}

.mdetail__link:hover {
  color: var(--el-color-primary);
}

.mdetail__time {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-top: 2px;
}

.mdetail__content {
  margin-top: 8px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--im-text);
  /* 正文按原样换行：动态里的换行是排版的一部分，压成一行会让人以为发出去的字被吞了 */
  white-space: pre-wrap;
  word-break: break-word;
}

.mdetail__media {
  display: grid;
  gap: 4px;
  margin-top: 8px;
}

.mdetail__media--single {
  grid-template-columns: minmax(0, 320px);
}

.mdetail__media--double {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-width: 420px;
}

.mdetail__media--triple {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  max-width: 480px;
}

.mdetail__img {
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: 4px;
  overflow: hidden;
  background: var(--im-bg-hover, rgba(0, 0, 0, 0.04));
}

.mdetail__media--single .mdetail__img {
  aspect-ratio: auto;
  max-height: 420px;
}

/* 视频占满整行：一个附件不需要网格，宽度交给弹层自己定 */
.mdetail__media--video {
  grid-template-columns: minmax(0, 1fr);
}

.mdetail__video {
  width: 100%;
  /* 从 420px 放宽到按视口算的高度，弹层也会随带视频而撑宽 */
  max-height: min(66vh, 620px);
  border-radius: 4px;
  background: #000;
  display: block;
}

.mdetail__actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--im-border, rgba(0, 0, 0, 0.06));
}

.mdetail__count {
  font-size: 13px;
  color: var(--im-text-secondary);
}

.mdetail__spacer {
  flex: 1;
}

.mdetail__comments {
  max-height: 320px;
  overflow-y: auto;
  padding: 8px 0;
}

.mdetail__editor {
  border-top: 1px solid var(--im-border, rgba(0, 0, 0, 0.06));
  padding-top: 8px;
}

.mdetail__editor-ops {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}

.comment {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.comment--reply {
  margin: 8px 0 0;
}

.comment__body {
  flex: 1;
  min-width: 0;
}

.comment__head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.comment__name {
  color: var(--el-color-primary);
}

.comment__reply-to {
  color: var(--im-text-secondary);
}

.comment__time {
  color: var(--im-text-secondary);
}

.comment__text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--im-text);
  word-break: break-word;
  white-space: pre-wrap;
}

.comment__ops {
  display: flex;
  gap: 12px;
  margin-top: 2px;
  font-size: 12px;
}

.comment__ops a {
  color: var(--im-text-secondary);
  cursor: pointer;
}

.comment__ops a:hover {
  color: var(--el-color-primary);
}

.comment__danger:hover {
  color: var(--el-color-danger) !important;
}
</style>
