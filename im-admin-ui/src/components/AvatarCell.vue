<template>
  <el-avatar v-if="src" :size="size" :src="src" />
  <el-avatar v-else :size="size" :style="fallbackStyle">{{ initial }}</el-avatar>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { fetchAvatar } from '@/api/request'

/**
 * 头像单元格：把「头像地址要先换成 blob 才能显示」这件事收在一个组件里。
 *
 * 为什么不直接 {@code <el-avatar :src="row.avatar">}：后端 /api/file/** 认的是<b>请求头里的
 * token</b>，img 标签带不了自定义头，直接塞地址只会得到一张裂图（im-ui 那边同样的问题，
 * 它用的是带 IndexedDB 的 mediaUrl，后台没必要那一层）。
 *
 * 取不到图时回退成昵称首字：后台列表一屏几十行，全裂成小图标方块比缺一张头像更难读。
 */
const props = defineProps({
  url: { type: String, default: '' },
  name: { type: String, default: '' },
  size: { type: [Number, String], default: 32 }
})

const src = ref('')

const initial = computed(() => (props.name || '?').trim().charAt(0).toUpperCase())

const fallbackStyle = computed(() => {
  // 首字母底色按昵称散列，同一用户在不同页面拿到同一个颜色，肉眼能靠颜色粗认人
  const palette = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#9254de']
  let hash = 0
  for (const ch of props.name || '?') {
    hash = (hash * 31 + ch.codePointAt(0)) % 997
  }
  return { background: palette[hash % palette.length], color: '#fff' }
})

watch(
  () => props.url,
  async (url) => {
    if (!url) {
      src.value = ''
      return
    }
    src.value = ''
    // 同一个地址在列表里会重复出现（同一个人被举报多次），缓存与合流都在 fetchAvatar 里
    src.value = await fetchAvatar(url)
  },
  { immediate: true }
)
</script>
