<template>
  <el-dialog
    v-model="visible"
    :title="titleText"
    width="640px"
    top="8vh"
    :close-on-click-modal="true"
    append-to-body
    @open="onOpen"
  >
    <div v-loading="loading" class="gcd__body im-scroll">
      <!-- ==================== 群相册：图片九宫格 ==================== -->
      <template v-if="mode === 'album'">
        <div v-if="!list.length && !loading" class="gcd__empty">
          <el-empty description="还没有群相册图片" :image-size="80" />
        </div>
        <div v-else class="gcd__grid">
          <el-image
            v-for="(item, idx) in list"
            :key="item.messageId"
            :src="mediaUrl(extraOf(item).fileUrl)"
            :preview-src-list="previewList"
            :initial-index="previewIndex(idx)"
            fit="cover"
            lazy
            class="gcd__grid-img"
            preview-teleported
          >
            <template #error>
              <div class="gcd__img-error"><el-icon><Picture /></el-icon></div>
            </template>
          </el-image>
        </div>
      </template>

      <!-- ==================== 群文件：附件列表 ==================== -->
      <template v-else-if="mode === 'files'">
        <div v-if="!list.length && !loading" class="gcd__empty">
          <el-empty description="还没有群文件" :image-size="80" />
        </div>
        <div v-else class="gcd__files">
          <div v-for="item in list" :key="item.messageId" class="gcd__file">
            <el-icon class="gcd__file-icon"><Document /></el-icon>
            <div class="gcd__file-info">
              <div class="gcd__file-name im-ellipsis" :title="extraOf(item).fileName">
                {{ extraOf(item).fileName || '未命名文件' }}
              </div>
              <div class="gcd__file-sub">
                {{ formatFileSize(extraOf(item).fileSize) }}
                · {{ item.fromNickname || '成员' }}
                · {{ formatDateTime(item.sendTime) }}
              </div>
            </div>
            <el-button
              size="small"
              text
              type="primary"
              :icon="Download"
              :disabled="!extraOf(item).fileUrl"
              @click="onDownload(item)"
            >下载</el-button>
          </div>
        </div>
      </template>

      <!-- ==================== 群精华：消息摘要 ==================== -->
      <template v-else>
        <div v-if="!list.length && !loading" class="gcd__empty">
          <el-empty description="还没有精华消息" :image-size="80" />
        </div>
        <div v-else class="gcd__essence">
          <div v-for="item in list" :key="item.messageId" class="gcd__essence-item">
            <div class="gcd__essence-head">
              <span class="gcd__essence-nick im-ellipsis">{{ item.fromNickname || '成员' }}</span>
              <span class="gcd__essence-time">{{ formatDateTime(item.sendTime) }}</span>
            </div>
            <div class="gcd__essence-text">{{ essencePreview(item) }}</div>
          </div>
        </div>
      </template>

      <!-- 加载更多 -->
      <div v-if="list.length && list.length < total" class="gcd__more">
        <el-button link type="primary" :loading="loading" @click="loadMore">加载更多</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Download, Picture } from '@element-plus/icons-vue'
import { fetchGroupMedia, fetchGroupEssence } from '@/api/message'
import { mediaUrl, downloadFile } from '@/utils/media'
import { formatFileSize, formatDateTime } from '@/utils/format'

/**
 * 群聊内容浏览面板。
 *
 * 三种形态复用同一个分页壳子：
 * - album：msgType=2 的图片消息，九宫格 + 大图预览
 * - files：msgType=3 的文件消息，列表行 + 下载
 * - essence：被群主/管理员标记为精华的消息，摘要列表
 *
 * 数据来源都是后端按 conversationId + msgType（或精华表）过滤的 PageResult，
 * 附件元信息在 item.extra（fileUrl/fileName/fileSize），与气泡渲染一致。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  conversationId: { type: [String, Number], default: null },
  mode: { type: String, default: 'files' } // files | album | essence
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const TITLE = { files: '群文件', album: '群相册', essence: '群精华' }
const titleText = computed(() => TITLE[props.mode] || '群聊内容')

const list = ref([])
const total = ref(0)
const current = ref(1)
const loading = ref(false)
const PAGE_SIZE = 20

const previewList = computed(() =>
  list.value
    .map((item) => mediaUrl(extraOf(item).fileUrl))
    .filter((url) => !!url)
)

// el-image 的 initial-index 要在过滤后的 previewList 里定位；
// 被 mediaUrl 过滤掉的空串极少（取不到图），退化为原索引即可
function previewIndex(idx) {
  const url = mediaUrl(extraOf(list.value[idx]).fileUrl)
  const pos = previewList.value.indexOf(url)
  return pos >= 0 ? pos : idx
}

function extraOf(item) {
  return (item && item.extra) || {}
}

function essencePreview(item) {
  const type = Number(item.msgType)
  if (type === 2) return '[图片]'
  if (type === 3) return `[文件] ${extraOf(item).fileName || ''}`.trim()
  if (type === 4) return '[语音]'
  return item.content || ''
}

async function fetchPage(page) {
  if (!props.conversationId) return { records: [], total: 0 }
  if (props.mode === 'essence') {
    return fetchGroupEssence(props.conversationId, page, PAGE_SIZE)
  }
  const msgType = props.mode === 'album' ? 2 : 3
  return fetchGroupMedia(props.conversationId, msgType, page, PAGE_SIZE)
}

async function load(reset) {
  if (!props.conversationId) return
  loading.value = true
  try {
    const page = reset ? 1 : current.value + 1
    const res = await fetchPage(page)
    const records = (res && res.records) || []
    if (reset) {
      list.value = records
      current.value = 1
    } else {
      list.value = list.value.concat(records)
      current.value = page
    }
    total.value = (res && res.total) || 0
  } catch {
    // 错误提示已由全局 toast 处理，这里保持已有列表不动
  } finally {
    loading.value = false
  }
}

function loadMore() {
  load(false)
}

function onOpen() {
  list.value = []
  total.value = 0
  current.value = 1
  load(true)
}

async function onDownload(item) {
  const extra = extraOf(item)
  if (!extra.fileUrl) {
    ElMessage.warning('文件地址不可用')
    return
  }
  await downloadFile(extra.fileUrl, extra.fileName)
}
</script>

<style scoped>
.gcd__body {
  max-height: 62vh;
  overflow-y: auto;
}

.gcd__empty {
  padding: 20px 0;
}

/* 群相册九宫格 */
.gcd__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
}

.gcd__grid-img {
  width: 100%;
  height: 96px;
  border-radius: 6px;
  cursor: zoom-in;
  background: var(--im-bg-hover, #f5f5f5);
}

.gcd__img-error {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--im-text-placeholder, #bbb);
  background: var(--im-bg-hover, #f5f5f5);
}

/* 群文件列表 */
.gcd__files {
  display: flex;
  flex-direction: column;
}

.gcd__file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 8px;
  border-bottom: 1px solid var(--im-border-light, #f0f0f0);
}

.gcd__file-icon {
  flex: none;
  font-size: 26px;
  color: var(--im-primary, #409eff);
}

.gcd__file-info {
  flex: 1;
  min-width: 0;
}

.gcd__file-name {
  font-size: 14px;
  color: var(--im-text, #303133);
}

.gcd__file-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/* 群精华 */
.gcd__essence {
  display: flex;
  flex-direction: column;
}

.gcd__essence-item {
  padding: 10px 8px;
  border-bottom: 1px solid var(--im-border-light, #f0f0f0);
}

.gcd__essence-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.gcd__essence-nick {
  font-size: 13px;
  font-weight: 600;
  color: #d48806;
}

.gcd__essence-time {
  flex: none;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.gcd__essence-text {
  font-size: 14px;
  line-height: 1.5;
  color: var(--im-text, #303133);
  word-break: break-word;
}

.gcd__more {
  padding: 12px 0;
  text-align: center;
}
</style>
