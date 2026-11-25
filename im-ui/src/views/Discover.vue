<template>
  <div class="discover">
    <el-tabs v-model="tab" class="discover__tabs" @tab-change="onTabChange">
      <!-- ==================== 滑动匹配 ==================== -->
      <el-tab-pane label="滑动匹配" name="deck">
        <!-- 筛选入口：chip 直接平铺而不是只留一个按钮，因为「刷不到人」的第一句追问永远是「我是不是设了什么条件」 -->
        <div class="peerbar">
          <el-button size="small" @click="filterVisible = true">
            <el-icon><Filter /></el-icon>
            <span>筛选</span>
          </el-button>
          <!-- 排序直接放在卡池这一栏而不是只藏在弹层里：换一口径看是「这一屏想换个看法」的即时诉求，
               为一个切换要开弹层、保存、再关掉，就没人会去试。选项来自后端字典，这里不抄一份 -->
          <el-radio-group
            v-if="sortOptions.length"
            v-model="deckSort"
            size="small"
            class="peerbar__sorts"
            @change="onSortChange"
          >
            <el-radio-button v-for="opt in sortOptions" :key="opt.code" :value="opt.code">
              {{ opt.desc }}
            </el-radio-button>
          </el-radio-group>
          <el-tag v-for="chip in filterChips" :key="chip" size="small" type="info" effect="plain">
            {{ chip }}
          </el-tag>
          <span v-if="!filterChips.length" class="peerbar__hint">当前没有生效的筛选条件，看到的是全部可被发现的人</span>
        </div>
        <div v-loading="deckLoading" class="discover__body im-scroll">
          <div v-if="current" class="card">
            <div class="card__cover">
              <img v-if="coverSrc" :src="coverSrc" alt="" />
              <div v-else class="card__cover-empty">
                <el-icon :size="34"><Picture /></el-icon>
                <span>TA 还没上传生活照</span>
              </div>
              <span v-if="current.photoCount > 0" class="card__badge card__badge--photo">
                {{ current.photoCount }} 张照片
              </span>
              <span v-if="current.distanceText" class="card__badge card__badge--distance">
                {{ current.distanceText }}
              </span>
            </div>

            <div class="card__body">
              <div class="card__title">
                <UserAvatar
                  :src="current.avatar"
                  :name="current.nickname"
                  :size="36"
                  :online="!!current.online"
                />
                <div class="card__namebox">
                  <div class="card__name im-ellipsis">
                    {{ current.nickname || '未命名' }}
                    <span class="card__meta">{{ headerMeta(current) }}</span>
                  </div>
                  <div class="card__sub im-ellipsis">{{ current.signature || '这个人很懒，什么都没写' }}</div>
                </div>
                <div class="card__score" :title="`完资料度 ${current.profileScore || 0} 分`">
                  {{ current.profileScore || 0 }}
                </div>
              </div>

              <!-- 后端直接给一句理由而不是前端自己拼：互惠与曝光这两个信号全住在 im-friend，
                   摊成布尔位就等于把推荐流的排序口径复制一份到浏览器里 -->
              <div v-if="current.matchReason" class="card__reason">{{ current.matchReason }}</div>

              <div class="card__facts">
                <span v-if="current.locationText" class="fact">{{ current.locationText }}</span>
                <span v-if="current.occupation" class="fact">{{ current.occupation }}</span>
                <span v-if="current.educationDesc" class="fact">{{ current.educationDesc }}</span>
                <span v-if="current.incomeDesc" class="fact">{{ current.incomeDesc }}</span>
                <span v-if="current.marriageDesc" class="fact">{{ current.marriageDesc }}</span>
                <span v-if="current.datingGoalDesc" class="fact fact--goal">{{ current.datingGoalDesc }}</span>
              </div>

              <div v-if="(current.tagNames || []).length" class="card__tags">
                <el-tag v-for="name in current.tagNames" :key="name" size="small" effect="plain">
                  {{ name }}
                </el-tag>
              </div>
            </div>

            <div class="card__actions">
              <!-- 没有 friend:apply 时只留下「看资料」：一个点不动的「喜欢」会让人反复去点 -->
              <el-button v-if="canSlide" :loading="sliding" @click="passCard">
                <el-icon><Close /></el-icon>
                <span>跳过</span>
              </el-button>
              <el-button plain @click="openProfile(current.userId)">看资料</el-button>
              <el-button v-if="canSlide" type="danger" :loading="sliding" @click="likeCard">
                <el-icon><Star /></el-icon>
                <span>喜欢</span>
              </el-button>
            </div>
          </div>

          <template v-else>
            <el-empty
              v-if="!deckLoading"
              :image-size="80"
              :description="hasSlid ? '这一批刷完了，休息一下吧' : '暂时没有可以匹配的人'"
            >
              <div class="discover__empty-tip">完善交友资料与照片会明显提升曝光，也可以过会儿再来刷。</div>
              <el-button type="primary" plain @click="router.push({ name: 'profile' })">去完善资料</el-button>
              <el-button :loading="deckLoading" @click="loadDeck(true)">刷新</el-button>
            </el-empty>
          </template>
        </div>
      </el-tab-pane>

      <!-- ==================== 动态 ==================== -->
      <!-- lazy：这一屏挂载即拉列表，而默认页是卡池，只想刷卡的人不该为它白发一次请求；
           与其它 Tab「先渲空壳、切到了才 load」的写法等效，只是那一套在这里由组件自己做了 -->
      <el-tab-pane label="动态" name="feed" lazy>
        <MomentFeed />
      </el-tab-pane>

      <!-- ==================== 附近的人 ==================== -->
      <el-tab-pane label="附近的人" name="nearby">
        <div class="peerbar">
          <span class="peerbar__label">范围</span>
          <el-select v-model="nearbyRadius" size="small" class="peerbar__select" @change="onRadiusChange">
            <el-option v-for="opt in RADIUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-button size="small" :loading="nearbyLoading" @click="loadNearby()">刷新</el-button>
          <!-- 明说「只给模糊距离」：用户担心的是被精确跟踪，而不是这一栏准不准 -->
          <span class="peerbar__hint">只按远近排序并显示模糊距离，谁也看不到谁的具体位置</span>
        </div>

        <div v-loading="nearbyLoading" class="discover__body im-scroll">
          <div v-for="item in nearby" :key="item.peerUserId" class="peer">
            <UserAvatar
              :src="peerCard(item).avatar"
              :name="peerCard(item).nickname"
              :size="44"
              :online="!!peerCard(item).online"
            />
            <div class="peer__body">
              <div class="peer__row">
                <span class="peer__name im-ellipsis">
                  {{ peerCard(item).nickname || '未命名' }}
                  <span class="peer__meta">{{ headerMeta(peerCard(item)) }}</span>
                </span>
                <span class="peer__time">{{ peerCard(item).distanceText || '' }}</span>
              </div>
              <div class="peer__sub im-ellipsis">
                <span v-if="peerCard(item).locationText">{{ peerCard(item).locationText }} · </span>
                {{ peerCard(item).signature || '这个人很懒，什么都没写' }}
              </div>
            </div>
            <div class="peer__action">
              <el-button
                v-if="item.paired"
                type="primary"
                size="small"
                @click="goChat(item.conversationId, item.peerUserId)"
              >
                去聊天
              </el-button>
              <el-button
                v-else-if="canSlide && !item.liked"
                type="danger"
                plain
                size="small"
                :loading="acting === item.peerUserId"
                @click="likeNearby(item)"
              >
                喜欢
              </el-button>
              <el-tag v-else-if="item.liked" size="small" type="info" effect="plain">已喜欢</el-tag>
              <el-button link size="small" @click="openProfile(item.peerUserId)">资料</el-button>
            </div>
          </div>

          <!-- 两种空态的下一句完全不同：「你没定位」能引导去修，「附近没人」只能换半径再试 -->
          <el-empty
            v-if="!nearbyLoading && nearbyNoLocation"
            :image-size="72"
            description="你还不能按距离找人"
          >
            <div class="discover__empty-tip">
              定位一次就能用（超过 7 天会自动失效）。下面这一下会把坐标直接存进交友资料，不用再跑去资料页点保存。
            </div>
            <el-button type="primary" :loading="nearbyLocating" @click="locateForNearby">立即定位</el-button>
            <el-button @click="router.push({ name: 'profile' })">去资料页</el-button>
          </el-empty>
          <el-empty v-else-if="!nearbyLoading && nearby.length === 0" :image-size="72" description="这个范围里暂时没人">
            <el-button :disabled="!canExpandRadius" @click="expandRadius">放大范围</el-button>
          </el-empty>
        </div>
      </el-tab-pane>

      <!-- ==================== 喜欢我的 ==================== -->
      <el-tab-pane label="喜欢我的" name="likes">
        <div v-loading="likesLoading" class="discover__body im-scroll">
          <div v-for="item in likes" :key="item.likeId" class="peer">
            <UserAvatar
              :src="peerCard(item).avatar"
              :name="peerCard(item).nickname"
              :size="44"
              :online="!!peerCard(item).online"
            />
            <div class="peer__body">
              <div class="peer__row">
                <span class="peer__name im-ellipsis">
                  {{ peerCard(item).nickname || '该用户已注销' }}
                  <span class="peer__meta">{{ headerMeta(peerCard(item)) }}</span>
                </span>
                <span class="peer__time">{{ formatConvTime(item.likeTime) }}</span>
              </div>
              <div class="peer__sub im-ellipsis">
                {{ item.sourceDesc ? `在「${item.sourceDesc}」里喜欢了你` : '喜欢了你' }}
              </div>
              <div v-if="(peerCard(item).tagNames || []).length" class="peer__tags">
                <el-tag v-for="name in peerCard(item).tagNames.slice(0, 4)" :key="name" size="small" effect="plain">
                  {{ name }}
                </el-tag>
              </div>
            </div>
            <div class="peer__action">
              <el-button
                v-if="item.paired"
                type="primary"
                size="small"
                @click="goChat(item.conversationId, item.peerUserId)"
              >
                去聊天
              </el-button>
              <el-button
                v-else-if="!item.likedBack"
                type="danger"
                plain
                size="small"
                :loading="acting === item.peerUserId"
                @click="likeBack(item)"
              >
                回个喜欢
              </el-button>
              <el-tag v-else size="small" type="info" effect="plain">已喜欢</el-tag>
              <el-button link size="small" @click="openProfile(item.peerUserId)">资料</el-button>
            </div>
          </div>

          <el-empty v-if="!likesLoading && likes.length === 0" description="还没有人喜欢过你" :image-size="72" />
        </div>
        <Pagination :total="likesTotal" v-model:current="likesPage" @change="loadLikes" />
      </el-tab-pane>

      <!-- ==================== 谁看过我 ==================== -->
      <el-tab-pane label="谁看过我" name="visits">
        <div class="peerbar">
          <el-button size="small" :loading="visitsLoading" @click="loadVisits()">刷新</el-button>
          <!-- 把两个「为什么是空的」一次说清：只看 30 天，以及隐身的人不留痕；
               不说这一句，空列表就会被读成「功能坏了」或「没人看我」 -->
          <span class="peerbar__hint">只回看近 30 天；开隐身的人来看你也不会留下记录</span>
        </div>

        <div v-loading="visitsLoading" class="discover__body im-scroll">
          <div v-for="item in visits" :key="item.visitorId" class="peer">
            <UserAvatar
              :src="peerCard(item).avatar"
              :name="peerCard(item).nickname"
              :size="44"
              :online="!!peerCard(item).online"
            />
            <div class="peer__body">
              <div class="peer__row">
                <span class="peer__name im-ellipsis">
                  {{ peerCard(item).nickname || '未命名' }}
                  <span class="peer__meta">{{ headerMeta(peerCard(item)) }}</span>
                </span>
                <span class="peer__time">{{ formatConvTime(item.lastTime) }}</span>
              </div>
              <div class="peer__sub im-ellipsis">{{ visitSummary(item) }}</div>
              <div v-if="(peerCard(item).tagNames || []).length" class="peer__tags">
                <el-tag v-for="name in peerCard(item).tagNames.slice(0, 4)" :key="name" size="small" effect="plain">
                  {{ name }}
                </el-tag>
              </div>
            </div>
            <div class="peer__action">
              <el-button
                v-if="item.paired"
                type="primary"
                size="small"
                @click="goChat(item.conversationId, item.visitorId)"
              >
                去聊天
              </el-button>
              <el-button
                v-else-if="canSlide && !item.liked"
                type="danger"
                plain
                size="small"
                :loading="acting === item.visitorId"
                @click="likeVisitor(item)"
              >
                喜欢
              </el-button>
              <el-tag v-else-if="item.liked" size="small" type="info" effect="plain">已喜欢</el-tag>
              <el-button link size="small" @click="openProfile(item.visitorId)">资料</el-button>
            </div>
          </div>

          <el-empty v-if="!visitsLoading && visits.length === 0" :image-size="72" description="最近 30 天没人来看过你">
            <div class="discover__empty-tip">资料越完整、照片越多，越容易被人点开。</div>
            <el-button type="primary" plain @click="router.push({ name: 'profile' })">去完善资料</el-button>
          </el-empty>
        </div>
      </el-tab-pane>

      <!-- ==================== 我的配对 ==================== -->
      <el-tab-pane label="我的配对" name="pairs">
        <div v-loading="pairsLoading" class="discover__body im-scroll">
          <div v-for="item in pairs" :key="item.pairId" class="peer">
            <UserAvatar
              :src="peerCard(item).avatar"
              :name="peerCard(item).nickname"
              :size="44"
              :online="!!peerCard(item).online"
            />
            <div class="peer__body">
              <div class="peer__row">
                <span class="peer__name im-ellipsis">
                  {{ peerCard(item).nickname || '该用户已注销' }}
                  <span class="peer__meta">{{ headerMeta(peerCard(item)) }}</span>
                </span>
                <span class="peer__time">{{ formatConvTime(item.pairTime) }}</span>
              </div>
              <div class="peer__sub im-ellipsis">
                {{ item.likeSourceDesc ? `在「${item.likeSourceDesc}」配对` : '已配对' }}
                · 配对后共 {{ item.msgCount || 0 }} 条消息
              </div>
            </div>
            <div class="peer__action">
              <el-button type="primary" size="small" @click="goChat(item.conversationId, item.peerUserId)">
                去聊天
              </el-button>
              <el-button
                link
                size="small"
                class="discover__unmatch"
                :loading="acting === item.pairId"
                @click="unmatch(item)"
              >
                解除配对
              </el-button>
            </div>
          </div>

          <el-empty v-if="!pairsLoading && pairs.length === 0" description="还没有配对" :image-size="72" />
        </div>
        <Pagination :total="pairsTotal" v-model:current="pairsPage" @change="loadPairs" />
      </el-tab-pane>
    </el-tabs>

    <DatingFilterDialog v-model="filterVisible" @applied="onFilterApplied" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Filter, Picture, Star } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import Pagination from '@/components/Pagination.vue'
import DatingFilterDialog from '@/components/DatingFilterDialog.vue'
import MomentFeed from '@/components/MomentFeed.vue'
import {
  actMatch,
  fetchDatingDict,
  fetchMatchDeck,
  fetchMatchLikes,
  fetchMatchPairs,
  fetchNearby,
  fetchPreference,
  fetchProfileVisits,
  saveDatingProfile,
  unmatchPair
} from '@/api/dating'
import { useConversationStore } from '@/stores/conversation'
import { useAuthStore } from '@/stores/auth'
import { formatConvTime, genderText } from '@/utils/format'
import { mediaUrl } from '@/utils/media'
import { asId } from '@/utils/id'
import { requestLocation } from '@/utils/location'

/**
 * 交友页：滑动匹配 + 动态 + 附近的人 + 谁喜欢我 + 谁看过我 + 我的配对。
 *
 * 六个 Tab 共用一页而不是拆成六个路由：它们本质是同一件事的六个阶段（找人 → 看人 → 被找 → 被看 → 聊上），
 * 拆开会让用户在六个 URL 之间来回跳，也不好在滑动成对后顺势看到「配对」列表。
 *
 * <p>动态那一栏整个交给 {@code MomentFeed}：同一份列表还要在「某人主页」上再出现一次，
 * 写在页里就没法复用，而可见范围文案与 2050 的提示两处各写一份很快就会长得不一样。
 *
 * <p>附近的人与卡池是同一个候选池的两种读法，所以共用 actMatch：
 * 这里不开第二条写路径，只是 source 传 2（后端据此在会话里写「你们在「附近的人」里互相喜欢」）。
 */
defineOptions({ name: 'Discover' })

const route = useRoute()
const router = useRouter()
const conversation = useConversationStore()
const auth = useAuthStore()

const PAGE_SIZE = 10
const DECK_SIZE = 10
/** 卡池剩余不足这个数就悄悄补一批，避免刷到最后一张才发现要等网络 */
const DECK_REFILL_AT = 3

/** 可选半径，与后端 {@code /dating/nearby} 的 [500, 20000] 一致；
 *  再远就不是「附近」而是同城筛选，那一档属于 f6 的按 city 包含匹配 */
const RADIUS_OPTIONS = [
  { value: 500, label: '500 米内' },
  { value: 1000, label: '1 公里内' },
  { value: 3000, label: '3 公里内' },
  { value: 5000, label: '5 公里内' },
  { value: 10000, label: '10 公里内' },
  { value: 20000, label: '20 公里内' }
]

/** 附近的人一次取多少条：这是一屏滚动列表而不是分页表格，后端上限也是 50 */
const NEARBY_SIZE = 30

/** 匹配来源同 MatchSource（1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好）；
 *  卡池与「喜欢我的」回滑都属于推荐流，f11～f12 上线后才会用到 3 与 5 */
const SOURCE_RECOMMEND = 1
const SOURCE_NEARBY = 2
const SOURCE_VISITOR = 4

/** 后端 ResultCode.DATING_LOCATION_REQUIRED：查看者自己没定位或定位过了采信期 */
const CODE_LOCATION_REQUIRED = 2042

/** 滑动动作：1 喜欢 2 跳过 */
const ACTION_LIKE = 1
const ACTION_PASS = 2

const TAB_NAMES = ['deck', 'feed', 'nearby', 'likes', 'visits', 'pairs']
const tab = ref(TAB_NAMES.includes(route.query.tab) ? route.query.tab : 'deck')
/** 列表项上的就地操作转圈，按对方 id / 配对 id 区分是哪一行 */
const acting = ref(null)

/** 没有 friend:apply 就只读不写：隐藏按钮比点了之后吃一个 403 提示友好 */
const canSlide = computed(() => auth.hasPermission('friend:apply'))

/* ------------------------------ 筛选与偏好 ------------------------------ */

const filterVisible = ref(false)
/** 生效中的偏好，只用来渲 chip：条件本身后端每次读池子都会重查那张表，不靠前端传过去 */
const preference = ref(null)

const filterChips = computed(() => describePreference(preference.value))

/**
 * 把一行偏好摊成几句人话。
 *
 * 只列「真的在筛」的那几条：一屏全灰着写「不限」等于没有反馈，而用户要确认的永远是
 * 「我那条城市筛选还在不在」。年龄是唯一例外——保存过就必然有上下限（出厂 18~45），
 * 它一定在生效，所以一定进 chip，不能因为「看起来像默认值」就藏掉。
 *
 * 学历与婚恋状况直接用后端给的 targetEducationDesc / targetMarriageDesc：
 * code→文案的映射只应该有一份出处（im-common 的枚举），在这里再抄一份字典就是第 2 份。
 *
 * 排序不进 chip：它现在由卡池那一栏的切换器直接显示（选中项就是当前生效的口径），
 * 旁边再挂一句「排序：热门」就是同一个事实说两遍，而这两句在用户临时切换后一定会不一致。
 */
function describePreference(vo) {
  if (!vo || vo.saved === false) {
    return []
  }
  const chips = []
  if (vo.targetGender === 1) {
    chips.push('只看男生')
  } else if (vo.targetGender === 2) {
    chips.push('只看女生')
  }
  chips.push(`${vo.ageMin}-${vo.ageMax} 岁`)
  if (vo.heightMin > 0 || vo.heightMax > 0) {
    chips.push(`身高 ${vo.heightMin > 0 ? vo.heightMin : '不限'}-${vo.heightMax > 0 ? vo.heightMax : '不限'} cm`)
  }
  if (vo.targetEducationDesc) {
    chips.push(`${vo.targetEducationDesc}及以上`)
  }
  if (vo.targetMarriageDesc) {
    chips.push(`婚恋：${vo.targetMarriageDesc}`)
  }
  if (vo.targetCity) {
    chips.push(`只看 ${vo.targetCity}`)
  }
  if (vo.distanceKm > 0) {
    chips.push(`${vo.distanceKm} km 内`)
  }
  if (vo.onlineFirst === 1) {
    chips.push('在线优先')
  }
  if (vo.excludePassed === 0) {
    chips.push('已跳过的人可重现')
  }
  return chips
}

/**
 * 拉一次偏好。
 *
 * 失败只让 chip 为空而不拦下卡池：筛选条件在后台照样生效，少一句摘要比刷不了人轻得多。
 * 而反过来——拉不到偏好就不给卡池——会把一个展示问题升级成不可用。
 */
async function loadPreference() {
  try {
    const vo = await fetchPreference()
    preference.value = vo
    syncNearbyRadius(vo)
    syncDeckSort(vo)
  } catch {
    // 提示已弹出
  }
}

/**
 * 把偏好里存的排序口径当作切换器的初始选中项。
 *
 * 只在还没选过时写：卡池与偏好是并行拉的，先抢到一个切换动作就把选中项抢回去了，
 * 这里不能把用户刚选的那一个洗掉。
 */
function syncDeckSort(vo) {
  if (!deckSort.value) {
    deckSort.value = (vo && vo.sortBy) || 'recommend'
  }
}

function onFilterApplied(vo) {
  preference.value = vo
  // 保存偏好是一个明确动作，里面的排序口径覆盖卡池上临时选的那个
  deckSort.value = (vo && vo.sortBy) || 'recommend'
  // 保存后立刻重载：偏好的语义就是「下一次取池子按新条件」，留着旧卡会让人以为没生效
  loadDeck(true)
  if (nearbyLoaded.value) {
    syncNearbyRadius(vo)
    loadNearby()
  }
}

/* ------------------------------ 卡池 ------------------------------ */

const deck = ref([])
const deckLoading = ref(false)
const sliding = ref(false)
/** 本页滑过至少一张：用来区分「池子真空」与「刷到底了」这两句完全不同的提示 */
const hasSlid = ref(false)
/** 上一次拉取就没拿到新人，不再自动补批 */
const deckExhausted = ref(false)

/**
 * 卡池当前生效的排序口径。
 *
 * 初始值从偏好里拿，但改它<b>只作用于接下来的每一次 deck 请求</b>，不写回偏好：
 * 「这一屏换成热门看看」与「我以后的默认口径就是热门」是两件事，合成一个开关会让人
 * 不敢点。后端也按这个口径设计：不传 sort 才用偏好里存的那个。
 */
const deckSort = ref('')
/** 可选的排序档，直接来自字典接口；拉不到就不渲这一组（而不是退回写死的两项） */
const sortOptions = ref([])

async function loadSortOptions() {
  try {
    const data = await fetchDatingDict()
    sortOptions.value = (data && data.sorts) || []
  } catch {
    // 只是一个排序开关，拉不到就是不给切，不拦下卡池
  }
}

function onSortChange() {
  // 换口径必须整池重拉：留着旧卡会把两个口径混在一列里，看上去就是「换了但没反」
  deckExhausted.value = false
  hasSlid.value = false
  loadDeck(true)
}

const current = computed(() => deck.value[0] || null)
const coverSrc = computed(() => (current.value ? mediaUrl(current.value.coverUrl || current.value.avatar) : ''))

async function loadDeck(reset = false) {
  if (deckLoading.value) {
    return
  }
  deckLoading.value = true
  try {
    const list = (await fetchMatchDeck(DECK_SIZE, deckSort.value)) || []
    if (reset) {
      deck.value = list
    } else {
      // 补批时按 userId 去重：两次请求之间对方可能同时存在于两批里，重复卡片会让人滑两次
      const known = new Set(deck.value.map((item) => item.userId))
      deck.value = deck.value.concat(list.filter((item) => !known.has(item.userId)))
    }
    if (list.length === 0) {
      deckExhausted.value = true
    }
  } catch {
    // 提示已弹出
  } finally {
    deckLoading.value = false
  }
}

function headerMeta(card) {
  if (!card || !card.userId) {
    return ''
  }
  const parts = []
  // 0 / null 时 genderText 回的是「未知」，卡在标题旁挂一个「未知 · 25 岁」不如不挂
  if (card.gender === 1 || card.gender === 2) {
    parts.push(genderText(card.gender))
  }
  if (card.age) {
    parts.push(`${card.age} 岁`)
  }
  if (card.heightCm) {
    parts.push(`${card.heightCm}cm`)
  }
  return parts.join(' · ')
}

/** 列表项可能因对方注销而没有 card，统一回空对象免得模板到处判空 */
function peerCard(item) {
  return item.card || {}
}

function passCard() {
  slide(ACTION_PASS)
}

function likeCard() {
  slide(ACTION_LIKE)
}

async function slide(action) {
  const target = current.value
  if (!target || sliding.value) {
    return
  }
  sliding.value = true
  try {
    const result = await actMatch({ targetUserId: target.userId, action, source: SOURCE_RECOMMEND })
    hasSlid.value = true
    deck.value = deck.value.filter((item) => item.userId !== target.userId)
    if (result && result.matched) {
      await celebrate(result)
    } else if (result && result.message) {
      ElMessage.success(result.message)
    }
    if (deck.value.length <= DECK_REFILL_AT && !deckExhausted.value) {
      loadDeck()
    }
  } catch {
    // 提示已弹出，卡片留在原地方便再点一次
  } finally {
    sliding.value = false
  }
}

/**
 * 本机促成配对后的处理。
 *
 * 只弹「去聊天 / 继续刷」这一个框：成对的通知与系统消息归 ws/dispatch.js 的
 * match-success 分支（两边都推），这里再弹一层就变成点一次喜欢出三个东西。
 * 会话是后端刚建的，本地会话列表里没有这一条，不 fetchList 直接跳转会话
 * 会因为找不到对应项而显示空白面板。
 */
async function celebrate(result) {
  conversation.fetchList()
  const nickname = (result.peer && result.peer.nickname) || '对方'
  if (!result.conversationId) {
    return
  }
  try {
    await ElMessageBox.confirm(`已和「${nickname}」配对成功，现在发消息？`, '配对成功', {
      confirmButtonText: '去聊天',
      cancelButtonText: '继续刷',
      type: 'success'
    })
    router.push({ name: 'chat', params: { conversationId: asId(result.conversationId) } })
  } catch {
    // 选了「继续刷」，留在本页
  }
}

/* ------------------------------ 附近的人 ------------------------------ */

const nearby = ref([])
const nearbyLoading = ref(false)
const nearbyLoaded = ref(false)
/** 区分「附近没人」与「你自己没定位」两种空态，后者的出路是在这一格直接采一次定位 */
const nearbyNoLocation = ref(false)
/** 空态里那个「立即定位」的占用位：与拉列表的 loading 分开，两者同时转会把文案遮掉 */
const nearbyLocating = ref(false)
const nearbyRadius = ref(3000)
/** 用户手动改过范围下拉框就不再拿偏好里的距离去抢他的选择 */
const nearbyRadiusTouched = ref(false)

/**
 * 把偏好里的 distance_km 预先选进范围下拉框。
 *
 * 后端在没显式传半径时会用偏好兜底，但这一栏永远把下拉框的值传过去——下拉框就在眼前，
 * 让它说的算才是所见即所得。所以兜底改在这里做：不传下拉框的值，而是把它改成偏好的值。
 * 对不上可选档的距离（比如存了 7）就不动：宁可按默认 3 km 展，也不往一个空下拉框里塞个选项外的值。
 */
function syncNearbyRadius(vo) {
  if (!vo || vo.saved === false || !vo.distanceKm || nearbyRadiusTouched.value) {
    return
  }
  const hit = RADIUS_OPTIONS.find((opt) => opt.value === vo.distanceKm * 1000)
  if (hit) {
    nearbyRadius.value = hit.value
  }
}

/** 下拉框变了就是用户自己的意思：记下来，此后保存偏好不再抢这一格 */
function onRadiusChange() {
  nearbyRadiusTouched.value = true
  loadNearby()
}

const canExpandRadius = computed(() => RADIUS_OPTIONS.some((opt) => opt.value > nearbyRadius.value))

/**
 * 拉附近的人。
 *
 * silent 请求：2042「你自己还没定位」在这一栏不是一次失败，而是一个要原地出引导的正常状态，
 * 弹全局红条会再对同一个问题说第二遍。其它错误因为没人被静默，补上弹条。
 */
async function loadNearby() {
  if (nearbyLoading.value) {
    return
  }
  nearbyLoading.value = true
  try {
    nearby.value = (await fetchNearby({ radius: nearbyRadius.value, size: NEARBY_SIZE }, { silent: true })) || []
    nearbyNoLocation.value = false
    nearbyLoaded.value = true
  } catch (err) {
    if (err && err.code === CODE_LOCATION_REQUIRED) {
      nearby.value = []
      nearbyNoLocation.value = true
      nearbyLoaded.value = true
    } else {
      ElMessage.error((err && err.message) || '附近的人加载失败')
    }
  } finally {
    nearbyLoading.value = false
  }
}

/** 「放大范围」直接跳到下一档：空态上点的那一下是一个动作，不是让人去下拉框里找 */
function expandRadius() {
  const next = RADIUS_OPTIONS.find((opt) => opt.value > nearbyRadius.value)
  if (!next) {
    return
  }
  nearbyRadius.value = next.value
  loadNearby()
}

/**
 * 就在这一格补一次定位：坐标直接写进交友资料，写完重拉列表。
 *
 * <p>以前这里只有一个「去定位」把人赶去资料页，而那边点完「重新定位」还要再点一次
 * 「保存交友资料」才落库 —— 回来自然还是空的，这就是「明明已经定位了却还不行」的来路。
 */
async function locateForNearby() {
  if (nearbyLocating.value) {
    return
  }
  nearbyLocating.value = true
  const got = await requestLocation()
  if (!got.ok) {
    nearbyLocating.value = false
    ElMessage.warning(got.message)
    return
  }
  try {
    await saveDatingProfile({ lng: got.lng, lat: got.lat })
    await loadNearby()
    ElMessage.success(nearby.value.length ? '定位已更新' : '定位已更新，但这个范围里还没人')
  } catch {
    // 提示已由 request.js 弹出
  } finally {
    nearbyLocating.value = false
  }
}

async function likeNearby(item) {
  acting.value = item.peerUserId
  try {
    const result = await actMatch({ targetUserId: item.peerUserId, action: ACTION_LIKE, source: SOURCE_NEARBY })
    if (result && result.matched) {
      // 就地改这一行而不是重拉整列：重拉会按距离重新排序，刚配对的那个人可能直接跳到底下去
      item.paired = true
      item.conversationId = result.conversationId
      await celebrate(result)
    } else {
      item.liked = true
      if (result && result.message) {
        ElMessage.success(result.message)
      }
    }
  } catch {
    // 提示已弹出，按钮留在「喜欢」上方便再点一次
  } finally {
    acting.value = null
  }
}

/* ------------------------------ 喜欢我的 ------------------------------ */

const likes = ref([])
const likesTotal = ref(0)
const likesPage = ref(1)
const likesLoading = ref(false)
const likesLoaded = ref(false)

async function loadLikes() {
  likesLoading.value = true
  try {
    const page = await fetchMatchLikes({ current: likesPage.value, size: PAGE_SIZE })
    likes.value = (page && page.records) || []
    likesTotal.value = (page && page.total) || 0
    likesLoaded.value = true
  } catch {
    // 提示已弹出
  } finally {
    likesLoading.value = false
  }
}

async function likeBack(item) {
  acting.value = item.peerUserId
  try {
    const result = await actMatch({ targetUserId: item.peerUserId, action: ACTION_LIKE, source: SOURCE_RECOMMEND })
    if (result && result.matched) {
      await celebrate(result)
    } else if (result && result.message) {
      ElMessage.success(result.message)
    }
    await loadLikes()
  } catch {
    // 提示已弹出
  } finally {
    acting.value = null
  }
}

/* ------------------------------ 谁看过我 ------------------------------ */

const visits = ref([])
const visitsLoading = ref(false)
const visitsLoaded = ref(false)

/**
 * 拉访客列表。
 *
 * 一次拉完、不分页（后端上限 50 位），所以这里只有 loaded 而没有「下一页」状态。
 * 失败就是空列表加一条提示：这一栏不驱动任何后续动作，不必区分「没人看」与「没拉到」。
 */
async function loadVisits() {
  if (visitsLoading.value) {
    return
  }
  visitsLoading.value = true
  try {
    visits.value = (await fetchProfileVisits()) || []
    visitsLoaded.value = true
  } catch {
    // 提示已弹出
  } finally {
    visitsLoading.value = false
  }
}

/**
 * 一行的来访摘要。
 *
 * 天数与次数都要给：「来过 1 天 · 看了 8 次」与「来过 5 天 · 共 8 次」是两种完全不同的兴趣强度
 * （前者是一晚上刷了八遍，后者是连着追了五天），只留一个数就分不出这个区别。
 *
 * viewDesc 暂不渲：目前只记得到「资料卡」一种入口，每行重复同一句只是噪音，
 * 第十二批动态真记进去了再上。
 */
function visitSummary(item) {
  const days = item.visitDays || 0
  const times = item.visitTimes || 0
  if (days <= 1) {
    return times > 1 ? `来过 1 天 · 看了 ${times} 次` : '来看过你一次'
  }
  return `来过 ${days} 天 · 共 ${times} 次`
}

/**
 * 从访客列表里回一个喜欢。
 *
 * source 传 4（MatchSource.VISITOR）而不是复用推荐流：后端成对时那句系统提示会带上这个入口名
 * （「你们在「访客」里互相喜欢」），后台也靠它统计哪个入口的成对率高 —— 填成 1 就是把这两件事都写错。
 */
async function likeVisitor(item) {
  acting.value = item.visitorId
  try {
    const result = await actMatch({ targetUserId: item.visitorId, action: ACTION_LIKE, source: SOURCE_VISITOR })
    if (result && result.matched) {
      // 就地改这一行而不是重拉整列：重拉会按来访时间重排，刚配对的人可能直接跳到底下去
      item.paired = true
      item.conversationId = result.conversationId
      await celebrate(result)
    } else {
      item.liked = true
      if (result && result.message) {
        ElMessage.success(result.message)
      }
    }
  } catch {
    // 提示已弹出，按钮留在「喜欢」上方便再点一次
  } finally {
    acting.value = null
  }
}

/* ------------------------------ 我的配对 ------------------------------ */

const pairs = ref([])
const pairsTotal = ref(0)
const pairsPage = ref(1)
const pairsLoading = ref(false)
const pairsLoaded = ref(false)

async function loadPairs() {
  pairsLoading.value = true
  try {
    const page = await fetchMatchPairs({ current: pairsPage.value, size: PAGE_SIZE })
    pairs.value = (page && page.records) || []
    pairsTotal.value = (page && page.total) || 0
    pairsLoaded.value = true
  } catch {
    // 提示已弹出
  } finally {
    pairsLoading.value = false
  }
}

async function unmatch(item) {
  const nickname = peerCard(item).nickname || '对方'
  try {
    await ElMessageBox.confirm(
      `解除和「${nickname}」的配对？聊天记录会保留，但你们不会再出现在彼此的推荐里。`,
      '解除配对',
      { confirmButtonText: '解除', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  acting.value = item.pairId
  try {
    await unmatchPair(item.pairId)
    ElMessage.success('已解除配对')
    await loadPairs()
  } catch {
    // 提示已弹出
  } finally {
    acting.value = null
  }
}

/* ------------------------------ 公共跳转 ------------------------------ */

function openProfile(userId) {
  router.push({ name: 'user-profile', params: { id: asId(userId) } })
}

/**
 * 去聊天。
 *
 * 优先用后端给的 conversationId 直接跳转；为 null 只可能是会话已被用户删除，
 * 此时按对方 id 现开一个（openWith 是幂等的），比提示「会话不存在」有用。
 */
async function goChat(conversationId, peerUserId) {
  if (conversationId) {
    router.push({ name: 'chat', params: { conversationId: asId(conversationId) } })
    return
  }
  try {
    const id = await conversation.openWith(peerUserId)
    router.push({ name: 'chat', params: { conversationId: asId(id) } })
  } catch {
    // 提示已弹出
  }
}

/**
 * 切 Tab 才拉对应列表。
 *
 * 只有卡池随挂载就拉：它是默认页且体量最大；附近的人要读定位、访客是一屏短列表、
 * 另两个是分页列表，只想刷卡片的用户不该为此白发四次请求。
 */
function onTabChange(name) {
  if (name === 'nearby' && !nearbyLoaded.value) {
    loadNearby()
  } else if (name === 'likes' && !likesLoaded.value) {
    loadLikes()
  } else if (name === 'visits' && !visitsLoaded.value) {
    loadVisits()
  } else if (name === 'pairs' && !pairsLoaded.value) {
    loadPairs()
  }
}

onMounted(() => {
  loadDeck(true)
  // 偏好与卡池并行拉：chip 晚一步到位比等它读完再出卡好，而 syncNearbyRadius / syncDeckSort
  // 都只在对应那一项还没被用户碰过时才写（第一次 deck 不传 sort，本来就等于用偏好里的）
  loadPreference()
  loadSortOptions()
  if (tab.value !== 'deck') {
    onTabChange(tab.value)
  }
})
</script>

<style scoped>
.discover {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--im-panel);
  padding: 0 20px;
}

.discover__tabs {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.discover__tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.discover__tabs :deep(.el-tab-pane) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.discover__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.discover__empty-tip {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.discover__unmatch {
  color: var(--im-text-secondary);
}

/* ------------------------------ 附近的人 ------------------------------ */
.peerbar {
  display: flex;
  align-items: center;
  /* 筛选 chip 能叠到六七个，不让它们把工具条撑出一屏横向滚动 */
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 0 2px;
}

.peerbar__label {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.peerbar__select {
  width: 108px;
}

/* 切换器不参与挤压：chip 多的时候它们自己换行，不能把几个排序档压到点不动 */
.peerbar__sorts {
  flex: none;
}

/* 不写成 tooltip：距离模糊化是这一栏能不能用的前提，得扫一眼就能看到 */
.peerbar__hint {
  font-size: 12px;
  color: var(--im-text-secondary);
}

/* ------------------------------ 滑动卡片 ------------------------------ */
.card {
  display: flex;
  flex-direction: column;
  max-width: 420px;
  margin: 12px auto 20px;
  border: 1px solid var(--im-border);
  border-radius: 12px;
  overflow: hidden;
}

.card__cover {
  position: relative;
  height: 260px;
  background: #f2f3f5;
}

.card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.card__cover-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 100%;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.card__badge {
  position: absolute;
  bottom: 8px;
  padding: 2px 8px;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  border-radius: 10px;
}

.card__badge--photo {
  left: 8px;
}

.card__badge--distance {
  right: 8px;
}

.card__body {
  padding: 12px 14px 4px;
}

.card__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card__namebox {
  flex: 1;
  min-width: 0;
}

.card__name {
  font-size: 15px;
  font-weight: 600;
}

.card__meta {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--im-text-secondary);
}

.card__sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

/* 完资料度放在角落而不是资料区：它是「这个人资料全不全」的元信息，不是 TA 的属性 */
.card__score {
  flex: none;
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 50%;
}

/* 推荐理由解释的是「为什么现在给你看 TA」，与下面那些 TA 自己的属性不是一类信息，
   所以单独一行而不是当作另一个 fact 胶囊抹平 */
.card__reason {
  margin-top: 10px;
  padding: 6px 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 6px;
}

.card__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.fact {
  padding: 2px 8px;
  font-size: 12px;
  color: #606266;
  background: #f4f5f7;
  border-radius: 4px;
}

.fact--goal {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.card__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 14px;
}

/* ------------------------------ 列表项 ------------------------------ */
.peer {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 4px;
  border-bottom: 1px solid var(--im-border);
}

.peer__body {
  flex: 1;
  min-width: 0;
}

.peer__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.peer__name {
  font-size: 14px;
}

.peer__meta {
  margin-left: 6px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.peer__time {
  flex: none;
  font-size: 11px;
  color: var(--im-text-secondary);
}

.peer__sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.peer__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.peer__action {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

/* ------------------------------ 窄屏 ------------------------------ */
@media (max-width: 768px) {
  .discover {
    padding: 0 8px;
  }

  .card__cover {
    height: 200px;
  }

  /* 动作区改为等宽三格：窄屏上三个按钮的文字容易被内边距挤成两行 */
  .card__actions :deep(.el-button) {
    flex: 1;
    margin-left: 0;
  }

  .peer__tags {
    display: none;
  }

  /* 窄屏优先保半径选择器与刷新按钮可用，这句口径说明可以省 */
  .peerbar__hint {
    display: none;
  }
}
</style>
