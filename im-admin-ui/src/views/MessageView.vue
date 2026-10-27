<template>
  <div class="messages">
    <div class="toolbar">
      <h2 class="page-title">消息管理</h2>
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
            <span class="kpi-value num">{{ displayValue(overview?.todayCount) }}</span>
            <span class="muted">今日消息量</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.weekCount) }}</span>
            <span class="muted">近 7 日消息量</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.todayRecalled) }}</span>
            <span class="muted">今日「发出且已被撤回」</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num warning">{{ displayValue(overview?.todayAdminRecalled) }}</span>
            <span class="muted">
              其中后台强撤
              <el-tooltip placement="top" content="这是对后台自身的问责指标：今天有几笔是管理员撤的，而不是用户自己撤的。从部署当天开始算，不回溯">
                <el-icon class="hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num" :class="overview && overview.expireDays === 0 ? 'warning' : ''">
              {{ retentionText }}
            </span>
            <span class="muted">保留天数（im.message.retention-days）</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num small">{{ formatListTime(overview?.oldestRetainedTime) }}</span>
            <span class="muted">库里最早的一条</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="12" :md="24" :sm="24" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="muted slice-title">今日各类型分布</span>
            <div v-if="overview?.typeSlices?.length" class="slices">
              <el-tag v-for="slice in overview.typeSlices" :key="slice.msgType" effect="plain" size="small">
                {{ slice.msgTypeDesc || typeText(slice.msgType) }} {{ formatNumber(slice.count) }}
              </el-tag>
            </div>
            <span v-else class="muted">今天还没有消息</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      class="hint"
      :closable="false"
      type="error"
      title="这一屏能读到任意两个人的聊天正文，权限点与 system:log 同级对待，且不随「群组管理」「举报处置」顺带给出"
      description="强制撤回无视时限与发送者身份，但转账 / 红包与系统通知仍会被后端拒绝——那两条拦的不是权限，而是「撤回会把已经生效的资金凭证抹掉」这个事实。响应不含 fileUrl：图片 / 文件 / 语音的 content 存的是文件 ID 而不是可读文本，所以要看的是扩展摘要（文件名、尺寸、时长）。想看附件本体要走文件那一屏的鉴权路径。"
    />

    <div class="filters">
      <el-input
        v-model="query.conversationId"
        :maxlength="20"
        clearable
        class="cid"
        placeholder="会话 ID"
        @clear="reload"
        @keyup.enter="reload"
      />
      <el-input
        v-model="query.fromUserId"
        :maxlength="20"
        clearable
        class="uid"
        placeholder="发送者用户 ID"
        @clear="reload"
        @keyup.enter="reload"
      />
      <el-select v-model="query.msgType" class="status" clearable placeholder="全部类型" @change="reload">
        <el-option v-for="item in MSG_TYPES" :key="item.value" :value="item.value" :label="item.label" />
      </el-select>
      <el-input
        v-model="query.keyword"
        :maxlength="30"
        clearable
        class="keyword"
        placeholder="正文关键词（须带范围）"
        @clear="reload"
        @keyup.enter="onSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
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
      <el-checkbox v-model="query.recalledOnly" @change="reload">只看已撤回</el-checkbox>
      <el-button :disabled="scopeMissing" :loading="loading" @click="onSearch">
        <el-icon><Refresh /></el-icon>
        <span>查询</span>
      </el-button>
    </div>

    <el-alert
      v-if="scopeMissing"
      class="hint"
      :closable="false"
      type="warning"
      title="带关键词就必须同时限定会话、发送者或时间范围，否则这一条查不了"
      description="这不是界面摆设：同一条硬闸在服务端（回 1001「按关键词检索消息必须同时限定会话、发送者或时间范围」），因为提示会被绕过，而 curl 不会读注释。那条 LIKE 是对全库最大的一张表做顺序扫描，im_message 没有全文索引，content 也进不了普通二级索引。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="时间" width="130">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.sendTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="会话" min-width="170">
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis">{{ row.conversationName || `会话 #${row.conversationId}` }}</span>
              <span class="muted">
                #{{ row.conversationId }} · {{ convTypeText(row.conversationType) }}
                <template v-if="row.seq"> · seq {{ row.seq }}</template>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="发送者" min-width="140">
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis">{{ row.fromName || EMPTY_TEXT }}</span>
              <span v-if="row.fromUserId" class="muted">#{{ row.fromUserId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.msgTypeDesc || typeText(row.msgType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="260">
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis content">{{ row.content || EMPTY_TEXT }}</span>
              <span v-if="row.extraSummary" class="muted">{{ row.extraSummary }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="已读" width="70">
          <template #default="{ row }">
            <!-- 群聊的已读要逐成员推算位点，那一页不值那个钱，所以后端只给单聊行填这个数 -->
            <span class="num">{{ row.readCount == null ? EMPTY_TEXT : formatNumber(row.readCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.recalled === 1" effect="dark" size="small" type="info">已撤回</el-tag>
            <el-tag v-else effect="plain" size="small" type="success">正常</el-tag>
            <div v-if="row.recallTime" class="muted num">{{ formatListTime(row.recallTime) }}</div>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="150">
          <template #default="{ row }">
            <el-button text type="primary" @click="openDetail(row.messageId)">全文</el-button>
            <el-button
              v-if="row.recalled !== 1"
              text
              type="danger"
              @click="confirmRecall(row)"
            >
              强撤
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的消息" :image-size="80" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @size-change="reload"
          @current-change="load"
        />
      </div>
    </el-card>

    <!-- 单条全文档案：后台里唯一交出完整正文的端点，读一次在后端留一条含 messageId 的日志 -->
    <el-dialog v-model="detail.visible" :title="`消息档案：#${detail.messageId}`" width="720px">
      <div v-loading="detail.loading">
        <template v-if="detail.row">
          <div class="detail-head">
            <el-tag :type="detail.row.recalled === 1 ? 'info' : 'success'" effect="plain" size="small">
              {{ detail.row.recalled === 1 ? '已撤回' : '正常' }}
            </el-tag>
            <el-tag effect="plain" size="small">{{ detail.row.msgTypeDesc || typeText(detail.row.msgType) }}</el-tag>
            <span class="muted">{{ formatDateTime(detail.row.sendTime) }}</span>
          </div>

          <el-descriptions :column="2" border class="block">
            <el-descriptions-item label="会话">
              {{ detail.row.conversationName || `会话 #${detail.row.conversationId}` }}
              <span class="muted ml">#{{ detail.row.conversationId }} · {{ convTypeText(detail.row.conversationType) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="发送者">
              {{ detail.row.fromName || EMPTY_TEXT }}
              <span v-if="detail.row.fromUserId" class="muted ml">#{{ detail.row.fromUserId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="序号 / 长度">
              <span class="num">{{ displayValue(detail.row.seq) }} · {{ formatNumber(detail.row.contentLength) }} 字</span>
            </el-descriptions-item>
            <el-descriptions-item label="撤回">
              <span v-if="detail.row.recalled === 1" class="muted num">{{ formatDateTime(detail.row.recallTime) }}</span>
              <span v-else class="muted">未撤回</span>
              <span class="muted ml">是不是后台撤的，看下面扩展摘要里有没有「后台强制撤回」那一段</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.row.extraSummary" label="扩展摘要" :span="2">
              <span class="code">{{ detail.row.extraSummary }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.row.quoteSummary" label="引用" :span="2">
              <span class="muted">{{ detail.row.quoteSummary }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="sub-title">正文全文</h4>
          <pre class="body">{{ detail.row.content || '（这一行没有文本正文，看上面的扩展摘要）' }}</pre>
          <p v-if="detail.row.contentTruncated" class="muted note">
            列表里这一条是被截断过的，这里给出的是完整正文（{{ formatNumber(detail.row.contentLength) }} 字）。
          </p>
        </template>
        <el-empty v-else description="消息没取到（不存在时后端回 5001）" :image-size="80" />
      </div>
      <template #footer>
        <el-button @click="detail.visible = false">关闭</el-button>
        <el-button
          v-if="detail.row && detail.row.recalled !== 1"
          type="danger"
          @click="confirmRecall(detail.row, true)"
        >
          强制撤回
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchMessageDetail, fetchMessageOverview, fetchMessagePage, recallMessage } from '@/api/admin'
import { EMPTY_TEXT, displayValue, formatDateTime, formatListTime, formatNumber } from '@/utils/format'

/**
 * 消息管理（四期第五批）——整个后台最敏感的一屏。
 *
 * keyword 检索等于读任意两个人的聊天正文，所以除了权限点，服务端还有三道与权限无关、
 * 只与代价和越权面有关的硬闸：带 keyword 必须带范围、单页 50、列表正文截到 2000 字。
 * 本页把第一条显式做成了「按钮禁用 + 一段解释」，而不是让人点下去吃一个 1001 红条；
 * 但真正的闸在服务端，这里的禁用只是把失败提前到还没发请求的那一刻。
 *
 * 排序固定按主键倒序（雪花 ID 即时间序），前端没有排序参数可传；
 * startTime / endTime 是过滤条件而不是排序键，走 idx_send_time 的是它们。
 *
 * 类型中文名一律取后端的 msgTypeDesc，下面这份 MSG_TYPES 只服务两处：筛选下拉的选项，
 * 以及万一 desc 缺失时的兜底。它不参与任何判定。
 */
const MSG_TYPES = [
  { value: 1, label: '文本' },
  { value: 2, label: '图片' },
  { value: 3, label: '文件' },
  { value: 4, label: '语音' },
  { value: 5, label: '系统通知' },
  { value: 6, label: '通话' },
  { value: 7, label: '卡片' },
  { value: 8, label: '位置' },
  { value: 9, label: '聊天记录' },
  { value: 10, label: '投票' },
  { value: 11, label: '机器人' },
  { value: 12, label: '转账' },
  { value: 13, label: '红包' },
  { value: 14, label: '接龙' }
]

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  conversationId: '',
  fromUserId: '',
  msgType: undefined,
  keyword: '',
  recalledOnly: false,
  current: 1,
  size: 20
})
const dateRange = ref(null)

const overview = ref(null)
const overviewLoading = ref(false)

const detail = reactive({ visible: false, loading: false, messageId: null, row: null })

/** el-date-picker 的范围端点：开始那天取 00:00:00、结束那天取 23:59:59，不然「今天」会漏掉一整天 */
const defaultTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

const hasScope = computed(
  () =>
    !!String(query.conversationId || '').trim() ||
    !!String(query.fromUserId || '').trim() ||
    !!(dateRange.value && dateRange.value.length === 2)
)

/** 唯一会被前端拦下来的那次调用：带关键词却没有范围，后端必定回 1001 */
const scopeMissing = computed(() => !!String(query.keyword || '').trim() && !hasScope.value)

const retentionText = computed(() => {
  const days = overview.value?.expireDays
  if (days === null || days === undefined) {
    return EMPTY_TEXT
  }
  // 0 表示永不清理，这时「库里最早的一条」可能就是几年前，关键词检索因此是真·全表扫
  return days === 0 ? '永不清理' : `${days} 天`
})

function digitsOrUndefined(value) {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) ? text : undefined
}

async function load() {
  if (scopeMissing.value) {
    ElMessage.warning('带关键词检索必须先限定会话、发送者或时间范围')
    return
  }
  loading.value = true
  try {
    const page = await fetchMessagePage({
      conversationId: digitsOrUndefined(query.conversationId),
      fromUserId: digitsOrUndefined(query.fromUserId),
      msgType: query.msgType,
      keyword: query.keyword || undefined,
      recalledOnly: query.recalledOnly || undefined,
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

function onSearch() {
  reload()
}

async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = await fetchMessageOverview()
  } catch {
    // 数字取不到时保留上一次的，别让一屏空白替一条错误说话
  } finally {
    overviewLoading.value = false
  }
}

async function openDetail(messageId) {
  detail.messageId = messageId
  detail.row = null
  detail.visible = true
  detail.loading = true
  try {
    detail.row = await fetchMessageDetail(messageId)
  } catch {
    // 取不到就画空态：全文这件事没有任何字段能以列表行冒充
  } finally {
    detail.loading = false
  }
}

/**
 * 强制撤回。
 *
 * fromDetail 为真时是弹窗里点的：那边看完的是全文，撤回决定应当就在读完之后做出，
 * 所以成功后把弹窗里的这一行也换掉（后端返回处置后的最新档案），而不是只刷列表。
 * 转账 / 红包与系统通知会被后端拒绝，那一句提示由接口层原样弹出，不在这里改写成「操作失败」。
 */
async function confirmRecall(row, fromDetail = false) {
  try {
    await ElMessageBox.confirm(
      `强制撤回 #${row.messageId}（${row.msgTypeDesc || typeText(row.msgType)}，来自 ${row.fromName || '未知发送者'}）：` +
        `无视撤回时限，双方在会话里会看到「平台管理员撤回了一条消息」。` +
        `这条消息会落下一个只进后台、不下发任何客户端的问责标记，并计入顶部那个「今日后台强撤」。`,
      '强制撤回消息',
      { confirmButtonText: '确认撤回', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const updated = await recallMessage(row.messageId)
    if (updated) {
      const target = rows.value.find((item) => item.messageId === updated.messageId)
      if (target) {
        Object.assign(target, updated)
      }
      if (fromDetail) {
        detail.row = updated
      }
      ElMessage.success('消息已强制撤回')
    } else {
      load()
    }
  } catch {
    // 资金类与系统通知被拒、消息不存在（5001）等已由接口层弹提示
  }
}

function convTypeText(type) {
  if (type === 1) {
    return '单聊'
  }
  if (type === 2) {
    return '群聊'
  }
  return EMPTY_TEXT
}

/** 兜底用的类型名：正常路径上后端已经给了 msgTypeDesc，这里只在它缺失时顶上 */
function typeText(code) {
  return MSG_TYPES.find((item) => item.value === code)?.label || EMPTY_TEXT
}

onMounted(() => {
  load()
  loadOverview()
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

.kpi-value.small {
  font-size: 16px;
  line-height: 1.9;
}

.kpi-value.warning {
  color: #e6a23c;
}

.overview {
  margin-bottom: -8px;
}

.slices {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.slice-title {
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
  width: 240px;
}

.status {
  width: 130px;
}

.cid,
.uid {
  width: 170px;
}

.range {
  width: 340px;
}

.content {
  max-width: 100%;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
}

.ml {
  margin-left: 6px;
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

.sub-title {
  align-items: center;
  display: flex;
  font-size: 14px;
  margin: 0 0 8px;
}

.body {
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
  margin: 0;
  padding: 10px;
  white-space: pre-wrap;
  word-break: break-word;
}

.note {
  font-size: 12px;
  line-height: 1.6;
  margin: 8px 0 0;
}

.hint-icon {
  color: #909399;
  margin-left: 2px;
}
</style>
