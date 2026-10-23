<template>
  <div class="dashboard">
    <div class="toolbar">
      <h2 class="page-title">数据看板</h2>
      <span class="grow" />
      <span v-if="data" class="muted">
        数据截至 {{ formatDateTime(data.cachedAt) }}（{{ staleText }}）
      </span>
      <el-button :loading="loading" @click="load(true)">
        <el-icon><Refresh /></el-icon>
        <span>强制刷新</span>
      </el-button>
    </div>

    <el-skeleton v-if="loading && !data" :rows="6" animated />

    <el-alert
      v-else-if="!data"
      :closable="false"
      title="看板没有数据"
      type="info"
      description="接口没有返回内容，通常是后端未启动或未登录。"
    />

    <template v-else>
      <el-row :gutter="16">
        <el-col :lg="6" :md="12" :sm="12" :xs="24">
          <el-card class="mb" shadow="never">
            <div class="kpi">
              <span class="kpi-value num warning">{{ displayValue(data.pendingReports) }}</span>
              <span class="muted">未处置工单（待处理 + 已受理）</span>
            </div>
            <el-button class="kpi-link" text type="primary" @click="goPendingReports">
              去处理队列
            </el-button>
          </el-card>
        </el-col>
        <el-col :lg="6" :md="12" :sm="12" :xs="24">
          <el-card class="mb" shadow="never">
            <div class="kpi">
              <span class="kpi-value num">{{ displayValue(data.todayReports) }}</span>
              <span class="muted">今日新增举报</span>
            </div>
          </el-card>
        </el-col>
        <el-col v-for="section in data.sections" :key="section.source" :lg="6" :md="12" :sm="12" :xs="24">
          <el-card class="mb" shadow="never">
            <template #header>
              <span class="section-title">{{ section.title }}</span>
            </template>
            <div v-for="metric in section.metrics" :key="metric.key" class="metric">
              <span class="metric-label">
                {{ metric.label }}
                <el-tooltip v-if="metric.hint" :content="metric.hint" placement="top">
                  <el-icon class="hint-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
              <span class="metric-value num">{{ displayValue(metric.value) }}</span>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :lg="12" :md="24" :sm="24" :xs="24">
          <el-card class="mb" shadow="never">
            <template #header>
              <span class="section-title">近 7 天举报趋势</span>
            </template>
            <div v-if="!data.trend?.length" class="muted">暂无数据</div>
            <div v-else class="trend">
              <div v-for="point in data.trend" :key="point.day" class="trend-col">
                <span class="trend-count num">{{ point.count }}</span>
                <div class="trend-bar-wrap">
                  <div class="trend-bar" :style="{ height: barHeight(point.count) }" />
                </div>
                <span class="muted trend-day">{{ formatDay(point.day) }}</span>
              </div>
            </div>
          </el-card>
        </el-col>

        <el-col :lg="12" :md="24" :sm="24" :xs="24">
          <el-card class="mb" shadow="never">
            <template #header>
              <span class="section-title">近 7 天举报理由分布</span>
            </template>
            <div v-if="!data.reasons?.length" class="muted">窗口内没有举报</div>
            <div v-else>
              <div v-for="slice in data.reasons" :key="slice.code" class="reason">
                <span class="reason-name">{{ slice.name }}</span>
                <el-progress
                  :percentage="reasonPercentage(slice.count)"
                  :show-text="false"
                  :stroke-width="10"
                  class="reason-bar"
                />
                <span class="reason-count num">{{ slice.count }}</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-card class="mb" shadow="never">
        <template #header>
          <span class="section-title">近 7 天被举报次数榜</span>
          <span class="muted header-note">点一行到工单队列里按该用户 ID 筛出他的全部工单</span>
        </template>
        <el-empty v-if="!data.topReportedUsers?.length" description="窗口内没有人被举报" :image-size="80" />
        <el-table v-else :data="data.topReportedUsers" @row-click="goReportsOf">
          <el-table-column label="用户" min-width="220">
            <template #default="{ row }">
              <div class="user-cell">
                <AvatarCell :url="row.avatar" :name="row.nickname" :size="30" />
                <span class="ellipsis">{{ row.nickname }}</span>
                <span class="muted">#{{ row.userId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="账号状态" width="120">
            <template #default="{ row }">
              <el-tag :type="accountStatusTag(row.status).type" size="small" effect="plain">
                {{ accountStatusTag(row.status).text }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column align="right" label="被举报次数" width="140">
            <template #default="{ row }">
              <span class="num">{{ row.count }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onActivated, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchDashboard } from '@/api/admin'
import AvatarCell from '@/components/AvatarCell.vue'
import {
  accountStatusTag,
  displayValue,
  formatDateTime,
  formatDay,
  formatFromNow
} from '@/utils/format'

/**
 * 数据看板。
 *
 * 三块内容的来源不同，页面上就分开摆：<b>平台数字来自各业务模块的 SPI</b>（分区是模块报上来的，
 * 模块没装配就少一个卡片，而不是补一个 0），<b>举报统计来自 im-admin 自己的表</b>。
 *
 * 刷新策略要说清：服务端有 60 秒聚合缓存（im.admin.dashboard-cache-seconds），
 * 所以这里的定时轮询走<b>缓存路径</b>（refresh=false），只有人手点「强制刷新」才真跑一遍
 * COUNT。把轮询也设成 refresh=true 等于每分钟对着几张业务表来一轮全量统计，
 * 而看板上的数字晚一分钟出现没有任何后果。
 *
 * 刻意<b>没有</b>做图表库：趋势就 7 根柱子、分布就几条进度带，用 div 画完，
 * 引一个 echarts 会让首包多出几百 KB，还要维护它的主题与响应式断点。
 */
const router = useRouter()
const data = ref(null)
const loading = ref(false)
let timer = null

const staleText = computed(() => {
  if (!data.value) {
    return ''
  }
  return data.value.staleSeconds > 0
    ? `缓存 ${data.value.staleSeconds} 秒 · ${formatFromNow(data.value.cachedAt)}`
    : '未缓存'
})

const trendMax = computed(() => {
  const counts = (data.value?.trend || []).map((p) => Number(p.count) || 0)
  return Math.max(...counts, 1)
})

const reasonMax = computed(() => {
  const counts = (data.value?.reasons || []).map((p) => Number(p.count) || 0)
  return Math.max(...counts, 1)
})

function barHeight(count) {
  const ratio = (Number(count) || 0) / trendMax.value
  // 保底 2px：某天只有 1 条时按比值算会细到看不见，而「有」这件事本身就是要看板回答的问题
  return `${Math.max(ratio * 100, 2)}%`
}

function reasonPercentage(count) {
  return Math.round(((Number(count) || 0) / reasonMax.value) * 100)
}

async function load(refresh = false) {
  loading.value = true
  try {
    data.value = await fetchDashboard(refresh)
  } catch {
    // 提示已由 request.js 统一弹过；这里保留上一屏数据，清成空白会让人以为平台没量了
  } finally {
    loading.value = false
  }
}

function goPendingReports() {
  router.push({ name: 'reports', query: { status: 0 } })
}

function goReportsOf(row) {
  // 工单队列的关键词是纯数字时按「对象 ID / 被举报人 ID / 举报人 ID」精确匹配，
  // 这里传用户 ID 正好落在被举报人这一支；用户列表的关键词不认 ID，所以不能跳那边
  router.push({ name: 'reports', query: { keyword: String(row.userId) } })
}

onMounted(() => {
  load()
  // 60s 与后端缓存时长对齐：更短的轮询只会反复命中同一份缓存，白打接口
  timer = setInterval(() => load(false), 60000)
})

onBeforeUnmount(() => {
  clearInterval(timer)
})

// 侧边菜单切换不会卸载外框，页面自身也不会被 keep-alive 缓存（一期没上 keep-alive），
// 这个钩子留着：将来给内页加了缓存，回到看板时该补一次静默刷新
onActivated(() => {
  if (data.value) {
    load(false)
  }
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

.kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kpi-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}

.kpi-value.warning {
  color: #e6a23c;
}

.kpi-link {
  margin-top: 8px;
  padding: 0;
}

.section-title {
  font-weight: 600;
}

.header-note {
  margin-left: 8px;
}

.metric {
  align-items: baseline;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  padding: 4px 0;
}

.metric-label {
  color: #606266;
  display: flex;
  gap: 4px;
}

.hint-icon {
  color: #c0c4cc;
  cursor: help;
  vertical-align: -2px;
}

.metric-value {
  font-size: 16px;
  font-weight: 600;
}

.trend {
  display: flex;
  gap: 8px;
  height: 160px;
}

.trend-col {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.trend-count,
.trend-day {
  font-size: 12px;
  text-align: center;
}

.trend-bar-wrap {
  align-items: flex-end;
  background: #f5f7fa;
  border-radius: 4px;
  display: flex;
  flex: 1;
}

.trend-bar {
  background: #409eff;
  border-radius: 4px 4px 0 0;
  transition: height 0.3s;
  width: 100%;
}

.reason {
  align-items: center;
  display: flex;
  gap: 12px;
  padding: 6px 0;
}

.reason-name {
  flex: 0 0 88px;
}

.reason-bar {
  flex: 1;
}

.reason-count {
  flex: 0 0 40px;
  text-align: right;
}

.user-cell {
  align-items: center;
  display: flex;
  gap: 8px;
  min-width: 0;
}

:deep(.el-table__row) {
  cursor: pointer;
}
</style>
