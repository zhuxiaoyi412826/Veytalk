# IM 即时通讯系统

基于 **Spring Boot 4 + JDK 21** 的多模块即时通讯后端，配套 **Vue 3** 前端。
后端 11 个 Maven 模块最终打成**一个可执行 jar**（`im-bootstrap/target/im-server.jar`）；
被控端 Agent（`im-remote-agent`）是独立 fat jar，跑在被控机器上，不打进后端；
前端是独立工程，通过 Vite 代理与后端通信，不参与 Maven 构建。

功能覆盖：注册登录（图形/短信验证码）、JWT 鉴权与 RBAC 权限、好友申请与管理（含黑名单：多处拉黑入口 + 集中管理）、
单聊/群聊会话、消息收发（幂等/撤回/已读回执/离线消息/历史分页）、群组权限与禁言、
文件上传（MinIO / 本地双实现，秒传 / 断点续传 / 大文件分片，单文件上限 2GB；聊天附件可选 / 可拖 / 可粘，
三者都先进「待发送托盘」再手动发送）、
WebSocket 实时推送（心跳/重连/多端踢下线）、
**远程桌面控制**（服务端中继 + AES-GCM 端到端加密 + 识别码跨账号，见「十二」）、
**AI 面试官**（本地知识库 BM25 RAG + SSE 流式）与**全网检索**（消息搜索框的「网络」分组，见「十三」）；
前端另可用 **Electron 打包为 Windows 桌面客户端**（安装包内置被控端 Agent 与裁剪 JRE）。

> 架构与请求链路的完整图集（三层架构、HTTP/WebSocket 链路、登录鉴权、文件上传下载）见 [`md/架构与请求链路图.md`](md/架构与请求链路图.md)。
> 多端入口（手机浏览器 / 安卓 APK / PC 浏览器 / Electron 桌面）与各级缓存的全链路图集
> （端侧 IndexedDB、本地 SQLite、服务端 L1 Caffeine + L2 Redis + L3 MySQL、从点击到数据接收）
> 见 [`md/多端入口与缓存全链路图.md`](md/多端入口与缓存全链路图.md)。

---

## 一、技术栈与版本

版本全部锁定在根 `pom.xml` 的 `<properties>` 里，升级只需改一处。

| 类别 | 组件 | 版本 | 说明 |
|---|---|---|---|
| 运行时 | JDK | 21 | 实测路径 `D:\software\jdk\jdk21` |
| 框架 | Spring Boot | 4.0.8 | knife4j-next 基线 4.0.7、mybatis-plus 基线 4.0.1，取 4.0.8 兼容性最好 |
| 持久层 | MyBatis-Plus | 3.5.17 | 用 `mybatis-plus-spring-boot4-starter`；分页插件 3.5.9+ 已拆分，需显式引入 |
| 数据库 | MySQL | 8.x | 库名默认 `im_db` |
| 缓存 | Redis | 5+ | Sa-Token 会话、验证码、在线状态、消息序号、全网检索结果 |
| 鉴权 | Sa-Token | 1.46.0 | `sa-token-spring-boot4-starter` + `sa-token-jwt` |
| 接口文档 | Knife4j | 5.6.0 | 官方停更于 4.5.0，Boot 4 用维护分支 `knife4j-next` |
| 对象存储 | MinIO | 8.6.0 | 可选，默认走本地磁盘 |
| HTTP 客户端 | OkHttp | 5.1.0 | **必须显式声明**，见下方「已知坑」 |
| JWT 工具 | Hutool | 5.8.47 | WS 连接票据、文件访问票据 |
| 密码加密 | spring-security-crypto | 7.0.7 | 只取加密算法，不引入整个 Spring Security |
| 加密算法 | BouncyCastle | 1.86 | Argon2id 依赖 |
| 前端 | Vue / Vite | 3.5.42 / 7.3.6 | |
| 前端 | Element Plus / Pinia / Vue Router | 2.14.5 / 3.0.4 / 4.6.4 | |

---

## 二、模块结构与依赖关系

```
im-parent (pom)
├── im-common        公共层：Result/异常/MP 配置/Sa-Token 拦截/Knife4j/日志 AOP/MDC
│                    /加密工具/JWT 工具/常量/过滤器/SPI 契约
├── im-user          用户中心：注册、登录、JWT 鉴权、资料、在线状态、角色权限
├── im-friend        好友关系：申请、同意/拒绝、列表、备注、分组、删除、拉黑
├── im-conversation  会话管理：会话列表、未读计数、置顶、免打扰、排序、单聊/群聊
├── im-message       消息核心：收发、状态流转、撤回、删除、离线消息、幂等、历史分页
├── im-group         群组管理：建群、改群、成员管理、群主/管理员权限、禁言、@提醒、解散
├── im-file          文件存储：图片/文件/语音上传、访问鉴权、元数据保存（MinIO + 本地双实现）
├── im-websocket     实时推送：连接管理、心跳、断线重连、消息路由分发、多端踢下线
├── im-ai            AI 能力：面试官（知识库 BM25 检索 RAG、DashScope SSE 客户端、会话与限流）+ 全网检索
│                    （抓取搜索引擎结果页、Redis 结果缓存）
├── im-remote        远程控制服务端：会话状态机、Agent/控制端双 WS 中继、审计与限流
├── im-bootstrap     启动模块：唯一的 main 类 + application.yml，repackage 成单 jar
├── im-remote-agent  被控端 Agent：独立 fat jar（纯 JDK 零依赖），不进上面那个单 jar，单独部署在被控机
└── im-ui            Vue 3 前端（独立工程，不在 Maven modules 里）
```

**依赖关系是星型而非网状**：

```
        im-user ─┐
       im-friend ┤
  im-conversation┤
      im-message ┼──► im-common ◄──┐   （SPI 契约 + 工具类）
        im-group ┤                 │
         im-file ┤                 │
    im-websocket ┤                 │
           im-ai ┤                 │
       im-remote ┘                 │
                                   │
              im-bootstrap ────────┴──► 依赖全部 11 个模块，负责装配启动
```

`im-remote-agent` 不在星型图里：它是跑在被控机器上的独立进程，与 `im-remote` 只通过
WS 协议通信，没有任何编译期依赖，所以能单独拷走运行。

业务模块之间**从不直接依赖**。跨模块调用一律走 `im-common` 里定义的 SPI 接口，
由目标模块提供实现，调用方用 `ObjectProvider` 注入（拿不到就降级，不报启动失败）：

| SPI 契约（im-common） | 实现方 | 用途 |
|---|---|---|
| `UserQuerySpi` | im-user | 按 ID 批量查用户、判断用户是否存在 |
| `UserProfileSpi` | im-user | 昵称/头像等展示信息，改资料时反向刷新会话快照 |
| `OnlineStatusSpi` | im-user | 在线状态的读写（Redis） |
| `PermissionProvider` | im-user | 供 `StpInterfaceImpl` 查角色与权限点 |
| `FriendRelationSpi` | im-friend | 是否好友、是否拉黑（会话创建的前置校验） |
| `ConversationSpi` | im-conversation | 建会话、刷未读数、更新最后一条消息 |
| `MessageSpi` | im-message | 系统消息落地（如「XX 撤回了一条消息」） |
| `GroupSpi` | im-group | 群成员查询、群内禁言校验 |
| `PushSpi` | im-websocket | 向指定用户/设备推送 WS 报文、踢下线 |
| `FileStorageSpi` | im-file | 文件元数据登记，供消息模块组装可访问 URL |

每个业务模块内部统一分层：
`controller` / `service`(+`impl`) / `mapper` / `entity` / `dto`(req+vo) / `convert` / `spi` / `config`。

---

## 三、环境准备

| 依赖 | 要求 | 检查命令 |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| MySQL | 8.x，已建库或允许建库 | `mysql --version` |
| Redis | 5+，本地 6379 | `redis-cli ping` |
| Node.js | ≥ 20（实测 v24.16.0） | `node -v` |
| npm | ≥ 10（实测 11.13.0） | `npm -v` |
| MinIO | 可选，不装也能跑 | — |

---

## 四、环境变量

**数据库凭据只从环境变量读取，代码与配置文件里不含任何明文密码。**
所有变量都有默认值，本地开发只需设置 `MYSQL_USER` 和 `MYSQL_PASSWORD` 两个。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MYSQL_HOST` | `127.0.0.1` | |
| `MYSQL_PORT` | `3306` | |
| `MYSQL_DATABASE` | `im_db` | |
| `MYSQL_USER` | `root` | **建议显式设置** |
| `MYSQL_PASSWORD` | 空 | **必须设置**（除非本地 root 真的无密码） |
| `REDIS_HOST` | `127.0.0.1` | |
| `REDIS_PORT` | `6379` | |
| `REDIS_PASSWORD` | 空 | |
| `REDIS_DATABASE` | `0` | |
| `SERVER_PORT` | `8080` | |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` 会在响应里回显验证码明文，**生产必须改成 `prod`** |
| `IM_JWT_SECRET` | 内置开发用长串 | Sa-Token 的 JWT 签名密钥，**生产必须替换** |
| `IM_TICKET_SECRET` | 内置开发用长串 | WS 连接票据 / 文件访问票据的签名密钥，**生产必须替换** |
| `IM_FILE_STORAGE` | `local` | `local` 或 `minio` |
| `IM_FILE_DIR` | `${user.home}/im-files` | 仅 `local` 模式生效 |
| `MINIO_ENDPOINT` | `http://127.0.0.1:9000` | 仅 `minio` 模式生效 |
| `MINIO_ACCESS_KEY` | `minioadmin` | |
| `MINIO_SECRET_KEY` | `minioadmin` | |
| `MINIO_BUCKET` | `im-files` | 启动时自动创建，无需手动建桶（桶名须≥3字符符合 S3 规范） |
| `LOG_PATH` | `logs` | 日志目录（相对启动路径） |
| `ALI_BABA_API_KEY` | 空 | AI 面试官的百炼（DashScope）API Key；未设置时仅面试功能不可用，其余功能照常 |
| `AI_MODEL_NAME` | `qwen-flash` | 百炼模型名（也可换 qwen-plus / qwen-max） |
| `IM_AI_KNOWLEDGE_DIR` | `D:/资料/知识库/面试官` | AI 面试知识库目录，递归扫 `.md`/`.txt`，内容增删改后自动重建索引 |

PowerShell 设置示例：

```powershell
$env:MYSQL_USER = 'root'
$env:MYSQL_PASSWORD = 'your-password'
$env:SPRING_PROFILES_ACTIVE = 'dev'
```

Bash 设置示例：

```bash
export MYSQL_USER=root
export MYSQL_PASSWORD=your-password
```

---

## 五、数据库初始化

两个脚本按顺序执行即可。

> ⚠️ **`im_schema.sql` 头部是 `DROP TABLE IF EXISTS` + 重建，重跑一次就会清空全库数据**。
> 它的「幂等」指的是重复执行得到同一套表结构，不是「不会动你的数据」。
> 已经有数据的库不要重跑这个脚本，改用下面的增量升级方式。

```powershell
# 1. 建表（含索引、虚拟生成列、外键约束）——仅限全新库
mysql -u $env:MYSQL_USER -p im_db < sql/im_schema.sql

# 2. 灌入演示数据（3 个用户、角色权限、一对好友、1 个会话、若干历史消息）
mysql -u $env:MYSQL_USER -p im_db < sql/im_data.sql
```

如果 `im_db` 库还不存在，先建：

```sql
CREATE DATABASE im_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

> 也可以直接 `source sql/im_schema.sql`，脚本头部已带 `CREATE DATABASE IF NOT EXISTS` 与 `USE`。

### 已有库的增量升级

本项目没有引入 Flyway/Liquibase，新增列靠手工 `ALTER`。已部署的库升到当前代码需要：

```sql
-- 远程审计新增「触发方」列（inviter 控制端 / invitee 被控端 Agent / system 服务端流程事件）
-- 不加这一列，写入审计会直接报 Unknown column 'actor'；加列前的历史记录该列为 NULL，前端显示「—」
ALTER TABLE `im_remote_audit_log`
    ADD COLUMN `actor` VARCHAR(16) DEFAULT NULL
    COMMENT '触发方：inviter 控制端 / invitee 被控端 Agent / system 服务端流程事件' AFTER `detail`;
```

---

## 六、启动后端

```powershell
# 编译打包（跳过单元测试，本次交付不含测试用例）
mvn clean package -DskipTests
# 启动
java -jar im-bootstrap/target/im-server.jar

# 这样启动每次修改都要返回上层目录进去编译打包 ，只在im-bootstrap目录下即可
mvn -f ..\pom.xml clean install -DskipTests
mvn springboot:run    

```

启动成功的标志是控制台打出这段横幅（由 `ImApplication#logStartupSummary` 打印，
端口、profile、存储实现都取自实际生效的配置，不靠猜）：

```
----------------------------------------------------------
  IM 即时通讯系统启动完成
  生效 Profile : dev
  接口文档     : http://localhost:8080/doc.html
  WebSocket    : ws://localhost:8080/ws
  文件存储     : local
----------------------------------------------------------
```

横幅之前还会有一条 `Sa-Token 启用 JWT 简单模式（StpLogicJwtForSimple），loginType=login`
——看到它说明鉴权用的是正确的 JWT 模式（详见「已知坑」第 2 条）。

| 地址 | 用途 |
|---|---|
| http://localhost:8080/doc.html | Knife4j 接口文档，按模块分成 9 个分组（01-09，含 AI 面试与远程控制） |
| http://localhost:8080/v3/api-docs | OpenAPI 3 原始 JSON |
| ws://localhost:8080/ws | WebSocket 端点（需先取票据） |
| http://localhost:8080/api/ | 全部 REST 接口 |

---

## 七、启动前端

前端是**完全独立的工程**，不参与 Maven 构建。

```powershell
cd im-ui
npm install
npm run dev
```

访问 **http://localhost:5173**。

Vite 会把 `/api` 与 `/ws` 代理到 `http://localhost:8080`，所以**必须先启动后端**。
`vite.config.js` 里设了 `strictPort: true`——5173 被占用时直接报错退出，
而不是悄悄换个端口，避免代理配置和浏览器书签对不上。

#### 手机真机访问（USB 共享网络 / 同一热点）

`vite.config.js` 里设了 `server.host: true`，dev server 监听所有网卡而不只是 localhost，
启动后控制台会多打一行 `Network: http://<电脑IP>:5173/`，**手机浏览器直接打开这个地址即可**。
电脑 IP 用 `ipconfig` 查（USB 共享网络时看「Remote NDIS Compatible Device」那块网卡）。

有两处配置是真机访问的前提，缺一不可：

1. **`server.host: true`**：Vite 默认只听 `localhost`，不改的话手机拿到 IP 也是「无法访问此网站」。
2. **`im.cors.allowed-origins` 里的 `http://*.*.*.*:5173`**：手机用 IP 打开页面后，Origin 变成
   `http://<电脑IP>:5173`。Vite 代理的 `changeOrigin` 只改 `Host` 头不改 `Origin` 头，而 Spring
   判定 CORS 请求只看有没有 `Origin` 头，所以照样会走白名单校验。不放行的表现很隐蔽——
   **页面能打开、GET 可能也正常，但登录这类 POST 全部 403 `Invalid CORS request`**，
   因为 Chrome 对同源 POST 也会带 `Origin` 头，而同源 GET 往往不带。

   写成 `*.*.*.*` 而不是 `*`：实测四段通配只匹配 IPv4 形式的 origin，
   `http://evil.example.com:5173` 这类域名仍被拒（403），端口也锁死在 5173/4173。
   **部署到公网前必须把这两条换成真实域名。**

WebSocket 不需要额外配置：`WebSocketConfig` 用的是 `setAllowedOriginPatterns("*")`，
而前端 `socket.js` 按 `window.location.host` 动态推导连接地址，手机访问时自动指向同一台机器。
Windows 防火墙方面，首次运行 `node.exe` 与 `java.exe` 时弹窗点了「允许访问」就已放行，
专用与公用两种网络配置文件都覆盖，热点网络无论被识别成哪种都能通。

> USB 共享网络下这个 IP 由手机分配，**重插 USB 或手机重启后可能变化**，届时以 Vite 控制台
> 打印的 `Network` 行为准。

#### 手机真机联调远程控制：需要 HTTPS

用手机浏览器以 `http://<电脑IP>:5173` 访问时，**远程控制会报「当前环境不支持 WebCrypto」**——
不是 bug，而是浏览器的**安全上下文**限制：`crypto.subtle`（远程控制做 AES-GCM 解密用）只在
localhost、https、Electron 下暴露，`http + 局域网IP` 拿不到它（普通聊天不碰 WebCrypto 所以照常）。
解法是给 dev server 上一张内网自签证书：

```powershell
cd im-ui
npm run gen:cert     # 纯 Node（node-forge）生成根 CA + 服务器证书到 im-ui/certs/，SAN 含本机全部 IP
npm run dev          # 证书存在即自动 https，Network 行变成 https://<电脑IP>:5173/
```

再把 `im-ui/certs/ca.pem` 传到手机装成受信任的 CA（Android：加密与凭据→安装 CA 证书；
iOS 装完描述文件后还需在「证书信任设置」里开启全信任），手机用 **`https://<电脑IP>:5173`** 打开即可。
WS 地址由前端按页面协议自动推导成 wss，`/api`、`/ws` 代理与后端都不用改。删掉 `certs/` 就回落 http，
不影响 Web 部署与 Electron 构建。根因、换网段重签、两系统装证书的分步操作与排错，
见 [`md/手机真机调试HTTPS配置指南.md`](md/手机真机调试HTTPS配置指南.md)。

生产构建：

```powershell
cd im-ui
npm run build      # 产物在 im-ui/dist
npm run preview    # 本地预览构建产物
```

前端详细说明见 [`im-ui/README.md`](im-ui/README.md)。

#### 打包为 Windows 桌面客户端（Electron）

前端可用 **Electron** 打包成独立的 Windows 桌面 exe（**只打前端**，后端仍远程部署、客户端连之）。
项目根 `electron/` 已配好主进程、preload 与 electron-builder；前端做了「浏览器 / Electron 两用」适配——
同一份代码，Web 部署走同源相对路径 + history 路由，桌面端自动切到后端绝对地址 + hash 路由 + 相对 `base`，
靠 `utils/env.js` 的 `isElectron()` 判断，互不影响。

```powershell
cd im-ui; npm run build:electron          # 相对 base './' + hash 路由
Copy-Item ".\dist\*" "..\electron\dist" -Recurse -Force
cd ..\electron; npm install; npm run build
# 产物：electron\release\IM通讯 Setup 1.0.0.exe（约 160MB，含内置裁剪 JRE 与被控端 Agent）
```

> ⚠️ 分发前先改 `electron/main.js` 的 `SERVER_BASE` 为实际后端地址。
> 安装包经 `extraResources` 内置 `im-remote-agent` 的 fat jar 与 jlink 裁剪 JRE，桌面端启动时
> 自动后台拉起被控端 Agent（双击 `resources\启动被控端.bat` 亦可），被控机**无需安装任何 Java**；
> 见 [`md/Electron打包指南.md`](md/Electron打包指南.md) 十二节。
> 完整的环境准备、打包流程图、踩坑（SSL 证书拦截、`file://` 白屏、大文件下载）与注意事项，
> 见 [`md/Electron打包指南.md`](md/Electron打包指南.md)。

---

## 八、演示账号

种子数据里 3 个账号，密码统一 **`123456`**（数据库存的是 Argon2id 哈希）。

| 账号 | 用户 ID | 昵称 | 角色 | 备注 |
|---|---|---|---|---|
| `admin` | 1000 | 系统管理员 | `admin` | 拥有全部权限点 |
| `alice` | 1001 | 爱丽丝 | `user` | 与 bob 互为好友，已有 1 个会话和若干历史消息 |
| `bob` | 1002 | Bob | `user` | alice 给他的备注是「Alice」，分组「同事」 |

登录页在 `dev` profile 下会**直接把图形验证码明文回显在表单下方**，不用眯着眼认图，短信验证码同理。
这两个开关（`im.captcha.expose-image-code` / `expose-sms-code`）**只在 `application-dev.yml` 里为 `true`**，
主配置 `application.yml` 与 `application-prod.yml` 一律 `false`——
这样即使哪天忘了切 profile，也不会把生产环境的验证码校验废掉。
前端不需要判环境：`debugCode` 字段为空时那段提示自然就不渲染。

> ⚠️ **同一个账号开两个浏览器标签页会互相顶下线。**
> 这是设计行为：`AuthServiceImpl#kickSameDevice` 按「用户 + 设备类型」顶号，
> 而两个标签页的 `deviceId` 都是 `web`，归一化后属于同一设备。
> 想同时挂 alice 和 bob，请用**两个不同的浏览器**或一个正常窗口 + 一个隐身窗口。
> 被顶下线的一方会收到 WS `kickout` 报文并看到明确的中文提示，不是莫名的「登录失效」。

---

## 九、接口一览

统一前缀 `/api`，统一返回体：

```json
{ "code": 200, "message": "操作成功", "data": {}, "success": true,
  "timestamp": 1789222068125, "traceId": "web-mtygmfe7-pxqigz4k" }
```

**所有失败也是 HTTP 200**，靠 `code` 区分（`GlobalExceptionHandler` 上没有 `@ResponseStatus`）。
鉴权失败 `code=1002`，权限不足 `code=403`，业务错误码见 `ResultCode` 枚举
（1xxx 通用 / 2xxx 用户 / 3xxx 好友 / 4xxx 会话 / 5xxx 消息 / 6xxx 群组 / 7xxx 文件 /
8xxx WS 票据 / 9xxx 远程控制）。

| 模块 | 前缀 | 放行 |
|---|---|---|
| 认证 | `/api/auth` | ✅ 免登录 |
| 验证码 | `/api/captcha` | ✅ 免登录 |
| 用户 | `/api/user` | |
| 好友 | `/api/friend`、`/api/friend/request` | |
| 会话 | `/api/conversation` | |
| 消息 | `/api/message` | |
| 群组 | `/api/group` | |
| 文件 | `/api/file` | 下载走一次性票据 |
| WS 票据 | `/api/ws` | |
| AI 面试 / 全网检索 | `/api/ai` | 对话接口是 SSE 流，不走 Result JSON；`/api/ai/search/web` 是普通 Result JSON |
| 远程控制 | `/api/remote` | 凭识别码邀请也要求登录（控制方必须有自己的账号） |

token 通过请求头 `satoken: <JWT>` 传递（`is-read-header=true`，Cookie 与 body 读取都已关闭）。

### 请求链路：从前端发出到拿到响应

关键约定：**后端所有失败都返回 HTTP 200 + `Result` 体**，业务错误码放在 `code` 里，
所以前端的错误判定全在 axios 响应拦截器的「成功分支」，错误分支只处理网络问题。

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户 / 组件
    participant ST as Pinia Store
    participant AX as axios（request.js）
    participant PX as Vite Proxy
    participant TF as TraceIdFilter
    participant SA as Sa-Token 拦截器
    participant CT as Controller
    participant SV as Service
    participant MP as Mapper
    participant DB as MySQL
    participant GEH as GlobalExceptionHandler

    U->>ST: 触发操作（进入会话 / 发消息 / 刷新列表）
    ST->>AX: 调用 api/*.js 方法
    Note over AX: 请求拦截器：<br/>注入 token 头（tokenName）<br/>+ X-Trace-Id
    AX->>PX: HTTP 请求 /api/xxx
    PX->>TF: 转发到 localhost:8080
    TF->>SA: 写入 MDC traceId 后放行
    SA->>CT: 鉴权通过（@SaCheckLogin / @SaCheckPermission）
    CT->>SV: 调业务方法（SecurityUtil.getUserId 取当前用户）
    SV->>MP: 数据访问
    MP->>DB: 执行 SQL
    DB-->>MP: 结果集
    MP-->>SV: Entity
    SV-->>CT: VO / DTO（经 Convert 转换）
    CT-->>PX: Result.ok(data)（HTTP 200）

    alt 出现异常（BusinessException / 未登录 / 参数校验失败）
        SV-->>GEH: 抛出异常
        GEH-->>PX: Result.fail(code, message)（HTTP 仍 200）
    end

    PX-->>AX: HTTP 200 + Result JSON
    Note over AX: 响应拦截器：<br/>code==200 → 只返回 data<br/>code==1002/2010 → 清 token 跳登录<br/>其余 → 弹错误并 reject(ApiError)
    AX-->>ST: 业务数据 或 抛出 ApiError
    ST-->>U: 更新视图 / 提示错误
```

> 更多链路图（三层架构、登录鉴权、文件上传下载）见 [`md/架构与请求链路图.md`](md/架构与请求链路图.md)。

### 黑名单：一份名单、四处入口、单向拦截

名单不建新表，就是 `im_friend.status = 2`（**只改我持有的那一行**，对方视角的关系仍在），
所以多端看到的是同一份。后端三个接口早就到位：`PUT /api/friend/{id}/block`（拉黑）、
`DELETE /api/friend/{id}/block`（移出）、`GET /api/friend/blacklist`（名单）。
**拉黑要求好友关系存在**（`block` 走 `requireRelation`），所以所有入口的候选人都只能是好友。

| 入口 | 位置 | 行为 |
|---|---|---|
| 加入 / 移出 | 好友列表右键菜单 | 单个切换，拉黑前弹确认 |
| 加入 / 移出 | 他人资料页按钮（`UserProfile.vue`，仅好友可见） | 同上，成功后只改本地 `card.blocked` |
| 加入 / 移出 | **会话列表右键菜单**（`ChatHome.vue`） | 只对单聊且 relation 存在的行出现（`blockableOf` 用 `friend.friendOf(target.targetId)` 判定），群聊没有「拉黑一个群」 |
| **添加黑名单** | **黑名单对话框工具栏** | 开二级弹窗多选好友（已拉黑的不再列出）→ 一次确认 → 串行 `block` |

黑名单对话框（`FriendList.vue` 的 `blacklist` / `blacklistAdd`）细节：

- 工具栏左侧显「已拉黑 N 人」，右侧「添加黑名单」；每一行两个按钮：「资料」与「移除黑名单」；
- 候选人列表**直接调 `fetchFriends()`**而不是取 `friend.friends`：store 里那份可能正被搜索关键字
  过滤着，拿它当候选会少一批人，而为了拉全量去调 store 动作又会把用户当前的搜索结果洗掉；
- 整行包成一个 `el-checkbox`（内容放在默认槽里）而不是 `<label>` 套 `el-checkbox`：
  后者是嵌套 label（非法 HTML），点击有双触发风险；
- 批量提交串行打接口，一个人失败不让整批停下，最后给「已加入 N 人，另有 M 人失败」的汇总；
- **操作后必须按当前关键字重拉好友列表**：store 的 `block/unblock` 只能给已经在 `friends` 里的行
  改状态位，对方被搜索过滤掉时就打不上补丁，表现为「拉黑了但好友行上没有「已拉黑」标签」；
- 风险提示只在确认框里做一次：拉黑是单向阻断，对方发消息会被 `validateSendRight` 拦下，
  而我主动发消息时后端会 `unblockSilently` 自动解除拉黑；
- **自动解除是静默的，本地必须跟着改**：`unblockSilently` 只是一条条件 UPDATE（不是拉黑态就
  不命中，所以每条单聊消息都打一次也不贵），改完不推任何帧。不跟着同步就会出现
  「消息明明发出去了，右键菜单还写着移出黑名单」。处理方式：`stores/chat.js` 的
  `syncSilentUnblock(conversationId)` 在四个提交入口（`send` / `resend` / `flushPending` / `forward`）
  成功后各调一次：查会话拿 `targetId`（只处理 `type === 1` 的单聊）→ `friend.applySilentUnblock()`
  把 `status` 从 2 改回 1，真的改到了才弹「发送成功，已自动解除对「X」的拉黑」。
  抽成一个函数而不是四处各写一遍，是为了避免以后只改其中一个入口；
- **被拉黑的一方（B → A）提示带名字**：`validateSendRight` 拒回去的是通用文案
  「对方已将你加入黑名单」（3006），而 `ChatWindow.sendErrorText()` 在单聊里把它换成
  「你已被「会话名」拉入黑名单，消息未送达」（`sendMessage` 走 `{ silent: true }`，
  toast 由 ChatWindow 的 catch 统一补，所以改这一处就够，不必动后端文案）。

---

## 十、WebSocket

连接流程是**两段式**的，因为浏览器原生 `WebSocket` 不能自定义请求头：

1. `POST /api/ws/ticket`（带 `satoken` 头）→ 拿到一个 60 秒有效的一次性 JWT 票据
2. `ws://localhost:8080/ws?ticket=<票据>&deviceId=web` → `WsHandshakeInterceptor` 验票后放行

票据 TTL 由 `im.jwt.ticket-ttl-seconds` 控制（默认 60 秒）。

远程控制的两条数据面端点 `/ws/remote/agent` 与 `/ws/remote/control` 与这个通用端点**相互独立**：
握手拦截器、票据体系、帧协议（文本信封 + 定长头二进制帧）都是另一套，见「十二、远程控制」。

报文类型定义在 `im-common` 的 `WsMessageType` 枚举里，客户端发与服务端推是两套：

| 方向 | 类型 | 含义 |
|---|---|---|
| 客户端 → 服务端 | `ping` | 心跳 |
| | `chat` | 发消息（也可走 HTTP `/api/message/send`） |
| | `delivered` / `read` | 上报送达 / 已读 |
| | `recall` | 撤回 |
| | `pull-offline` | 拉取离线消息 |
| 服务端 → 客户端 | `pong` | 心跳响应 |
| | `ack` | 对客户端报文的确认 |
| | `message` | 新消息推送 |
| | `delivered-notify` / `read-notify` | 把送达/已读状态推回发送方 |
| | `recall-notify` | 撤回通知 |
| | `notify` | 系统/业务通知（好友申请、入群等） |
| | `kickout` | 多端登录被顶下线 |
| | `online-state` | 好友在线状态变更 |
| | `unread` | 会话未读数变更 |
| | `error` | 错误响应 |

断线后前端按指数退避重连，重连时会重新取票据（旧票据 60 秒就过期了，不可能复用）。

### 消息链路：实时收发

连接建立走「先 HTTP 换票据、再握手」；发消息是上行 `chat` 帧，落库后由**写库的那一方**
在事务提交后推送：接收方走 `message` 通道、发送方走 `ack` 通道（推给发送者的全部设备，实现多端同步）。

```mermaid
sequenceDiagram
    autonumber
    participant U as 发送方组件
    participant CS as chat store
    participant SK as socket.js
    participant HS as WsHandshakeInterceptor
    participant HD as ImWebSocketHandler
    participant DP as WsInboundDispatcher
    participant SPI as MessageSpi
    participant MS as MessageServiceImpl
    participant DB as MySQL
    participant SM as WsSessionManager
    participant RC as 接收方连接

    Note over U,HS: 阶段一：建立连接（先换票据）
    CS->>SK: 请求 WS 票据 POST /api/ws/ticket
    SK->>HS: 握手 ws://host/ws?ticket=xxx
    HS-->>SK: 校验票据通过，握手成功，注册连接

    Note over U,RC: 阶段二：发送消息（上行 chat 帧）
    U->>CS: send(conversationId, content, quoteMsgId)
    CS->>SK: wsSend('chat', payload)
    SK->>HD: 发送文本帧
    HD->>DP: dispatch(connection, payload)
    DP->>DP: route → handleChat
    DP->>SPI: send(MessageSendCmd)，fromUserId 只认连接身份
    SPI->>MS: 落库（幂等：uk_from_client 唯一键 + Redis 标记）
    MS->>DB: INSERT im_message
    DB-->>MS: 成功
    Note over MS: 事务提交后 afterCommit 再推送
    MS->>SM: 推送新消息
    SM->>RC: message 通道（接收方渲染气泡）
    SM->>SK: ack 通道（发送方全部设备）
    SK-->>CS: 收到 ack，按 clientMsgId 匹配本地占位消息
    CS-->>U: 气泡状态从「发送中」变为「已发送 √」

    Note over DP,SK: 任一步失败 → reject 回 error 帧<br/>异常绝不逃逸到容器，否则会误判为断连
```

## 十一、文件存储：local / MinIO 切换

默认 `IM_FILE_STORAGE=local`，文件落在 `${user.home}/im-files`，**不装 MinIO 也能完整跑通上传下载**。

切到 MinIO 只需两步：

```powershell
# 1. 启动 MinIO（Windows）
minio.exe server D:\minio\data --console-address ":9001"
# 控制台 http://127.0.0.1:9001，默认账号 minioadmin / minioadmin

# 2. 改一个环境变量后重启后端
$env:IM_FILE_STORAGE = 'minio'
$env:MINIO_ENDPOINT  = 'http://127.0.0.1:9000'
java -jar im-bootstrap/target/im-server.jar
```

**桶不需要手动创建**：`MinioFileStorage#ensureBucket` 在初始化时会检查 `MINIO_BUCKET`（默认 `im-files`），
不存在就自动建。这里刻意让「连不上 MinIO」直接导致启动失败——
既然显式选了 MinIO，继续启动只会把问题推迟到第一次上传，那时更难排查。

无论哪种实现，对外暴露的都是 `/api/file/download/{id}` 这种**受控地址**：
下载时校验登录态与访问权限，再签发一个 30 分钟有效的文件票据
（`im.jwt.file-ticket-ttl-seconds`），不存在裸的对象存储直链。

前端下载**不把整文件读成 blob**（大文件在手机上会超时 + 撑爆内存，表现为「点了没反应」），
而是先用 `/api/file/{id}/url` 换取带票据的直链，再交给 `<a download>` 让浏览器 / Electron
原生下载器流式接管——不占内存、不受 axios 超时限制，1GB 大文件也能正常下（见 `utils/media.js`）。

### 秒传与断点续传（分片上传）

大文件不再走单请求 `/api/file/upload`（受 `byte[]` 与 multipart 内存上限约束，默认 100MB），
而是走三段式分片通道，前端 `uploadFileSmart` 按 **5MB 阈值**自动选路，对上层透明：

| 端点 | 作用 |
|---|---|
| `POST /api/file/upload/init` | 上报整文件 MD5：命中**秒传**直接返回文件记录（零字节上传）；否则下发权威分片大小、会话 ID 与已收分片下标（**断点续传**） |
| `POST /api/file/upload/chunk` | 逐片上传，「临时文件 + 原子改名」落盘，重复投递同一分片幂等 |
| `POST /api/file/upload/merge` | 合并落库：服务端**重读全部分片、重算 MD5 与大小并与声明值核对**，对不上直接拒绝 |

- 会话状态落在临时目录 `im.file.upload.tmp-dir`（默认 `${user.home}/im-upload-tmp`），
  **不引入 Redis**：断点续传要的就是「进程重启后分片还在」，磁盘天然满足。
  `uploadId = userId + "-" + md5`，天然幂等；每个写操作都核对 meta 里的 `uploaderId`，别人拿到 ID 也无法操作。
- 走分片通道的整体上限由 `im.file.upload.max-size` 控制（默认 **2GB**），与普通上传的 `im.file.max-size`（100MB）刻意分开。
- 过期会话（默认 24 小时未更新）由 `ChunkUploadServiceImpl#cleanExpiredSessions` 定时回收。
- 合并出的可信内容仍交回 `FileService#storeMerged`，走与普通上传**完全相同**的类型白名单 / 大小 / 秒传校验，不存在「分片能绕过白名单」的口子。

> ⚠️ 秒传会采信客户端上报的 MD5（能报出某文件 MD5 的前提是本地真的持有它），
> 且要求 `size` 与已有记录一致才判定命中，以此收窄碰撞与谎报空间。
> 前端计算 MD5 依赖 `spark-md5`，首次拉取代码后需在 `im-ui` 下 `npm install`。

**前端体验**：`uploadFileSmart` 的进度条按阶段渲染——「计算文件中」（MD5，0~15%）、「上传中 N/M 片」
（分片并发上传，15~95%）、「合并中…」（96~100%），命中秒传时直接显示「秒传完成」。
前端选文件的总上限已对齐后端的 2GB（`ChatWindow.vue` 的 `MAX_UPLOAD_BYTES`）；类型白名单覆盖常见
文档 / 图片 / 音视频 / 压缩包与 Windows 安装包（exe/msi），而高风险后缀（安装包 / 证书私钥 / 凭据库）
还要前端二次确认才发（`utils/riskFile.js`）。要传更大文件，需同时调大后端 `im.file.upload.max-size`、
`max-chunks` 与前端这个常量。

### 聊天附件的三个入口（选 / 拖 / 粘）与待发送托盘

`ChatWindow.vue` 里三条入口**都不直接发送**，而是先放进输入框上方的「待发送托盘」（`stageFile` → `tray`），
点「发送」才按「先文字、后附件」一批批发出（`sendMessage`）。校验（权限 / 禁言 / 空文件 / 体积 /
高风险格式确认）只在 `stageFile` 这一处写，所以三个入口行为完全一致——否则会出现
「同一个文件点着进能过、拖着进绕过风险提示」这种不一致。

| 入口 | 触发方式 | 说明 |
|---|---|---|
| 图标选文件 | 点「添加图片 / 添加文件」 | 隐藏 `<input type=file multiple>`，`showPicker` 优先（见 `utils/picker.js`）；`input.value` 必须清空，否则连着选同一个文件不再触发 change |
| 拖拽 | 把文件拖进聊天窗口 | 仅 PC 浏览器 / Electron（手机没有 HTML5 拖放，`acceptsDrop` 里已排除）；提示文案为「松开即可添加到待发送」 |
| 粘贴 | 窗口内 Ctrl+V / 手机长按粘贴 | 绑在 `document` 而不是输入框的 `@paste`：截完图回到窗口直接 Ctrl+V 时焦点多半不在输入框上，绑输入框会表现为「粘贴没反应」；焦点在其他可编辑区域（会话内搜索框等）时让开不抢 |

托盘的行为细节：

- 一次最多 9 个（`MAX_PENDING_FILES`），放不下时按剩余容量取前 N 个并提示；
- 托盘在**输入框上方**，整行两栏（`.chat-window__tray` 是 `flex`）：左边 `.chat-window__tray-list`
  （`flex: 1` + `min-width: 0`）一个挨着一个横向排，超过宽度就 `overflow-x: auto` 内部滚；
  右边 `.chat-window__tray-side`（`flex: none`，右对齐）竖排放「待发送 N / 9」与「清空」。
  右侧不参与压缩，所以卡片区宽度只随窗口变、不随名字长短变；
- 一项一卡：图片给 68px 方形缩略图（**点开走与消息气泡同一个图片放大弹窗**），其余给 132×68 的
  类型卡片（图标 + 名字 + 体积，名字 `im-ellipsis` 截断、悬停 `title` 看全名）；
- **移除用的 ✕ 默认 `opacity: 0`，鼠标移到那张卡片上才显形**（`position: absolute` 但偏移是
  `top/right: 2px` 的**卡片内部正值**——压到卡片外面会被 `overflow-x: auto` 裁掉半截）；
  卡片因此要留 `padding-right: 20px`，否则名字末尾被按钮压住；隐藏时必须同时
  `pointer-events: none`，否则看不见也能被点到，变成「没看到叉却把文件删了」；
  触屏没得 hover，`@media (hover: none)` 下恢复常显；
- 风险确认在入托盘时做完，**发送时不再弹任何确认框**，一批文件不会被弹窗逐个打断；
- 与草稿同一套「按会话暂存」（模块级 `Map` + `ChatHome` 的 `:key` 重挂载），切会话再回来，挑了一半的附件还在；
- 缩略图的 `objectURL` 是唯一要手动 `revokeObjectURL` 的东西（移除 / 清空 / 发送后都释放），不 revoke 会一直占着那份文件字节；
- 附件严格串行发送：`uploadAndSend` 自带占位气泡与单槽进度条，并行会互相搅乱；
  发送失败不回摆托盘——File 已存进 store，气泡上的红叹号「重发」直接复用。

粘贴这一路有三个坑：① 富文本复制（Word / 网页）会同时带 `text/plain` 与图片，只有没文字时才
`preventDefault`，有文字则「文字留在框里、图片进托盘」（与微信一致）；② 资源管理器里复制的文件，
浏览器通常只给文件名不给字节，读不到 `file` 项时退化成普通文字粘贴，不弹「粘贴失败」这种无能为力的提示；
③ 部分系统的截图 File 名字里没扩展名，而后端类型白名单按后缀放行（判不出类型就判不出风险），
所以前端按 MIME 补一个 `粘贴图片_20260927-153012.png` 这样的名字再入托盘。

**聊天附件的类型白名单（`FileBizType` 四个常量）**：

| 分类 | 放行扩展名 |
|---|---|
| `avatar` / `chat_image` | `jpg` `jpeg` `png` `gif` `webp` `bmp` `avif` `jfif` |
| `chat_voice` | `mp3` `wav` `aac` `m4a` `ogg` `opus` `amr` `flac` |
| `chat_file` | 文档 / 代码 / 压缩包（`zip` `rar` `7z` `gz` `tar` `tgz`）/ 音视频 / 安装包与高风险后缀（需前端二次确认）；图片扩展名也在列，因为 `bizType` 是前端按 MIME 选的，部分安卓文件管理器只给 `application/octet-stream`，退化成普通文件后若不放行就成了「图明明选了却传不上去」 |

两条易踩的规则：

- **新增能在站内展示/播放的类型，`FileBizType` 与 `FileConvert.CONTENT_TYPES` 两处要一起改**。
  只放白名单不补 MIME，会落到 `application/octet-stream` + `attachment`，表现为「能传上去但图片卡片
  渲染不出来 / 语音只弹下载框」。`CONTENT_TYPES` 里没有的类型一律归 octet-stream，
  这是故意的安全默认（宁可弹下载框，也不能让未知内容在本站的源里渲染）。
- **扩展名取的是最后一个点之后的部分**（`TextUtil.extension`），所以 `a.tar.gz` 解析出的是 `gz`、
  早就放行了，**不要往白名单里加 `tar.gz`**（列了也永不命中，反而让人以为支持复合后缀匹配）；
  真正会漏的是 `a.tgz`（解析出 `tgz`）与 `avif`/`jfif`/`opus` 这类新格式。
  `jfif` 的字节就是 JPEG、`opus` 装在 Ogg 容器里（MIME 同 `ogg`）、`avif` 是部分系统的截图默认格式，
  不支持 avif 的浏览器会退到点卡片下载，也比伪造成 png 存进去好（实际字节仍是 avif，存下来就是坏图）。

---

## 十二、远程控制（远程桌面）

类 ToDesk 的分工：**服务端只中继、不解密**，画面与输入流在控制端与被控端之间端到端加密。三个部件：

| 部件 | 位置 | 职责 |
|---|---|---|
| 中继服务端 | `im-remote` | 会话状态机、两条 WS 端点、脏块转发、只读策略、审计与限流 |
| 被控端 Agent | `im-remote-agent` | 独立 fat jar（纯 JDK 零依赖）：截屏、输入注入、文件操作、授权弹窗 |
| 控制端 | `im-ui` 的 `Remote.vue` | 画面渲染（关键帧定尺寸 + 脏块按坐标贴图）、输入采集、工具面板 |

**连接链路**（控制方必须登录；被控方可免账号走识别码模式）：

1. Agent 长连 `/ws/remote/agent`：带账号 token 则绑定该账号的设备；不带 token 则进入**识别码模式**
   （userId=0，凭 6-12 位大写字母数字识别码跨账号路由），被控机无需注册账号；
2. 控制端发起邀请（`/api/remote/session/invite` 或凭识别码 `invite-by-code`，后者限流 5 次/分/用户）→
   Agent 弹确认框（可把 operate 降档为 readonly）→ 同意后会话 `inviting → active`；
3. 控制端轮询 `/api/remote/session/{id}` 拿一次性 ticket → 连 `/ws/remote/control` 发 `control-ready`
   消费票据 → 中继双向绑定，并经各自已鉴权通道下 `session-start` 帧下发会话 AES 密钥；
4. 画面走二进制帧 `[1B 类型][8B 会话ID][4B 元数据长度][元数据JSON][载荷]`，载荷为 AES-256-GCM 密文
   （12B IV + 密文 + 16B Tag，与 WebCrypto 互相兼容）；中继只解析信封头做路由与策略，**看不到画面内容**。

会话状态机 `inviting → active → ended / rejected`，库里的状态是唯一事实，WS 帧只是它的投影；
超时巡检收尾悬空的 inviting 会话。流量在内存绑定里计数，收尾时一次性落库。

**会话历史与审计**：`GET /api/remote/session/page` 返回的是已补齐的展示视图而不是裸实体——
设备名（批量查 `im_remote_device`，沿用 detail() 的归属优先级：先按被控方配对、退回控制方、再退回 deviceId）、
我的角色（我控对方 / 对方控我）、对端昵称（批量走 `UserQuerySpi`，识别码接入无账号时前端显示「匿名设备」）、
时长、平均码率与审计条数，全部在这一页里算好（每页固定几次批量 SQL，与条数无关），列表不再逐行回查；
进行中会话的流量从内存绑定取实时值（库里那一行要到收尾才写）。可按 `status` 筛选。

`GET /api/remote/session/{id}/audit` 是分页审计流水，只增不改不删，每条带 `actor` 标明触发方
（`inviter` 控制端 / `invitee` 被控端 Agent / `system` 服务端流程事件）——超时、掉线、被新邀请顶替
这些系统收尾记 `system` 而不是随便挑一方，否则事后追责会把人冤枉。前端把动作分三档配色
（红＝删文件/重命名/结束进程/执行命令/电源这类不可逆操作），detail 解析成键值对并可展开看全文
（裸命令文本不拆，按原样等宽展示），支持按 `action` 模糊过滤、每页 20/50/100、导出 CSV
（前端循环翻页拉全量拼 Blob，不另开导出接口）。抽屉顶部还带一个会话概要头，交代「这是哪一次会话」。

> 审计覆盖的动作：服务端写 `invite` / `accept` / `reject` / `control-bound` / `session-end` / `input-blocked`
> （只读模式下被拦的输入帧）/ `direct-*`；Agent 自报 `agent-ready` / `file-get` / `file-put` /
> `file-rm` / `file-rename` / `file-mkdir` / `ps-kill` / `ps-run` / `exec` / `power` / `clip-sync` /
> `input-blocked` / `direct-rejected` / `danger-denied`（高危开关未开时的拒绝）。

**录屏审计（仅桌面端）**：设置 →「远程控制录屏审计」里可开关会话录屏、自定义存储目录
（默认系统「视频」\IM远程录屏）、决定是否附带操作审计。开启后每次远控会话都从画面 canvas 取流，
录成 `远控录屏_<设备名>_<时间戳>.webm`（vp9 / 15fps / 2Mbps，约 15MB/分钟）；MediaRecorder 每秒切一片，
经 IPC 交主进程**串行追加写盘**，因此内存占用有界、进程被强杀也只丢最后一片。同名 `.json` sidecar
记录会话元信息（sessionId、设备名、权限、链路类型、编码、操作者、起止时间）与该会话的全部敏感操作事件。
录像**只落本机、不上传**，与服务端 `im_remote_audit_log` 互补：前者是操作者本地的举证材料，后者是跨端可查的台账。
浏览器端三项控件置灰并标「需桌面版」——Web 没有 preload 拿不到主进程录制桥，也不应在用户不知情下往磁盘落录像。
录像用浏览器或 VLC 打开（`.webm` 不支持 Windows 自带播放器）；会话结束时弹「录屏已保存」通知，
里面直接带「打开录像」按钮（走 `shell.showItemInFolder` 在资源管理器里选中文件），不用手拷路径。

**直连（P2P）：绕开中继的第三条通路**。建会话后两端的候选地址经中继交换（`direct-candidates`），
控制端按阶梯逐级尝试，**画面、输入、文件三条流量一起走直连**，哪档通了就走哪档：

| 档位 | 通道 | 可用场景 | 实现位置 |
|---|---|---|---|
| `tcp` | 局域网 WebSocket | 同网段的浏览器与 Electron（被控端自己实现了 RFC6455，不需打洞） | `DirectTcpServer.java` / `directChannel.js` |
| `udp` | UDP 打洞 | 仅 Electron 桌面端（浏览器开不了原始 UDP，**手机浏览器永远享受不到这一档**） | `DirectUdpServer.java` / `electron/direct.js` |
| `relay` | 原有中继 WS | 始终可用，兼作兼容底线 | `RemoteRelayService.java` / `remoteWs.js` |

报文统一用 DXP（`[12B 头][body]`，与 `RemoteFrame` 的 13B 帧头 + AES-256-GCM 完全复用），
UDP 档自带 Go-Back-N 重传与分片重组（画面走不可靠通道、丢了等下一帧，指令/信封/文件块走可靠通道）；
鉴权是「一次性票据 + 会话密钥证明」两道门，`token` 与 `aesKey` 都只随 `session-start` 下发。
监听口固定 TCP 18924 / UDP 18925（被控机首次启动会弹 Windows 防火墙确认框）；
打洞靠反射服务回答「我看起来是谁」，需服务端配 `IM_DIRECT_PUNCH_HOST` 并放行 UDP 8947 入站。
**直连失败永远不是错误**：防火墙拒连、对称 NAT、没开开关都是常态，中继全程另开一条兜住，
表现退回与不开直连时完全一致。

**硬件编码（H.264）**：`screen-start` 可带 `codec: h264`，被控端用 ffmpeg 的 `gdigrab + nvenc/qsv/amf`
直推 Annex-B，控制端用 WebCodecs `VideoDecoder` 硬解；任一环节不具备条件（无 GPU/无 ffmpeg/
浏览器不支持解码）一律回落 JPEG，工具栏里会显示当前链路与实际生效的编码。

**本机识别码面板**：Agent 在 `127.0.0.1:18923/local-info`（`local.infoPort` 可配）绑一个只读回环接口，
浏览器「远程」页探测到就展示绿色「本机识别码」面板——本机跑着 Agent 就能直接看到码，不用去 Agent 窗口抄。

**被控机零 Java**：桌面安装包经 `extraResources` 内置 `agent/im-remote-agent.jar` 与 jlink 裁剪 JRE（约 74MB），
桌面端启动时自动后台拉起 Agent（已在运行则跳过），或双击 `resources\启动被控端.bat`；详见
[`md/Electron打包指南.md`](md/Electron打包指南.md) 十二节与 [`md/远程控制Agent使用说明.md`](md/远程控制Agent使用说明.md)。

主要配置（`im.remote.*`）：`enabled` 总开关、`aes` 端到端加密开关、`invite-timeout-seconds` 授权超时、
`control-ticket-ttl-seconds` 票据 TTL、`max-frame-bytes` 单帧上限；直连子节 `im.remote.direct.*`
（`enabled` / `lan-enabled` / `udp-enabled` / `punch-host` / `punch-port` / `mtu`）默认关闭，
需与被控端 Agent 自己的「允许直连」勾选两道门都打开才生效，端口放行命令见
[`im-bootstrap/src/main/resources/application.yml`](im-bootstrap/src/main/resources/application.yml) 里该节的注释。

---

## 十三、AI 能力：面试官（知识库 RAG）与全网检索

基于本地知识库 RAG 的「后端 Java 全栈」模拟面试，前端是 `Interview.vue`「面试」页。

**模型接入**：阿里云百炼 DashScope（OpenAI 兼容协议），配置复用 `spring.ai.openai.*` 坐标
（im-ai 模块未引入 Spring AI 依赖，用自己的 `AiProperties` 读取，将来切真 Spring AI 时 yml 不用改）：

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `spring.ai.openai.api-key` | `ALI_BABA_API_KEY` | 空 | 未设置时面试接口返回明确的「API Key 未配置」，**不影响其余功能** |
| `spring.ai.openai.chat.options.model` | `AI_MODEL_NAME` | `qwen-flash` | 百炼模型名 |
| `im.ai.knowledge-dir` | `IM_AI_KNOWLEDGE_DIR` | `D:/资料/知识库/面试官` | 知识库目录，递归扫 `.md`/`.txt` |

**接口**：`POST /api/ai/interview/chat` 返回 **SSE 流**（事件 `delta`/`done`/`error`；模型逐 token 生成，
等全文再返回意味着候选人盯着空屏等十几秒），请求体携带完整对话历史（空数组 = 开始新面试）；
按用户限流 20 次/分钟——每次调用都是一次真实的大模型计费请求。进入流之前的失败仍走全局异常处理器的
HTTP 200 + Result JSON，前端按 Content-Type 区分两条路径。`GET /api/ai/interview/status` 返回知识库
目录、文件数、片段数，供前端状态条展示。

**RAG 的 R 用 BM25 关键词检索而非向量检索**，是几百文件规模下的刻意取舍：不依赖 embedding 模型
（少一个网络调用、少一份计费、少一种失败模式）、索引纯内存构建、文件变更后秒级重建（热更新不用重启）；
面试查询与文档用词高度重合，正是关键词检索最擅长的分布。实现要点（`KnowledgeBaseService`）：
中文不引分词器、按二元组切，英文/数字按连续词切；分块目标 500 字、超 900 硬切、Markdown 标题强制开新块；
每轮检索前对比目录签名（相对路径+大小+修改时间），变了才重建并受 30 秒限频；每轮注入 top-4 片段、
总计 ≤6000 字进提示词，历史消息防御性裁剪 30 条 / 单条 4000 字。知识库为空时面试照常进行，只是没有检索增强。

### 全网检索：消息搜索框的「网络」分组

首页搜索框（占位文案「搜索全部会话/网络」）背后是两个并行请求：`GET /api/message/search`
（跨全部会话的本地消息，300ms 防抖）与 `GET /api/ai/search/web`（全网检索，700ms 防抖）。
本地结果永远排在上面，下面用一条带「网络」小字的分隔横线隔开（仿微信搜一搜的分节线），
网络条目右侧标「网络」，点击在新标签页打开。聊天窗口内的「仅当前会话」搜索不受影响。

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant H as ChatHome.vue
    participant MC as MessageController
    participant WS as WebSearchController
    participant RD as Redis
    participant BG as cn.bing.com

    U->>H: 输入关键字
    par 本地路（300ms 防抖）
        H->>MC: GET /api/message/search?keyword
        MC-->>H: 本地消息（LIKE 匹配 + 权限校验 + 会话名回填）
    and 网络路（700ms 防抖，不等本地结果）
        H->>WS: GET /api/ai/search/web?keyword
        WS->>RD: 查 im:web:search:{关键字 MD5}
        alt 缓存命中
            RD-->>WS: 上次结果（不再打外网）
        else 未命中
            WS->>BG: GET /search?q=...（浏览器 UA，6s 超时）
            BG-->>WS: 结果页 HTML → 正则解析 b_algo 块
            WS->>RD: 写缓存（空结果不写，免 TTL 内始终空白）
        end
        WS-->>H: { keyword, results, moreUrl }
    end
    H-->>U: 本地分组 + 带「网络」小字的分隔横线 + 网络分组
    Note over H: 任一路失败只收自己那一路；关键字变化时旧请求的回调直接丢弃
```

**实现是抓取搜索引擎的结果页再解析 HTML**，而不是接搜索 API：主流的网页搜索接口都要密钥与配额。
Bing 在国内可直连、结果页结构近年稳定。配置项（`im.ai.web-search`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉就不打外网，前端的分隔线与「网络」栏直接不出现 |
| `endpoint` | `https://cn.bing.com/search` | 抓取入口，换引擎只改这一处（`moreUrl` 由它拼出，前端不写死地址） |
| `timeout-seconds` | `6` | 单次抓取超时，超时按空结果处理 |
| `max-results` / `max-keyword-chars` / `max-snippet-chars` | `10` / `60` / `200` | 返回条数、关键字截断长度（整句话不适合打搜索引擎）、摘要截断长度 |
| `cache-seconds` | `600` | 同一关键字的结果缓存时长（需 Redis） |

代价是解析规则绑在对方的 class 名（`b_algo` / `b_lineclamp`）上，对方改版就取不到结果——
因此**所有失败路径都收敛成空结果**（抓取超时、被限流、解析不到、Redis 不可用）：
前端只是不渲染「网络」这一栏，本地消息的检索不能被外网拖死。

**限流与兜底**：接口按用户限流 20 次/分钟（`@RateLimit`）——每一个未命中的关键字都是一次真实的跨网抓取；
`results` 为空时返回体仍带 `moreUrl`，前端据此渲染「在浏览器中打开搜索」兜底入口。
文档里该接口落在路径前缀 `/api/ai/**` 对应的分组（Knife4j 按路径分组，不新增分组数），Tag 为「10-全网搜索」。

---

## 十四、已知坑（踩过的，别再踩）

## 十五、目录结构

```
spring-boot-duomokuia/
├── pom.xml                  父 POM：版本统一管理、12 个 module、编译插件配置
├── README.md                本文件
├── sql/
│   ├── im_schema.sql        建库建表 DDL（索引、虚拟生成列、约束）
│   └── im_data.sql          演示数据（用户/角色/权限/好友/会话/消息）
├── md/                      专项文档（架构与请求链路图、Electron 打包指南、远程控制 Agent 使用说明、手机真机调试 HTTPS 配置、MinIO 部署指南等）
├── im-common/               公共层 + SPI 契约
├── im-user/                 用户中心
├── im-friend/               好友关系
├── im-conversation/         会话管理
├── im-message/              消息核心
├── im-group/                群组管理
├── im-file/                 文件存储（MinIO + 本地）
├── im-websocket/            实时推送
├── im-ai/                   AI 面试官（BM25 知识库检索 + DashScope SSE 客户端）+ 全网检索（WebSearchService）
├── im-remote/               远程控制服务端（会话状态机 + 双 WS 中继）
├── im-bootstrap/            启动模块
│   ├── src/main/java/.../ImApplication.java     唯一的 main 类 + 启动横幅
│   ├── src/main/resources/application.yml       主配置（16 KB，逐项带注释）
│   ├── src/main/resources/application-dev.yml   开发环境：回显验证码、打开 SQL 日志
│   ├── src/main/resources/application-prod.yml  生产环境：关闭全部回显与 DEBUG 日志
│   ├── src/main/resources/logback-spring.xml    控制台 + 按天滚动文件（pattern 含 traceId/userId）
│   └── target/im-server.jar                     打包产物（单 jar）
├── im-remote-agent/         被控端 Agent（独立 fat jar，纯 JDK 零依赖，Swing UI + 回环识别码接口）
├── im-ui/                   Vue 3 前端（独立工程，浏览器 / Electron 两用）
│   ├── README.md            前端专项说明
│   ├── vite.config.js       含 /api 与 /ws 代理；--mode electron 时 base 切 './'；检测到 certs 则自动 https
│   ├── scripts/gen-cert.cjs 内网自签证书生成（node-forge，纯 Node 免 OpenSSL，手机真机联调远程控制用）
│   ├── certs/               自签 HTTPS 证书产物（ca/server 的 pem+key，已 gitignore，含私钥）
│   └── src/
│       ├── api/             9 个接口模块
│       ├── components/      9 个可复用组件
│       ├── layout/          主框架（左侧导航 + 路由出口）
│       ├── stores/          7 个 Pinia store
│       ├── router/          路由与登录守卫（Electron 用 hash 模式）
│       ├── styles/          全局样式与 CSS 变量
│       ├── utils/           格式化、ID 归一化、媒体地址/下载、标题、token、env（环境适配）
│       ├── views/           10 个页面（含远程控制 Remote 与面试 Interview）
│       └── ws/              WebSocket 客户端（连接管理 + 报文分发）
└── electron/                Electron 桌面端（前端壳 + electron-builder，连远程后端）
    ├── main.js              主进程：窗口 / 下载处理 / 跨域 / 注入后端地址 / Agent 自启
    ├── preload.js           contextBridge 注入 window.__IM_SERVER__
    ├── package.json         electron-builder 配置（electronDist 指向本地 electron；extraResources 内置 agent/jre/bat）
    ├── launch/              启动被控端.bat（双击用内置 JRE 拉起 Agent）
    ├── jre/                 jlink 裁剪 JRE（随安装包分发，被控机免装 Java）
    ├── dist/                从 im-ui/dist 复制来的前端产物
    └── release/             打包产物（IM通讯 Setup 1.0.0.exe）
```

---

## 十六、验证清单

按顺序执行，每步都有明确的通过标志：

1. **构建** — `mvn clean package -DskipTests` → `BUILD SUCCESS`，12 个模块全部通过，
   生成 `im-bootstrap/target/im-server.jar` 与 `im-remote-agent/target/*-jar-with-dependencies.jar`
2. **依赖抽查** — `mvn dependency:tree` → 确认 30 个 `org.springframework.boot:*` 构件全部是 4.0.8、
   Spring Framework 一致为 7.0.9，无 Boot 3 残留；Jackson 2 只允许从 knife4j 与 minio 两条链进来
   （见「已知坑」第 3 条）
3. **建库** — 执行 `sql/im_schema.sql` 与 `sql/im_data.sql`，无报错
4. **启动** — `java -jar im-bootstrap/target/im-server.jar` → 8080 端口起来了，
   控制台打出上述横幅，`logs/im-server.log` 里没有 `ERROR`
5. **文档** — 浏览器打开 http://localhost:8080/doc.html，左侧 9 个分组齐全
   （也可以直接请 `GET /v3/api-docs/swagger-config`，返回的 `urls` 数组应当是 9 项）
6. **接口串测** — 注册 → 登录取 token → `/api/user/profile` → 搜索用户 →
   发起好友申请 → 对方登录同意 → `/api/conversation/single` → `/api/message/send` 双向收发 →
   `/api/message/history` 分页 → 撤回 → `/api/conversation/list` 看未读数 →
   `/api/file/upload` + `/api/file/download/{id}` → 建群/加人/禁言/解散 →
   用无权限账号访问管理接口，确认返回权限错误码
7. **WebSocket** — 取 ticket → 连接 → ping/pong → 双端实时收发 →
   断网重连 → 同设备二次登录触发 `kickout`
8. **前端** — `cd im-ui; npm install; npm run dev` → 用 alice / bob
   开两个不同浏览器，验证 10 个页面的交互闭环
9. **远程控制** — `java -jar im-remote-agent/target/im-remote-agent-jar-with-dependencies.jar` 起 Agent →
   网页「远程」页出现绿色「本机识别码」面板 → 另一个登录账号凭识别码发起连接 →
   Agent 弹窗点同意 → 控制端看到画面；降档为仅观看后输入操作被拒
10. **AI 面试** — 设置 `ALI_BABA_API_KEY` 后打开「面试」页开始新面试 → SSE 逐 token 流式输出；
    不设 Key 时返回明确的「API Key 未配置」，其余功能不受影响
11. **全网检索** — 首页搜索框输入关键字（不选会话）→ 本地消息先出，短暂延迟后分隔横线下方出「网络」条目；
    F12 应看到 `/api/message/search` 与 `/api/ai/search/web` **两个** 请求（只看到一个 = 前端未热更新或后端未重启）；
    同一关键字再搜一次应秒回（走 Redis 缓存），`im-bootstrap/logs/im-server.log` 里不再打外网请求；
    外网不可达时该栏整体隐藏且本地结果照常可用（失败收敛为空结果），结果为空时给「在浏览器中打开搜索」入口

已实测通过的项：1、2、3、4、5（9 个分组已核实）、6/7 中的
「登录 → WS 握手 → 双向收发 → 未读数 → 已读回执 → 顶下线」主链路，
以及 9 的远程控制主链路（Agent 识别码注册 → 凭码跨账号邀请 → 弹窗同意 →
票据消费 → 中继绑定 → 出画面，含桌面端自启 Agent 与内置 JRE）。
第 10 项需自备 `ALI_BABA_API_KEY` 实测；第 11 项的前端分节布局已实测（本地/网络两段分隔正常），
网络条目需重启后端加载新接口后才算跑通（旧进程里 `/api/ai/search/web` 会返回 404）。

> 群聊后端功能完整实现，前端聊天页以单聊交互为主，**群聊 UI 不在交付范围内**。
> 单元测试不纳入本次交付，验证以真实启动 + 接口/WebSocket 串测为准。
>
> **桌面端（可选）**：`cd im-ui; npm run build:electron` → 复制 dist 到 `electron/` →
> `npm install; npm run build` 生成 `release\IM通讯 Setup 1.0.0.exe`；双击 `win-unpacked\IM通讯.exe`
> 应能登录、收发、上传、下载大文件（前提：`main.js` 的 `SERVER_BASE` 指向的后端已启动）；
> 启动后还会自动后台拉起内置的被控端 Agent（回环接口 `127.0.0.1:18923/local-info` 可验证）。
