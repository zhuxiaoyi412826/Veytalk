# im-website —— Veytalk 官网

独立前端工程（Vite + Vue 3），**纯静态展示站**，不进 Maven module 序列、无后端接口依赖。
端口序列与另两个前端对齐：im-ui `5173`、im-admin-ui `5273`、本模块 `5373`（均 `strictPort`），
preview 验货口 `4373`。

## 页面内容

单页营销站，自上而下五块：

1. **顶栏**：logo + 锚点导航（产品特性 / 下载 / 资源）+ GitHub 按钮
2. **Hero**：产品名、一句话定位、下载/文档两个 CTA
3. **产品特性**：8 张能力卡（消息 / 社交 / 文件 / 远程控制 / AI 面试 / 直播 / 隐私 / 后台）+ 技术栈标签
4. **下载**：三张卡对应三个规范文件名（见下），推荐位高亮 Windows 安装版
5. **资源 + 页脚**：GitHub 仓库、官方文档（仓库 README）、Releases 页；页脚另有「用户协议 / 隐私政策 / 服务条款」三个法定文本入口（见下）

文案与链接全部在 `src/App.vue` 顶部的常量 / 数组里集中维护，改内容不动模板。

## 快速开始

```powershell
cd im-website
npm install
npm run dev        # http://localhost:5373
```

## 下载产物投放

三个下载按钮的 href 固定为：

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
跳过并提示。注意：**升级版本时 `App.vue` 里的 `VERSION` 常量要与 `electron/package.json`
同步改**，否则按钮文件名与实际投放文件对不上。

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

## 构建与部署

```powershell
npm run build      # 产物在 dist/，纯静态
npm run preview    # 本地 4373 验货
```

`dist/` 可投放到任意静态服务器 / Nginx / GitHub Pages，无运行期依赖。
