<template>
  <div class="moments">
    <el-tabs v-model="activeTab" class="moments__tabs">
      <!-- ==================== 动态 ==================== -->
      <el-tab-pane label="动态列表" name="moments">
        <div class="toolbar">
          <h2 class="page-title">动态管理</h2>
          <span class="grow" />
          <el-input
            v-model="query.keyword"
            :maxlength="50"
            clearable
            class="keyword"
            placeholder="正文关键词"
            @clear="reload"
            @keyup.enter="reload"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-input v-model="query.userId" :maxlength="20" clearable class="uid" placeholder="发布者 ID" @clear="reload" @keyup.enter="reload" />
          <el-select v-model="query.status" class="pick" clearable placeholder="全部状态" @change="reload">
            <el-option :value="1" label="正常" />
            <el-option :value="2" label="已下架" />
          </el-select>
          <el-select v-model="query.visibility" class="pick" clearable placeholder="全部可见范围" @change="reload">
            <el-option v-for="(text, code) in VISIBILITY_TEXT" :key="code" :value="Number(code)" :label="text" />
          </el-select>
          <el-button :loading="loading" @click="reload">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-alert
          class="hint"
          :closable="false"
          type="info"
          title="下架是保留行的隐藏，删除会连带四张附属表"
          description="下架后这条动态在任何人的 Feed 与详情里都不再出现（含作者本人），随时可恢复；删除会一并清掉媒体、评论、点赞与可见范围白名单，一期没有回收站。注意「公开」这一档也不是全站可见——动态的受众恒为好友与配对过的人。"
        />

        <el-card shadow="never">
          <el-table v-loading="loading" :data="rows">
            <el-table-column label="动态" min-width="300">
              <template #default="{ row }">
                <div class="moment-cell">
                  <span class="content">{{ row.content || '（纯图动态）' }}</span>
                  <div v-if="(row.mediaUrls || []).length" class="media-row">
                    <template v-for="item in previewOf(row)" :key="item.src">
                      <!-- 列表里就得能播：以前这里无条件画 <img>，视频地址画进 img 就是一个碎图标，
                           后台「看不了视频」就是这么来的 -->
                      <video
                        v-if="item.type === MEDIA_VIDEO"
                        class="thumb thumb--video"
                        :src="item.src"
                        controls
                        preload="metadata"
                        playsinline
                      />
                      <img v-else class="thumb" :src="item.src" alt="" />
                    </template>
                    <span v-if="row.mediaCount > row.mediaUrls.length" class="muted">+{{ row.mediaCount - row.mediaUrls.length }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="发布者" min-width="150">
              <template #default="{ row }">
                <div class="user-text">
                  <span class="ellipsis">{{ row.userNickname || '已注销用户' }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="可见范围" width="120">
              <template #default="{ row }">
                <el-tag effect="plain" size="small">{{ visibilityText(row.visibility) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column align="center" label="赞/评" width="100">
              <template #default="{ row }">
                <span class="num">{{ row.likeCount || 0 }} / {{ row.commentCount || 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'warning'" effect="plain" size="small">
                  {{ row.status === 1 ? '正常' : '已下架' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发布时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="200">
              <template #default="{ row }">
                <el-button text type="primary" @click="openDetail(row)">详情</el-button>
                <el-button v-if="row.status === 1" text type="danger" @click="confirmOffline(row)">下架</el-button>
                <el-button v-else text type="success" @click="confirmOnline(row)">恢复</el-button>
                <el-button text type="danger" @click="confirmDelete(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的动态" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="query.current"
              v-model:page-size="query.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="total"
              layout="total, sizes, prev, pager, next"
              @size-change="reload"
              @current-change="load"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 评论队列 ==================== -->
      <el-tab-pane label="评论队列" name="comments">
        <div class="toolbar">
          <span class="muted">跨动态的一级评论队列，按创建时间倒序。不填动态 ID 就是全站待处理评论——举报里最常见的一类目标。</span>
          <span class="grow" />
          <el-input v-model="commentQuery.momentId" :maxlength="20" clearable class="uid" placeholder="动态 ID" @clear="reloadComments" @keyup.enter="reloadComments" />
          <el-input v-model="commentQuery.keyword" :maxlength="50" clearable class="keyword" placeholder="评论关键词" @clear="reloadComments" @keyup.enter="reloadComments" />
          <el-select v-model="commentQuery.status" class="pick" clearable placeholder="全部状态" @change="reloadComments">
            <el-option :value="1" label="正常" />
            <el-option :value="2" label="已下架" />
          </el-select>
          <el-button :loading="commentLoading" @click="reloadComments">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="commentLoading" :data="commentRows">
            <el-table-column label="评论内容" min-width="260">
              <template #default="{ row }">
                <div class="moment-cell">
                  <span class="content">{{ row.content }}</span>
                  <span v-if="row.parentId" class="muted">回复 #{{ row.replyUserId }} {{ row.replyUserNickname || '' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="评论人" min-width="140">
              <template #default="{ row }">
                <div class="user-text">
                  <span class="ellipsis">{{ row.userNickname || '已注销用户' }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="所在动态" min-width="190">
              <template #default="{ row }">
                <div class="user-text">
                  <span class="ellipsis">{{ row.momentSummary || '（无正文）' }}</span>
                  <el-button text type="primary" size="small" class="link" @click="jumpToMoment(row.momentId)">
                    #{{ row.momentId }}
                  </el-button>
                </div>
              </template>
            </el-table-column>
            <el-table-column align="center" label="层级" width="90">
              <template #default="{ row }">
                <el-tag :type="row.parentId ? 'info' : 'primary'" effect="plain" size="small">
                  {{ row.parentId ? '回复' : '一级' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column align="center" label="赞" width="70">
              <template #default="{ row }">
                <span class="num">{{ row.likeCount || 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'warning'" effect="plain" size="small">
                  {{ row.status === 1 ? '正常' : '已下架' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="180">
              <template #default="{ row }">
                <el-button v-if="row.status === 1" text type="danger" @click="confirmCommentOffline(row)">下架</el-button>
                <el-button v-else text type="success" @click="confirmCommentOnline(row)">恢复</el-button>
                <el-button text type="danger" @click="confirmCommentDelete(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的评论" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="commentQuery.current"
              v-model:page-size="commentQuery.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="commentTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadComments"
              @current-change="loadComments"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 详情弹框：列表只给前 3 个缩略与截断正文，这里换成全文与全部媒体 -->
    <!-- 带视频就把弹层撑宽：一个 16:9 的视频挤在 640px 里，字幕与人脸都看不清 -->
    <el-dialog v-model="detail.visible" title="动态详情" :width="detailVideo ? '880px' : '640px'">
      <div v-loading="detail.loading">
        <div class="detail-head">
          <span>{{ detail.row?.userNickname || '已注销用户' }}</span>
          <span class="muted">#{{ detail.row?.userId }}</span>
          <el-tag effect="plain" size="small">{{ visibilityText(detail.row?.visibility) }}</el-tag>
          <el-tag :type="detail.row?.status === 1 ? 'success' : 'warning'" effect="plain" size="small">
            {{ detail.row?.status === 1 ? '正常' : '已下架' }}
          </el-tag>
          <span class="muted">{{ formatDateTime(detail.row?.createTime) }}</span>
        </div>
        <p class="detail-content">{{ detail.row?.content || '（纯图动态）' }}</p>
        <div class="detail-media">
          <template v-for="item in detail.media" :key="item.src">
            <video
              v-if="item.type === MEDIA_VIDEO"
              class="detail-video"
              :src="item.src"
              controls
              preload="metadata"
              playsinline
            />
            <img v-else class="detail-img" :src="item.src" alt="" />
          </template>
        </div>
        <p v-if="detail.row && !detail.media.length" class="muted">这条动态没有媒体</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteMoment,
  deleteMomentComment,
  fetchMomentCommentPage,
  fetchMomentDetail,
  fetchMomentPage,
  offlineMoment,
  offlineMomentComment,
  onlineMoment,
  onlineMomentComment
} from '@/api/admin'
import { fetchMedia } from '@/api/request'
import { formatDateTime, formatListTime } from '@/utils/format'

/**
 * 后台「动态管理」屏：动态本体 + 跨动态的评论队列（权限点 133 system:moment:manage）。
 *
 * 两页共用一个权限点而不拆成两屏：评论只能属于某条动态，处置它的人必然也要能看见那条动态，
 * 分开授权只会造出「能删评论却看不到评论挂在哪儿」这种没法用的组合。
 *
 * 状态码表在这里写死而不是取接口：<b>im_moment.status 只有 1 正常 / 2 已下架</b>，
 * 与视频、直播那几屏同一套数字；可见范围四档同理（服务端 MomentVisibility）。
 * 这两份是库表结构的一部分，加值必然要改 DDL 与枚举，届时这一屏也躲不过一次改动。
 */
const VISIBILITY_TEXT = { 1: '公开', 2: '仅好友', 3: '部分人可见', 4: '仅自己' }
/** im_moment_media.type：1 图片 / 2 视频，与用户端同一套数字 */
const MEDIA_VIDEO = 2

function visibilityText(code) {
  return VISIBILITY_TEXT[code] || '未知'
}

onMounted(() => {
  load()
  loadComments()
})

/* ==================== 动态列表 ==================== */

const activeTab = ref('moments')
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', userId: '', status: undefined, visibility: undefined, current: 1, size: 20 })

async function load() {
  loading.value = true
  try {
    const page = await fetchMomentPage({
      keyword: query.keyword || undefined,
      userId: query.userId || undefined,
      status: query.status,
      visibility: query.visibility,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
    loadPreviews()
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果，不把列表清成空白
  } finally {
    loading.value = false
  }
}

function reload() {
  query.current = 1
  load()
}

/**
 * 缩略图：后端 mediaUrls 只给前 3 个受控地址，它们与 mediaTypes 同序一一对应；
 * <img> 与 <video> 的 src 都带不了登录头，必须经 fetchMedia 换成 blob（与视频封面同一套路），
 * 而视频动辄几十 MB，timeout 给 0 不让默认的 20s 掐断。
 * 键用动态 ID，值是 { src, type } 数组；未就绪时画不出东西，回来后再填。
 */
const previews = reactive({})

function previewOf(row) {
  return previews[row.id] || []
}

/**
 * 把地址与类型成对交给模板。
 *
 * 不能只交地址：丢掉类型就丢掉「该用 img 还是 video」，而先过滤失败的地址再配类型
 * 会让下标错位（第 2 条拉失败，第 3 条的类型就挂到了第 2 条上），所以先配后筛。
 */
function zipMedia(urls, types, blobs) {
  return (urls || [])
    .map((url, index) => ({ src: blobs[index], type: Number(types && types[index]) || 1 }))
    .filter((item) => item.src)
}

function loadPreviews() {
  rows.value.forEach((row) => {
    if (previews[row.id] || !(row.mediaUrls || []).length) {
      return
    }
    previews[row.id] = []
    Promise.all(row.mediaUrls.map((url) => fetchMedia(url, { timeout: 0 }))).then((blobs) => {
      previews[row.id] = zipMedia(row.mediaUrls, row.mediaTypes, blobs)
    })
  })
}

const detail = reactive({ visible: false, loading: false, row: null, media: [] })
/** 有视频就把详情弹层撑宽，纯图动态不给多余宽度 */
const detailVideo = computed(() => detail.media.some((item) => item.type === MEDIA_VIDEO))
let detailSeq = 0

async function openDetail(row) {
  const seq = ++detailSeq
  detail.visible = true
  detail.loading = true
  detail.row = row
  detail.media = []
  try {
    const full = await fetchMomentDetail(row.id)
    if (seq !== detailSeq) {
      return
    }
    detail.row = full
    await fillDetail(full, seq)
  } catch {
    // 同上
  } finally {
    if (seq === detailSeq) {
      detail.loading = false
    }
  }
}

/**
 * 把详情里的媒体换上 blob 地址。
 *
 * 列表页直接点开与从评论跳过来两条路都要走这一步，抽出来是为了两边不会各自长出一套
 * 「只收地址不收类型」的写法 —— 那样视频在一条路上能播、在另一条路上是个碎图标。
 */
async function fillDetail(full, seq) {
  detail.row = full
  const blobs = await Promise.all((full.mediaUrls || []).map((url) => fetchMedia(url, { timeout: 0 })))
  if (seq === detailSeq) {
    detail.media = zipMedia(full.mediaUrls, full.mediaTypes, blobs)
  }
}

function confirmOffline(row) {
  ElMessageBox.prompt(
    `下架后这条动态在任何人的 Feed 与详情里都不再出现（含作者本人），行保留、可随时恢复；事由进服务端日志。`,
    '下架动态',
    { confirmButtonText: '确认下架', cancelButtonText: '取消', inputPlaceholder: '选填：下架事由', type: 'warning' }
  )
    .then(async ({ value }) => {
      const updated = await offlineMoment(row.id, (value || '').trim() || undefined)
      Object.assign(row, updated)
      ElMessage.success('动态已下架')
    })
    .catch(() => {})
}

async function confirmOnline(row) {
  try {
    const updated = await onlineMoment(row.id)
    Object.assign(row, updated)
    ElMessage.success('动态已恢复')
  } catch {
    // 接口层已弹提示
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      '删除会连带清掉这条动态的媒体、评论、点赞与可见范围白名单，一期没有回收站，删后无法自助恢复。绝大多数情况下架就够了。',
      '删除动态',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'error',
        inputPlaceholder: '请输入「删除」两个字确认',
        inputValidator: (value) => ((value || '').trim() === '删除' ? true : '请精确输入：删除')
      }
    )
  } catch {
    return
  }
  try {
    await deleteMoment(row.id)
    ElMessage.success('动态已删除')
    load()
  } catch {
    // 同上
  }
}

/* ==================== 评论队列 ==================== */

const commentRows = ref([])
const commentTotal = ref(0)
const commentLoading = ref(false)
const commentQuery = reactive({ keyword: '', momentId: '', status: undefined, current: 1, size: 20 })

async function loadComments() {
  commentLoading.value = true
  try {
    const page = await fetchMomentCommentPage({
      keyword: commentQuery.keyword || undefined,
      momentId: commentQuery.momentId || undefined,
      status: commentQuery.status,
      current: commentQuery.current,
      size: commentQuery.size
    })
    commentRows.value = page.records || []
    commentTotal.value = Number(page.total) || 0
  } catch {
    // 同上
  } finally {
    commentLoading.value = false
  }
}

function reloadComments() {
  commentQuery.current = 1
  loadComments()
}

/** 从评论跳到它所在的动态：直接开那一条的详情弹框。
 * 列表页没有「按动态 ID 筛」的输入框（后端只认 userId / keyword），
 * 所以这里不去改列表的筛选条件再切页签——那样点一下会被当作「刷新列表」而看不出发生了什么。*/
function jumpToMoment(momentId) {
  const seq = ++detailSeq
  detail.visible = true
  detail.loading = true
  detail.row = null
  detail.media = []
  fetchMomentDetail(momentId)
    .then((full) => fillDetail(full, seq))
    .catch(() => {})
    .finally(() => {
      if (seq === detailSeq) {
        detail.loading = false
      }
    })
}

function confirmCommentOffline(row) {
  ElMessageBox.prompt(
    '下架这条评论会连带藏掉它当前的回复，并同步扣掉动态上的评论数——否则列表显示「3 条评论」而屏上只有 2 条。恢复时只恢复这一条。',
    '下架评论',
    { confirmButtonText: '确认下架', cancelButtonText: '取消', inputPlaceholder: '选填：事由，进服务端日志', type: 'warning' }
  )
    .then(async ({ value }) => {
      const updated = await offlineMomentComment(row.id, (value || '').trim() || undefined)
      Object.assign(row, updated)
      ElMessage.success('评论已下架')
    })
    .catch(() => {})
}

async function confirmCommentOnline(row) {
  try {
    const updated = await onlineMomentComment(row.id)
    Object.assign(row, updated)
    ElMessage.success('评论已恢复')
  } catch {
    // 同上
  }
}

async function confirmCommentDelete(row) {
  try {
    await ElMessageBox.confirm(
      '删除这条评论会连带删掉它下面的全部回复，评论数按真正删掉的行数回退。不可自助恢复。',
      '删除评论',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'error',
        inputPlaceholder: '请输入「删除」两个字确认',
        inputValidator: (value) => ((value || '').trim() === '删除' ? true : '请精确输入：删除')
      }
    )
  } catch {
    return
  }
  try {
    await deleteMomentComment(row.id)
    ElMessage.success('评论已删除')
    loadComments()
  } catch {
    // 同上
  }
}
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.keyword {
  width: 220px;
}

.uid {
  width: 150px;
}

.pick {
  width: 150px;
}

.moments__tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.hint {
  margin-bottom: 16px;
}

.moment-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.content {
  white-space: pre-line;
  word-break: break-all;
}

.media-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: flex-start;
}

.thumb {
  background: #f5f7fa;
  border-radius: 4px;
  height: 46px;
  object-fit: cover;
  width: 62px;
}

/* 视频缩略给到能看清人脸的尺寸：46px 高的播放器上连进度条都点不到 */
.thumb--video {
  background: #000;
  height: 120px;
  width: 180px;
  object-fit: contain;
}

.user-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.link {
  align-self: flex-start;
  padding: 0;
}

.detail-head {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.detail-content {
  white-space: pre-line;
  word-break: break-all;
}

.detail-media {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-img {
  background: #f5f7fa;
  border-radius: 4px;
  height: 150px;
  object-fit: cover;
  width: 200px;
}

/* 详情里的视频按弹层宽度铺满，高度封顶在视口内，不出双层滚动条 */
.detail-video {
  background: #000;
  border-radius: 4px;
  display: block;
  max-height: 62vh;
  width: 100%;
}
</style>
