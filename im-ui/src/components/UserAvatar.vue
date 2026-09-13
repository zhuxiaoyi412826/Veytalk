<template>
  <div class="user-avatar" :style="{ width: size + 'px', height: size + 'px' }">
    <el-avatar :size="size" :src="resolved" :style="fallbackStyle">
      {{ initial }}
    </el-avatar>
    <span
      v-if="online !== null && online !== undefined"
      class="im-online-dot"
      :class="{ 'im-offline-dot': !online }"
    ></span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { mediaUrl } from '@/utils/media'
import { initialOf } from '@/utils/format'

/**
 * 用户头像。
 *
 * 三件事集中在这里，避免每个列表各写一遍：
 * 1. 后端存的是受控地址，要经 mediaUrl 换成带鉴权取回的 blob 地址才能显示；
 * 2. 种子数据与新注册用户的 avatar 都是 NULL，必须回退到昵称首字符，否则是一片空白方块；
 * 3. 在线状态的绿点定位需要外层是 relative 容器，el-avatar 自身撑不起来。
 */
const props = defineProps({
  /** 后端返回的原始地址，可能是受控地址、完整 http 地址或空 */
  src: { type: String, default: '' },
  /** 昵称或备注，用于生成占位首字符 */
  name: { type: String, default: '' },
  size: { type: Number, default: 40 },
  /** null / undefined 表示不显示在线状态（例如自己的头像、群头像） */
  online: { type: Boolean, default: null }
})

const resolved = computed(() => mediaUrl(props.src))
const initial = computed(() => initialOf(props.name))

/**
 * 占位底色按名字散列，让不同用户在列表里有稳定的颜色区分。
 * 用名字而不是 ID 做种子：备注名改了颜色会变，但同一个人不会在一次会话里变色。
 */
const fallbackStyle = computed(() => {
  const palette = ['#5b8ff9', '#61c0bf', '#f6903d', '#e8684a', '#9270ca', '#5ad8a6', '#f08bb4']
  const text = props.name || '?'
  let hash = 0
  for (let i = 0; i < text.length; i++) {
    hash = (hash * 31 + text.charCodeAt(i)) % 100000
  }
  return { background: palette[hash % palette.length], color: '#fff', fontSize: Math.round(props.size * 0.4) + 'px' }
})
</script>

<style scoped>
.user-avatar {
  position: relative;
  flex: none;
}
</style>
