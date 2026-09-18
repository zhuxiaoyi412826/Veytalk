import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 前端独立运行，不参与 Maven 构建。
 *
 * 开发期所有后端流量都走这里的 proxy 转发到 8080，好处是浏览器看到的
 * 始终是同源请求（不管是 localhost:5173 还是局域网 IP:5173），不必在 axios
 * 里拼绝对地址，WebSocket 地址也由 socket.js 按 location.host 动态推导。
 */
export default defineConfig(({ mode }) => ({
  // Electron 桌面端从 file:// 加载，必须用相对路径 ./ 才能定位到 assets；Web 部署仍用根路径 /
  // （配合 history 路由，避免深层刷新时相对路径错乱）。Electron 打包走 `vite build --mode electron`
  // （见 package.json 的 build:electron），与 Web 构建互不影响。
  base: mode === 'electron' ? './' : '/',
  plugins: [vue()],
  resolve: {
    alias: {
      // 与后端多模块的观感保持一致：@ 指向 src
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // TODO: 视频压缩功能启用后需要取消以下注释（ffmpeg.wasm 依赖 SharedArrayBuffer 和 Web Worker）
  // worker: {
  //   format: 'es'
  // },
  // optimizeDeps: {
  //   exclude: ['@ffmpeg/ffmpeg', '@ffmpeg/util']
  // },
  server: {
    // 监听 0.0.0.0（所有网卡）而不只是 localhost：手机、局域网其他机器、
    // 内网穿透都能用本机在该网段的 IP / 域名打开页面。代价是同网段的
    // 其他机器也能访问这个 dev server，只在可信网络下这么跑；启动后控制台
    // 会多打一行 Network 地址，那就是给手机 / 外部访问用的。
    host: '0.0.0.0',
    port: 5173,
    // 端口被占用时直接报错，而不是自动顺延到 5174。
    // README 与后端联调说明里写死的是 5173，静默换端口会让人以为文档过期了。
    strictPort: true,
    // 本地开发 / 内网穿透：放行任意 Host 头。Vite 5+ 默认会拦截不在白名单
    // 里的域名（返回 "Blocked request. This host is not allowed"），true 表示全部放行，
    // 任何 IP / 域名 / 穿透工具都能直接访问，无需再维护白名单。
    // 注意：仅限本地开发使用，生产环境应收紧为具体域名。
    allowedHosts: true,
    // TODO: 视频压缩功能启用后需要取消以下注释（ffmpeg.wasm 依赖 SharedArrayBuffer）
    // headers: {
    //   'Cross-Origin-Opener-Policy': 'same-origin',
    //   'Cross-Origin-Embedder-Policy': 'credentialless'
    // },
    proxy: {
      '/api': {
        // target 必须是 localhost：proxy 请求是 vite 进程自己发的，永远在电脑本机，
        // 与浏览器用什么地址访问无关，改成局域网 IP 反而会绕一圈网卡。
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // WebSocket 握手同样经代理转发。ws:true 是必需的，
      // 否则 vite 只代理普通 HTTP 请求，/ws 的 Upgrade 会被当成 404。
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // element-plus 全量引入后单包偏大，抬高告警阈值避免每次构建都刷一屏无意义的提示
    chunkSizeWarningLimit: 1500
  }
}))
