<template>
  <div class="monitor">
    <div class="toolbar">
      <h2 class="page-title">监控告警</h2>
      <span class="muted">数据截至 {{ formatFromNow(data?.collectedAt) }}</span>
      <span class="grow" />
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="这一屏与 Prometheus 抓的是同一本账"
      description="后台页面读的是进程内的 MeterRegistry，与 /actuator/prometheus（management 端口，默认只绑 127.0.0.1 供 Prometheus 抓取）出自同一个注册表，不存在「页面说健康、Prometheus 说宕了」的双账本。取不到的指标显示「—」而不是 0——把「没注册」画成 0 等于制造一个永远不会告警的假健康值。"
    />

    <div v-loading="loading">
      <!-- 健康检查：聚合状态一个大字 + 逐组件小标签，缺席的组件不硬凑 DOWN -->
      <el-card shadow="never" class="block">
        <template #header>
          <div class="card-head">
            <span>健康检查（actuator）</span>
            <el-tag v-if="health" :type="health.status === 'UP' ? 'success' : 'danger'" effect="dark" size="small">
              {{ health.status || EMPTY_TEXT }}
            </el-tag>
          </div>
        </template>
        <div class="health-row">
          <el-tag v-for="(value, key) in health?.components || {}" :key="key" effect="plain" size="small" :type="value === 'UP' ? 'success' : 'danger'">
            {{ key }}: {{ value }}
          </el-tag>
          <span v-if="!health || !Object.keys(health.components || {}).length" class="muted">
            没有可读的组件状态（health 端点未装配或全部被禁用）
          </span>
        </div>
        <p class="muted up">进程已运行 {{ formatUptime(data?.uptimeSeconds) }}</p>
      </el-card>

      <div class="grid">
        <el-card shadow="never" class="block">
          <template #header>JVM</template>
          <div class="metric-line"><span class="label">堆内存</span><span class="num">{{ metric(data?.jvm?.heapUsedMb) }} / {{ metric(data?.jvm?.heapMaxMb) }} MB</span></div>
          <el-progress
            v-if="data?.jvm?.heapPct != null"
            :percentage="Math.min(Math.round(data.jvm.heapPct), 100)"
            :status="heapStatus"
            :stroke-width="10"
            :show-text="false"
            class="heap-bar"
          />
          <div class="metric-line"><span class="label">堆占用</span><span class="num">{{ data?.jvm?.heapPct != null ? `${data.jvm.heapPct.toFixed(1)}%` : EMPTY_TEXT }}</span></div>
          <div class="metric-line"><span class="label">非堆</span><span class="num">{{ metric(data?.jvm?.nonHeapUsedMb) }} MB</span></div>
          <div class="metric-line"><span class="label">线程</span><span class="num">{{ metric(data?.jvm?.threadCount) }}</span></div>
          <div class="metric-line"><span class="label">CPU 核数 / 进程占用</span><span class="num">{{ metric(data?.jvm?.cpuCores) }} / {{ pctText(data?.jvm?.processCpuPct) }}</span></div>
          <div class="metric-line"><span class="label">系统 1 分钟负载</span><span class="num">{{ loadText(data?.jvm?.systemLoadAverage) }}</span></div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>HTTP 请求</template>
          <div class="metric-line"><span class="label">累计请求</span><span class="num">{{ metric(data?.http?.requestCount) }}</span></div>
          <div class="metric-line"><span class="label">其中 5xx</span><span class="num">{{ metric(data?.http?.serverErrorCount) }}</span></div>
          <div class="metric-line"><span class="label">平均耗时</span><span class="num">{{ msText(data?.http?.avgMs) }}</span></div>
          <div class="metric-line"><span class="label">P50</span><span class="num">{{ msText(data?.http?.p50Ms) }}</span></div>
          <div class="metric-line"><span class="label">P95</span><span class="num">{{ msText(data?.http?.p95Ms) }}</span></div>
          <div class="metric-line"><span class="label">P99</span><span class="num">{{ msText(data?.http?.p99Ms) }}</span></div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>连接池（HikariCP）</template>
          <div class="metric-line"><span class="label">活跃</span><span class="num">{{ metric(data?.pool?.active) }}</span></div>
          <div class="metric-line"><span class="label">空闲</span><span class="num">{{ metric(data?.pool?.idle) }}</span></div>
          <div class="metric-line"><span class="label">上限</span><span class="num">{{ metric(data?.pool?.max) }}</span></div>
          <div class="metric-line">
            <span class="label">排队等待</span>
            <!-- pending 持续 >0 说明池小了或有慢 SQL 占着连接，标红提醒 -->
            <span class="num" :class="{ bad: (data?.pool?.pending ?? 0) > 0 }">{{ metric(data?.pool?.pending) }}</span>
          </div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>慢接口</template>
          <div class="metric-line"><span class="label">阈值</span><span class="num">{{ msText(data?.slow?.thresholdMs) }}</span></div>
          <div class="metric-line"><span class="label">累计触发</span><span class="num">{{ metric(Math.round(data?.slow?.slowTotal ?? 0)) }}</span></div>
          <div class="metric-line"><span class="label">明细缓冲</span><span class="num">{{ (data?.slow?.recent || []).length }} 条</span></div>
          <p class="muted tip">同一条记录还走 WARN 日志（SLOW-API 档）与 Prometheus counter（im.slow.request.total），告警规则认后者。</p>
        </el-card>
      </div>

      <el-card shadow="never" class="block">
        <template #header>最近慢接口（时间倒序，进程内存环形缓冲，重启清零）</template>
        <el-table :data="data?.slow?.recent || []" max-height="320">
          <el-table-column label="时间" width="140">
            <template #default="{ row }">
              <span class="num">{{ formatListTime(row.time) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="方法" width="80" prop="method" />
          <el-table-column label="路径" min-width="240" prop="path" show-overflow-tooltip />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="num">{{ row.status ?? EMPTY_TEXT }}</span>
            </template>
          </el-table-column>
          <el-table-column align="right" label="耗时" width="100">
            <template #default="{ row }">
              <span class="num slow">{{ row.durationMs }} ms</span>
            </template>
          </el-table-column>
          <el-table-column label="请求者" width="110">
            <template #default="{ row }">
              <span class="muted">{{ row.userId != null ? `#${row.userId}` : '匿名' }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有慢接口记录——这是好消息" :image-size="60" />
          </template>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchMonitorOverview } from '@/api/admin'
import { EMPTY_TEXT, formatFromNow, formatListTime, formatNumber, formatUptime } from '@/utils/format'

/**
 * 监控告警页（三期）。
 *
 * 手动刷新而不是自动轮询：先把「看得懂的一屏」做对，再谈「不用点刷新」。
 * 取数是内存读为主（MeterRegistry 现值 + 健康聚合里一个 SELECT 1），代价可控，
 * 但 60 秒轮询在一个只有管理员偶尔打开的页面上仍然是白烧。
 *
 * 所有指标格走同一个空值口径：null/undefined 显示「—」（metric/pctText/msText 都兜这件事），
 * 绝不把「取不到」渲染成 0。
 */
const data = ref(null)
const loading = ref(false)

const health = computed(() => data.value?.health)

const heapStatus = computed(() => {
  const pct = data.value?.jvm?.heapPct
  if (pct == null) {
    return ''
  }
  if (pct >= 90) {
    return 'exception'
  }
  return pct >= 75 ? 'warning' : 'success'
})

/** 空值占位与千分位一步到位：数字类指标全走它 */
function metric(value) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY_TEXT
  }
  return formatNumber(value)
}

function msText(value) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY_TEXT
  }
  return `${Math.round(value)} ms`
}

/** processCpuPct 后端给的是 0~1 的小数，乘 100 显示；采不到为 null */
function pctText(value) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY_TEXT
  }
  return `${(value * 100).toFixed(1)}%`
}

/** systemLoadAverage 的 -1 是「该 JDK 不支持采集」的意思，不是负载真为负，按取不到处理 */
function loadText(value) {
  if (value === null || value === undefined || Number.isNaN(value) || value < 0) {
    return EMPTY_TEXT
  }
  return value.toFixed(2)
}

async function load() {
  loading.value = true
  try {
    data.value = await fetchMonitorOverview()
  } catch {
    // 提示已由 request.js 弹过；保留上一次快照，刷新失败不等于服务宕了
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.hint {
  margin-bottom: 16px;
}

.grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  margin-bottom: 16px;
}

.block {
  margin-bottom: 0;
}

.grid .block {
  margin-bottom: 0;
}

.card-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.health-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.up {
  margin: 12px 0 0;
}

.metric-line {
  display: flex;
  justify-content: space-between;
  line-height: 2;
}

.metric-line .label {
  color: #909399;
}

.heap-bar {
  margin: 4px 0 8px;
}

.bad {
  color: #f56c6c;
}

.slow {
  color: #e6a23c;
}

.tip {
  font-size: 12px;
  line-height: 1.6;
  margin: 8px 0 0;
}
</style>
