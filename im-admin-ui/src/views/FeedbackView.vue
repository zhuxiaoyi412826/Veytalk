<template>
  <div class="feedback">
    <div class="toolbar">
      <h2 class="page-title">意见反馈</h2>
      <span class="grow" />
      <span class="muted">反馈由前台用户在「设置 · 意见反馈」提交，后台只能回复与删除</span>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="回复即把该条置为「已回复」，再次回复会覆盖旧文"
      description="反馈是客服台账不是合同原件，不做回复版本化——需要追责时后台操作日志里有。删除是物理删行、没有回收站，用于清理已处置完的台账。提交人昵称实时取自用户模块，账号注销后显示「已注销」，反馈行本身仍保留。"
    />

    <div class="filters">
      <el-input
        v-model="query.keyword"
        :maxlength="50"
        class="keyword"
        clearable
        placeholder="正文 / 联系方式关键词"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.type" class="type" clearable placeholder="全部类型" @change="reload">
        <el-option v-for="t in typeOptions" :key="t.code" :value="t.code" :label="t.name" />
      </el-select>
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option :value="0" label="待处理" />
        <el-option :value="1" label="已回复" />
      </el-select>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>查询</span>
      </el-button>
      <span class="muted">列表固定按提交时间倒序，接口不提供排序参数</span>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <!-- 展开行装全文与回复：1000 字的正文塞不进单元格，处置前往往要看清用户到底说了什么 -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <div class="detail-content">{{ row.content }}</div>
              <div v-if="row.reply" class="detail-reply">
                <span class="detail-reply-label">后台回复：</span>{{ row.reply }}
              </div>
              <div class="detail-meta">
                <span>提交人：{{ row.userName || '已注销' }}（#{{ row.userId }}）</span>
                <span>联系方式：{{ row.contact || EMPTY_TEXT }}</span>
                <span>状态：{{ row.statusName }}</span>
                <span>提交：{{ formatDateTime(row.createTime) }}</span>
                <span v-if="row.replyTime">回复：{{ formatDateTime(row.replyTime) }}（by #{{ row.replyBy || EMPTY_TEXT }}）</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="提交人" width="150">
          <template #default="{ row }">
            <span v-if="row.userName" class="ellipsis">{{ row.userName }}</span>
            <span v-else class="muted">已注销</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.typeName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="280">
          <template #default="{ row }">
            <span class="ellipsis muted">{{ oneLine(row.content) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="联系方式" width="150">
          <template #default="{ row }">
            <span class="ellipsis">{{ row.contact || EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" effect="plain" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="120">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openReply(row)">
                {{ row.status === 1 ? '再次回复' : '回复' }}
              </el-button>
              <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有反馈" :image-size="80" />
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
      v-model="dialog.visible"
      :title="`回复反馈：${dialog.userName}`"
      width="560px"
      :close-on-click-backdrop="false"
    >
      <div class="dialog-origin">
        <div class="dialog-origin-label">用户原文</div>
        <div class="dialog-origin-content">{{ dialog.content }}</div>
      </div>
      <el-form label-position="top">
        <el-form-item label="回复内容（必填，最多 1000 字）">
          <el-input
            v-model="form.reply"
            :maxlength="1000"
            :rows="5"
            show-word-limit
            type="textarea"
            placeholder="处理结果或答复，写入即把这条置为已回复"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="dialog.saving" @click="dialog.visible = false">取消</el-button>
        <el-button :disabled="!formReady || dialog.saving" :loading="dialog.saving" type="primary" @click="submitReply">
          提交回复
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteFeedback,
  fetchFeedbackPage,
  fetchFeedbackTypes,
  replyFeedback
} from '@/api/admin'
import { EMPTY_TEXT, formatDateTime, formatListTime } from '@/utils/format'

/**
 * 意见反馈管理。
 *
 * 与公告不同，这一页没有「新建 / 编辑」：反馈由前台用户产生，后台只能回复与删除——
 * 允许后台凭空造一条或改用户原文，台账就失去了「用户到底说了什么」的凭证价值。
 * 回复按钮在待处理行叫「回复」、已回复行叫「再次回复」，两者是同一条端点（覆盖旧文）。
 *
 * 每一次写动作都以服务端回来的结果为准并重新拉列表：这一页可能开着好几个客服，
 * 两秒前的「待处理」可能刚被同事回复掉。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ keyword: '', type: undefined, status: undefined, current: 1, size: 20 })

// 类型下拉由服务端码表给出（前端不自造串）；接口没回时退回这份与后端 FeedbackType 一致的兜底
const typeOptions = ref([
  { code: 'bug', name: '功能异常' },
  { code: 'suggestion', name: '产品建议' },
  { code: 'experience', name: '体验问题' },
  { code: 'other', name: '其他' }
])

const dialog = reactive({ visible: false, saving: false, id: null, userName: '', content: '' })
const form = reactive({ reply: '' })

const formReady = computed(() => form.reply.trim() !== '')

async function load() {
  loading.value = true
  try {
    const page = await fetchFeedbackPage({
      keyword: query.keyword || undefined,
      type: query.type || undefined,
      status: query.status,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
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

async function loadTypes() {
  try {
    const list = await fetchFeedbackTypes()
    if (Array.isArray(list) && list.length) {
      typeOptions.value = list
    }
  } catch {
    // 码表拉不到就用兜底的那份，不影响列表本身
  }
}

function oneLine(content) {
  return String(content || '').replace(/\s+/g, ' ').trim()
}

/** 待处理是「还没人管」，用 warning 催一下；已回复是处置完的终态，用 success */
function statusTag(status) {
  return status === 1 ? 'success' : 'warning'
}

function openReply(row) {
  dialog.id = row.id
  dialog.userName = row.userName || '已注销用户'
  dialog.content = row.content
  // 再次回复时带出旧文，客服能看着上一次答了什么再改
  form.reply = row.reply || ''
  dialog.visible = true
}

async function submitReply() {
  if (!formReady.value) {
    return
  }
  dialog.saving = true
  try {
    await replyFeedback(dialog.id, { reply: form.reply.trim() })
    dialog.visible = false
    ElMessage.success('已回复')
    load()
  } catch {
    // 接口失败：弹框留着，request.js 已经报过原因，改完可以直接再点
  } finally {
    dialog.saving = false
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      '删除是物理删行，没有回收站，用于清理已处置完的台账。删除后这条反馈与它的回复都不再可见。',
      '确认删除这条反馈？',
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteFeedback(row.id)
    // 删掉的是本页最后一条时让页码往回退一格，否则停在一个空页上
    if (rows.value.length === 1 && query.current > 1) {
      query.current -= 1
    }
    load()
  } catch {
    // 已提示
  }
}

onMounted(() => {
  loadTypes()
  load()
})
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.hint {
  margin-bottom: 16px;
}

.filters {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}

.keyword {
  width: 240px;
}

.type {
  width: 140px;
}

.status {
  width: 140px;
}

.detail {
  padding: 4px 48px 12px;
}

.detail-content {
  /* 正文里的换行是用户打上去的，这里不能当成排版空白吃掉 */
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-reply {
  background: #f4f4f5;
  border-radius: 4px;
  margin-top: 10px;
  padding: 8px 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-reply-label {
  color: #67c23a;
  font-weight: 600;
}

.detail-meta {
  border-top: 1px dashed #dcdfe6;
  color: #909399;
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 16px;
  margin-top: 10px;
  padding-top: 8px;
}

.dialog-origin {
  background: #fafafa;
  border-radius: 4px;
  margin-bottom: 12px;
  padding: 10px 12px;
}

.dialog-origin-label {
  color: #909399;
  font-size: 12px;
  margin-bottom: 4px;
}

.dialog-origin-content {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
