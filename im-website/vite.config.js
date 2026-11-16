import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 官网模块：展示型单页站，唯一的后端依赖是下载页那两条匿名接口
// （GET /api/release/list 与 /api/release/download/{id}，同源反代免 CORS）。
// 端口序列与现有前端对齐：im-ui 5173、im-admin-ui 5273、本模块 5373（均 strictPort）。
// preview 4373 对应 im-admin-ui 的 4273 惯例，用于 npm run build 后的本地验货。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: true,
    port: 5373,
    strictPort: true,
    // dev 下把 /api 代理到本地后端（与 im-ui/im-admin-ui 同一口径），
    // 否则下载页永远只能看到静态回落卡，验不了「后台启用什么访客就下载什么」这条链路
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
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
