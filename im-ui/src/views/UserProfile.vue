<template>
  <div class="user-profile im-scroll">
    <div class="user-profile__inner">
      <div class="user-profile__back">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
      </div>

      <el-skeleton v-if="loading" :rows="5" animated class="user-profile__card" />

      <!-- 用户 ID 打错、账号已注销都会走到这里，给一个明确的说法而不是空白页 -->
      <el-empty v-else-if="!card" description="用户不存在或已注销">
        <el-button type="primary" @click="goBack">返回</el-button>
      </el-empty>

      <template v-else>
        <!-- ==================== 身份 ==================== -->
        <section class="user-profile__card">
          <div class="user-profile__head">
            <UserAvatar :src="card.avatar" :name="displayName" :size="88" :online="!!card.online" />
            <div class="user-profile__identity">
              <div class="user-profile__nickname">
                <span class="user-profile__nickname-text">{{ displayName }}</span>
                <el-tag v-if="card.remark" size="small" effect="plain">已备注</el-tag>
                <el-tag v-if="blocked" size="small" type="danger" effect="plain">已在黑名单</el-tag>
                <el-tag v-if="blockedByOther" size="small" type="warning" effect="plain">对方已拉黑我</el-tag>
              </div>
              <div class="user-profile__account">@{{ card.username }}</div>
              <div class="user-profile__signature">{{ card.signature || '这个人很懒，什么都没留下' }}</div>
            </div>
          </div>

          <el-descriptions :column="2" border size="small" class="user-profile__facts">
            <el-descriptions-item label="用户 ID">{{ card.userId }}</el-descriptions-item>
            <el-descriptions-item label="性别">{{ genderText(card.gender) }}</el-descriptions-item>
            <el-descriptions-item label="在线状态">
              <span class="user-profile__state" :class="{ 'user-profile__state--off': !card.online }">
                <i class="user-profile__dot"></i>{{ card.online ? '在线' : '离线' }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="注册时间">
              {{ formatDateTime(card.createTime) || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="好友关系">
              {{ isSelf ? '这是你自己' : isFriend ? '已是好友' : '非好友' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="relation" label="好友分组">
              {{ relation.groupName || '未分组' }}
            </el-descriptions-item>
            <!-- 关系建立时间只有 FriendVO 才带，UserCardVO 里没有这个字段 -->
            <el-descriptions-item v-if="relation" label="成为好友">
              {{ formatDateTime(relation.createTime) || '—' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="relatedRequest" label="申请记录" :span="2">
              {{ formatDateTime(relatedRequest.createTime) }} · {{ relatedRequest.statusDesc }}
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="blockedByOther" class="user-profile__warn">
            对方已将你加入黑名单，你发出的消息会被拒绝，直到对方取消拉黑。
          </div>
          <div v-else-if="blocked" class="user-profile__warn">
            你已将对方加入黑名单：对方发来的消息会被拦截，但你仍可主动发消息，发送后自动解除拉黑。
          </div>
        </section>

        <!-- ==================== 交友资料 ==================== -->
        <section v-if="datingFacts.length || otherTags.length || photoWall.length" class="user-profile__card">
          <div class="user-profile__card-title user-profile__card-title--row">
            <span>交友资料</span>
            <span v-if="dating && dating.distanceText" class="user-profile__distance">
              <el-icon><Location /></el-icon>{{ dating.distanceText }}
            </span>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item v-for="item in datingFacts" :key="item.label" :label="item.label">
              {{ item.value }}
            </el-descriptions-item>
          </el-descriptions>
          <!-- 兴趣标签按对方自己拖的顺序给，最重要的兴趣在前；后端已经按这个序排好了，这里不再排 -->
          <div v-if="otherTags.length" class="user-profile__tags">
            <span v-for="item in otherTags" :key="item.tagId" class="user-profile__tag">{{ item.name }}</span>
          </div>
          <!--
            照片墙：后端只会把审核通过的几张发过来，因此这里拿到的就是「能被看的」，
            不需要也不应该在前端再按状态过滤一遍（那等于默认相信接口会漏）。
          -->
          <div v-if="photoWall.length" class="user-profile__wall">
            <el-image
              v-for="(item, index) in photoWall"
              :key="item.id"
              class="user-profile__wall-img"
              :src="mediaUrl(item.url)"
              :preview-src-list="photoWallSrcs"
              :initial-index="index"
              preview-teleported
              fit="cover"
            />
          </div>
        </section>

        <!-- ==================== TA 的动态 ==================== -->
        <!--
          v-show 而不是 v-if：组件得先挂载才能拉一次并报出条数，而陌生人恒为空列表
          （可见范围逐条过滤后什么都不剩）—— 为「什么都看不到」留一张空卡只是噪音。
          :key 绑 targetId：同一路由下换 :id 时组件会被复用，不重建就会把上一个人的列表留在屏上。
        -->
        <section v-show="momentsVisible" class="user-profile__card">
          <div class="user-profile__card-title">{{ isSelf ? '我的动态' : 'TA 的动态' }}</div>
          <MomentFeed :key="targetId" :user-id="targetId" compact @loaded="onMomentsLoaded" />
        </section>

        <!-- ==================== 亲密度与火花 ==================== -->
        <!--
          后端从没来往过时也回一组 0，所以这里只需要判「有没有拿到」，不需要判「是不是 0」。
          进度条绑的是 progress（当前档位内的百分比），档位边界只有后端知道，
          前端不去用 level 反推分值——那边插一档就会出现两边进度条不一样长。
        -->
        <section v-if="intimacy" class="user-profile__card">
          <div class="user-profile__card-title user-profile__card-title--row">
            <span>你们的火花</span>
            <span class="user-profile__tip">{{ intimacy.levelDesc }} · {{ intimacy.score }} 分</span>
          </div>
          <div class="user-profile__spark" :class="{ 'user-profile__spark--off': !intimacy.sparkAlive }">
            <strong>{{ intimacy.streakDays }}</strong>
            <span>天连续聊天</span>
          </div>
          <div class="user-profile__tip">{{ sparkHint }}</div>
          <el-progress
            class="user-profile__spark-bar"
            :percentage="intimacy.progress"
            :stroke-width="8"
            :show-text="false"
          />
          <div class="user-profile__tip">{{ nextLevelHint }}</div>
          <div class="user-profile__spark-stats">
            <span>消息 {{ intimacy.msgCount }} 条</span>
            <span>互动 {{ intimacy.interactCount }} 次</span>
            <span>共同好友 {{ intimacy.commonFriendCount }}</span>
            <span>共同群 {{ intimacy.commonGroupCount }}</span>
          </div>
        </section>

        <!-- ==================== 破冰建议 ==================== -->
        <!--
          三档分开展示而不是混成一列：标签开场白带「你们都喜欢 X」这个前提，
          混在打招呼模板里就看不出它为什么比别的句子更值得发。
          后端返回的 content 已经是成品（占位符替过），这里不再做任何模板处理。
        -->
        <section v-if="icebreakerGroups.length" class="user-profile__card">
          <div class="user-profile__card-title user-profile__card-title--row">
            <span>不知道聊什么？</span>
            <span class="user-profile__tip">点一条即复制，去聊天框粘贴发出</span>
          </div>
          <div v-for="group in icebreakerGroups" :key="group.key" class="user-profile__ice-group">
            <div class="user-profile__ice-label">{{ group.label }}</div>
            <div class="user-profile__ice-list">
              <button
                v-for="item in group.items"
                :key="item.id"
                type="button"
                class="user-profile__ice-item"
                @click="pickIcebreaker(item)"
              >
                {{ item.content }}
              </button>
            </div>
          </div>
        </section>

        <!-- ==================== 操作 ==================== -->
        <section class="user-profile__card">
          <div class="user-profile__card-title">{{ isSelf ? '我的资料' : '操作' }}</div>

          <div v-if="isSelf" class="user-profile__actions">
            <el-button type="primary" :icon="Setting" @click="goProfile">前往个人中心</el-button>
            <span class="user-profile__tip">资料卡展示的是别人看到的你，修改资料请到个人中心。</span>
          </div>

          <div v-else class="user-profile__actions">
            <!--
              不可用时置灰而不是隐藏，并把原因写在按钮旁边：
              后端 createSingle 对「非好友 / 被对方拉黑」一律拒绝，
              让按钮可点再弹一条错误码翻译过来的提示，用户只会觉得是系统坏了。
            -->
            <el-tooltip :content="chatDisabledReason" :disabled="!chatDisabledReason" placement="top">
              <span>
                <el-button type="primary" :icon="ChatDotRound" :disabled="!canChat" @click="startChat">
                  发起聊天
                </el-button>
              </span>
            </el-tooltip>

            <template v-if="isFriend">
              <el-button :icon="EditPen" @click="openRemark">修改备注</el-button>
              <el-button
                :type="blocked ? 'success' : 'warning'"
                plain
                :icon="blocked ? RefreshLeft : CircleClose"
                :loading="acting === 'block'"
                @click="toggleBlock"
              >
                {{ blocked ? '移出黑名单' : '加入黑名单' }}
              </el-button>
              <el-button
                type="danger"
                plain
                :icon="Delete"
                :loading="acting === 'remove'"
                @click="removeFriend"
              >
                删除好友
              </el-button>
            </template>

            <el-button v-else type="success" :icon="Plus" :disabled="!canApply" @click="openApply">
              添加好友
            </el-button>

            <!--
              从资料卡直接喜欢：看到合眼缘的人不必退回交友页翻卡池。只在对方填过交友资料时出现
              （一个连一行资料都没有的人进不了卡池，在这里喜欢 TA 等于把一个隐身号拉进配对）。
            -->
            <el-button
              v-if="canLike"
              type="danger"
              plain
              :icon="Star"
              :loading="acting === 'like'"
              @click="likePeer"
            >
              喜欢 TA
            </el-button>

            <!--
              举报和拉黑是两件事：拉黑只挡住发给我的消息，举报是让运营去看这个人。
              isSelf 在上面已经分叉，这里不会举报自己（后端 REPORT_SELF_FORBIDDEN 也会拒）；
              report.enabled 是服务端开关，功能关了整按钮消失，而不是留个点了弹错的入口。
            -->
            <el-button v-if="report.enabled" type="warning" plain :icon="Warning" @click="reportUser">
              举报该用户
            </el-button>

            <span v-if="chatDisabledReason" class="user-profile__tip">{{ chatDisabledReason }}</span>
          </div>
        </section>
      </template>
    </div>

    <!-- ==================== 添加好友 ==================== -->
    <el-dialog v-model="applyDialog.visible" title="添加好友" width="420px">
      <div class="user-profile__dialog-target">
        <UserAvatar :src="card && card.avatar" :name="displayName" :size="40" />
        <div>
          <div>{{ displayName }}</div>
          <div class="user-profile__account">@{{ card && card.username }}</div>
        </div>
      </div>
      <el-input
        v-model="applyDialog.message"
        type="textarea"
        :rows="3"
        maxlength="255"
        show-word-limit
        placeholder="填写验证消息，让对方知道你是谁"
      />
      <template #footer>
        <el-button @click="applyDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="applyDialog.saving" @click="submitApply">发送申请</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 修改备注 ==================== -->
    <el-dialog
      v-model="remarkDialog.visible"
      title="修改备注"
      width="380px"
      @opened="focusRemarkInput"
    >
      <el-input
        ref="remarkInputRef"
        v-model.trim="remarkDialog.value"
        maxlength="32"
        show-word-limit
        clearable
        placeholder="留空则显示对方昵称"
        @keyup.enter="submitRemark"
      />
      <template #footer>
        <el-button @click="remarkDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="remarkDialog.saving" @click="submitRemark">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ChatDotRound,
  CircleClose,
  Delete,
  EditPen,
  Location,
  Plus,
  RefreshLeft,
  Setting,
  Star,
  Warning
} from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import MomentFeed from '@/components/MomentFeed.vue'
import { fetchUserCard } from '@/api/user'
import { actMatch, fetchDatingCard, fetchIcebreakers, fetchIntimacy, fetchPhotos, fetchTags, useIcebreaker } from '@/api/dating'
import { fetchFriendCard } from '@/api/friend'
import { useAuthStore } from '@/stores/auth'
import { useFriendStore } from '@/stores/friend'
import { useConversationStore } from '@/stores/conversation'
import { useReportStore } from '@/stores/report'
import { asId, sameId } from '@/utils/id'
import { formatDateTime, genderText } from '@/utils/format'
import { mediaUrl } from '@/utils/media'

defineOptions({ name: 'UserProfile' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const friend = useFriendStore()
const conversation = useConversationStore()
const report = useReportStore()

/** 路由参数一律是字符串，而后端 ID 是雪花数字，比较前必须先归一化 */
const targetId = computed(() => asId(route.params.id))

const loading = ref(false)
const card = ref(null)
/** 好友关系详情，仅在已是好友且关系仍然存在时才有值 */
const relation = ref(null)
/** 当前正在执行的操作，用于给对应按钮上 loading */
const acting = ref('')
/** 交友资料（/api/dating/profile/{id}）；对方一行资料都没填时为 null */
const dating = ref(null)
/** 对方的照片墙：接口只回审核通过的那几张，这里不再按状态过滤一遍 */
const photoWall = ref([])
/** 大图预览用的地址列表：在 computed 里调 mediaUrl，取回 blob 后预览器才会跟着重渲染 */
const photoWallSrcs = computed(() => photoWall.value.map((item) => mediaUrl(item.url)))
/** 对方的兴趣标签（已下架的那些仍会给，名字本身还是对方说过的话） */
const otherTags = ref([])
/** 破冰建议（/api/dating/icebreakers）；看自己时不拉，保持 null */
const ice = ref(null)
/** 亲密度与火花（/api/dating/intimacy）；同样只在看别人时拉 */
const intimacy = ref(null)

const isSelf = computed(() => sameId(card.value && card.value.userId, auth.userId))
const isFriend = computed(() => !!(card.value && card.value.friend))
const blocked = computed(() => !!(card.value && card.value.blocked))
const blockedByOther = computed(() => !!(card.value && card.value.blockedByOther))
/** 单向阻断：只拦「对方拉黑我」；我拉黑对方后仍可主动发，发送成功会顺带自动解除拉黑 */
const canChat = computed(() => isFriend.value && !blockedByOther.value)
const canApply = computed(() => !isSelf.value && auth.hasPermission('friend:apply'))
/** 喜欢 TA：与滑动匹配同一个权限，对方得先有一张能出现在卡池里的交友资料 */
const canLike = computed(() => !isSelf.value && !isFriend.value && !!dating.value && canApply.value)
const displayName = computed(() => {
  const target = card.value
  if (!target) {
    return ''
  }
  return target.remark || target.nickname || target.username || `用户${target.userId}`
})

const chatDisabledReason = computed(() => {
  if (isSelf.value || canChat.value) {
    return ''
  }
  if (blockedByOther.value) {
    return '对方已将你加入黑名单，无法发起聊天'
  }
  return '添加为好友后才能发消息'
})

/**
 * 与这个人的好友申请记录，用于展示「好友申请时间」。
 *
 * 只能从 store 里已经加载过的申请列表中找：后端没有「按目标用户查申请」的接口，
 * 而在这里翻页拉全量会覆盖 friend.sent / friend.received，把申请管理页的分页状态搞乱
 * （那个页面只在列表为空时才重新拉数据）。所以这是尽力而为 —— 找到就显示，
 * 找不到整行不渲染，不会给出一个错误的时间。
 */
const relatedRequest = computed(() => {
  const id = targetId.value
  if (!id || isFriend.value) {
    return null
  }
  return (
    friend.sent.find((item) => sameId(item.peerUserId, id)) ||
    friend.received.find((item) => sameId(item.peerUserId, id)) ||
    null
  )
})

/* ------------------------------ 交友资料展示 ------------------------------ */

/**
 * 只拼有值的行。
 *
 * <p>年龄为空代表两种情况：对方没填，或对方藏了年龄（showAge=0）——后端两种情况都返回 null，
 * 前端也不去区分，因为对看资料的人来说结果一样：这一行不该存在。
 * 把它渲染成「0 岁」或「未填」都是替对方多交代了一层信息。
 */
const datingFacts = computed(() => {
  const d = dating.value
  if (!d) {
    return []
  }
  const facts = [
    { label: '年龄', value: d.age != null ? `${d.age} 岁` : '' },
    { label: '身高', value: d.heightCm ? `${d.heightCm} cm` : '' },
    { label: '学历', value: d.educationDesc || '' },
    { label: '职业', value: d.occupation || '' },
    { label: '收入区间', value: d.incomeDesc || '' },
    { label: '婚恋状况', value: d.marriageDesc || '' },
    { label: '交友目的', value: d.datingGoalDesc || '' },
    { label: '所在地', value: d.locationText || '' }
  ]
  return facts.filter((item) => item.value)
})

/* ------------------------------ 亲密度与火花 ------------------------------ */

/**
 * 火花旁边那句说明。
 *
 * 熄灭时写清楚「说一句话就能重新点燃」而不是只写「已熄灭」：
 * 天数归零是个不可逆的事实，但接下来该做什么得说出来，否则这一块看起来像一条惩罚。
 * 历史最高只在它比当前高时提：两者相等时「最长连续过 3 天」和上面那个 3 是同一个数，重复了。
 */
const sparkHint = computed(() => {
  const data = intimacy.value
  if (!data) {
    return ''
  }
  const parts = [data.sparkAlive ? '今天或昨天聊过，火花还亮着' : '火花已经熄灭，说一句话就能重新点燃']
  if (data.maxStreak > data.streakDays) {
    parts.push(`最长连续过 ${data.maxStreak} 天`)
  }
  return parts.join(' · ')
})

/**
 * 进度条下面那句「还差多少」。
 *
 * 分值差在前端算（两个已经下发的数相减），但**下一档叫什么名字不在前端算**：
 * 后端只给了 nextLevelScore，没给下一档的文案，自己拿 level+1 去映射一份名字
 * 就等于在前端存了一份档位表，后端插一档两边就会对不上。
 */
const nextLevelHint = computed(() => {
  const data = intimacy.value
  if (!data) {
    return ''
  }
  if (!data.nextLevelScore) {
    return '已经是最高档了'
  }
  // 差值理论上恒为正（下一档的下界一定大于当前分），夹一下只为防库里的分被手改过
  return `再累积 ${Math.max(0, data.nextLevelScore - data.score)} 分升到下一档`
})

/* ------------------------------ 破冰建议 ------------------------------ */

/**
 * 三档分组；标签开场白排在最前。
 *
 * 它有「你们都喜欢 X」这个前提，是三档里最不容易被当成客套话的一句；
 * 打招呼模板与话题卡则谁都能拿到，排后面。
 * 三档全空时整块不渲染（后端把字典清空了才会这样）。
 */
const icebreakerGroups = computed(() => {
  const data = ice.value
  if (!data) {
    return []
  }
  const groups = []
  const openers = data.tagOpeners || []
  if (openers.length) {
    const names = (data.commonTags || []).join('、')
    groups.push({
      key: 'tag',
      label: names ? `你们都喜欢：${names}` : '按共同兴趣挑的开场白',
      items: openers
    })
  }
  const greetings = data.greetings || []
  if (greetings.length) {
    groups.push({ key: 'greeting', label: '打招呼模板', items: greetings })
  }
  const topics = data.topics || []
  if (topics.length) {
    groups.push({ key: 'topic', label: '聊不下去时，抛个问题', items: topics })
  }
  return groups
})

/**
 * 点一条：先复制到剪贴板，再静默记一次使用。
 *
 * 计数失败不影响复制结果，所以它不 await、也不弹错：用户已经把句子拿到手了，
 * 为了一个运营用的计数告诉他「操作失败」只会让人以为没复制上。
 */
async function pickIcebreaker(item) {
  await copyText(item.content)
  useIcebreaker(item.id, { silent: true }).catch(() => {})
}

/** 非 HTTPS 且非 localhost 时 navigator.clipboard 不可用，退回 execCommand（与其它页面同一个写法）。 */
async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
    }
    ElMessage.success('已复制，去聊天框粘贴即可')
  } catch {
    ElMessage.warning('浏览器不允许访问剪贴板，请长按或选中文字手动复制')
  }
}

/* -------------------------------- 加载 -------------------------------- */

/**
 * 请求序号。快速在两个用户之间切换时，先发出的那个请求可能后返回，
 * 不做这层判断就会把旧用户的资料糊到新用户的页面上。
 */
let loadToken = 0

async function load() {
  const id = targetId.value
  if (!id) {
    return
  }
  const token = ++loadToken
  loading.value = true
  card.value = null
  relation.value = null
  dating.value = null
  photoWall.value = []
  otherTags.value = []
  ice.value = null
  intimacy.value = null
  try {
    const vo = await fetchUserCard(id)
    if (token !== loadToken) {
      return
    }
    card.value = vo
    // 交友块与好友详情互不依赖，单独一发：对方一行交友资料都没填也得得到一张空卡，
    // 但不能因为它报错就把整个资料卡打回空状态
    fetchDatingCard(id, { silent: true })
      .then((dto) => {
        if (token === loadToken) {
          dating.value = dto
        }
      })
      .catch(() => {})
    // 照片、标签与结构化资料是三个接口：一张没传的人仍然可以有一堆填齐的条件
    fetchPhotos(id, { silent: true })
      .then((list) => {
        if (token === loadToken) {
          photoWall.value = list || []
        }
      })
      .catch(() => {})
    fetchTags(id, { silent: true })
      .then((list) => {
        if (token === loadToken) {
          otherTags.value = list || []
        }
      })
      .catch(() => {})
    // 破冰建议与亲密度只在「看别人」时拉：自己资料页上摆一排开场白没有对象可发，
    // 而后端拿到自己当目标也只会退回通用两档与一组 0，不如不发这两个请求
    if (!sameId(id, auth.userId)) {
      fetchIcebreakers(id, { silent: true })
        .then((dto) => {
          if (token === loadToken) {
            ice.value = dto
          }
        })
        .catch(() => {})
      // 亲密度拉失败就整块不渲染：一块写着「0 天 0 分」的卡片比没有卡片更误导人
      fetchIntimacy(id, { silent: true })
        .then((dto) => {
          if (token === loadToken) {
            intimacy.value = dto
          }
        })
        .catch(() => {})
    }
    if (vo && vo.friend) {
      try {
        relation.value = await fetchFriendCard(id, { silent: true })
      } catch {
        // 对方刚好在这期间删了好友：把资料卡上的关系位纠正过来，
        // 否则界面会摆着一排点了必然报错的按钮
        if (token === loadToken && card.value) {
          card.value.friend = false
        }
        relation.value = null
      }
    }
  } catch {
    // 用户不存在或已注销，card 保持 null，模板走空状态分支
  } finally {
    if (token === loadToken) {
      loading.value = false
    }
  }
}

// 同一个路由下换 :id 时组件会被复用，必须靠 watcher 而不是 onMounted
watch(targetId, load, { immediate: true })

/* ------------------------------ TA 的动态 ------------------------------ */

/** 首页报上来的条数，只用来决定整张卡要不要显示 */
const momentsCount = ref(0)

/**
 * 这一张卡要不要显示。
 *
 * 看自己时恒显示：主页上要能看到自己被下架的那几条，而那一档只有 /user/{id} 才给。
 * isSelf 依赖 card，而 card 可能比动态晚到，所以这里用 computed 而不是在回调里一次算完 ——
 * 否则「自己的主页上一条动态都没发过」会因为 card 还没回来而被永久藏掉。
 */
const momentsVisible = computed(
  () => momentsCount.value > 0 || isSelf.value || sameId(targetId.value, auth.userId)
)

function onMomentsLoaded(count) {
  momentsCount.value = count
}

// 换 :id 时先把上一个人的条数清掉：不清就会先按旧条数显示一下再消失
watch(targetId, () => {
  momentsCount.value = 0
})

/* -------------------------------- 操作 -------------------------------- */

function goProfile() {
  router.push({ name: 'profile' })
}

/**
 * 在资料卡上直接喜欢 TA。
 *
 * 成对时会话已由后端建好，这里先刷会话列表再问要不要过去；与交友页不同，
 * 本页不需要处理「继续刷」，用户留下自然还在资料上。
 */
async function likePeer() {
  acting.value = 'like'
  try {
    const result = await actMatch({ targetUserId: targetId.value, action: 1, source: 1 })
    if (result && result.matched) {
      conversation.fetchList()
      try {
        await ElMessageBox.confirm(`已和「${displayName.value}」配对成功，现在发消息？`, '配对成功', {
          confirmButtonText: '去聊天',
          cancelButtonText: '稍后',
          type: 'success'
        })
        router.push({ name: 'chat', params: { conversationId: asId(result.conversationId) } })
      } catch {
        // 选了「稍后」，留在资料页
      }
    } else if (result && result.message) {
      ElMessage.success(result.message)
    }
  } catch {
    // 提示已弹出
  } finally {
    acting.value = ''
  }
}

async function startChat() {
  if (!canChat.value) {
    return
  }
  try {
    // openWith 内部是「有则取、无则建」，重复点不会产生第二个会话
    const conversationId = await conversation.openWith(targetId.value)
    router.push({ name: 'chat', params: { conversationId: asId(conversationId) } })
  } catch {
    // 提示已由 request.js 弹出
  }
}

const applyDialog = reactive({ visible: false, saving: false, message: '' })

function openApply() {
  applyDialog.message = `你好，我是 ${auth.nickname}`
  applyDialog.visible = true
}

async function submitApply() {
  applyDialog.saving = true
  try {
    await friend.apply({
      targetUserId: targetId.value,
      verifyMessage: applyDialog.message.trim() || undefined,
      source: 'web'
    })
    applyDialog.visible = false
    ElMessage.success('好友申请已发送，等待对方确认')
  } catch {
    // 已经申请过、对方设置了拒绝添加等情况，提示已弹出，保留对话框让用户改验证消息
  } finally {
    applyDialog.saving = false
  }
}

const remarkInputRef = ref(null)
const remarkDialog = reactive({ visible: false, saving: false, value: '' })

function openRemark() {
  remarkDialog.value = (card.value && card.value.remark) || ''
  remarkDialog.visible = true
}

function focusRemarkInput() {
  remarkInputRef.value && remarkInputRef.value.focus()
}

async function submitRemark() {
  remarkDialog.saving = true
  try {
    const remark = remarkDialog.value.trim()
    await friend.updateRemark(targetId.value, remark)
    if (card.value) {
      card.value.remark = remark
    }
    remarkDialog.visible = false
    ElMessage.success('备注已更新')
  } catch {
    // 提示已弹出
  } finally {
    remarkDialog.saving = false
  }
}

async function toggleBlock() {
  const next = !blocked.value
  const tip = next
    ? '拉黑后对方发来的消息会被拦截；你仍可向对方发消息，发送后自动解除拉黑。确定拉黑？'
    : '移出黑名单后双方可以正常收发消息。'
  try {
    await ElMessageBox.confirm(tip, next ? '加入黑名单' : '移出黑名单', {
      type: 'warning',
      confirmButtonText: next ? '拉黑' : '移出',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  acting.value = 'block'
  try {
    if (next) {
      await friend.block(targetId.value)
    } else {
      await friend.unblock(targetId.value)
    }
    if (card.value) {
      card.value.blocked = next
    }
    ElMessage.success(next ? '已加入黑名单' : '已移出黑名单')
  } catch {
    // 提示已弹出
  } finally {
    acting.value = ''
  }
}

async function removeFriend() {
  try {
    await ElMessageBox.confirm(
      `删除后「${displayName.value}」将从你的好友列表移除，双方的好友关系同时解除，已有的聊天记录仍会保留。确定删除？`,
      '删除好友',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = 'remove'
  try {
    await friend.remove(targetId.value)
    ElMessage.success('已删除好友')
    // 关系没了，资料卡上的 friend 位、按钮组、好友时间都要跟着变，重新拉一次最省事
    await load()
  } catch {
    // 提示已弹出
  } finally {
    acting.value = ''
  }
}

/**
 * 打开举报弹窗。title 包了账号，因为同名昵称在这个系统里很常见，
 * 弹窗顶部那行字是给人确认「我举报的是不是这个人」用的；
 * 真正进证据快照的是 userId，由后端回库抓。
 */
function reportUser() {
  const account = card.value?.username ? `（@${card.value.username}）` : ''
  report.openReport({ targetType: 'user', targetId: targetId.value, title: `${displayName.value}${account}` })
}

function goBack() {
  // 从收藏或刷新直接落进来时没有历史记录，退回好友列表比退到浏览器空白页有用
  if (window.history.state && window.history.state.back) {
    router.back()
    return
  }
  router.replace({ name: 'friends' })
}
</script>

<style scoped>
.user-profile {
  height: 100%;
  overflow-y: auto;
}

.user-profile__inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 768px) {
  .user-profile__inner {
    padding: 12px;
    gap: 12px;
  }
}

.user-profile__back {
  margin-left: -8px;
}

.user-profile__card {
  padding: 20px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: var(--im-radius);
}

.user-profile__card-title {
  margin-bottom: 16px;
  font-size: 15px;
  font-weight: 600;
}

/* 交友块要把模糊距离顶到行尾，其余标题保持原本的整行文本 */
.user-profile__card-title--row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.user-profile__distance {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--im-text-secondary);
}

/* 固定格宽而不是按张数均分：九张和两张都要占同样的位置，否则别人的资料卡会一刷新就改版式 */
.user-profile__wall {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 12px;
}

.user-profile__wall-img {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 8px;
  cursor: zoom-in;
  background: var(--im-bg);
}

.user-profile__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

/* 破冰：一档一个小标题 + 一排可点的句子 */
.user-profile__ice-group + .user-profile__ice-group {
  margin-top: 14px;
}

.user-profile__ice-label {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.user-profile__ice-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 用 button 而不是 div：这一条是可点的，得能被键盘选中、在移动端有按下去的反馈 */
.user-profile__ice-item {
  padding: 8px 12px;
  text-align: left;
  font-size: 13px;
  line-height: 20px;
  color: var(--im-text);
  background: var(--im-bg);
  border: 1px solid var(--im-border);
  border-radius: 8px;
  cursor: pointer;
}

.user-profile__ice-item:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.user-profile__spark {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 4px;
  color: var(--el-color-primary);
}

.user-profile__spark strong {
  font-size: 26px;
  line-height: 1;
}

.user-profile__spark span {
  font-size: 13px;
}

/* 火花熄灭时只把它压成次要色，不隐藏整块：天数归零本身是事实，
   把这一块藏起来会让人以为接口没返数据 */
.user-profile__spark--off {
  color: var(--im-text-secondary);
}

.user-profile__spark-bar {
  margin: 12px 0 6px;
}

.user-profile__spark-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.user-profile__tag {
  padding: 3px 10px;
  border: 1px solid var(--im-border);
  border-radius: 12px;
  color: var(--im-text-secondary);
  font-size: 12px;
}

.user-profile__head {
  display: flex;
  align-items: center;
  gap: 20px;
}

.user-profile__identity {
  min-width: 0;
}

.user-profile__nickname {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 20px;
  font-weight: 600;
}

.user-profile__nickname-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-profile__account {
  margin-top: 4px;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.user-profile__signature {
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--im-text-secondary);
  word-break: break-all;
}

.user-profile__facts {
  margin-top: 20px;
}

.user-profile__state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #67c23a;
}

.user-profile__state--off {
  color: var(--im-text-secondary);
}

.user-profile__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}

/* 拉黑提示用告警色块，比一个 tag 更能让人在点按钮之前先看到 */
.user-profile__warn {
  margin-top: 14px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 20px;
  color: #b88230;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
}

.user-profile__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.user-profile__tip {
  font-size: 12px;
  line-height: 18px;
  color: var(--im-text-secondary);
}

.user-profile__dialog-target {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 14px;
}
</style>
