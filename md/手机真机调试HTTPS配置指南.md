# 手机真机调试 HTTPS 配置指南

开发期用手机浏览器连电脑上的 Vite dev server 做真机联调时，**远程控制会报
「当前环境不支持 WebCrypto」**。本文说明根因与一劳永逸的解法：给 dev server 上
内网自签 HTTPS，让手机以 `https://<电脑IP>:5173` 访问。

## 一、为什么 http + 局域网 IP 会失败

浏览器的敏感 API 只在**安全上下文（Secure Context）**里暴露，`crypto.subtle`
（WebCrypto，远程控制用来做 AES-256-GCM 解密的接口）就是其一。满足安全上下文的只有：

| 访问方式 | 安全上下文 | 说明 |
|---|---|---|
| `http://localhost:5173` / `127.0.0.1` | ✅ | localhost 是永久豁免，**电脑本机怎么访问都行** |
| `https://` 任意地址 | ✅ | 本文要达成的目标 |
| Electron 加载本地页面 | ✅ | 桌面端天然满足 |
| **`http://<局域网IP>:5173`** | ❌ | 手机用 IP 访问就是这种，`crypto.subtle` 直接是 `undefined` |

关键点：**跟手机无关，跟「http + 非 localhost」有关**。在电脑上用
`http://10.216.54.50:5173` 打开也会报一样的错。普通聊天、收发文件不碰 WebCrypto，
所以只有远程控制这一处会炸——这也解释了「其他功能正常、只有远程连不上」的现象。

报错是[刻意抛出]的而非静默丢帧（见 `im-ui/src/utils/remoteWs.js` 头部注释），
否则表现会是「连上了但一直黑屏」，更难排查：

```js
if (this.aesKeyB64) {
  if (!globalThis.crypto?.subtle) {
    throw new Error('当前环境不支持 WebCrypto（请用 localhost / HTTPS / Electron 打开）')
  }
  // ... importKey('raw', ..., 'AES-GCM')
}
```

## 二、解法总览

给 dev server 配一张内网自签证书，`npm run dev` 自动走 https。整套只涉及三处，
都已在仓库里配好，日常只需「生成一次证书 + 手机信任一次根 CA」：

```
im-ui/
├── scripts/gen-cert.cjs   生成根 CA + 服务器证书（纯 Node，不需要系统装 OpenSSL）
├── vite.config.js         检测到 certs/server.* 就自动启用 https，否则回落 http
└── certs/                 证书产物目录（已 gitignore，含私钥绝不入库）
    ├── ca.pem / ca.key        根 CA（CA:TRUE，10 年）——手机只需信任这一张
    └── server.pem / server.key 服务器证书，SAN 含本机全部 IP，由根 CA 签发
```

用「根 CA → 服务器证书」两级而非一张自签证书，是为了**手机装上根 CA 后地址栏无警告**，
不依赖 `thisisunsafe` 之类的临时绕过；服务器证书的 SAN（Subject Alternative Name）
覆盖 `localhost`、`127.0.0.1` 与本机所有非回环 IPv4，现代浏览器只认 SAN、忽略 CN。

## 三、电脑侧：生成证书并启动

```powershell
cd im-ui
npm install          # 首次会带上 devDependency node-forge
npm run gen:cert     # 生成 certs/（脚本用 node-forge 自签，纯 Node 免装 OpenSSL）
npm run dev          # 证书存在即自动 https
```

启动成功的标志是控制台那两行地址变成了 **https**：

```
  VITE v7.3.6  ready in ...
  ➜  Local:    https://localhost:5173/
  ➜  Network:  https://10.216.54.50:5173/     ← 手机用这个
```

`vite.config.js` 里的开关逻辑（有证书才 https，删掉证书就回到 http，
**不影响 Web 部署与 Electron 构建**）：

```js
function devHttps() {
  const key = join(configDir, 'certs', 'server.key')
  const cert = join(configDir, 'certs', 'server.pem')
  if (!existsSync(key) || !existsSync(cert)) return undefined   // 回落 http
  return { key: readFileSync(key), cert: readFileSync(cert) }
}
// server: { ..., https: devHttps() }
```

### 换网段 / 换 IP 时重新生成

USB 共享网络、热点、有线各自的 IP 不同，脚本会把**当时本机所有 IPv4** 都写进 SAN。
换了调试机或想追加某个地址，把它作为参数传进去即可（会覆盖重签）：

```powershell
npm run gen:cert -- 192.168.1.88      # 额外把 192.168.1.88 加进 SAN
```

> 服务器证书有效期贴近 iOS 对 TLS 证书的 825 天红线，明年此时若手机提示证书过期，
> 重跑一次 `npm run gen:cert` 换一张、并重新在手机上信任即可。

## 四、手机侧：信任根 CA（关键一步）

只做第三步、跳过本步，手机浏览器仍会拦「您的连接不是私密连接」——因为它拿到了
证书但不认识签发者。把 `im-ui/certs/ca.pem` 传到手机（微信文件传输助手 / 网盘 /
数据线均可），按系统装成受信任的 CA：

**Android**

1. 设置 → 安全（或「密码与安全」）→ 更多安全设置 → 加密与凭据
2. 「从存储设备安装」→ **CA 证书** → 选中传过去的 `ca.pem` → 安装
3. 部分机型需在文件管理器里把 `ca.pem` 重命名为 `ca.crt` 才可见

**iOS**

1. 用「邮件 / 隔空投送 / 文件 App」保存 `ca.pem`，点击后提示安装描述文件
2. 设置 → 通用 → VPN与设备管理 → 点该配置文件 → 安装
3. **再走一步（很多人卡在这）**：设置 → 通用 → 关于本机 → 证书信任设置 →
   打开「IM Dev Root CA」的全信任开关

装好后，手机浏览器访问 **`https://10.216.54.50:5173/`**（注意是 https、端口仍是 5173），
地址栏应为小锁图标，远程控制页面的 WebCrypto 报错随之消失。

## 五、WebSocket 无需改动

前端所有连接地址都按当前页面协议动态推导，切到 https 后自动变 wss，代理 target 仍是
后端 `localhost:8080`（Vite 在本地终结 TLS，再明文转发到后端，后端无需配证书）。
见 `im-ui/src/utils/env.js`：

```js
const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
return `${protocol}//${window.location.host}`
```

通用 `/ws`、远程控制的 `/ws/remote/agent` 与 `/ws/remote/control` 三条通道都走这套推导，
`vite.config.js` 里 `/api`、`/ws` 的 proxy 配置一个字都不用改。

## 六、常见问题

- **电脑本机也想复现手机问题**：用 `http://<本机IP>:5173`（而不是 localhost）打开即可，
  同样会因为没有 WebCrypto 而报「当前环境不支持」。
- **地址栏仍警告 / 报「证书不受信」**：根 CA 没装或没在「证书信任设置」里开启全信任
  （iOS 尤其容易漏第五步）；或访问的是新增 IP 但没重新 `gen:cert` 把它写进 SAN。
- **改了代码 dev server 没起来 / 端口被占**：`5173` 被旧进程占用时 Vite 因 `strictPort`
  直接报错退出，任务管理器结束残留的 `node.exe` 后重跑 `npm run dev`。
- **不想用 https，只是临时看一眼**：可用 `http://localhost:5173`（本机 localhost 豁免
  WebCrypto）或走 `adb reverse tcp:5173 tcp:5173` 让安卓手机以 localhost 访问；
  但这些都不适合「手机用 IP 真机联调远程控制」，HTTPS 是唯一通用解。
- **证书目录会被误提交吗**：不会，根 `.gitignore` 已忽略 `im-ui/certs/` 与 `*.key`、`*.pem`。

> 远程控制端到端加密与连接链路的完整说明，见同目录《远程控制Agent使用说明.md》与
> README「十二、远程控制」。
