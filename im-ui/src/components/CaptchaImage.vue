<template>
  <div class="captcha-image" :title="src ? '点击刷新验证码' : '验证码加载失败，点击重试'" @click="emit('refresh')">
    <img v-if="src" :src="src" alt="图形验证码" class="captcha-image__img" />
    <el-icon v-else class="captcha-image__placeholder" :class="{ 'is-loading': loading }">
      <component :is="loading ? Loading : Refresh" />
    </el-icon>
  </div>
</template>

<script setup>
import { Loading, Refresh } from '@element-plus/icons-vue'

/**
 * 图形验证码图片。
 *
 * 后端返回的 image 是完整 Data URI（data:image/png;base64,...），可以直接放进 src，
 * 不需要再过 mediaUrl —— 它不是受控文件地址，没有鉴权问题。
 *
 * 单独抽成组件是因为「图没加载出来」这个状态需要占位与重试入口：
 * 只写一个 <img> 的话，加载失败会显示浏览器的裂图图标，用户不知道该点哪里。
 */
defineProps({
  src: { type: String, default: '' },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['refresh'])
</script>

<style scoped>
.captcha-image {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 116px;
  height: 40px;
  flex: none;
  border: 1px solid var(--im-border);
  border-radius: 4px;
  background: var(--im-bg);
  cursor: pointer;
  overflow: hidden;
}

.captcha-image:hover {
  border-color: var(--im-primary);
}

.captcha-image__img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.captcha-image__placeholder {
  font-size: 18px;
  color: var(--im-text-secondary);
}
</style>
