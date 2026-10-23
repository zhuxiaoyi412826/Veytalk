<template>
  <div class="logs">
    <div class="toolbar">
      <h2 class="page-title">日志管理</h2>
      <span class="grow" />
      <el-button :loading="filesLoading" @click="loadFiles">
        <el-icon><Refresh /></el-icon>
        <span>刷新清单</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="只尾读、不下载；只认白名单档位、不传路径"
      description="前端永远只把 key（如 error）发给后端，路径映射表写死在服务端，这是路径穿越防线的主要一层。尾读行数有上限，关键词只在本页尾读窗口内过滤——不是全文件搜索，想找历史就去归档文件里 grep。日志原文可能含手机号等个人信息，别截图外发。"
    />

    <div class="cols">
      <el-card shadow="never" class="files">
        <template #header>日志档位</template>
        <el-table
          v-loading="filesLoading"
          :data="files"
          size="small"
          highlight-current-row
          @row-click="pickFile"
        >
          <el-table-column label="档" min-width="120">
            <template #default="{ row }">
              <div class="file-text">
                <span class="ellipsis">{{ row.label }}</span>
                <span class="muted code">{{ row.relPath }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="90">
            <template #default="{ row }">
              <span class="num">{{ row.exists ? formatBytes(row.sizeBytes) : EMPTY_TEXT }}</span>
            </template>
          </el-table-column>
          <el-table-column label="修改" width="110">
            <template #default="{ row }">
              <span class="num">{{ row.exists ? formatListTime(row.lastModified) : EMPTY_TEXT }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag :type="row.exists ? 'success' : 'info'" effect="plain" size="small">
                {{ row.exists ? '有' : '空' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="muted note">「空」表示这个档还没产生过内容（比如从没见过慢 SQL），不是错误。</p>
      </el-card>

      <el-card shadow="never" class="viewer">
        <template #header>
          <div class="viewer-head">
            <span>{{ current ? `尾读：${current.label}` : '尾读：先从左边选一个档' }}</span>
            <el-tag v-if="current?.sensitive" type="warning" effect="plain" size="small">敏感档</el-tag>
          </div>
        </template>
        <div class="read-bar">
          <el-select v-model="read.tail" class="tail" @change="loadContent">
            <el-option :value="200" label="最近 200 行" />
            <el-option :value="500" label="最近 500 行" />
            <el-option :value="1000" label="最近 1000 行" />
            <el-option :value="2000" label="最近 2000 行" />
          </el-select>
          <el-input
            v-model="read.keyword"
            :maxlength="50"
            clearable
            class="kw"
            placeholder="窗口内过滤关键词（回车）"
            @keyup.enter="loadContent"
            @clear="loadContent"
          />
          <el-button :disabled="!current" :loading="contentLoading" @click="loadContent">读取</el-button>
        </div>

        <div v-loading="contentLoading" class="content">
          <template v-if="content && content.exists && (content.lines || []).length">
            <pre class="lines">{{ content.lines.join('\n') }}</pre>
          </template>
          <el-empty
            v-else
            :description="content ? (content.exists ? '窗口里没有内容（或被关键词过滤光了）' : '这个档还不存在') : '选一个日志档再读取'"
            :image-size="60"
          />
        </div>

        <div v-if="content" class="muted meta">
          <span>{{ content.relPath }} · 返回 {{ content.returnedLines ?? 0 }} 行</span>
          <span v-if="content.truncated" class="bad">（已达尾读上限，还有更早的内容没给）</span>
          <span v-if="content.filterNote"> · {{ content.filterNote }}</span>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { fetchLogContent, fetchLogFiles } from '@/api/admin'
import { EMPTY_TEXT, formatBytes, formatListTime } from '@/utils/format'

/**
 * 日志管理页（三期）。
 *
 * 左列是七个逻辑档的清单，右列是尾读查看器。两列都手动触发、不自动轮询——
 * 这是排障页不是驾驶舱，「刷一下看新行」的点一下成本比常驻定时器低。
 *
 * 行序：后端返回与文件一致（旧→新），最后一行在最下面，和 tail -f 的肌肉记忆一致，
 * 前端不再倒序。
 *
 * 关键词只在本次尾读窗口内做不区分大小写过滤（后端注释里钉死的口径），
 * 空结果可能是「窗口里没有」也可能是「被过滤光了」，占位文案两种都说。
 */
const files = ref([])
const filesLoading = ref(false)
const current = ref(null)

const content = ref(null)
const contentLoading = ref(false)
const read = reactive({ tail: 200, keyword: '' })

async function loadFiles() {
  filesLoading.value = true
  try {
    files.value = await fetchLogFiles()
    // 默认选中第一个存在的档，减少一次点击；全都不存在就留在空态
    if (!current.value) {
      const first = files.value.find((f) => f.exists) || files.value[0]
      if (first) {
        pickFile(first)
      }
    }
  } catch {
    // 接口层已弹提示
  } finally {
    filesLoading.value = false
  }
}

function pickFile(row) {
  if (!row || !row.key) {
    return
  }
  current.value = row
  loadContent()
}

async function loadContent() {
  if (!current.value) {
    return
  }
  contentLoading.value = true
  try {
    content.value = await fetchLogContent(current.value.key, read.tail, read.keyword?.trim())
  } catch {
    // 保留上一次读到的内容：读取失败不等于日志被删了
  } finally {
    contentLoading.value = false
  }
}

onMounted(loadFiles)
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.hint {
  margin-bottom: 16px;
}

.cols {
  align-items: start;
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(360px, 460px) minmax(0, 1fr);
}

.file-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.note {
  font-size: 12px;
  line-height: 1.6;
  margin: 12px 0 0;
}

.viewer-head {
  align-items: center;
  display: flex;
  gap: 8px;
  justify-content: space-between;
}

.read-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.tail {
  width: 150px;
}

.kw {
  flex: 1;
  max-width: 320px;
}

.content {
  background: #1e1e1e;
  border-radius: 6px;
  min-height: 320px;
  overflow: auto;
  max-height: 560px;
  padding: 12px;
}

.lines {
  color: #d4d4d4;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.meta {
  font-size: 12px;
  margin-top: 8px;
}

.bad {
  color: #e6a23c;
}

@media (max-width: 1100px) {
  .cols {
    grid-template-columns: 1fr;
  }
}
</style>
