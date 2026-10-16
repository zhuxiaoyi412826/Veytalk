<template>
  <el-dialog
    v-model="visible"
    title="入群申请"
    width="460px"
    top="8vh"
    :close-on-click-modal="true"
    append-to-body
    @open="onOpen"
  >
    <div v-loading="loading" class="gjr__body im-scroll">
      <div v-if="!list.length && !loading" class="gjr__empty">
        <el-empty description="暂无待审批的入群申请" :image-size="80" />
      </div>
      <div v-else class="gjr__list">
        <div v-for="item in list" :key="item.requestId" class="gjr__item">
          <UserAvatar :src="item.avatar" :name="item.nickname || item.username" :size="40" />
          <div class="gjr__info">
            <div class="gjr__head">
              <span class="gjr__nick im-ellipsis">{{ item.nickname || item.username || ('用户' + item.userId) }}</span>
              <el-tag size="small" effect="plain" type="info">{{ sourceText(item.source) }}</el-tag>
            </div>
            <div v-if="item.message" class="gjr__msg">{{ item.message }}</div>
            <div class="gjr__time">{{ formatDateTime(item.createTime) }}</div>
          </div>
          <div class="gjr__actions">
            <el-button
              size="small"
              type="primary"
              :loading="busyId === item.requestId"
              @click="onHandle(item, true)"
            >通过</el-button>
            <el-button
              size="small"
              :loading="busyId === item.requestId"
              @click="onHandle(item, false)"
            >拒绝</el-button>
          </div>
        </div>
      </div>

      <div v-if="list.length && list.length < total" class="gjr__more">
        <el-button link type="primary" :loading="loading" @click="loadMore">加载更多</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import UserAvatar from '@/components/UserAvatar.vue'
import { useGroupStore } from '@/stores/group'
import { formatDateTime } from '@/utils/format'

/**
 * 入群申请审批面板（仅管理层）。
 *
 * 分页列出本群待审批申请，逐条通过 / 拒绝。通过后后端同事务把申请人写入成员表并推 NOTIFY，
 * 本端只需从列表移除该行并递减群详情上的待审批数。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  groupId: { type: [String, Number], default: null }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const group = useGroupStore()
const list = ref([])
const total = ref(0)
const current = ref(1)
const loading = ref(false)
const busyId = ref(null)
const PAGE_SIZE = 20

function sourceText(source) {
  return source === 'qrcode' ? '扫码' : '链接'
}

async function load(reset = false) {
  if (!props.groupId) return
  if (reset) {
    current.value = 1
  }
  loading.value = true
  try {
    const page = await group.fetchJoinRequests(props.groupId, current.value, PAGE_SIZE)
    const records = (page && page.records) || []
    list.value = reset ? records : list.value.concat(records)
    total.value = (page && page.total) || 0
  } catch {
    if (reset) {
      list.value = []
      total.value = 0
    }
  } finally {
    loading.value = false
  }
}

function onOpen() {
  load(true)
}

function loadMore() {
  current.value += 1
  load(false)
}

async function onHandle(item, approve) {
  busyId.value = item.requestId
  try {
    await group.handleJoinRequest(props.groupId, item.requestId, approve)
    list.value = list.value.filter((r) => r.requestId !== item.requestId)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success(approve ? '已通过' : '已拒绝')
  } catch {
    /* request.js 已处理 */
  } finally {
    busyId.value = null
  }
}
</script>

<style scoped>
.gjr__body {
  max-height: 60vh;
  overflow-y: auto;
  min-height: 120px;
}

.gjr__list {
  display: flex;
  flex-direction: column;
}

.gjr__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--im-border-light, #f0f2f5);
}

.gjr__info {
  flex: 1;
  min-width: 0;
}

.gjr__head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.gjr__nick {
  font-size: 14px;
  color: var(--im-text);
  max-width: 160px;
}

.gjr__msg {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-top: 2px;
  word-break: break-all;
}

.gjr__time {
  font-size: 11px;
  color: var(--im-text-placeholder, #a8abb2);
  margin-top: 2px;
}

.gjr__actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}

.gjr__more {
  text-align: center;
  padding: 10px 0;
}
</style>
