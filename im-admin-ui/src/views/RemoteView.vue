<template>
  <div class="remote">
    <div class="toolbar">
      <h2 class="page-title">远程控制</h2>
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
            <span class="kpi-value num warning">{{ displayValue(overview?.activeSessions) }}</span>
            <span class="muted">进行中会话</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.invitingSessions) }}</span>
            <span class="muted">等待授权的邀请</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.todaySessions) }}</span>
            <span class="muted">今日新建会话</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.onlineAgents) }}</span>
            <span class="muted">
              在线 Agent
              <el-tooltip placement="top" content="只统计当前这个后端实例内存里的连接，多实例部署下不是全站总数">
                <el-icon class="hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="这一屏只有「看」：没有中断会话、没有删除流水，四张数字与两张表全是只读"
      description="没有中断按钮不是因为漏做：被控端随时能自己断，真要阻断一个人的远程控制该走工单处置；尤其别指望「先封号再说」——禁用账号只踢聊天长连接，已经建起来的远程两条通道是握手期鉴权，封号之后画面照传。流水表只增不删，它是这条链路唯一的事后凭据。"
    />

    <el-tabs v-model="tab" class="mb">
      <el-tab-pane label="会话台账" name="sessions">
        <div class="filters">
          <el-input
            v-model="sessionQuery.keyword"
            :maxlength="50"
            clearable
            class="keyword"
            placeholder="设备标识 / 设备名关键词"
            @clear="reloadSessions"
            @keyup.enter="reloadSessions"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select v-model="sessionQuery.status" class="status" clearable placeholder="全部状态" @change="reloadSessions">
            <el-option value="active" label="进行中" />
            <el-option value="inviting" label="等待授权" />
            <el-option value="rejected" label="已拒绝" />
            <el-option value="ended" label="已结束" />
          </el-select>
          <el-input
            v-model="sessionQuery.userId"
            :maxlength="20"
            clearable
            class="uid"
            placeholder="参与方用户 ID"
            @clear="reloadSessions"
            @keyup.enter="reloadSessions"
          />
          <el-button :loading="sessionLoading" @click="reloadSessions">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="sessionLoading" :data="sessions">
            <el-table-column label="被控设备" min-width="200">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.deviceName || EMPTY_TEXT }}</span>
                  <span class="muted code">{{ row.deviceId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="系统" width="130">
              <template #default="{ row }">
                <span class="muted">{{ row.deviceOs || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="控制方" min-width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.inviterName || EMPTY_TEXT }}</span>
                  <span class="muted">#{{ row.inviterUserId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="被控方" min-width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ inviteeText(row) }}</span>
                  <span class="muted">#{{ row.inviteeUserId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="档位" width="90">
              <template #default="{ row }">
                <el-tag :type="permissionTag(row.permission).type" effect="plain" size="small">
                  {{ permissionTag(row.permission).text }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status).type" effect="plain" size="small">
                  {{ statusTag(row.status).text }}
                </el-tag>
                <el-tag v-if="row.relaying" class="ml" type="success" effect="dark" size="small">传图中</el-tag>
                <el-tag v-else-if="row.agentOnline" class="ml" type="warning" effect="plain" size="small">
                  仅 Agent 在线
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时长" width="90">
              <template #default="{ row }">
                <span class="num">{{ formatDuration(row.durationSeconds) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="流量 / 码率" width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="num">{{ formatBytes(row.bytes) }}</span>
                  <span class="muted num">
                    {{ row.bitrateMbps == null ? EMPTY_TEXT : `${row.bitrateMbps} Mbps` }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column align="right" label="流水" width="80">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.auditCount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="发起时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="90">
              <template #default="{ row }">
                <el-button text type="primary" @click="openSession(row.sessionId)">档案</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的会话" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="sessionQuery.current"
              v-model:page-size="sessionQuery.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="sessionTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadSessions"
              @current-change="loadSessions"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="操作流水" name="audits">
        <div class="filters">
          <el-input
            v-model="auditQuery.keyword"
            :maxlength="50"
            clearable
            class="keyword"
            placeholder="详情关键词（路径 / 命令 / 原因）"
            @clear="reloadAudits"
            @keyup.enter="reloadAudits"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-input
            v-model="auditQuery.action"
            :maxlength="30"
            clearable
            class="action"
            placeholder="动作名精确匹配（如 exec）"
            @clear="reloadAudits"
            @keyup.enter="reloadAudits"
          />
          <el-select v-model="auditQuery.actor" class="actor" clearable placeholder="全部触发方" @change="reloadAudits">
            <el-option value="inviter" label="控制端" />
            <el-option value="invitee" label="被控端" />
            <el-option value="system" label="服务端" />
          </el-select>
          <el-checkbox v-model="auditQuery.dangerOnly" class="danger" @change="onDangerChange">只看高危</el-checkbox>
          <el-button :loading="auditLoading" @click="reloadAudits">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-alert
          class="hint"
          :closable="false"
          type="warning"
          title="跨会话检索走不到索引，本页不自动刷新"
          description="流水表只有 (session_id, create_time) 一条索引，不带 sessionId 的全局过滤要付一次 COUNT(*)，单页因此夹在 50 条。想要实时的东西就回到「会话台账」按会话点进去看——那个查询走索引，便宜得多。"
        />

        <el-card shadow="never">
          <el-table v-loading="auditLoading" :data="audits">
            <el-table-column label="时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="动作" width="170">
              <template #default="{ row }">
                <span :class="row.dangerous ? 'bad' : ''">{{ actionText(row.action) }}</span>
                <span class="muted code ml">{{ row.action }}</span>
                <el-tag v-if="row.dangerous" class="ml" type="danger" effect="dark" size="small">高危</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="详情" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="row.dangerous ? 'code bad' : 'code'">{{ row.detail || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="触发方" width="90">
              <template #default="{ row }">
                <span class="muted">{{ actorText(row.actor) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="谁控谁 / 哪台机器" min-width="200">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">
                    {{ row.inviterName || EMPTY_TEXT }} → {{ row.inviteeName || '匿名设备' }}
                  </span>
                  <span class="muted code">{{ row.deviceName || EMPTY_TEXT }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="档位" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.permission" :type="permissionTag(row.permission).type" effect="plain" size="small">
                  {{ permissionTag(row.permission).text }}
                </el-tag>
                <span v-else class="muted">{{ EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="100">
              <template #default="{ row }">
                <el-button text type="primary" :disabled="!row.sessionId" @click="openSession(row.sessionId)">
                  所属会话
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的流水" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="auditQuery.current"
              v-model:page-size="auditQuery.size"
              :page-sizes="[20, 50]"
              :total="auditTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadAudits"
              @current-change="loadAudits"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 单会话档案 + 该会话的流水：台账行与全局流水行都能打开这里 -->
    <el-drawer v-model="drawer" :size="drawerSize" title="会话档案">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <el-tag :type="statusTag(detail.status).type" effect="dark">{{ statusTag(detail.status).text }}</el-tag>
            <span class="muted">#{{ detail.sessionId }} · 发起于 {{ formatDateTime(detail.createTime) }}</span>
          </div>

          <el-descriptions :column="1" border class="block">
            <el-descriptions-item label="被控设备">
              {{ detail.deviceName || EMPTY_TEXT }}
              <span class="muted code ml">{{ detail.deviceId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="系统">{{ detail.deviceOs || EMPTY_TEXT }}</el-descriptions-item>
            <el-descriptions-item label="控制方">
              {{ detail.inviterName || EMPTY_TEXT }} #{{ detail.inviterUserId }}
            </el-descriptions-item>
            <el-descriptions-item label="被控方">
              {{ inviteeText(detail) }} #{{ detail.inviteeUserId }}
            </el-descriptions-item>
            <el-descriptions-item label="授权档位">
              <el-tag :type="permissionTag(detail.permission).type" effect="plain" size="small">
                {{ permissionTag(detail.permission).text }}
              </el-tag>
              <span class="muted ml">只读会话连输入帧都发不出去，同一条 exec 的意味完全不同</span>
            </el-descriptions-item>
            <el-descriptions-item label="起止">
              <span class="num">{{ formatDateTime(detail.startTime) }}</span> →
              <span class="num">{{ formatDateTime(detail.endTime) }}</span>
              <span v-if="detail.endReason" class="muted">（{{ endReasonText(detail.endReason) }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="时长 / 流量 / 码率">
              <span class="num">
                {{ formatDuration(detail.durationSeconds) }} · {{ formatBytes(detail.bytes) }} ·
                {{ detail.bitrateMbps == null ? EMPTY_TEXT : `${detail.bitrateMbps} Mbps` }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="此刻状态">
              <el-tag v-if="detail.relaying" type="success" effect="dark" size="small">控制端已接入，正在传</el-tag>
              <el-tag v-else-if="detail.agentOnline" type="warning" effect="plain" size="small">
                仅 Agent 在线
              </el-tag>
              <el-tag v-else type="info" effect="plain" size="small">两端都不在本实例上</el-tag>
              <span v-if="detail.directPath" class="muted ml">
                媒体路径：{{ detail.directPath === 'udp' ? 'UDP 打洞直连' : '局域网直连' }}
              </span>
            </el-descriptions-item>
          </el-descriptions>

          <p class="muted note">
            这里没有「中断会话」按钮：被控端随时能自己断，控制端断线也无需在后台留一个止损入口；
            而对着一个滥用中的账号点「禁用」也断不掉这条通道（远程两条 WS 是握手期鉴权）。
            真要阻断走举报工单处置，并让被控端把 Agent 退出。
          </p>

          <h4 class="sub-title">
            操作流水
            <el-checkbox v-model="detailAudit.dangerOnly" class="ml" @change="reloadDetailAudit">
              只看高危
            </el-checkbox>
          </h4>
          <el-table v-loading="detailAudit.loading" :data="detailAudit.rows" size="small">
            <el-table-column label="时间" width="110">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="动作" width="140">
              <template #default="{ row }">
                <span :class="row.dangerous ? 'bad' : ''">{{ actionText(row.action) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="详情" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="row.dangerous ? 'code bad' : 'code'">{{ row.detail || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="触发方" width="80">
              <template #default="{ row }">
                <span class="muted">{{ actorText(row.actor) }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="这场会话没有留下水" :image-size="60" />
            </template>
          </el-table>
          <div class="pager">
            <el-pagination
              v-model:current-page="detailAudit.current"
              v-model:page-size="detailAudit.size"
              :page-sizes="[20, 50, 100]"
              :total="detailAudit.total"
              layout="total, sizes, prev, pager, next"
              @size-change="loadDetailAudit"
              @current-change="loadDetailAudit"
            />
          </div>
        </template>
        <el-empty v-else description="会话档案没取到（可能已被清理）" :image-size="80" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import {
  fetchRemoteAuditPage,
  fetchRemoteOverview,
  fetchRemoteSessionDetail,
  fetchRemoteSessionPage
} from '@/api/admin'
import {
  EMPTY_TEXT,
  displayValue,
  formatBytes,
  formatDateTime,
  formatDuration,
  formatListTime,
  formatNumber
} from '@/utils/format'

/**
 * 远程控制审计（四期第二批）。
 *
 * 两个 tab 是同两张已有表的两种切法：台账按会话看、流水按动作看，都不带任何写操作。
 * 全页<b>没有一处自动轮询</b>：概览没有缓存层挡着，轮询就是把 COUNT 直接打到库上；
 * 全局流水检索还要额外付一次 COUNT(*)（那张表只有 idx_session_time）。数字新不新，
 * 由顶部那个「数据截至」和时间戳自己说，想看新的就手点一次。
 *
 * 高危标记直接取服务端给的 {@code dangerous}，前端不复制动作词表：词表的写侧在被控端
 * Agent，前端再抄一份就会出现「Agent 加了破坏动作但后台还是旧的没标红」这种漂移。
 * 下面这份 ACTION_TEXT 只是中文名映射——它是显示层的生词表，抄错了顶多看到一个英文原词，
 * 与「该不该标红」是两件事，所以保留。
 */
const tab = ref('sessions')

const overview = ref(null)
const overviewLoading = ref(false)

const sessions = ref([])
const sessionTotal = ref(0)
const sessionLoading = ref(false)
const sessionQuery = reactive({ keyword: '', status: undefined, userId: '', current: 1, size: 20 })

const audits = ref([])
const auditTotal = ref(0)
const auditLoading = ref(false)
const auditLoaded = ref(false)
const auditQuery = reactive({ keyword: '', action: '', actor: undefined, dangerOnly: false, current: 1, size: 20 })

const drawer = ref(false)
const drawerSize = ref('720px')
const detailLoading = ref(false)
const detail = ref(null)
const detailAudit = reactive({ rows: [], total: 0, loading: false, current: 1, size: 20, dangerOnly: false })

async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = await fetchRemoteOverview()
  } catch {
    // 接口层已弹提示：数字取不到时保留上一次的，别让一屏空白替一条错误说话
  } finally {
    overviewLoading.value = false
  }
}

async function loadSessions() {
  sessionLoading.value = true
  try {
    const uid = String(sessionQuery.userId || '').trim()
    const page = await fetchRemoteSessionPage({
      keyword: sessionQuery.keyword || undefined,
      status: sessionQuery.status,
      // 只把纯数字送进 userId：这个条件是 Long，塞一句昵称进去后端会在参数绑定阶段就报错
      userId: /^\d+$/.test(uid) ? uid : undefined,
      current: sessionQuery.current,
      size: sessionQuery.size
    })
    sessions.value = page.records || []
    sessionTotal.value = Number(page.total) || 0
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果
  } finally {
    sessionLoading.value = false
  }
}

function reloadSessions() {
  sessionQuery.current = 1
  loadSessions()
}

async function loadAudits() {
  auditLoading.value = true
  try {
    const page = await fetchRemoteAuditPage({
      keyword: auditQuery.keyword || undefined,
      action: auditQuery.action || undefined,
      actor: auditQuery.actor,
      dangerOnly: auditQuery.dangerOnly || undefined,
      current: auditQuery.current,
      size: auditQuery.size
    })
    audits.value = page.records || []
    auditTotal.value = Number(page.total) || 0
  } catch {
    // 同上
  } finally {
    auditLoading.value = false
  }
}

function reloadAudits() {
  auditQuery.current = 1
  loadAudits()
}

/** 勾「只看高危」时动作名过滤会被服务端忽略（dangerOnly 优先），把输入框清空免得看着像还能叠加 */
function onDangerChange() {
  if (auditQuery.dangerOnly) {
    auditQuery.action = ''
  }
  reloadAudits()
}

// 流水页签第一次点开才查：一进来就查两个页面，多半有一半的查询是白付的
watch(tab, (name) => {
  if (name === 'audits' && !auditLoaded.value) {
    auditLoaded.value = true
    loadAudits()
  }
})

async function openSession(sessionId) {
  if (!sessionId) {
    return
  }
  drawer.value = true
  detail.value = null
  detailAudit.rows = []
  detailAudit.total = 0
  detailAudit.current = 1
  detailAudit.dangerOnly = false
  detailLoading.value = true
  try {
    detail.value = await fetchRemoteSessionDetail(sessionId)
  } catch {
    // 详情接口回 9005 时抽屉里留空态提示，request.js 已经弹过原因
  } finally {
    detailLoading.value = false
  }
  loadDetailAudit()
}

async function loadDetailAudit() {
  if (!detail.value?.sessionId) {
    return
  }
  detailAudit.loading = true
  try {
    const page = await fetchRemoteAuditPage({
      sessionId: detail.value.sessionId,
      dangerOnly: detailAudit.dangerOnly || undefined,
      current: detailAudit.current,
      size: detailAudit.size
    })
    detailAudit.rows = page.records || []
    detailAudit.total = Number(page.total) || 0
  } catch {
    // 档案已经拿到，流水取不到不影响上面那一段
  } finally {
    detailAudit.loading = false
  }
}

function reloadDetailAudit() {
  detailAudit.current = 1
  loadDetailAudit()
}

/* ---------- 展示层映射 ---------- */

/** 识别码接入的会话 inviteeUserId=0：那不是账号，不能被显示成「已注销用户」 */
function inviteeText(row) {
  if (row.inviteeName) {
    return row.inviteeName
  }
  return Number(row.inviteeUserId) > 0 ? EMPTY_TEXT : '匿名设备'
}

function statusTag(status) {
  switch (status) {
    case 'active':
      return { type: 'success', text: '进行中' }
    case 'inviting':
      return { type: 'warning', text: '等待授权' }
    case 'rejected':
      return { type: 'info', text: '已拒绝' }
    case 'ended':
      return { type: 'info', text: '已结束' }
    default:
      return { type: 'info', text: status || EMPTY_TEXT }
  }
}

function permissionTag(permission) {
  return permission === 'operate'
    ? { type: 'danger', text: '可操作' }
    : { type: 'info', text: permission === 'readonly' ? '只读' : permission || EMPTY_TEXT }
}

// 结束原因：谁按的结束、还是系统收尾的（码表与用户端 Remote.vue 同源）
function endReasonText(reason) {
  const map = {
    'inviter-end': '控制方主动结束',
    'invitee-end': '被控方主动结束',
    rejected: '对方拒绝授权',
    superseded: '被新邀请顶替',
    'agent-offline': '被控端掉线',
    'control-offline': '控制端掉线',
    'idle-timeout': '长时间无操作自动结束',
    'invite-timeout': '授权超时未响应'
  }
  return map[reason] || reason || ''
}

function actorText(actor) {
  const map = { inviter: '控制端', invitee: '被控端', system: '服务端' }
  // 加 actor 列之前的历史流水这一格是 null，宁可显示「—」也不猜一个值
  if (!actor) {
    return EMPTY_TEXT
  }
  return map[actor] || actor
}

/**
 * 动作中文名。
 *
 * <p>词表逐个核过写侧：服务端自己落的（invite / accept / reject / control-bound / session-end /
 * direct-stats / input-blocked）、被控端 Agent 上报的（exec / ps-run / ps-kill / power /
 * file-* / clip-sync / danger-denied / agent-ready / direct-rejected）、直连状态事件（direct-candidates /
 * direct-up / direct-failed）。未收录的动作原样显示英文（表格里同时带着原词）：
 * 宁可直接把生词摆出来，也不要映射成「未知动作」把线索抹掉。
 */
function actionText(action) {
  const map = {
    invite: '发起邀请',
    accept: '同意授权',
    reject: '拒绝授权',
    'agent-ready': 'Agent 就绪',
    'control-bound': '控制端接入',
    'session-end': '会话结束',
    'direct-candidates': '直连地址交换',
    'direct-up': '直连已建立',
    'direct-failed': '直连失败',
    'direct-rejected': '直连被拒',
    'direct-stats': '直连流量统计',
    'clip-sync': '剪贴板同步',
    'file-mkdir': '新建目录',
    'file-rename': '重命名文件',
    'file-rm': '删除文件',
    'file-get': '下载文件',
    'file-put': '上传文件',
    'ps-run': '启动程序',
    'ps-kill': '结束进程',
    exec: '执行命令',
    power: '电源操作',
    'input-blocked': '输入被拦截',
    'danger-denied': '高危操作被拒',
    // 服务端取不到 action 字段时落的就是这个串，它是一个信号而不是一个动作
    unknown: '上报缺 action 字段'
  }
  return map[action] || action || EMPTY_TEXT
}

onMounted(() => {
  loadOverview()
  loadSessions()
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

.overview {
  margin-bottom: -8px;
}

.stack {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.filters {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.keyword {
  width: 240px;
}

.status {
  width: 130px;
}

.uid {
  width: 150px;
}

.action {
  width: 200px;
}

.actor {
  width: 130px;
}

.danger {
  align-self: center;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.bad {
  color: #f56c6c;
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

.hint-icon {
  color: #909399;
  margin-left: 2px;
}
</style>
