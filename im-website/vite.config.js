import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 官网模块：纯展示型单页站，无后端接口依赖。
// 端口序列与现有前端对齐：im-ui 5173、im-admin-ui 5273、本模块 5373（均 strictPort）。
// preview 4373 对应 im-admin-ui 的 4273 惯例，用于 npm run build 后的本地验货。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: true,
    port: 5373,
    strictPort: true
  },
  preview: {
    host: true,
    port: 4373,
    strictPort: true
  },
  build: {
    outDir: 'dist',
    // 官网静态资源直出，不需要 hash 以外的处理；下载大文件走 public 原样拷贝
    assetsInlineLimit: 4096
  }
})
