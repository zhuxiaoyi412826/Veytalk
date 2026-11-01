<template>
  <div class="ai-guard">
    <div class="toolbar">
      <h2 class="page-title">AI 用量管控</h2>
      <span class="grow" />
      <span v-if="overview" class="muted">数据截至 {{ formatDateTime(overview.generatedAt) }}</span>
      <el-button :loading="overviewLoading" @click="loadOverview">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </div>

    <!-- 护栏状态告警 -->
    <el-alert
      v-if="overview && !overview.guardEnabled"
      class="hint"
      :closable="false"
      type="warning"
      title="护栏总开关已关闭——所有账号（含非 admin）不限预算、不查封禁"
      description="只有接口限流还在兜底。如果这不是有意的应急操作，请尽快去系统配置把 ai-guard.enabled 改回 true。"
    />

    <!-- KPI 概览 -->
    <el-row :gutter="16" class="overview">
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.totalCalls) }}</span>
            <span class="muted">累计调用</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.todayCalls) }}</span>
            <span class="muted">今日调用</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ msToSec(overview?.totalMs) }}</span>
            <span class="muted">累计耗时</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ msToSec(overview?.todayMs) }}</span>
            <span class="muted">今日耗时</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.activeUsers) }}</span>
            <span class="muted">用过 AI 的人数</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.todayActiveUsers) }}</span>
            <span class="muted">今日活跃</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num" :class="overview?.bannedUsers > 0 ? 'out' : ''">
              {{ formatNumber(overview?.bannedUsers) }}
            </span>
            <span class="muted">已封禁</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="3" :md="6" :sm="8" :xs="12">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ overview?.trialSeconds ?? '—' }}s</span>
            <span class="muted">非 admin 预算</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 烧钱榜 -->
    <el-card v-if="overview?.topUsers?.length" class="mb" shadow="never">
      <h4 class="sub-title">烧钱榜 前 {{ overview.topUsers.length }} 人</h4>
      <el-table :data="overview.topUsers" size="small">
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <span class="ellipsis">{{ row.userName || DELETED_NAME }}</span>
            <span class="muted"> #{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="累计耗时" width="100">
          <template #default="{ row }">
            <span class="num">{{ msToSec(row.totalMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="调用次数" width="90">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.calls) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 按人聚合 -->
    <h3 class="section-title">用户用量</h3>
    <div class="filters">
      <el-input
        v-model="userQuery.userId"
        :maxlength="20"
        clearable
        class="uid"
        placeholder="用户 ID"
        @clear="reloadUsers"
        @keyup.enter="reloadUsers"
      />
      <el-checkbox v-model="userQuery.bannedOnly" @change="reloadUsers">只看已封禁</el-checkbox>
      <el-button :loading="userLoading" @click="reloadUsers">
        <el-icon><Search /></el-icon>
        <span>查询</span>
      </el-button>
    </div>
    <el-card shadow="never" class="mb">
      <el-table v-loading="userLoading" :data="userRows" size="small">
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis">{{ row.userName || DELETED_NAME }}</span>
              <span class="muted">#{{ row.userId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column align="right" label="调用" width="70">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.totalCalls) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="累计耗时" width="100">
          <template #default="{ row }">
            <span class="num">{{ msToSec(row.totalMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="今日耗时" width="100">
          <template #default="{ row }">
            <span class="num muted">{{ msToSec(row.todayMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="剩余预算" width="100">
          <template #default="{ row }">
            <span v-if="row.unlimited" class="muted">不限</span>
            <span v-else class="num" :class="row.remainingMs <= 0 ? 'out strong' : ''">
              {{ msToSec(row.remainingMs) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最近使用" width="130">
          <template #default="{ row }">
            <span class="num muted">{{ formatListTime(row.lastUsedTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.banned" type="danger" effect="dark" size="small">封禁</el-tag>
            <el-tag v-else type="success" effect="plain" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="!row.banned" text type="danger" size="small" @click="openBan(row)">封禁</el-button>
            <el-button v-else text type="success" size="small" @click="handleUnban(row)">解封</el-button>
            <el-button text type="primary" size="small" @click="filterUsageBy(row.userId)">明细</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有人使用过 AI 功能" :image-size="60" />
        </template>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="userQuery.current"
          v-model:page-size="userQuery.size"
          :page-sizes="[10, 20, 50]"
          :total="userTotal"
          layout="total, sizes, prev, pager, next"
          @size-change="reloadUsers"
          @current-change="loadUsers"
        />
      </div>
    </el-card>

    <!-- 调用明细 -->
    <h3 class="section-title">调用明细</h3>
    <div class="filters">
      <el-input
        v-model="usageQuery.userId"
        :maxlength="20"
        clearable
        class="uid"
        placeholder="用户 ID"
        @clear="reloadUsage"
        @keyup.enter="reloadUsage"
      />
      <el-select v-model="usageQuery.scene" clearable placeholder="全部场景" class="scene" @change="reloadUsage">
        <el-option value="interview_chat" label="面试对话" />
        <el-option value="interview_asr" label="面试语音转文字" />
        <el-option value="interview_tts" label="文字合成朗读" />
        <el-option value="message_to_text" label="聊天转文字" />
      </el-select>
      <el-select v-model="usageQuery.success" clearable placeholder="全部结果" class="result" @change="reloadUsage">
        <el-option :value="true" label="成功" />
        <el-option :value="false" label="失败" />
      </el-select>
      <el-button :loading="usageLoading" @click="reloadUsage">
        <el-icon><Search /></el-icon>
        <span>查询</span>
      </el-button>
    </div>
    <el-card shadow="never">
      <el-table v-loading="usageLoading" :data="usageRows" size="small">
        <el-table-column label="用户" min-width="120">
          <template #default="{ row }">
            <span class="ellipsis">{{ row.userName || DELETED_NAME }}</span>
            <span class="muted"> #{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="场景" width="130">
          <template #default="{ row }">
            <span class="muted">{{ sceneText(row.scene) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="耗时" width="80">
          <template #default="{ row }">
            <span class="num">{{ msToSec(row.durationMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="70">
          <template #default="{ row }">
            <el-tag :type="row.success === 1 ? 'success' : 'danger'" effect="plain" size="small">
              {{ row.success === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="code muted">{{ row.errorMsg || EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="130">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的调用记录" :image-size="60" />
        </template>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="usageQuery.current"
          v-model:page-size="usageQuery.size"
          :page-sizes="[20, 50, 100]"
          :total="usageTotal"
          layout="total, sizes, prev, pager, next"
          @size-change="reloadUsage"
          @current-change="loadUsage"
        />
      </div>
    </el-card>

    <!-- 封禁弹窗 -->
    <el-dialog v-model="banDialog" title="封禁 AI 功能" width="400px">
      <p class="muted note">
        只停该账号调用付费模型的能力（面试对话/语音转文字/朗读/聊天转文字），<b>不影响登录与聊天</b>。封禁即时生效。
      </p>
      <el-input
        v-model="banReason"
        type="textarea"
        :maxlength="200"
        show-word-limit
        :rows="3"
        placeholder="封禁原因（会展示给被封者，可留空）"
      />
      <template #footer>
        <el-button @click="banDialog = false">取消</el-button>
        <el-button type="danger" :loading="banLoading" @click="confirmBan">确认封禁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAiOverview,
  fetchAiUserPage,
  fetchAiUsagePage,
  banAiUser,
  unbanAiUser
} from '@/api/admin'
import {
  EMPTY_TEXT,
  displayValue,
  formatDateTime,
  formatListTime,
  formatNumber
} from '@/utils/format'

/**
 * AI 用量管控（四期第六批）。
 *
 * 这一屏有三个视图：概览 KPI（全局数字）、按人聚合翻页（烧钱排行榜 + 封禁/解封入口）、
 * 调用明细翻页（一笔一行，可按用户/场景/结果筛）。
 *
 * 封禁只停付费模型调用，不动账号登录——与 /admin/user/{id}/disable 是两种处置。
 * 封禁与解封即时生效（护栏每次模型调用现查库），无需重启或清缓存。
 */

const DELETED_NAME = '已注销用户'

/* ===== 概览 ===== */
const overview = ref(null)
const overviewLoading = ref(false)

async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = await fetchAiOverview()
  } catch {
    // 保留上一次
  } finally {
    overviewLoading.value = false
  }
}

/* ===== 用户聚合 ===== */
const userRows = ref([])
const userTotal = ref(0)
const userLoading = ref(false)
const userQuery = reactive({ userId: '', bannedOnly: false, current: 1, size: 20 })

async function loadUsers() {
  userLoading.value = true
  try {
    const page = await fetchAiUserPage({
      userId: digitsOrUndefined(userQuery.userId),
      bannedOnly: userQuery.bannedOnly || undefined,
      current: userQuery.current,
      size: userQuery.size
    })
    userRows.value = page.records || []
    userTotal.value = Number(page.total) || 0
  } catch {
    // 保留上一次
  } finally {
    userLoading.value = false
  }
}

function reloadUsers() {
  userQuery.current = 1
  loadUsers()
}

/* ===== 调用明细 ===== */
const usageRows = ref([])
const usageTotal = ref(0)
const usageLoading = ref(false)
const usageQuery = reactive({ userId: '', scene: undefined, success: undefined, current: 1, size: 20 })

async function loadUsage() {
  usageLoading.value = true
  try {
    const page = await fetchAiUsagePage({
      userId: digitsOrUndefined(usageQuery.userId),
      scene: usageQuery.scene || undefined,
      success: usageQuery.success,
      current: usageQuery.current,
      size: usageQuery.size
    })
    usageRows.value = page.records || []
    usageTotal.value = Number(page.total) || 0
  } catch {
    // 保留上一次
  } finally {
    usageLoading.value = false
  }
}

function reloadUsage() {
  usageQuery.current = 1
  loadUsage()
}

function filterUsageBy(userId) {
  usageQuery.userId = String(userId)
  reloadUsage()
}

/* ===== 封禁 / 解封 ===== */
const banDialog = ref(false)
const banReason = ref('')
const banLoading = ref(false)
const banTarget = ref(null)

function openBan(row) {
  banTarget.value = row
  banReason.value = ''
  banDialog.value = true
}

async function confirmBan() {
  banLoading.value = true
  try {
    await banAiUser(banTarget.value.userId, banReason.value || undefined)
    ElMessage.success(`已封禁 ${banTarget.value.userName || '用户 #' + banTarget.value.userId} 的 AI 功能`)
    banDialog.value = false
    loadUsers()
    loadOverview()
  } catch {
    // request.js 已弹提示
  } finally {
    banLoading.value = false
  }
}

async function handleUnban(row) {
  try {
    await ElMessageBox.confirm(
      `确认解除 ${row.userName || '用户 #' + row.userId} 的 AI 功能封禁？解封即时生效。`,
      '解除封禁',
      { type: 'info', confirmButtonText: '确认解封', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await unbanAiUser(row.userId)
    ElMessage.success('已解除封禁')
    loadUsers()
    loadOverview()
  } catch {
    // request.js 已弹提示
  }
}

/* ===== 工具 ===== */
function msToSec(ms) {
  if (ms === null || ms === undefined) return EMPTY_TEXT
  const num = Number(ms)
  if (!Number.isFinite(num)) return String(ms)
  if (num < 0) return '不限'
  if (num < 1000) return `${num}ms`
  return `${(num / 1000).toFixed(1)}s`
}

function sceneText(scene) {
  switch (scene) {
    case 'interview_chat': return '面试对话'
    case 'interview_asr': return '面试语音转文字'
    case 'interview_tts': return '文字合成朗读'
    case 'message_to_text': return '聊天转文字'
    default: return scene || EMPTY_TEXT
  }
}

function digitsOrUndefined(value) {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) ? text : undefined
}

onMounted(() => {
  loadOverview()
  loadUsers()
  loadUsage()
})
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.grow {
  flex: 1;
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
  font-size: 22px;
  font-weight: 600;
}

.kpi-value.out {
  color: #f56c6c;
}

.overview {
  margin-bottom: -8px;
}

.section-title {
  font-size: 15px;
  margin: 20px 0 10px;
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

.uid {
  width: 130px;
}

.scene {
  width: 150px;
}

.result {
  width: 110px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.sub-title {
  align-items: center;
  display: flex;
  font-size: 14px;
  margin: 0 0 8px;
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

.note {
  font-size: 12px;
  line-height: 1.6;
  margin: 0 0 12px;
}

.toolbar {
  align-items: center;
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
