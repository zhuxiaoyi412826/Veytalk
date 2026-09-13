<template>
  <div v-if="pageCount > 1" class="pager">
    <el-pagination
      :current-page="current"
      :page-size="size"
      :total="total"
      :pager-count="5"
      layout="prev, pager, next"
      background
      small
      @current-change="onChange"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 列表分页条。
 *
 * 收到的申请与我发出的申请两处用的是同一套翻页逻辑，抽出来避免两份 el-pagination
 * 各配一遍 layout 与样式。只有一页时整条不渲染 —— 一个孤零零的「1」占着高度没有意义。
 */
const props = defineProps({
  total: { type: Number, default: 0 },
  current: { type: Number, default: 1 },
  size: { type: Number, default: 10 }
})

const emit = defineEmits(['update:current', 'change'])

const pageCount = computed(() => Math.max(1, Math.ceil((props.total || 0) / (props.size || 1))))

function onChange(page) {
  emit('update:current', page)
  // 父组件靠这个事件去拉数据。v-model 只改本地状态，不发请求，
  // 少一个事件的话每个调用方都得再 watch 一次 current
  emit('change', page)
}
</script>

<style scoped>
.pager {
  display: flex;
  justify-content: center;
  flex: none;
  padding: 8px 0;
}
</style>
