<template>
  <div class="interview">
    <div class="toolbar">
      <h2 class="page-title">AI 面试记录</h2>
      <span class="grow" />
      <span v-if="overview" class="muted">数据截至 {{ formatDateTime(overview.generatedAt) }}</span>
      <el-button :loading="overviewLoading" @click="loadOverview">
        <el-icon><Refresh /></el-icon>
        <span>刷新数字</span>
      </el-button>
    </div>

    <el-row :gutter="16" class="overview">
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.totalSessions) }}</span>
            <span class="muted">累计面试场次</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.todaySessions) }}</span>
            <span class="muted">今日新建</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num warning">{{ displayValue(overview?.ongoingSessions) }}</span>
            <span class="muted">
              进行中
              <el-tooltip placement="top" content="其中开始时间超过合理时长但状态还是 0 的僵尸行，见下面那张「疑似掉线」卡">
                <el-icon class="hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.staleOngoing) }}</span>
            <span class="muted">疑似掉线（不自动改状态）</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num out">{{ displayValue(overview?.violationLimitSessions) }}</span>
            <span class="muted">违规达阈值强停（全历史）</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num out">{{ displayValue(overview?.todayViolationLimit) }}</span>
            <span class="muted">今日违规强停</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ rateText(overview?.violationRate) }}</span>
            <span class="muted">
              违规率
              <el-tooltip placement="top" content="分母只含已结束场次（正常结束 / 违规强停 / 未完成），把刚开考的算进去会在每个面试高峰虚假跳水">
                <el-icon class="hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="muted rank-title">违规榜前 {{ overview?.topViolators?.length ?? 0 }} 人</span>
            <el-table
              v-if="overview?.topViolators?.length"
              :data="overview.topViolators"
              class="rank"
              size="small"
            >
              <el-table-column label="候选人" min-width="110">
                <template #default="{ row }">
                  <span class="ellipsis">{{ row.candidateName || DELETED_NAME }}</span>
                </template>
              </el-table-column>
              <el-table-column align="right" label="违规" width="60">
                <template #default="{ row }">
                  <span class="num out">{{ formatNumber(row.violations) }}</span>
                </template>
              </el-table-column>
              <el-table-column align="right" label="场次" width="60">
                <template #default="{ row }">
                  <span class="num muted">{{ formatNumber(row.sessions) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <span v-else class="muted">没人有过违规</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      class="hint"
      :closable="false"
      type="warning"
      title="这一屏三个端点全是只读：没有删除场次、没有清零计数、也没有「中止这场面试」"
      description="那些违规计数是判罚依据——「违规达阈值强制结束」这句话成立的前提，就是这些数字由写侧在当时一口气加出来、事后没人碰过；事件流水表的注释写死只增不改不删。真要中止一场正在作弊的面试，正当路径是去用户管理禁用那个候选人的账号：那是一次有权限、有留痕、有理由的处置。注意禁用账号不会让那一屏立刻停下（面试页是纯前端的，它只会在下一次上报时被鉴权拦住）。"
    />

    <div class="filters">
      <el-input
        v-model="query.keyword"
        :maxlength="50"
        clearable
        class="keyword"
        placeholder="岗位/面试标题关键词"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-input
        v-model="query.userId"
        :maxlength="20"
        clearable
        class="uid"
        placeholder="候选人 ID"
        @clear="reload"
        @keyup.enter="reload"
      />
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option :value="0" label="进行中" />
        <el-option :value="1" label="已结束" />
        <el-option :value="2" label="违规强停" />
        <el-option :value="3" label="未完成" />
      </el-select>
      <el-input
        v-model="query.minViolations"
        :maxlength="4"
        class="uid"
        clearable
        placeholder="违规数下限"
        @clear="reload"
        @keyup.enter="reload"
      />
      <el-date-picker
        v-model="dateRange"
        :default-time="defaultTime"
        class="range"
        end-placeholder="结束"
        start-placeholder="开始"
        type="datetimerange"
        unlink-panels
        value-format="YYYY-MM-DDTHH:mm:ss"
        @change="reload"
      />
      <el-checkbox v-model="query.violationOnly" @change="reload">只看有违规</el-checkbox>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>查询</span>
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="候选人" min-width="150">
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis">{{ row.candidateName || DELETED_NAME }}</span>
              <span class="muted">#{{ row.userId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="岗位 / 标题" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis">{{ row.title || '（无标题）' }}</span>
              <span class="muted">#{{ row.sessionId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status).type" effect="plain" size="small">
              {{ statusTag(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始" width="120">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.startTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="90">
          <template #default="{ row }">
            <span class="num">{{ formatDuration(row.durationSeconds) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="轮次" width="70">
          <template #default="{ row }">
            <span class="num">{{ displayValue(row.turnCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="违规" width="70">
          <template #default="{ row }">
            <span class="num" :class="row.violationCount > 0 ? 'out strong' : ''">
              {{ displayValue(row.violationCount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="切屏 / 复制 / 粘贴" width="140">
          <template #default="{ row }">
            <span class="num muted">
              {{ displayValue(row.blurCount) }} / {{ displayValue(row.copyCount) }} /
              <span :class="row.pasteCount > 0 ? 'out' : ''">{{ displayValue(row.pasteCount) }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="结束原因" width="110">
          <template #default="{ row }">
            <span class="muted">{{ reasonText(row.endReason) }}</span>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="90">
          <template #default="{ row }">
            <el-button text type="primary" @click="openDetail(row.sessionId)">档案</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的面试场次" :image-size="80" />
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

    <!-- 单场档案：后台里唯一交出作答原文的路径，所以只在抽屉真打开时才发这个请求 -->
    <el-drawer v-model="drawer" :size="drawerSize" title="面试档案">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <el-tag :type="statusTag(detail.status).type" effect="dark">{{ statusTag(detail.status).text }}</el-tag>
            <span class="muted">#{{ detail.sessionId }} · 建档于 {{ formatDateTime(detail.createTime) }}</span>
          </div>

          <el-descriptions :column="1" border class="block">
            <el-descriptions-item label="候选人">
              {{ detail.candidateName || DELETED_NAME }} #{{ detail.userId }}
            </el-descriptions-item>
            <el-descriptions-item label="岗位 / 标题">{{ detail.title || '（无标题）' }}</el-descriptions-item>
            <el-descriptions-item label="起止">
              <span class="num">{{ formatDateTime(detail.startTime) }}</span> →
              <span class="num">{{ formatDateTime(detail.endTime) }}</span>
              <span v-if="detail.endReason" class="muted">（{{ reasonText(detail.endReason) }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="时长 / 轮次">
              <span class="num">{{ formatDuration(detail.durationSeconds) }} · {{ displayValue(detail.turnCount) }} 轮</span>
            </el-descriptions-item>
            <el-descriptions-item label="违规合计">
              <span class="num" :class="detail.violationCount > 0 ? 'out strong' : ''">
                {{ displayValue(detail.violationCount) }}
              </span>
              <span v-if="detail.violationByType" class="ml">
                <el-tag
                  v-for="(count, type) in detail.violationByType"
                  :key="type"
                  class="mr"
                  effect="plain"
                  size="small"
                  type="danger"
                >
                  {{ eventText(type) }} ×{{ count }}
                </el-tag>
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="退出全屏">{{ displayValue(detail.fullscreenExitCount) }}</el-descriptions-item>
            <el-descriptions-item label="屏幕 / 客户端">
              <span class="muted">{{ detail.screen || EMPTY_TEXT }} · {{ detail.clientInfo || EMPTY_TEXT }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <p class="muted note">
            计数与事件不一致时以事件流水为准：会话表上那几个数字是展示缓存，
            由写侧在事件落库的同一刻累加出来，而定性依据永远是下面那份只能追加的流水。
          </p>

          <h4 class="sub-title">逐轮问答原文（{{ detail.qaList?.length ?? 0 }} 条）</h4>
          <div class="qa">
            <div
              v-for="item in detail.qaList || []"
              :key="item.seq"
              class="qa-item"
              :class="item.role === 'user' ? 'qa-item--answer' : 'qa-item--ask'"
            >
              <div class="qa-head">
                <span class="muted">
                  #{{ item.seq }}
                  <template v-if="item.turnNo"> · 第 {{ item.turnNo }} 轮</template>
                  · {{ roleText(item.role) }}
                  <template v-if="item.charCount"> · {{ formatNumber(item.charCount) }} 字</template>
                  <template v-if="item.elapsedMs != null"> · 用时 {{ Math.round(item.elapsedMs / 1000) }} 秒</template>
                </span>
                <span class="muted num">{{ formatListTime(item.createTime) }}</span>
              </div>
              <pre class="qa-body">{{ item.content }}</pre>
            </div>
            <el-empty v-if="!detail.qaList?.length" description="这场还没有问答内容" :image-size="60" />
          </div>

          <h4 class="sub-title">事件流水（{{ detail.events?.length ?? 0 }} 条，服务端夹到 500）</h4>
          <el-table :data="detail.events || []" size="small">
            <el-table-column label="事件时间" width="130">
              <template #default="{ row }">
                <div class="stack">
                  <span class="num">{{ formatDateTime(row.eventTime) }}</span>
                  <span v-if="row.createTime" class="muted num">落库 {{ formatListTime(row.createTime) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <span :class="row.violation === 1 ? 'out' : 'muted'">{{ eventText(row.eventType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="计违规" width="80">
              <template #default="{ row }">
                <el-tag :type="row.violation === 1 ? 'danger' : 'info'" effect="plain" size="small">
                  {{ row.violation === 1 ? '计入' : '仅留痕' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="轮次" width="60">
              <template #default="{ row }">
                <span class="num">{{ displayValue(row.turnNo) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="详情" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="code">{{ row.detail || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="这场没有留下事件" :image-size="60" />
            </template>
          </el-table>
        </template>
        <el-empty v-else description="档案没取到（场次不存在或已过期，后端回 10001）" :image-size="80" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { fetchInterviewDetail, fetchInterviewOverview, fetchInterviewPage } from '@/api/admin'
import {
  EMPTY_TEXT,
  displayValue,
  formatDateTime,
  formatDuration,
  formatListTime,
  formatNumber
} from '@/utils/format'

/**
 * AI 面试记录（四期第五批，纯读）。
 *
 * 概览那八个格子一条带 CASE 的聚合拿全，日界由 MySQL 的 CURDATE() 定——不在应用侧算时间戳再传过去，
 * 否则「今日」会随着两台机器时区不一致而变成两个答案。违规榜直接嵌在第八张卡里，
 * 因为它只有 10 行，为它单开一屏会让「该找谁聊聊」这件事变成两次点击。
 *
 * 列表行不带问答与事件正文（那是 TEXT 列，一页 20 行各带一份会把响应撑爆），
 * 所以档案抽屉才发第二个请求；这也是后台唯一交出作答原文的路径，后端为此留一条含场次 ID 的日志。
 *
 * violationOnly 与 minViolations 都没有索引，默认都不勾——本页绝大多数时候是按人找，不是按违规找。
 */
const DELETED_NAME = '已注销用户'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  keyword: '',
  userId: '',
  status: undefined,
  violationOnly: false,
  minViolations: '',
  current: 1,
  size: 20
})
const dateRange = ref(null)

const overview = ref(null)
const overviewLoading = ref(false)

const drawer = ref(false)
const drawerSize = ref('720px')
const detailLoading = ref(false)
const detail = ref(null)

/** el-date-picker 的范围端点：开始那天取 00:00:00、结束那天取 23:59:59，不然「今天」会漏掉一整天 */
const defaultTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

function digitsOrUndefined(value) {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) ? text : undefined
}

async function load() {
  loading.value = true
  try {
    const page = await fetchInterviewPage({
      keyword: query.keyword || undefined,
      userId: digitsOrUndefined(query.userId),
      status: query.status,
      violationOnly: query.violationOnly || undefined,
      minViolations: digitsOrUndefined(query.minViolations),
      startTime: dateRange.value?.[0] || undefined,
      endTime: dateRange.value?.[1] || undefined,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果
  } finally {
    loading.value = false
  }
}

function reload() {
  query.current = 1
  load()
}

async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = await fetchInterviewOverview()
  } catch {
    // 同上：数字取不到时保留上一次的
  } finally {
    overviewLoading.value = false
  }
}

async function openDetail(sessionId) {
  detail.value = null
  drawer.value = true
  detailLoading.value = true
  try {
    detail.value = await fetchInterviewDetail(sessionId)
  } catch {
    // 取不到就画空态：档案里没有任何字段能以列表行冒充
  } finally {
    detailLoading.value = false
  }
}

function statusTag(status) {
  switch (status) {
    case 0:
      return { type: 'warning', text: '进行中' }
    case 1:
      return { type: 'success', text: '已结束' }
    case 2:
      return { type: 'danger', text: '违规强停' }
    case 3:
      return { type: 'info', text: '未完成' }
    default:
      return { type: 'info', text: EMPTY_TEXT }
  }
}

/** endReason 码表在服务端（im-ai 的 REASON_*），这里只是显示层的生词表，抄错了顶多看到一个英文原词 */
function reasonText(reason) {
  switch (reason) {
    case 'user-end':
      return '本人结束'
    case 'violation-limit':
      return '违规达阈值'
    case 'reload':
      return '页面刷新'
    case 'close':
      return '关闭页面'
    case 'timeout':
      return '超时未完成'
    default:
      return reason || EMPTY_TEXT
  }
}

/**
 * 事件类型中文名。违规类（切屏/复制/粘贴/剪切/右键/退全屏）与流程类（开考/每轮交卷/收卷）
 * 都要有名字：复盘时要能说清「第 3 轮那次粘贴」是什么，而不是只剩一个数字。
 */
function eventText(type) {
  switch (type) {
    case 'visibility-hidden':
      return '切标签页'
    case 'blur':
      return '窗口失焦'
    case 'copy':
      return '复制'
    case 'paste':
      return '粘贴'
    case 'cut':
      return '剪切'
    case 'contextmenu':
      return '右键菜单'
    case 'fullscreen-exit':
      return '退出全屏'
    case 'session-start':
      return '面试开始'
    case 'turn-submit':
      return '提交作答'
    case 'session-end':
      return '面试结束'
    default:
      return type || EMPTY_TEXT
  }
}

function roleText(role) {
  if (role === 'user') {
    return '候选人作答'
  }
  if (role === 'assistant') {
    return '面试官提问'
  }
  return role || EMPTY_TEXT
}

/** 后端给的是 0..1 的比率，不是百分数：直接贴 0.125 会让人以为只有 0.125% */
function rateText(rate) {
  if (rate === null || rate === undefined) {
    return EMPTY_TEXT
  }
  return `${(Number(rate) * 100).toFixed(1)}%`
}

onMounted(() => {
  load()
  loadOverview()
  drawerSize.value = window.innerWidth < 900 ? '100%' : '720px'
})
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.mb {
  margin-bottom: 16px;
}

.hint {
  margin-bottom: 16px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kpi-value {
  font-size: 26px;
  font-weight: 600;
}

.kpi-value.warning {
  color: #e6a23c;
}

.kpi-value.out {
  color: #f56c6c;
}

.overview {
  margin-bottom: -8px;
}

.rank {
  margin-top: -4px;
}

.rank-title {
  margin-bottom: 2px;
}

.stack {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.filters {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.keyword {
  width: 220px;
}

.status {
  width: 130px;
}

.uid {
  width: 130px;
}

.range {
  width: 340px;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.strong {
  font-weight: 600;
}

.out {
  color: #f56c6c;
}

.ml {
  margin-left: 6px;
}

.mr {
  margin-right: 4px;
}

.detail-head {
  align-items: center;
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.block {
  margin-bottom: 16px;
}

.note {
  font-size: 12px;
  line-height: 1.6;
  margin: 0 0 16px;
}

.sub-title {
  align-items: center;
  display: flex;
  font-size: 14px;
  margin: 0 0 8px;
}

.qa {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.qa-item {
  border-left: 3px solid #dcdfe6;
  border-radius: 4px;
  background: #f5f7fa;
  padding: 8px 10px;
}

/* 提问与作答要一眼分得开：一屏流水读下来，「谁说的」比「说了什么」更早被需要 */
.qa-item--ask {
  border-left-color: #409eff;
  background: #f2f8ff;
}

.qa-item--answer {
  border-left-color: #67c23a;
  background: #f4fbf2;
}

.qa-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}

.qa-body {
  font-family: inherit;
  font-size: 13px;
  margin: 0;
  /* 作答原文里的换行是候选人自己敲的，不能当空白折叠掉；长句又要能折行，不然抽屉会被一行撑破 */
  white-space: pre-wrap;
  word-break: break-word;
}

.hint-icon {
  color: #909399;
  margin-left: 2px;
}
</style>
