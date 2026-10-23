<template>
  <div class="lives">
    <div class="toolbar">
      <h2 class="page-title">直播管理</h2>
      <span class="grow" />
      <el-input
        v-model="query.keyword"
        :maxlength="30"
        clearable
        class="keyword"
        placeholder="直播标题关键词"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option :value="1" label="直播中" />
        <el-option :value="2" label="已结束" />
        <el-option :value="3" label="已封禁" />
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
      title="只有「看」与「掐」两个动作：没有删房间、没有解封、更没有开播"
      description="场次表是处置台账，删一行等于抹掉一次直播存在过的证据；强制关播以 ban 原因中断，封禁是终态、无解封接口——streamKey 已作废、分片已过期，恢复按钮是个说谎的按钮。开播永远是主播侧动作。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="房间" min-width="220">
          <template #default="{ row }">
            <div class="room-cell">
              <img v-if="row.cover" class="cover" :src="row.cover" alt="" />
              <div v-else class="cover cover-empty"><el-icon><VideoCamera /></el-icon></div>
              <div class="room-text">
                <span class="ellipsis">{{ row.title || '（无标题）' }}</span>
                <span class="muted">#{{ row.roomId }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="主播" min-width="150">
          <template #default="{ row }">
            <div class="room-text">
              <span class="ellipsis">{{ row.anchorName || '已注销用户' }}</span>
              <span class="muted">#{{ row.anchorId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="liveStatusTag(row.status).type" effect="plain" size="small">
              {{ liveStatusTag(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在线 / 峰值" width="110">
          <template #default="{ row }">
            <span class="num">{{ row.status === 1 ? formatNumber(row.onlineNow) : EMPTY_TEXT }} / {{ formatNumber(row.peakOnline) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="累计观看" width="100">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.viewerTotal) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="开播时间" width="120">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.startTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结束" width="140">
          <template #default="{ row }">
            <div class="room-text">
              <span class="num">{{ formatListTime(row.endTime) }}</span>
              <span v-if="row.endReason" class="muted">{{ endReasonText(row.endReason) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="150">
          <template #default="{ row }">
            <el-button text type="primary" @click="openDetail(row)">详情</el-button>
            <!-- 只对直播中的场次给掐断按钮：已结束的点了也只是幂等空转，不如不放 -->
            <el-button v-if="row.status === 1" text type="danger" @click="confirmStop(row)">关播</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的场次" :image-size="80" />
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

    <!-- 单场档案：详情接口回的是同一份 AdminLiveDTO，绝不含 streamKey（后端红线，这里也不留字段位） -->
    <el-dialog v-model="detail.visible" :title="`场次档案：#${detail.room?.roomId ?? ''}`" width="520px">
      <el-descriptions v-if="detail.room" :column="1" border v-loading="detail.loading">
        <el-descriptions-item label="标题">{{ detail.room.title || '（无标题）' }}</el-descriptions-item>
        <el-descriptions-item label="主播">
          {{ detail.room.anchorName || '已注销用户' }}（#{{ detail.room.anchorId }}）
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="liveStatusTag(detail.room.status).type" effect="plain" size="small">
            {{ liveStatusTag(detail.room.status).text }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源 / 分辨率">
          {{ sourceTypeText(detail.room.sourceType) }} / {{ detail.room.resolution || EMPTY_TEXT }}
        </el-descriptions-item>
        <el-descriptions-item label="公告">
          <span class="notice">{{ detail.room.notice || '（未填写）' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="在线 / 峰值 / 累计">
          {{ formatNumber(detail.room.onlineNow) }} / {{ formatNumber(detail.room.peakOnline) }} / {{ formatNumber(detail.room.viewerTotal) }}
        </el-descriptions-item>
        <el-descriptions-item label="开播 → 关播">
          {{ formatDateTime(detail.room.startTime) }} → {{ formatDateTime(detail.room.endTime) }}
          <span v-if="detail.room.endReason" class="muted">（{{ endReasonText(detail.room.endReason) }}）</span>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detail.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchLiveDetail, fetchLivePage, stopLive } from '@/api/admin'
import { EMPTY_TEXT, formatDateTime, formatListTime, formatNumber } from '@/utils/format'

/**
 * 直播管理列表（三期）。
 *
 * 与用户端大厅的口径差异全在后端：后台能看到全部历史与已封禁场次（不受「结束超
 * 1 小时隐藏」规则约束），直播中的恒排最前，其余按开播时间倒序。
 *
 * 「在线」列只在直播中显示实时值——已结束场次的 onlineNow 是关播时定格的 peakOnline
 * 口径，把它当实时在线显示会谎报，所以非直播中一律画「—」。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ keyword: '', status: undefined, current: 1, size: 20 })

async function load() {
  loading.value = true
  try {
    const page = await fetchLivePage({
      keyword: query.keyword || undefined,
      status: query.status,
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

function liveStatusTag(status) {
  switch (status) {
    case 1:
      return { type: 'danger', text: '直播中' }
    case 2:
      return { type: 'info', text: '已结束' }
    case 3:
      return { type: 'warning', text: '已封禁' }
    default:
      return { type: 'info', text: EMPTY_TEXT }
  }
}

/** endReason 码表在服务端：stop 主播自关 / timeout 超时回收 / ban 处置掐断 */
function endReasonText(reason) {
  switch (reason) {
    case 'stop':
      return '主播结束'
    case 'timeout':
      return '超时回收'
    case 'ban':
      return '强制关播'
    default:
      return reason || ''
  }
}

function sourceTypeText(sourceType) {
  switch (sourceType) {
    case 'screen':
      return '屏幕分享'
    case 'camera':
      return '摄像头'
    case 'chat':
      return '聊天室'
    default:
      return sourceType || EMPTY_TEXT
  }
}

const detail = reactive({ visible: false, loading: false, room: null })

async function openDetail(row) {
  detail.room = row
  detail.visible = true
  detail.loading = true
  try {
    const fresh = await fetchLiveDetail(row.roomId)
    if (fresh) {
      detail.room = fresh
    }
  } catch {
    // 详情拉失败就展示列表里这一行，至少标题与状态是真的
  } finally {
    detail.loading = false
  }
}

async function confirmStop(row) {
  try {
    await ElMessageBox.confirm(
      `强制关播「${row.title || `#${row.roomId}`}」：中断弹幕、清掉心跳与门禁、删除回放入口。` +
        `封禁是终态，主播要再播必须重新开播；此操作不可撤销。`,
      '强制关播',
      { confirmButtonText: '确认关播', cancelButtonText: '取消', type: 'error' }
    )
  } catch {
    return
  }
  try {
    const updated = await stopLive(row.roomId)
    // 后端带回处置后的最新档案，就地刷那一行；若响应仍是直播中说明指令没生效，提示刷新
    if (updated) {
      Object.assign(row, updated)
      if (updated.status === 1) {
        ElMessage.warning('关播指令未生效，请刷新后重试')
      } else {
        ElMessage.success('直播已中断')
      }
    } else {
      load()
    }
  } catch {
    // 接口层已弹提示
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.keyword {
  width: 220px;
}

.status {
  width: 130px;
}

.hint {
  margin-bottom: 16px;
}

.room-cell {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.cover {
  background: #f5f7fa;
  border-radius: 4px;
  flex: none;
  height: 40px;
  object-fit: cover;
  width: 64px;
}

.cover-empty {
  align-items: center;
  color: #c0c4cc;
  display: flex;
  justify-content: center;
}

.room-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.notice {
  white-space: pre-wrap;
}
</style>
