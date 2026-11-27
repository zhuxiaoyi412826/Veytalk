# im-website —— Veytalk 官网

独立前端工程（Vite + Vue 3），**静态展示站**。不进 Maven module 序列；运行期只读两条匿名接口
（`/api/release/list` 与 `/api/release/history`）用来把下载卡对准后台真发的版本，
**拉不到就回落内置静态卡**——后端没起、迁移 SQL 没跑、跨域未配时页面照常可用，不白屏也不报错。
端口序列与另两个前端对齐：im-ui `5173`、im-admin-ui `5273`、本模块 `5373`（均 `strictPort`），
preview 验货口 `4373`。

## 页面内容

单页营销站，自上而下六块：

1. **顶栏**：logo + 锚点导航（产品特性 / 下载 / 历史版本 / 资源）+ GitHub 按钮
2. **Hero**：产品名、一句话定位、下载/文档两个 CTA，下方那行「当前版本 v… · 平台清单」取后台启用版本
3. **产品特性**：8 张能力卡（消息 / 社交 / 文件 / 远程控制 / AI 面试 / 直播 / 隐私 / 后台）+ 技术栈标签
4. **下载**：后台「客户端版本管理」里启用中的行驱动，每张卡带版本号、体积、发布日期与 MD5 校验值；
   拉不到接口时回落为下表三个规范文件名的静态卡（静态卡没有校验值，那一行就不出现，**不编一个假的上去**）
5. **历史版本**：每平台最近 5 条（含已停用），停用行只展示版本信息、标「已停用」而不给下载按钮；
   整块数据拉不到时**连标题一起不渲染**，不留一个加载失败的空坑
6. **资源 + 页脚**：GitHub 仓库、官方文档（仓库 README）、Releases 页；页脚另有「用户协议 / 隐私政策 / 服务条款」三个法定文本入口（见下）

文案与链接全部在 `src/App.vue` 顶部的常量 / 数组里集中维护，改内容不动模板。

## 快速开始

```powershell
cd im-website
npm install
npm run dev        # http://localhost:5373
```

## 下载产物投放

**首选路径是后台**：官网下载区的版本、文件名、体积、MD5 与下载地址都来自「客户端版本管理」
（后台上传安装包并启用即生效，无需重新构建官网）。下表是**拉不到接口时的静态回落**，
仅在未部署后端、或把官网当纯静态站挂 GitHub Pages 时才会用到：

| 按钮 | 文件 | 来源 |
| --- | --- | --- |
| Windows 安装版（推荐） | `/downloads/Veytalk-Setup-1.0.0.exe` | `electron/release/IM通讯 Setup 1.0.0.exe` |
| Windows 免安装版 | `/downloads/Veytalk-Windows-x64-portable.zip` | `electron/release/win-unpacked/` 整目录 zip |
| Android 版 | `/downloads/Veytalk-Android.apk` | 仓库内 `*.apk`（当前未产出，缺失时按钮 404） |

一键收集（需要 electron 侧已打包过）：

```powershell
npm run fetch:downloads
```

脚本为 `scripts/fetch-downloads.ps1`：版本号取自 `electron/package.json`，源产物缺失时逐项
跳过并提示。注意：**升级版本时 `App.vue` 里的 `STATIC_VERSION` 常量要与 `electron/package.json`
同步改**，否则静态回落卡的文件名与实际投放文件对不上。走后台发版时不用管它——
那个常量只是兜底展示，真实版本号在 `im_client_release` 表里。

大文件不进 git（根 `.gitignore` 已排除 `im-website/dist/`、downloads 下 zip/apk），
部署静态站点时把产物拷进 `public/downloads/`，或直接改用 GitHub Releases 直链。

## 法定文本入口

应用商店上架与各类审核都要求「隐私政策能从官网直达」，所以页脚固定摆三个链接。**官网不抄一份正文**：
三份文本的真源在数据库（后台「法定文本」那屏可起草发布），公示页是 im-ui 那个免登录即可打开的
`/legal/{code}`，这里只放链接。

链接基址由环境变量 `VITE_APP_BASE` 决定：

| 部署形态 | 配置 | 生成结果 |
| --- | --- | --- |
| 官网与应用同域（应用挂在域名根路径） | 不配 | `/legal/terms` |
| 官网与应用不同源 | `VITE_APP_BASE=https://im.example.com` | `https://im.example.com/legal/terms` |

两种写法都行：构建前的 `.env`（`im-website/.env.production`）或命令行临时变量
（`$env:VITE_APP_BASE='https://im.example.com'; npm run build`）。尾部斜杠会被抹掉，
配成 `https://im.example.com/` 也不会拼出 `//legal/terms` 那种地址。

## SEO 与站点域名

`index.html` 里的 meta / OG / Twitter / JSON-LD 结构化数据是静态的，随构建一起出去；
但 **canonical、og:url、robots.txt、sitemap.xml 四样必须要绝对域名**才有意义，所以仓库里不写死域名——
写错了搜索引擎会照着收录，比没有更难纠。

域名按三级优先级取，取到就停（实现见 `vite.config.js` 的 `resolveSiteOrigin`）：

| 优先级 | 来源 | 什么时候用 |
| --- | --- | --- |
| ① | 环境变量 `VITE_SITE_ORIGIN` | 显式覆盖：换域名试跑、本地临时改口径 |
| ② | `../Docker/.env` 的 `WEBSITE_DOMAIN` | **部署机上的真源**（`bootstrap.sh` 顶替 nginx 里 `__WEBSITE_DOMAIN__` 读的就是它） |
| ③ | `../Docker/.env.example` 的 `WEBSITE_DOMAIN` | 仓库里那份；本项目的 example 填的是真实域名而不是占位符，所以**CI 上不配任何 secrets/variables 也能出正确的 sitemap** |
| 都没有 | — | 只跳过这四样需要绝对 URL 的产物，其余 meta 照常，构建日志打一行 `[seo]` 说明缺到哪级 |

**所以日常什么都不用敲**：本地与 CI 直接 `npm run build` 就会从 ② 或 ③ 拿到域名。
构建日志会把**用的是哪一级**打出来（`[seo] 站点域名 https://…（来源：…）`），
因为「nginx 用 .env、sitemap 用另一套变量」各写一遍迟早会飘，飘了就出现
「canonical 指向一个根本没证书的域」这种收录事故。

值带不带 `http(s)://` 都行（只写域名会补成 `https://`），尾斜杠会被抹掉。
`robots.txt` 里屏蔽了 `/api/`：同域部署时该前缀下是 GB 级安装包，让爬虫拖走既费带宽又没意义。

CI 侧（`build-and-package.yml` 的 `vite build` 步）额外留了一个仓库级覆盖口：
Settings → Secrets and variables → Actions 新建 **Variables**（不是 Secrets）`WEBSITE_SITE_ORIGIN`，
不配则走回落链。改 nginx 那四个域名时记得这里是同一个值。

## 构建与部署

```powershell
npm run build      # 产物在 dist/，纯静态
npm run preview    # 本地 4373 验货
```

`dist/` 可投放到任意静态服务器 / Nginx / GitHub Pages，无运行期依赖。
