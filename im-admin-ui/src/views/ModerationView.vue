<template>
  <div class="moderation">
    <div class="toolbar">
      <h2 class="page-title">审核记录</h2>
      <span class="grow" />
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="图片内容审核的命中流水（纯读）：谁在什么时候试过什么图、撞在哪一类上，点「查看」可以看被拦下的那张图"
      description="识别在发送端跑，本行记的是判定结果，图本体则靠行末的「查看」按钮——发送端拦下后会把原图另传一份到隔离区（im_file 里 biz_type=moderation_quarantine）。★ 这意味着被拦下的图片已经离开过本机，它只存在服务端、只给有 system:log 权限的人看；不想要这份副本就在系统设置里关掉「命中图片是否上传隔离区副本」（关掉不影响拦图与审计）。没有按钮或点开后说不可用，可能是历史命中（副本机制上线前的记录不会补图）、图超过隔离区上限、上传失败，或 sql/migration_moderation_evidence.sql 没跑。与日志管理页的 moderation.log 双写同源：文件只留 30 天，表不限。判定不采信客户端结论，概率与文件名都经过服务端清洗；这一页没有删除与修改入口，销毁追责现场的口子从表结构上就不留。"
    />

    <div class="filters">
      <el-input
        v-model="query.userId"
        :maxlength="20"
        clearable
        class="uid"
        placeholder="用户 ID"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.scene" class="pick" clearable placeholder="全部场景" @change="reload">
        <el-option value="chat_image" label="聊天图片" />
        <el-option value="avatar" label="头像" />
        <el-option value="video_cover" label="直播封面" />
      </el-select>
      <el-select v-model="query.hitClass" class="pick" clearable filterable placeholder="全部类别" @change="reload">
        <el-option v-for="c in HIT_CLASSES" :key="c" :value="c" :label="c" />
      </el-select>
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
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" size="small">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="num">{{ formatDateTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" min-width="150">
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis">{{ row.nickname }}</span>
              <span class="muted">#{{ row.userId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="场景" width="100">
          <template #default="{ row }">
            <span>{{ sceneText(row.scene) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="命中类别" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small" type="danger">{{ row.hitClass }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column align="right" label="概率" width="90">
          <template #default="{ row }">
            <span class="num out">{{ percent(row.hitProbability) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="阈值" width="90">
          <template #default="{ row }">
            <span class="num muted">{{ row.threshold != null ? percent(row.threshold) : EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="文件名" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="code">{{ row.fileName || EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="大小" width="90">
          <template #default="{ row }">
            <span class="num">{{ row.fileSize != null ? formatBytes(row.fileSize) : EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="会话" width="120">
          <template #default="{ row }">
            <span v-if="row.conversationId" class="muted num">#{{ row.conversationId }}</span>
            <span v-else class="muted">{{ EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column align="center" label="图片" width="76">
          <template #default="{ row }">
            <!-- 有 evidenceFileId 才给按钮：无图是常态而不是错误（开关关掉 / 图超大 / 上传失败），
                 所以下面的提示文案不能弹错，也不能让人去点一个永远取不到字节的链接 -->
            <el-button v-if="row.evidenceFileId" link size="small" type="primary" @click="openImage(row)">
              查看
            </el-button>
            <span v-else class="muted">无图</span>
          </template>
        </el-table-column>
        <el-table-column label="评分明细" min-width="200">
          <template #default="{ row }">
            <span v-if="!row.scores?.length" class="muted">{{ EMPTY_TEXT }}</span>
            <el-tag
              v-for="s in row.scores || []"
              :key="s.className"
              class="score-tag"
              :type="s.className === row.hitClass ? 'danger' : 'info'"
              effect="plain"
              size="small"
            >
              {{ s.className }} {{ percent(s.probability) }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的审核命中记录" :image-size="80" />
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

    <el-dialog
      v-model="viewer.visible"
      :title="viewerTitle"
      width="640px"
      append-to-body
      destroy-on-close
    >
      <div class="viewer">
        <p v-if="viewer.loading" class="muted">图片加载中…</p>
        <p v-else-if="!viewer.url" class="muted">
          图片不可用：隔离区里的字节已经不在了，而这条记录当时确实上传过一份副本。
        </p>
        <template v-else>
          <!-- el-image 自带点击放大；src 是 blob 地址（登录头取回来的），直接塞受控地址会是一张裂图 -->
          <el-image :src="viewer.url" fit="contain" :preview-src-list="[viewer.url]" preview-teleported />
          <p class="muted">
            {{ viewer.row?.fileName || EMPTY_TEXT }} · {{ viewer.row?.fileSize != null ? formatBytes(viewer.row.fileSize) : EMPTY_TEXT }}
            · {{ sceneText(viewer.row?.scene) }} · {{ viewer.row?.nickname }}
          </p>
          <p class="muted tip">
            这是发送端拦下后另传的证据副本，它从未作为消息投递给任何人；原图仍在那台设备上，服务端只持有这一份。
          </p>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchModerationLogs } from '@/api/admin'
import { fetchMedia } from '@/api/request'
import { EMPTY_TEXT, formatBytes, formatDateTime } from '@/utils/format'

/**
 * 审核记录（五期·路线 2，纯读）。
 *
 * 数据来自 im_moderation_log：发送端识别到违规图片并拒发后上报，服务端清洗后双写
 * （审计文件 + 这张表）。文件侧受 logback 保留期约束（默认 30 天），表侧不限——
 * 迁表就是为了「按人翻历史」这件事不随文件滚存蒸发。
 *
 * 「查看」开的是图本体：它经 /api/admin/log/moderation/{id}/image 取隔离区里那份副本，
 * 不用 im-file 的受控地址——那套 canAccess 只认上传者与同会话成员，管理员两者都不是
 * （与举报工单的证据媒体同一个口径），而判「这张拦得对不对」偏偏必须看到图。
 *
 * 排序固定主键倒序（雪花即时间序），没有给排序旋钮：这一屏的用法永远是「最近的在上面」，
 * 而 create_time 上没有索引，拿它排在带时间区间时会对整个命中集 filesort。
 *
 * 类别下拉是写死的前端字典（模型输出的五类 + 血腥模型两类），不是从库里 distinct 出来的：
 * 后端不校验类别名，distinct 会把某次异常上报里的脏字符串也变成候选项。
 */
const HIT_CLASSES = ['Porn', 'Hentai', 'Sexy', 'Violent', 'Drawing', 'Neutral']

/** 与后端 FileBizType 对齐的图片类场景；下拉不给 chat_file 等非图片类型 */
const SCENE_TEXT = {
  chat_image: '聊天图片',
  avatar: '头像',
  video_cover: '直播封面'
}

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  userId: '',
  scene: undefined,
  hitClass: undefined,
  current: 1,
  size: 20
})
const dateRange = ref(null)

/** el-date-picker 的范围端点：开始那天取 00:00:00、结束那天取 23:59:59，不然「今天」会漏掉一整天 */
const defaultTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

function digitsOrUndefined(value) {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) ? text : undefined
}

function sceneText(scene) {
  return SCENE_TEXT[scene] || scene
}

function percent(value) {
  return value == null ? EMPTY_TEXT : `${Math.round(value * 100)}%`
}

async function load() {
  loading.value = true
  try {
    const page = await fetchModerationLogs({
      userId: digitsOrUndefined(query.userId),
      scene: query.scene || undefined,
      hitClass: query.hitClass || undefined,
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

/**
 * 隔离区图片的弹框状态。
 *
 * 地址是 blob：后端要登录头，而 <img> 带不了自定义头（Cookie 读取为规避 CSRF 已关）。
 * timeout 0 不限时长：副本最大可能有 10MB；自增序号防快速连点两行时先回的覆盖后开的。
 * fetchMedia 失败回的是空串而不是抛错（业务失败体是 JSON，它按内容类型识破），
 * 所以这里不需要 catch，也不需要把「无图」当成一次错误弹给用户。
 */
const viewer = reactive({ visible: false, loading: false, url: '', row: null })
let viewerSeq = 0

const viewerTitle = computed(() =>
  viewer.row ? `命中记录 #${viewer.row.id} 的隔离区图片` : '隔离区图片'
)

async function openImage(row) {
  const seq = ++viewerSeq
  viewer.row = row
  viewer.url = ''
  viewer.loading = true
  viewer.visible = true
  const src = await fetchMedia(`/api/admin/log/moderation/${row.id}/image`, { timeout: 0 })
  if (seq === viewerSeq) {
    viewer.url = src
    viewer.loading = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.grow {
  flex: 1;
}

.toolbar {
  align-items: center;
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.hint {
  margin-bottom: 16px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.uid {
  width: 190px;
}

.pick {
  width: 140px;
}

.range {
  max-width: 420px;
}

.stack {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.num {
  font-variant-numeric: tabular-nums;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.out {
  color: #f56c6c;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.score-tag {
  margin: 2px 4px 2px 0;
}

.viewer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 80px;
}

.viewer :deep(.el-image) {
  max-height: 60vh;
  width: 100%;
}

.tip {
  line-height: 1.6;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
