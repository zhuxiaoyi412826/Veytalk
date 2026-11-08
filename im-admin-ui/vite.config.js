import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 代理错误处理：与 im-ui/vite.config.js 的 devProxy 同一口径（那边有完整的实测注料与原因）。
 *
 * 一句话版本：vite（本工程实测 7.3.7，dist/node/chunks/config.js 里 `opts.configure` 在
 * 它自己注册 error 监听之前执行）默认在上游连不上时只打一行 `http proxy error`、
 * 然后回一个 **空 body 的 500**，后台页面因此只能读到「服务异常（HTTP 500）」，
 * 分不清是后端没起、正在重启，还是接口真坏了。这里改成 502 + Result 形状的 JSON，
 * 把 target 与 errno 写进 message；`api/request.js` 会优先显示那句文案。
 * 两侧不要各自演化：改这一处请同步改 im-ui 那一份。
 */
function devProxy(target, hint) {
  return {
    target,
    changeOrigin: true,
    configure(proxy) {
      proxy.on('error', (err, _req, res) => {
        if (!res || typeof res.writeHead !== 'function' || res.headersSent || res.writableEnded) {
          return
        }
        res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
        res.end(
          JSON.stringify({
            code: 502,
            message: `后端未就绪：dev server 连不上 ${target}（${err.code || err.message}）。` +
              `若正在启动请稍后重试；否则${hint}。`,
            data: null,
            success: false,
            traceId: '',
            timestamp: Date.now()
          })
        )
      })
    }
  }
}

/**
 * 管理后台前端的构建配置。
 *
 * 与 im-ui 的关系是「同栈、不同进程、不同端口」：两个工程各自 npm run dev，
 * 端口刻意错开（5173 / 5273），因为后端 CORS 白名单是按 origin 写的，
 * 复用同一个端口只会得到「另一个工程起不来」。两侧的端口与白名单必须成对修改，
 * 这一点在 im-admin-ui/README.md 与 application.yml 的注释里都留了话。
 *
 * 不配 /ws 与 /hls 代理：后台是纯拉模型（看板与工单队列都靠刷新），
 * 既不建长连接也不播流，多两条代理只是多两个没人走的入口。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 与后端多模块和 im-ui 的观感保持一致：@ 指向 src
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 只监听本机：后台管理界面不该在局域网里对任何设备敞开，
    // 真要远程访问走内网域名 + 网关鉴权，而不是把 dev server 暴露出去。
    // 需要给同事临时看效果时显式加 --host 覆盖，不默认放开。
    host: '127.0.0.1',
    port: 5273,
    // 端口被占用时直接报错，静默顺延会让后端 CORS 白名单莫名对不上（403 Invalid CORS request）
    strictPort: true,
    proxy: {
      '/api': devProxy('http://localhost:8080', '请确认 ImApplication 已启动并监听 8080')
    }
  },
  preview: {
    // 预览端口与后端 CORS 白名单里的 4273 一一对应，改这里要同步改 application.yml
    host: '127.0.0.1',
    port: 4273,
    strictPort: true
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    // element-plus 全量引入后单包偏大，抬高阈值免得每次构建都刷一屏无意义提示
    chunkSizeWarningLimit: 1500
  }
})
