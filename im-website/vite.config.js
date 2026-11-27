import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * SEO 产物生成：canonical / og:url / robots.txt / sitemap.xml 都要**绝对域名**。
 *
 * <p>不在仓库里写死域名（写错了搜索引擎会照着收录，比没有更难纠），也不要求「每次构建手敲一个变量」：
 * 一个域名在两个地方各写一遍早晚会飘，而 nginx 用的是 Docker/.env、sitemap 用手工变量的话，
 * 就会出现「canonical 指向一个根本没证书的域名」这种收录事故。所以域名按下面的优先级取，取到就停。
 *
 * <p>三级都没有才跳过「需要绝对 URL」的那几件（canonical / og:url / sitemap），
 * 其余 meta 仍由 index.html 静态提供，并在构建日志里说明缺到哪一级。
 */
function seoPlugin() {
  let origin = ''
  let from = ''
  let outDir = ''
  return {
    name: 'im-website-seo',
    // 只在 build 生效：dev 下服务器从内存取模块，往磁盘写 dist 产物没有意义
    apply: 'build',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir)
      // 必须走 loadEnv 而不是 import.meta.env：后者在 vite.config.js 自身里只能拿到已注入进程的那部分
      const picked = resolveSiteOrigin(config.root, loadEnv(config.mode, config.root, '').VITE_SITE_ORIGIN)
      origin = picked.origin
      from = picked.from
    },
    transformIndexHtml(html) {
      if (!origin) {
        return html
      }
      // 注在 </head> 前而不是替换 index.html 里的占位注释：占位符没被替掉时会以 %XXX% 原样留在产物里，
      // 那种错很难看出来；这里「加不上就原文不动」，错了直接看不到标签，反而好查
      const tags = [
        `  <link rel="canonical" href="${origin}/" />`,
        `  <meta property="og:url" content="${origin}/" />`
      ].join('\n')
      return html.replace('</head>', `${tags}\n</head>`)
    },
    closeBundle() {
      if (!origin) {
        this.warn('[seo] 取不到站点域名（VITE_SITE_ORIGIN、../Docker/.env 与 .env.example 的 WEBSITE_DOMAIN 都没有），' 
          + '已跳过 canonical / og:url / robots.txt / sitemap.xml')
        return
      }
      // 把来源一起打出来：CI 日志里看见「用的是 .env.example」就该提醒自己去配真值，
      // 而不是等上线后发现站点地图指错了域
      this.warn(`[seo] 站点域名 ${origin}（来源：${from}）`)
      mkdirSync(outDir, { recursive: true })
      // 屏蔽 /api/：同域部署时这个前缀下是 GB 级安装包与匿名接口，让爬虫拉包既没意义又白吃带宽
      writeFileSync(resolve(outDir, 'robots.txt'),
        `User-agent: *\nAllow: /\nDisallow: /api/\n\nSitemap: ${origin}/sitemap.xml\n`, 'utf8')
      // lastmod 取构建日而不是内容日：单页站的文案随版本发布一起重建，两个日子实际是同一个
      writeFileSync(resolve(outDir, 'sitemap.xml'),
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        + '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        + `  <url><loc>${origin}/</loc><lastmod>${new Date().toISOString().slice(0, 10)}</lastmod></url>\n`
        + '</urlset>\n', 'utf8')
    }
  }
}

/** 归一域名：补协议、去尾斜杠，避开拼出 https://x// 这种地址；空值一律当「没配」。 */
function normalizeOrigin(raw) {
  const text = (raw || '').trim().replace(/\/+$/, '')
  if (!text) {
    return ''
  }
  return /^https?:\/\//.test(text) ? text : `https://${text}`
}

/**
 * 站点域名取法（按优先级）：
 *  ① {@code VITE_SITE_ORIGIN} —— 显式覆盖，换域名试跑或本地临时改口径时用；
 *  ② {@code ../Docker/.env} 的 {@code WEBSITE_DOMAIN} —— 部署机上的真源，
 *     bootstrap.sh 顶替 nginx 里 __WEBSITE_DOMAIN__ 占位符读的就是它，两边同源就不会各说各话；
 *  ③ {@code ../Docker/.env.example} 的同名键 —— 仓库里那份（本项目的 example 填的是真实域名而不是占位符），
 *     所以 CI 上不用任何 secrets/variables 也能产出正确的 sitemap。
 */
function resolveSiteOrigin(root, envOrigin) {
  const explicit = normalizeOrigin(envOrigin)
  if (explicit) {
    return { origin: explicit, from: 'VITE_SITE_ORIGIN' }
  }
  for (const name of ['Docker/.env', 'Docker/.env.example']) {
    const domain = readWebsiteDomain(resolve(root, '..', name))
    if (domain) {
      return { origin: normalizeOrigin(domain), from: `../${name} 的 WEBSITE_DOMAIN` }
    }
  }
  return { origin: '', from: '' }
}

/**
 * 从 .env 形态的文件里抠一个键值：跳过注释行、去掉可能带的引号。
 * 只认 WEBSITE_DOMAIN 这一个键、不做通用 dotenv 解析——多写的那点解析器只会带来解析差异这种新坑。
 */
function readWebsiteDomain(file) {
  if (!existsSync(file)) {
    return ''
  }
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const text = line.trim()
    if (text.startsWith('#') || !text.startsWith('WEBSITE_DOMAIN=')) {
      continue
    }
    return text.slice('WEBSITE_DOMAIN='.length).trim().replace(/^["']|["']$/g, '')
  }
  return ''
}

// 官网模块：展示型单页站，唯一的后端依赖是下载页那三条匿名接口
// （GET /api/release/list、/api/release/history 与 /api/release/download/{id}，同源反代免 CORS）。
// 端口序列与现有前端对齐：im-ui 5173、im-admin-ui 5273、本模块 5373（均 strictPort）。
// preview 4373 对应 im-admin-ui 的 4273 惯例，用于 npm run build 后的本地验货。
export default defineConfig({
  plugins: [vue(), seoPlugin()],
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
