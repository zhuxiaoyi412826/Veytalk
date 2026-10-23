<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="380px"
    append-to-body
    @open="onOpen"
  >
    <div v-loading="loading" class="gcinvite__body">
      <el-input v-model="keyword" size="small" placeholder="搜索成员" clearable :prefix-icon="Search" />

      <div class="gcinvite__list">
        <el-checkbox-group v-model="picked">
          <label
            v-for="m in filtered"
            :key="asId(m.userId)"
            class="gcinvite__item"
            :class="{ 'gcinvite__item--over': isOver(m) }"
          >
            <!-- value 统一走 asId：后端小 id 是 JSON number、雪花 id 是字符串，不归一化就选不中 -->
            <el-checkbox :value="asId(m.userId)" :disabled="isOver(m)">
              <span class="gcinvite__row">
                <UserAvatar :src="m.avatar" :name="nameOf(m)" :size="28" />
                <span class="gcinvite__name im-ellipsis">{{ nameOf(m) }}</span>
              </span>
            </el-checkbox>
          </label>
        </el-checkbox-group>
        <div v-if="!filtered.length && !loading" class="gcinvite__empty">没有可选成员</div>
      </div>

      <div class="gcinvite__foot">
        <span>已选 {{ picked.length }} 人</span>
        <span v-if="maxPick > 0" class="gcinvite__cap">上限 {{ maxPick }} 人（含你自己）</span>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!picked.length" @click="confirm">{{ confirmText }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { useGroupStore } from '@/stores/group'
import { asId, sameId } from '@/utils/id'

/**
 * 群通话成员选择器：发起时选首批邀请，通话中「邀请」也复用它追加邀请。
 *
 * 只选人、不发信令——确认事件把 ID 清单交给调用方，由 groupcall store 决定是 create 还是 invite。
 * 上限是「服务端 groupMaxSlots 减去已在房里的人」，超出的格子置灰而不是让他选完再报错：
 * 服务端对超限是默默截断（不报错），前端如果不拦，用户会以为拉了 8 个人、进房发现只有 5 个。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  groupId: { type: [String, Number], default: null },
  /** 已经在房里或已邀请的人，不可再选 */
  excludeIds: { type: Array, default: () => [] },
  /** 服务端人数上限（含自己），来自 /api/call/config */
  maxSlots: { type: Number, default: 0 },
  title: { type: String, default: '选择邀请对象' },
  confirmText: { type: String, default: '邀请' }
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const group = useGroupStore()
const loading = ref(false)
const keyword = ref('')
const picked = ref([])

const excludes = computed(() => props.excludeIds.map((id) => asId(id)))
/** 还能再拉几个人：总上限减去已在房里的（excludeIds 不含自己，所以自己那一名要再减 1） */
const maxPick = computed(() => (props.maxSlots > 0 ? Math.max(props.maxSlots - 1 - excludes.value.length, 0) : 0))

const candidates = computed(() => (group.members || []).filter((m) => !excludes.value.includes(asId(m.userId))))

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) {
    return candidates.value
  }
  return candidates.value.filter((m) => nameOf(m).toLowerCase().includes(kw))
})

/** 已经选满之后，剩下的格子置灰；已选中的仍然可以取消 */
function isOver(member) {
  const uid = asId(member.userId)
  if (picked.value.includes(uid)) {
    return false
  }
  return maxPick.value > 0 && picked.value.length >= maxPick.value
}

function nameOf(member) {
  return member.nicknameInGroup || member.displayName || member.nickname || '群成员'
}

async function onOpen() {
  picked.value = []
  keyword.value = ''
  if (!props.groupId) {
    return
  }
  loading.value = true
  try {
    // 成员是分页的（默认 50 条），选择器一次多取一些，免得用户翻不到后面的人
    if (!group.members.length) {
      await group.fetchMembers(props.groupId, 1, 200)
    }
  } catch {
    /* 拉不到就显示空列表，调用方不需要为选择器弹错误框 */
  } finally {
    loading.value = false
  }
}

function confirm() {
  // picked 里可能是数字（后端把小 id 直接写成 JSON number），统一转字符串再交给 store
  const ids = picked.value.map((id) => asId(id)).filter((id) => !sameId(id, ''))
  emit('confirm', ids)
  visible.value = false
}
</script>

<style scoped>
.gcinvite__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.gcinvite__list {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 8px;
  padding: 6px 10px;
}

.gcinvite__item {
  display: block;
  margin: 0;
  padding: 4px 0;
}

.gcinvite__item--over {
  opacity: 0.45;
}

.gcinvite__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.gcinvite__name {
  max-width: 220px;
  font-size: 13px;
}

.gcinvite__empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--im-text-secondary, #909399);
}

.gcinvite__foot {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}
</style>
