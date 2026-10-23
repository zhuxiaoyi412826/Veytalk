<template>
  <el-dialog
    v-model="visible"
    title="主播数据"
    width="880px"
    top="6vh"
    :close-on-click-modal="true"
    append-to-body
    @open="onOpen"
  >
    <!-- 窗口切换：换天数就是换一次服务端聚合范围，本地不做二次过滤（本地没有全量数据） -->
    <div class="stats__bar">
      <el-radio-group v-model="days" size="small" @change="load">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
        <el-radio-button :value="366">近一年</el-radio-button>
      </el-radio-group>
      <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-loading="loading" class="stats__body im-scroll">
      <el-empty
        v-if="!loading && isEmpty"
        description="这个窗口里还没有直播记录，播一场就有数据了"
        :image-size="80"
      />

      <template v-else-if="stats">
        <!-- 概览只覆盖最近 N 场，这一行必须显出来，否则主播会把局部数当全量 -->
        <div v-if="stats.sessionsCapped" class="stats__cap">
          窗口内场次多于 {{ stats.sessionLimit }} 场，概览与明细只统计了最近 {{ stats.sessionLimit }} 场
        </div>

        <!-- 正在播的那场：只有这里有点赞数（热度键只活 24 小时，历史场次取不到） -->
        <div v-if="stats.current" class="stats__live">
          <span class="stats__live-tag">直播中</span>
          <span class="stats__live-item">在线 <b>{{ stats.current.onlineCount || 0 }}</b></span>
          <span class="stats__live-item">峰值 <b>{{ stats.current.peakOnline || 0 }}</b></span>
          <span class="stats__live-item">人次 <b>{{ stats.current.viewerTotal || 0 }}</b></span>
          <span class="stats__live-item">礼物 <b>{{ stats.current.giftPrice || 0 }}</b></span>
          <span class="stats__live-item">点赞 <b>{{ stats.current.likeHeat || 0 }}</b></span>
          <span class="stats__live-item">已播 <b>{{ fmtLen(stats.current.durationSeconds) }}</b></span>
        </div>

        <div class="stats__grid">
          <div v-for="m in metrics" :key="m.label" class="stats__cell">
            <div class="stats__num">{{ m.value }}</div>
            <div class="stats__label">{{ m.label }}</div>
          </div>
        </div>

        <div class="stats__title">逐场明细</div>
        <el-table :data="stats.sessions" size="small" class="stats__table" max-height="260">
          <el-table-column label="场次" min-width="150">
            <template #default="{ row }">
              <div class="im-ellipsis" :title="row.title || '未命名场次'">{{ row.title || '未命名场次' }}</div>
              <div class="stats__sub">
                {{ formatDateTime(row.startTime) }}
                <span v-if="row.endReason" class="stats__reason">（{{ reasonText(row.endReason) }}）</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时长" width="80" align="right">
            <template #default="{ row }">{{ fmtLen(row.durationSeconds) }}</template>
          </el-table-column>
          <el-table-column label="峰值" width="70" align="right">
            <template #default="{ row }">{{ row.peakOnline || 0 }}</template>
          </el-table-column>
          <el-table-column label="人次" width="70" align="right">
            <template #default="{ row }">{{ row.viewerTotal || 0 }}</template>
          </el-table-column>
          <el-table-column label="礼物" min-width="110" align="right">
            <template #default="{ row }">
              <span v-if="row.giftPrice">{{ row.giftPrice }} 分</span>
              <span v-else class="stats__muted">—</span>
              <div v-if="row.giftCount" class="stats__sub">{{ row.giftCount }} 笔 / {{ row.giftSenderCount }} 人</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.living" size="small" type="success" effect="dark">直播中</el-tag>
              <el-tag v-else-if="row.status === 3" size="small" type="danger">已封禁</el-tag>
              <el-button v-else-if="row.hasReplay" size="small" link type="primary" @click="emit('open-replay', row.roomId)">
                ▶ 看回放
              </el-button>
              <span v-else class="stats__muted">已结束</span>
            </template>
          </el-table-column>
          <el-table-column label="" width="70" align="center">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="emit('open-room', row.roomId)">进房</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="stats__ranks">
          <div class="stats__rank">
            <div class="stats__title">金主榜</div>
            <div v-if="!stats.topGifters.length" class="stats__muted">还没有人送礼</div>
            <div v-for="(g, idx) in stats.topGifters" :key="g.senderId" class="stats__row">
              <span class="stats__no">{{ idx + 1 }}</span>
              <UserAvatar :src="g.senderAvatar" :name="g.senderName" :size="22" />
              <span class="stats__name im-ellipsis" :title="g.senderName">{{ g.senderName }}</span>
              <span class="stats__val">{{ g.totalPrice }} 分</span>
              <span class="stats__sub">{{ g.recordCount }} 笔</span>
            </div>
          </div>
          <div class="stats__rank">
            <div class="stats__title">收到最多的礼物</div>
            <div v-if="!stats.topGiftTypes.length" class="stats__muted">还没有人送礼</div>
            <div v-for="t in stats.topGiftTypes" :key="t.giftId" class="stats__row">
              <span class="stats__icon">{{ t.giftIcon || '🎁' }}</span>
              <span class="stats__name im-ellipsis" :title="t.giftName">{{ t.giftName }}</span>
              <span class="stats__val">×{{ t.totalCount }}</span>
              <span class="stats__sub">{{ t.totalPrice }} 分</span>
            </div>
          </div>
        </div>

        <!-- 口径说明：面板上的数很容易被当成「收入」和「全量」，这几行不是客套话 -->
        <div class="stats__note">
          <div>· 礼物价值是<b>虚拟积分</b>，且不会进你的钱包（送礼只扣送礼人），也不能提现。</div>
          <div>· 点赞数只存 24 小时，所以只有「直播中」那一行有点赞，历史场次取不到；弹幕条数服务端没有存储，这里不提供。</div>
          <div>· 直播中的峰值与人次来自本实例内存，后端重启会归零；关播时才定格落库，之后不会再变。</div>
          <div>· 不含 PK 战绩：PK 会话与票数只在 Redis 活一天，且没有按主播的索引，要历史得另建表。</div>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { fetchAnchorStats } from '@/api/live'
import UserAvatar from './UserAvatar.vue'
import { formatDateTime } from '@/utils/format'

/**
 * 主播数据面板。
 *
 * 一次 GET /api/live/anchor/stats 换回概览 + 逐场 + 两个榜单 + 本场实时，
 * 服务端是纯读聚合（im_live_room + im_gift_record + 现有 Redis 计数），零 DDL。
 *
 * 窗口切换交给服务端：本地只有这一页数据，前端过滤只会把「近 90 天」显示成「近 30 天里的近 7 天」，
 * 那种数看着精确其实口径是错的。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'open-room', 'open-replay'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const stats = ref(null)
const loading = ref(false)
const days = ref(30)

const isEmpty = computed(() => !stats.value || !stats.value.sessions || !stats.value.sessions.length)

const metrics = computed(() => {
  const o = stats.value && stats.value.overview
  if (!o) {
    return []
  }
  return [
    { label: '场次', value: o.totalSessions || 0 },
    { label: '累计时长', value: fmtLen(o.totalDurationSeconds) },
    { label: '观看人次', value: o.totalViewer || 0 },
    { label: '平均峰值', value: o.avgPeakOnline || 0 },
    { label: '最高峰值', value: o.maxPeakOnline || 0 },
    { label: '礼物积分', value: o.totalGiftPrice || 0 },
    { label: '送礼人数', value: o.giftSenderCount || 0 },
    { label: '上次开播', value: o.lastLiveAt ? formatDateTime(o.lastLiveAt) : '—' }
  ]
})

/**
 * 直播时长用「X 小时 Y 分」而不是 utils/format 的 formatDuration：
 * 那个是语音时长（mm:ss），两小时的直播会变成「120:00」，读的人得自己换算。
 */
function fmtLen(seconds) {
  const total = Math.max(0, Math.round(Number(seconds) || 0))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h > 0) {
    return `${h} 小时 ${m} 分`
  }
  if (m > 0) {
    return `${m} 分 ${s} 秒`
  }
  return `${s} 秒`
}

function reasonText(reason) {
  if (reason === 'timeout') {
    return '断流超时自动关播'
  }
  if (reason === 'ban') {
    return '被管理员中断'
  }
  return '主动关播'
}

async function load() {
  loading.value = true
  try {
    stats.value = await fetchAnchorStats(days.value)
  } catch {
    // request.js 已弹过提示；留旧数据在屏上比刷成空白好读
  } finally {
    loading.value = false
  }
}

function onOpen() {
  load()
}
</script>

<style scoped>
.stats__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.stats__body {
  max-height: 66vh;
  overflow-y: auto;
  min-height: 180px;
}

.stats__cap {
  font-size: 12px;
  color: #b8860b;
  background: #fdf6ec;
  border: 1px solid #f5dab1;
  border-radius: 6px;
  padding: 6px 10px;
  margin-bottom: 10px;
}

.stats__live {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 12px;
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(255, 90, 120, 0.12), rgba(255, 90, 120, 0.02));
  border: 1px solid rgba(255, 90, 120, 0.28);
}

.stats__live-tag {
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: #fa5169;
  border-radius: 10px;
  padding: 2px 8px;
}

.stats__live-item {
  font-size: 12px;
  color: var(--im-text-2, #666);
}

.stats__live-item b {
  font-size: 14px;
  color: var(--im-text-1, #333);
}

.stats__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}

.stats__cell {
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--im-bg-2, #f7f8fa);
  text-align: center;
}

.stats__num {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  word-break: break-all;
}

.stats__label {
  font-size: 12px;
  color: var(--im-text-3, #999);
}

.stats__title {
  font-size: 14px;
  font-weight: 600;
  margin: 4px 0 8px;
}

.stats__table {
  margin-bottom: 14px;
}

.stats__sub {
  font-size: 11px;
  color: var(--im-text-3, #999);
}

.stats__reason {
  color: #e6a23c;
}

.stats__muted {
  color: var(--im-text-3, #999);
  font-size: 12px;
}

.stats__ranks {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.stats__rank {
  flex: 1 1 260px;
  min-width: 0;
}

.stats__row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--im-border-2, #eee);
}

.stats__no {
  width: 18px;
  font-size: 12px;
  color: var(--im-text-3, #999);
  text-align: center;
}

.stats__icon {
  font-size: 16px;
  width: 22px;
  text-align: center;
}

.stats__name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
}

.stats__val {
  font-size: 13px;
  font-weight: 600;
  color: #fa5169;
}

.stats__note {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--im-border-2, #eee);
  font-size: 12px;
  line-height: 1.7;
  color: var(--im-text-3, #999);
}

.stats__note b {
  color: #e6a23c;
}
</style>
