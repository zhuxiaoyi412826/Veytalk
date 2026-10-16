<template>
  <el-dialog
    v-model="visible"
    title="邀请入群"
    width="360px"
    :close-on-click-modal="true"
    append-to-body
    @open="onOpen"
  >
    <div v-loading="loading" class="gid__body">
      <template v-if="invite">
        <div class="gid__qr">
          <el-image :src="invite.qrcode" fit="contain" class="gid__qr-img" />
          <div class="gid__qr-hint">扫一扫，申请加入「{{ groupName }}」</div>
        </div>

        <div class="gid__link-row">
          <span class="gid__link-label">邀请链接</span>
          <div class="gid__link-box">
            <span class="gid__link im-ellipsis" :title="invite.inviteLink">{{ invite.inviteLink }}</span>
            <el-button size="small" text type="primary" :icon="CopyDocument" @click="copyLink">复制</el-button>
          </div>
        </div>
      </template>
      <el-empty v-else-if="!loading" description="邀请信息加载失败" :image-size="70" />
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument } from '@element-plus/icons-vue'
import { useGroupStore } from '@/stores/group'

/**
 * 群邀请面板：展示后端 zxing 生成的二维码 + 可复制的邀请链接。
 *
 * 打开时懒拉一次邀请信息（后端首次会生成邀请码），之后同一会话内复用；
 * 二维码 base64 直接进 <img>，前端不需要二维码库。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  groupId: { type: [String, Number], default: null },
  groupName: { type: String, default: '群聊' }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const group = useGroupStore()
const invite = ref(null)
const loading = ref(false)

async function onOpen() {
  if (!props.groupId) return
  loading.value = true
  try {
    invite.value = await group.fetchInvite(props.groupId)
  } catch {
    invite.value = null
  } finally {
    loading.value = false
  }
}

async function copyLink() {
  const text = invite.value?.inviteLink
  if (!text) return
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      // 非安全上下文（http 部署 / file:// 打包）下 navigator.clipboard 不可用，退回到 execCommand
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('邀请链接已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择链接')
  }
}
</script>

<style scoped>
.gid__body {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.gid__qr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.gid__qr-img {
  width: 220px;
  height: 220px;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 8px;
  padding: 6px;
  background: #fff;
}

.gid__qr-hint {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.gid__link-row {
  width: 100%;
}

.gid__link-label {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.gid__link-box {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--im-bg-hover, #f5f7fa);
}

.gid__link {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--im-text);
  word-break: break-all;
}
</style>
