<template>
  <div class="moment-feed" :class="{ 'moment-feed--compact': compact }">
    <!-- 紧凑模式（资料卡里嵌一块）不出工具条：那一屏已经有「操作」区，再摆一排按钮就是两个入口做同一件事 -->
    <div v-if="!compact" class="peerbar">
      <el-button v-if="isMineFeed" size="small" type="primary" @click="openPublish">
        <el-icon><EditPen /></el-icon>
        <span>发动态</span>
      </el-button>
      <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
      <!-- 两个入口不是一回事：动态流是好友与配对对象的游标无限流，「我的动态」是按条件分页翻自己的历史（含已下架的那几条） -->
      <el-radio-group v-if="isMineFeed" v-model="viewMode" size="small" @change="switchView">
        <el-radio-button value="feed">动态流</el-radio-button>
        <el-radio-button value="mine">我的动态</el-radio-button>
      </el-radio-group>
      <!-- 「公开」这个词最容易被读成全站公开，所以在工具条上就把受众说清，不等用户刷不到人再来问 -->
      <span class="peerbar__hint">{{ viewHint }}</span>
    </div>

    <!-- 筛选栏只属于「我的动态」：动态流是滚到底自动续页的，那里没有「第几页」可筛 -->
    <div v-if="isMineView" class="minebar">
      <el-select v-model="filters.contentType" size="small" placeholder="全部形态" clearable @change="applyMineFilter">
        <el-option v-for="opt in KIND_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-select v-model="filters.status" size="small" placeholder="全部状态" clearable @change="applyMineFilter">
        <el-option label="正常" :value="1" />
        <el-option label="已下架" :value="STATUS_OFFLINE" />
      </el-select>
      <el-date-picker
        v-model="filters.dates"
        type="daterange"
        size="small"
        class="minebar__date"
        value-format="YYYY-MM-DD"
        format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        :shortcuts="DATE_SHORTCUTS"
        clearable
        @change="applyMineFilter"
      />
      <el-input
        v-model="filters.keyword"
        size="small"
        class="minebar__kw"
        placeholder="搜正文关键字"
        clearable
        :prefix-icon="Search"
        @keyup.enter="applyMineFilter"
        @clear="applyMineFilter"
      />
      <el-input-number
        v-model="filters.minCommentCount"
        size="small"
        class="minebar__num"
        :min="0"
        :max="999999"
        :controls="false"
        :value-on-clear="null"
        placeholder="评论≥"
        @change="applyMineFilter"
      />
      <el-input-number
        v-model="filters.minViewCount"
        size="small"
        class="minebar__num"
        :min="0"
        :max="999999"
        :controls="false"
        :value-on-clear="null"
        placeholder="浏览≥"
        @change="applyMineFilter"
      />
      <el-select v-model="filters.sortBy" size="small" placeholder="排序" @change="applyMineFilter">
        <el-option v-for="opt in SORT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-button size="small" @click="toggleSortOrder">{{ filters.sortOrder === 'asc' ? '升序' : '降序' }}</el-button>
      <el-button size="small" text @click="resetMineFilter">重置</el-button>
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
            <!-- 视频单独一层：竖屏视频挤在九宫格里根本看不清，给一个占满整行的大窗口外加放大弹层 -->
            <div v-if="m.type === MEDIA_VIDEO" class="moment__videobox">
              <video class="moment__video" :src="mediaUrl(m.url)" controls preload="metadata" />
              <el-button
                class="moment__video-zoom"
                size="small"
                type="primary"
                circle
                title="放大播放"
                @click="openVideo(m)"
              >
                <el-icon><FullScreen /></el-icon>
              </el-button>
            </div>
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
          <!-- 浏览量只对作者下发（别人看到自己的动态被多少人点开过就是泄底），所以这里判的是「字段在不在」 -->
          <span v-if="item.viewCount !== null && item.viewCount !== undefined" class="moment__count">
            浏览 {{ item.viewCount || 0 }}
          </span>
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

      <el-empty v-if="!loading && list.length === 0" :image-size="72" :description="emptyText">
        <div v-if="isMineFeed && !isMineView" class="moment-feed__tip">
          发一条动态，你的好友与配对对象就能在这里看到；也可以在资料页把「动态可见天数」调宽。
        </div>
        <el-button v-if="isMineFeed && !isMineView" type="primary" plain @click="openPublish">发第一条</el-button>
        <!-- 分页模式下的空列表有两种意思：真的一条没有，与有但都被条件筛掉了，后者得能一键退回 -->
        <el-button v-if="isMineView && filtersDirty" size="small" @click="resetMineFilter">清空筛选条件</el-button>
      </el-empty>

      <!-- 分页模式靠页码而不是滚动到底：翻几年前的历史时自动续页会把人不知道带到哪一页 -->
      <div v-if="isMineView && list.length" class="moment-feed__pager">
        <el-pagination
          v-model:current-page="mine.current"
          v-model:page-size="mine.size"
          background
          small
          layout="total, sizes, prev, pager, next, jumper"
          :total="mine.total"
          :page-sizes="[10, 20, 50]"
          @size-change="onMineSize"
          @current-change="fetchMine"
        />
      </div>
      <div v-else-if="list.length" class="moment-feed__more">
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

    <!-- ==================== 视频放大播放 ==================== -->
    <!-- 卡片里的窗口再放宽也受版面限制：竖屏视频与长视频要点开一个几乎满屏的层才看得清，
         destroy-on-close 是必需的：关掉后不销毁就不会停下来，音频会始终在背后面跑 -->
    <el-dialog
      v-model="videoPlayer.visible"
      title="播放视频"
      width="min(1000px, 94vw)"
      top="4vh"
      append-to-body
      destroy-on-close
      class="video-player"
    >
      <video class="video-player__el" :src="videoPlayer.src" controls autoplay preload="metadata" />
    </el-dialog>

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
  ChatDotRound, CircleCloseFilled, EditPen, FullScreen, Loading, Picture, PictureFilled, Plus, Promotion,
  Search, Select, Star, VideoCamera
} from '@element-plus/icons-vue'
import UserAvatar from './UserAvatar.vue'
import MomentDetailDialog from './MomentDetailDialog.vue'
import {
  deleteMoment, fetchMatchPairs, fetchMomentFeed, fetchMyMoments, fetchUserMoments, likeMoment,
  publishMoment, shareMoment, unlikeMoment
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
 *
 * <p>「我的动态」是同一个组件里的第二种读法（{@link #viewMode}）：游标流不适合回头查
 * 自己几年前发的那一条，所以那里换成页码分页 + 时间/评论数/浏览量筛选，并且**不过可见范围、
 * 含已下架的行** —— 否则作者会以为自己的动态发丢了。
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

/** 内容形态与后端 MomentMapper.KIND_IMAGE / KIND_VIDEO / KIND_TEXT 一致，按附件实际形态而不是发布时填的类型 */
const KIND_OPTIONS = [
  { value: 1, label: '图文' },
  { value: 2, label: '视频' },
  { value: 3, label: '纯文字' }
]
/** 排序字段，与后端 orderColumnOf 的 switch 一一对应；这里多一个值后端只会当成时间，不会拼进 SQL */
const SORT_OPTIONS = [
  { value: 'time', label: '按发布时间' },
  { value: 'view', label: '按浏览量' },
  { value: 'comment', label: '按评论数' },
  { value: 'like', label: '按点赞数' }
]

/** 日期快捷项给的是 Date，填进表单时由 value-format 统化成 YYYY-MM-DD */
function recentRange(days) {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - days + 1)
  return [start, end]
}
const DATE_SHORTCUTS = [
  { text: '近 7 天', value: () => recentRange(7) },
  { text: '近 30 天', value: () => recentRange(30) },
  { text: '近 90 天', value: () => recentRange(90) },
  { text: '今年', value: () => [new Date(new Date().getFullYear(), 0, 1), new Date()] }
]

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

/* --------------------- 我的动态：分页与筛选 --------------------- */

const viewMode = ref('feed')
/** 紧凑模式是嵌在资料卡里的一小块，那一屏既没有地方放筛选栏，也不该有两个入口 */
const isMineView = computed(() => isMineFeed.value && !props.compact && viewMode.value === 'mine')
const mine = reactive({ current: 1, size: 10, total: 0 })
const filters = reactive({
  contentType: null,
  status: null,
  dates: [],
  keyword: '',
  minCommentCount: null,
  minViewCount: null,
  sortBy: 'time',
  sortOrder: 'desc'
})

// el-select 清空后给的是 undefined 而不是 null，所以这里统一按「不为空」判，
// 否则清空一次筛选会仍被当成脏条件（那个「清空筛选条件」按钮就会一直在）
const filtersDirty = computed(() =>
  filters.contentType != null || filters.status != null || filters.keyword.trim() !== ''
  || filters.minCommentCount != null || filters.minViewCount != null
  || (Array.isArray(filters.dates) && filters.dates.length === 2)
)

const viewHint = computed(() => {
  if (isMineView.value) {
    return '自己发过的全部动态，含已下架的，不受可见范围与可见天数限制'
  }
  return '只显示你的好友与配对对象的动态，「公开」不是全站公开'
})

const emptyText = computed(() => {
  if (isMineView.value) {
    return filtersDirty.value ? '没有符合筛选条件的动态' : '还没有动态'
  }
  return isMineFeed.value ? '还没有动态' : 'TA 还没有可见的动态'
})

/**
 * 拉一页「我的动态」。
 *
 * 不复用 exhausted：分页有 total，翻到最后一页 el-pagination 自己就禁了下一页；
 * 也不往 list 里追加：页码翻页的语义就是「当前页只放这一页的内容」。
 * 这里不加「已在加载就返回」的闸门而是用序号让最后一次请求说了算：切页码要能连续响应，
 * 而一个迟到的旧页盖掉新页比多一个请求难查得多。
 */
let mineSeq = 0

async function fetchMine() {
  const seq = ++mineSeq
  loading.value = true
  try {
    const [startDate, endDate] = Array.isArray(filters.dates) ? filters.dates : []
    const page = (await fetchMyMoments({
      current: mine.current,
      size: mine.size,
      contentType: filters.contentType,
      status: filters.status,
      startDate,
      endDate,
      sortBy: filters.sortBy,
      sortOrder: filters.sortOrder,
      minCommentCount: filters.minCommentCount,
      minViewCount: filters.minViewCount,
      keyword: (filters.keyword || '').trim()
    })) || {}
    if (seq !== mineSeq) {
      // 已经有人点了更新的页或改了条件，这个迟到的响应不能再写列表
      return
    }
    list.value = page.records || []
    mine.total = Number(page.total) || 0
    emit('loaded', list.value.length)
  } catch {
    // 提示由 request.js 弹过，列表留在原地；把人推到空列表比留着旧数据更难解释
  } finally {
    if (seq === mineSeq) {
      loading.value = false
    }
  }
}

/** 改任何一个条件都回到第一页：停在第 6 页改筛选条件，多半会落到一个空页上 */
function applyMineFilter() {
  mine.current = 1
  fetchMine()
}

function resetMineFilter() {
  filters.contentType = null
  filters.status = null
  filters.dates = []
  filters.keyword = ''
  filters.minCommentCount = null
  filters.minViewCount = null
  filters.sortBy = 'time'
  filters.sortOrder = 'desc'
  applyMineFilter()
}

function toggleSortOrder() {
  filters.sortOrder = filters.sortOrder === 'asc' ? 'desc' : 'asc'
  applyMineFilter()
}

function onMineSize() {
  mine.current = 1
  fetchMine()
}

function switchView() {
  list.value = []
  exhausted.value = false
  if (isMineView.value) {
    mine.current = 1
    fetchMine()
    return
  }
  fetchPage(true)
}

/* ------------------------------ 列表 ------------------------------ */

/**
 * 拉一页。
 *
 * 游标推进用「本页最后一条」而不是「挑中的最后一条」——后端已经这么做了，
 * 前端只要把返回数组的尾巴交回去即可，不要自己按时间排序后另取一个游标。
 */
async function fetchPage(reset) {
  // 「我的动态」与游标流是两套取数口径，共用的只有 reload 这一个入口
  if (isMineView.value) {
    return fetchMine()
  }
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
    if (isMineView.value) {
      // 请求在飞的这段时间里用户切到了「我的动态」：这一批游标流的结果不能再写回列表
      return
    }
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

/** 滚动到离底 120px 以内就自动续一页：这一列是无限流，让人每屏都去找「加载更多」按钮很蠢 */
function onScroll() {
  const el = bodyRef.value
  // 分页模式下不自动续页：那会把页码与看到的内容对不上，翻到第 3 页却插进第 4 的数据
  if (!el || isMineView.value || exhausted.value || loading.value || loadingMore.value) {
    return
  }
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 120) {
    loadMore()
  }
}

function authorName(item) {
  return (item.author && item.author.nickname) || '该用户已注销'
}

/**
 * 一张图给大图，2 或 4 张给两列，其余给三列：九宫格的意义就是让格数看起来是齐的。
 *
 * 视频必须在最前面单独判：它永远只有一条，落到下面三个分支里会被归成「0 张图」而拿到
 * triple，于是在一个 480px 宽的三列网格里只分到三分之一——这就是「播放窗口太小」的根源。
 */
function gridClass(media) {
  const images = (media || []).filter((m) => m.type !== MEDIA_VIDEO)
  if (images.length !== (media || []).length) {
    return 'moment__media--video'
  }
  if (images.length === 1) {
    return 'moment__media--single'
  }
  if (images.length === 2 || images.length === 4) {
    return 'moment__media--double'
  }
  return 'moment__media--triple'
}

/* --------------------------- 视频放大播放 --------------------------- */

const videoPlayer = reactive({ visible: false, src: '' })

/**
 * 开一个满屏的播放层。
 *
 * 地址现取 mediaUrl：卡片里那份可能还是个空串（blob 没回写完成），
 * 而弹层里的视频一旦绑上空 src 就不会再自己重取，所以进弹层时重新调一次。
 */
function openVideo(media) {
  videoPlayer.src = mediaUrl(media.url)
  videoPlayer.visible = true
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
    // 分页模式下的总数得跟着减，否则最后一行只剩一条而页码显示「共 11 条」
    if (isMineView.value && mine.total > 0) {
      mine.total -= 1
    }
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
    if (isMineView.value) {
      // 分页模式回到第一页重拉：新发的那一条按时间一定在第一页，而就地插会多出一条不属于这一页的卡片
      mine.current = 1
      mine.total += 1
      fetchMine()
    } else if (isMineFeed.value && vo && vo.momentId) {
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
    // 切到别人的主页时把分页状态带回去：这一列只在自己翻历史时才有意义，
    // 留着 mine 会让回到自己页面时直接落在一个带旧筛选条件的分页上
    viewMode.value = 'feed'
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

/* 「我的动态」的筛选栏：控件一律 small，挤不下一行就换行，不为它专门开一个弹层 */
.minebar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 0 8px;
}

.minebar__date {
  width: 240px;
}

.minebar__kw {
  width: 160px;
}

.minebar__num {
  width: 84px;
}

/* el-input-number 的 placeholder 默认居中且不换行，收窄后要让它靠右才能与旁边的数字一眼对上 */
.minebar__num :deep(.el-input__inner) {
  text-align: right;
}

.moment-feed__pager {
  display: flex;
  justify-content: center;
  padding: 10px 0 4px;
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

/* 视频占满整行而不是九宫格里的一格：一行只能有一个附件，网格收成一个列 */
.moment__media--video {
  grid-template-columns: minmax(0, 1fr);
  max-width: 720px;
}

.moment__videobox {
  position: relative;
  min-width: 0;
}

.moment__video {
  width: 100%;
  /* 从 420px 放宽到按视口算的高度：竖屏视频在 420px 里只能看到一小块画面 */
  max-height: min(70vh, 640px);
  border-radius: 4px;
  background: #000;
  display: block;
}

.moment__video-zoom {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  opacity: 0.85;
}

.moment__video-zoom:hover {
  opacity: 1;
}

.video-player__el {
  display: block;
  width: 100%;
  max-height: 76vh;
  margin: 0 auto;
  border-radius: 6px;
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
