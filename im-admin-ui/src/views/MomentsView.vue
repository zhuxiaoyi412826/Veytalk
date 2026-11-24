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
                    <img v-for="url in previewOf(row)" :key="url" class="thumb" :src="url" alt="" />
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

    <!-- 详情弹框：列表只给前 3 张缩略与截断正文，这里换成全文与全部媒体 -->
    <el-dialog v-model="detail.visible" title="动态详情" width="640px">
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
          <img v-for="url in detail.media" :key="url" class="detail-img" :src="url" alt="" />
        </div>
        <p v-if="detail.row && !detail.media.length" class="muted">这条动态没有媒体</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
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
 * 缩略图：后端 mediaUrls 只给前 3 个受控地址，<img src> 带不了登录头，
 * 必须经 fetchMedia 换成 blob（与视频封面同一套路，缓存与合流都在 fetchMedia 里）。
 * 键用动态 ID，值是已换好的地址数组；未就绪时画不出图，回来后再填。
 */
const previews = reactive({})

function previewOf(row) {
  return previews[row.id] || []
}

function loadPreviews() {
  rows.value.forEach((row) => {
    if (previews[row.id] || !(row.mediaUrls || []).length) {
      return
    }
    previews[row.id] = []
    Promise.all(row.mediaUrls.map((url) => fetchMedia(url))).then((list) => {
      previews[row.id] = list.filter(Boolean)
    })
  })
}

const detail = reactive({ visible: false, loading: false, row: null, media: [] })
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
    // 全量媒体同样要过 fetchMedia：详情里的图片比列表多，一条九图动态就是九个请求，串行等会白屏
    const list = await Promise.all((full.mediaUrls || []).map((url) => fetchMedia(url)))
    if (seq === detailSeq) {
      detail.media = list.filter(Boolean)
    }
  } catch {
    // 同上
  } finally {
    if (seq === detailSeq) {
      detail.loading = false
    }
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
  fetchMomentDetail(momentId)
    .then((full) => {
      detail.row = full
      detail.media = []
      detail.visible = true
      return Promise.all((full.mediaUrls || []).map((url) => fetchMedia(url)))
    })
    .then((list) => {
      detail.media = list.filter(Boolean)
    })
    .catch(() => {})
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
  gap: 4px;
}

.thumb {
  background: #f5f7fa;
  border-radius: 4px;
  height: 46px;
  object-fit: cover;
  width: 62px;
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
</style>
