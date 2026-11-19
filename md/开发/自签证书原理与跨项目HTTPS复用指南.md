# 自签证书原理与跨项目 HTTPS 复用指南

> 本文解答三件事：`im-ui/certs/` 里 4 个文件各是什么、这套两级自签证书如何把 HTTP 变成 HTTPS、
> 以及如何把同一套做法原样搬到任何新项目。
> 手机侧安装/信任 CA 的具体步骤见 [手机真机调试HTTPS配置指南.md](./手机真机调试HTTPS配置指南.md) 第四节；
> SAN 与 DHCP 换 IP 的坑见 [已踩坑.md](./已踩坑.md) 第 5 条。

## 0. 四个文件一张表看懂

| 文件 | 是什么 | 能否离开本机 | 干什么用 |
|---|---|---|---|
| `ca.pem` | **根 CA 证书**（公钥侧），自签（issuer = 自己），`CA:TRUE`，10 年期 | ✅ 可以，且**必须**分发 | 信任锚。手机 / 电脑只需安装并信任这一张 |
| `ca.key` | **根 CA 私钥** | ❌ 绝不 | 只在签发服务器证书时用一次；泄露 = 别人能以你的根 CA 名义伪造任意"受信"证书 |
| `server.pem` | **服务器证书**（公钥侧，叶子），由 `ca.key` 签发，820 天 | ✅ 可以 | TLS 握手时由服务器出示给浏览器的那张 |
| `server.key` | **服务器私钥** | ❌ 绝不 | 服务器在 TLS 握手中完成密钥交换 / 签名用 |

四个文件都是 PEM 格式（Base64 文本，`-----BEGIN ...-----` 头尾），记事本可直接打开。

**配对与信任链**（记住这两组关系就记住了全部）：

- `ca.pem` ↔ `ca.key` 是同一把 RSA 密钥对的公私两半（自签）；
- `server.pem` ↔ `server.key` 是另一把密钥对的公私两半；
- `server.pem` 上的**签名是 `ca.key` 盖的**，所以持有 `ca.pem` 的一方就能验证 `server.pem` 可信：

```
  手机/电脑信任库                    TLS 握手时出示
┌──────────────┐   验签名+验SAN+验有效期   ┌──────────────────┐
│  ca.pem 根CA  │ ─────────────────────→ │ server.pem 服务器证书 │ ←→ server.key
│ （信任锚）     │                        │ SAN: localhost/各IP │
└──────────────┘                        └──────────────────┘
      ↑ 设备只装这一次                        ↑ 换IP/到期可随时重签
      ca.key 只留在签发机，签完即封存
```

口诀：**pem 是公的（证书、可分发），key 是私的（私钥、绝不出门）；ca 是根，server 是叶。**

## 1. 这套证书是怎么实现 HTTPS 的

### 1.1 生成：两级结构（[im-ui/scripts/gen-cert.cjs](../im-ui/scripts/gen-cert.cjs)，全文见附录 A）

用 `node-forge` 手工构造两张证书（不用 `selfsigned` 库：它对 extensions 透传不可靠，签出来 **SAN 会丢**，这是本项目实测过的坑）：

- **根 CA**：`basicConstraints CA:TRUE` + `keyUsage keyCertSign`，自签，10 年期；
- **服务器证书**：
  - `subjectAltName` = `localhost` + `127.0.0.1` + 本机全部非回环 IPv4（还可命令行追加）；
  - `extKeyUsage serverAuth`（表明用途是服务器认证）；
  - 820 天有效期（贴 iOS/Chrome 对 TLS 证书 825 天红线之下）；
  - `notBefore` 回拨 1 小时，抵消手机与电脑之间的时钟偏差；
  - 由 `ca.key` 签名。

**默认复用已有根 CA、只重签服务器证书**（`ca.pem/ca.key` 一字不改），于是手机里已装的信任继续有效；
只有显式传 `--fresh-ca` 才连根重签（所有设备必须重装 `ca.pem`）。脚本结束会打印
`根 CA：复用已有（…无需重装）` 或 `根 CA：重新签发（…都要重新安装）`，**看这行就知道要不要动手机**。

### 1.2 服务端：握手时出示 server.pem

证书本身不提供 HTTPS，是**服务器的 TLS 层在握手时把 `server.pem` 发给浏览器**。本项目 Vite 的接法
（[vite.config.js](../im-ui/vite.config.js) `devHttps()`）：启动时 `readFileSync` 读入 `server.key/server.pem`
交给 `server.https`，Node 的 TLS 层据此应答握手。其他服务器（Express / 原生 https / Nginx）的等价接法见 §2.2。

注意：pem/key 是**启动时读进内存**的，重签证书后不重启服务 = 没换。

### 1.3 客户端：用 ca.pem 验三件事

浏览器拿到 `server.pem` 后，用信任库里的 `ca.pem` 验：

1. **链签名**：server.pem 的签名能否被 ca.pem 的公钥验证通过；
2. **SAN 匹配**：访问的主机名/IP 是否在 server.pem 的 SAN 里（2012 年后浏览器**只看 SAN、不看 CN**）；
3. **有效期**：当前时间是否落在 notBefore/notAfter 之间。

三项全过 = 地址栏锁标志、无警告；任一不过 = 「不受信」或「主机名不匹配」。
且失败后果因连接类型而异：fetch/XHR 能点「继续访问」绕过，**wss 与媒体加载会被静默拒绝**
（直播"网络异常"、远控一闪即断的根因，见 [已踩坑.md](./已踩坑.md) 第 5 条）。

### 1.4 为什么做两级，而不是一张自签证书

| | 单张自签服务器证书 | 两级（根 CA + 服务器证书） |
|---|---|---|
| 设备要信任什么 | 信任这张服务器证书本身 | 只信任根 CA 一次 |
| 换 IP / 到期重签后 | **所有设备重装**新证书 | 手机零操作（根没变，链仍成立） |
| 与现实 CA 模型 | 不像 | 同构（根 CA ≈ Let's Encrypt 根，server.pem ≈ 域名证书） |

换网段是开发期高频事件，两级结构把"重签"的成本从"全设备重装"降为"跑一次脚本 + 重启服务"，这是它存在的唯一理由。

## 2. 在别的项目复用这套 HTTPS

### 2.1 搬生成脚本（自包含，一步到位）

1. 把 [im-ui/scripts/gen-cert.cjs](../im-ui/scripts/gen-cert.cjs) **整份拷贝**到新项目（如 `scripts/gen-cert.cjs`）——它只依赖 `node-forge` 与 Node 内置模块，无其他耦合；
2. `npm i -D node-forge`；
3. `package.json` 加 `"gen:cert": "node scripts/gen-cert.cjs"`；
4. `.gitignore` 加 `certs/`（内含私钥，**绝不入库**）；
5. 跑 `npm run gen:cert`，产物落在 `certs/` 四件套。

常用变体：

```bash
npm run gen:cert                        # 重签服务器证书（复用根 CA，客户端零操作）
node scripts/gen-cert.cjs 192.168.1.8   # 额外追加 IP/域名进 SAN（给别的调试机）
node scripts/gen-cert.cjs --fresh-ca    # 连根 CA 一起重签（所有设备重装 ca.pem，慎用）
```

### 2.2 服务端接入四种常见形态

**A. Vite 前端 dev server（本项目同款）**

```js
// vite.config.js
import { readFileSync, existsSync } from 'node:fs'

function devHttps() {
  const key = 'certs/server.key'
  const cert = 'certs/server.pem'
  if (!existsSync(key) || !existsSync(cert)) return undefined  // 没证书回落 http
  return { key: readFileSync(key), cert: readFileSync(cert) }
}

export default { server: { host: '0.0.0.0', port: 5173, https: devHttps() } }
```

**B. Node 原生 https**

```js
const https = require('https')
const fs = require('fs')

https.createServer(
  { key: fs.readFileSync('certs/server.key'), cert: fs.readFileSync('certs/server.pem') },
  (req, res) => res.end('ok')
).listen(5173, '0.0.0.0')
```

**C. Express**

```js
const https = require('https')
const fs = require('fs')
const app = require('express')()
// ... app.get(...) 路由照旧
https.createServer(
  { key: fs.readFileSync('certs/server.key'), cert: fs.readFileSync('certs/server.pem') },
  app
).listen(5173)
```

**D. Nginx 反代**

```nginx
server {
    listen 5173 ssl;
    ssl_certificate     /path/to/certs/server.pem;
    ssl_certificate_key /path/to/certs/server.key;
    location / { proxy_pass http://127.0.0.1:8080; }
}
```

自签 + 客户端已装根 CA 的场景，`server.pem` 只含叶子证书也够（客户端自己持有根）。
面向公众的真实部署应使用受信 CA 证书并配 fullchain，**自签证书只用于开发/内网**。

### 2.3 客户端信任与验证

- **Windows**：双击 `ca.pem` → 安装证书 → 本地计算机 → 「受信任的根证书颁发机构」；
- **Android / iOS**：见 [手机真机调试HTTPS配置指南.md](./手机真机调试HTTPS配置指南.md) 第四节
  （iOS 装完必须再去 设置 → 通用 → 关于本机 → 证书信任设置 开「完全信任」，否则 wss 仍被静默拒绝）；
- **curl**：`curl --cacert certs/ca.pem https://<IP>:5173/`（`-k` 是跳过验证，只供临时排查）；
- **Node 客户端**：`set NODE_EXTRA_CA_CERTS=certs\ca.pem`（PowerShell：`$env:NODE_EXTRA_CA_CERTS="certs\ca.pem"`）再启动；
- **验证端口实际下发的是哪张证书**（排查"重签没生效"利器，附录 B）。

### 2.4 日常运维三条铁律

1. **重签必重启**：pem/key 启动时读进内存，不重启服务下发的还是旧证书；
2. **换 IP 只跑 `npm run gen:cert`**：根 CA 复用，客户端零操作；确认输出是「根 CA：复用已有」；
3. **看到「根 CA：重新签发」才动客户端**：此时所有装过旧 ca.pem 的设备删旧装新（iOS 重开完全信任）。

## 3. 坑清单（本项目全部实测踩过）

- **SAN 漏 IP** → 主机名不匹配；浏览器只看 SAN 不看 CN，CN 写对也没用；
- **重签不重启** → 内存里还是旧证书，现象完全像"没重签"；
- **用 `selfsigned` 库** → SAN/extensions 丢失，必须 node-forge 手工构造；
- **协议错配**：https-only 端口用 `http://` 访问（或反之）→ TLS 握手直接失败，表象是莫名其妙的"网络异常/打不开"，先确认服务端当前模式；
- **部分国产手机浏览器不认用户安装的 CA**，或其原生播放器不继承页面证书例外 → 自签 https 下视频/弹幕静默失败；内网联调可退回纯 http（本项目 `npm run dev:lan` 的思路）；
- **iOS 装 ca.pem 未开完全信任** → 页面/fetch 正常、wss 静默拒绝，后端不留一行日志；
- **私钥入库** → `ca.key` 泄露等于把"受信根"送人，`certs/` 必须在 `.gitignore`。

## 附录 A：gen-cert.cjs 全文快照（2026-10）

> 权威版本以 [im-ui/scripts/gen-cert.cjs](../im-ui/scripts/gen-cert.cjs) 为准，此处为便于整份拷贝的快照。

```js
/**
 * 开发期自签 HTTPS 证书生成脚本（npm run gen:cert）。
 *
 * 产物（certs/，已 gitignore，含私钥绝不入库）：
 *  - ca.pem / ca.key          自签根 CA（CA:TRUE，10 年）——手机只需要信任这一张
 *  - server.pem / server.key  服务器证书（820 天）：SAN 含 localhost、127.0.0.1
 *                             与本机全部非回环 IPv4，由根 CA 签发。
 *
 * 用法：
 *   npm run gen:cert                  # 重新签发服务器证书（根 CA 存在则复用）
 *   node scripts/gen-cert.cjs 192.168.1.8   # 额外追加 IP/域名到 SAN
 *   node scripts/gen-cert.cjs --fresh-ca    # 连根 CA 一起重签（ca.pem 作废，设备重装）
 */
const fs = require('fs')
const os = require('os')
const path = require('path')
const forge = require('node-forge')

const CERT_DIR = path.join(__dirname, '..', 'certs')

const args = process.argv.slice(2)
const extraAlts = args.filter((a) => !a.startsWith('--'))
const freshCa = args.includes('--fresh-ca')

// 本机全部非回环 IPv4：USB 共享网络 / 热点 / 有线各自的网段都一并写进 SAN
const localIPv4s = Object.values(os.networkInterfaces())
  .flat()
  .filter((i) => i && i.family === 'IPv4' && !i.address.startsWith('127.'))
  .map((i) => i.address)

const alts = [...new Set(['localhost', '127.0.0.1', ...localIPv4s, ...extraAlts])]
const isIPv4 = (s) => /^\d+\.\d+\.\d+\.\d+$/.test(s)

// 回拨 1 小时生效，抵消手机与电脑间的时钟偏差
const notBefore = new Date(Date.now() - 60 * 60 * 1000)
const daysLater = (days) => new Date(notBefore.getTime() + days * 86400000)

function makeKey() {
  return forge.pki.rsa.generateKeyPair({ bits: 2048, e: 0x10001 })
}

const caSubjectAttrs = [{ name: 'commonName', value: 'IM Dev Root CA' }]
const serverSubjectAttrs = [{ name: 'commonName', value: 'im-dev-server' }]

/** 复用已有根 CA：换网段要重签的是服务器证书，根 CA 不该跟着换 */
function loadExistingCa() {
  if (freshCa) return null
  const certPath = path.join(CERT_DIR, 'ca.pem')
  const keyPath = path.join(CERT_DIR, 'ca.key')
  if (!fs.existsSync(certPath) || !fs.existsSync(keyPath)) return null
  try {
    const cert = forge.pki.certificateFromPem(fs.readFileSync(certPath, 'utf8'))
    const privateKey = forge.pki.privateKeyFromPem(fs.readFileSync(keyPath, 'utf8'))
    if (cert.validity.notAfter.getTime() <= Date.now()) {
      console.log('已有根 CA 已过期，重新签发（手机 / 电脑必须重装 ca.pem）')
      return null
    }
    return { cert, privateKey }
  } catch (e) {
    console.log('已有根 CA 解析失败，重新签发：', e.message)
    return null
  }
}

// ---- 1) 根 CA：CA:TRUE + 可签证书 ----
const existingCa = loadExistingCa()
const ca = existingCa || (() => {
  const { privateKey, publicKey } = makeKey()
  const cert = forge.pki.createCertificate()
  cert.publicKey = publicKey
  cert.serialNumber = Date.now().toString(16)
  cert.validity.notBefore = notBefore
  cert.validity.notAfter = daysLater(3650)
  cert.setSubject(caSubjectAttrs)
  cert.setIssuer(caSubjectAttrs) // 自签：签发者就是自己
  cert.setExtensions([
    { name: 'basicConstraints', cA: true },
    { name: 'keyUsage', keyCertSign: true, cRLSign: true, digitalSignature: true }
  ])
  cert.sign(privateKey, forge.md.sha256.create())
  return { privateKey, cert }
})()
const caKey = ca.privateKey

// ---- 2) 服务器证书：SAN 齐活、serverAuth 用途、由根 CA 签发 ----
const server = (() => {
  const { privateKey, publicKey } = makeKey()
  const cert = forge.pki.createCertificate()
  cert.publicKey = publicKey
  cert.serialNumber = Date.now().toString(16) + 'a1'
  cert.validity.notBefore = notBefore
  cert.validity.notAfter = daysLater(820)
  cert.setSubject(serverSubjectAttrs)
  cert.setIssuer(caSubjectAttrs)
  cert.setExtensions([
    { name: 'basicConstraints', cA: false },
    { name: 'keyUsage', digitalSignature: true, keyEncipherment: true },
    { name: 'extKeyUsage', serverAuth: true },
    {
      name: 'subjectAltName',
      altNames: alts.map((a) => (isIPv4(a) ? { type: 7, ip: a } : { type: 2, value: a }))
    }
  ])
  cert.sign(caKey, forge.md.sha256.create())
  return { privateKey, cert }
})()

fs.mkdirSync(CERT_DIR, { recursive: true })
const toPem = (cert) => forge.pki.certificateToPem(cert)
const keyToPem = (key) => forge.pki.privateKeyToPem(key)
const written = {
  'server.pem': toPem(server.cert),
  'server.key': keyToPem(server.privateKey)
}
// 复用根 CA 时不回写 ca.*：文件内容一字不改，手机里那张信任证书才继续有效
if (!existingCa) {
  written['ca.pem'] = toPem(ca.cert)
  written['ca.key'] = keyToPem(ca.privateKey)
}
for (const [name, content] of Object.entries(written)) {
  fs.writeFileSync(path.join(CERT_DIR, name), content)
}

console.log('证书生成完成 →', CERT_DIR)
console.log(existingCa ? '根 CA：复用已有（手机 / 电脑无需重装 ca.pem）' : '根 CA：重新签发（所有设备都要重新安装 ca.pem）')
console.log('SAN 覆盖：', alts.join(', '))
console.log('下一步：重启 dev server 才会读到新证书（vite 在启动时把 pem/key 读进内存）')
```

## 附录 B：TLS 探针——确认端口实际下发的证书

排查"重签了但没生效 / 下发的还是旧证书"时，直接问运行中的服务器要它正在用的证书：

```bash
node -e "const tls=require('tls');const s=tls.connect(5173,'127.0.0.1',{rejectUnauthorized:false},()=>{const c=s.getPeerCertificate();console.log('SAN:',c.subjectaltname);console.log('issuer:',c.issuer.CN);console.log('valid:',c.valid_from,'->',c.valid_to);s.end()});s.on('error',e=>console.log('ERR',e.message))"
```

输出里的 SAN 与有效期就是**客户端此刻真正看到的那张**；与 `certs/server.pem` 文件对不上 = 服务没重启。
