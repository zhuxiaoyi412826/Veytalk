# im-ui · IM 即时通讯前端

Vue 3 + Vite + Element Plus + Pinia 实现的单聊 IM 界面，与后端 `im-server.jar` **前后端分离、独立运行**：
本目录不参与 Maven 构建，开发期通过 Vite 代理把 `/api` 与 `/ws` 转发到 `http://localhost:8080`。

---

## 1. 环境要求

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| Node.js | ≥ 20（实测 v24.16.0） | Vite 7 的最低要求是 Node 20.19+ |
| npm | ≥ 10（实测 11.13.0） | |
| 后端服务 | `im-server.jar` 已在 8080 启动 | 见根目录 README 的构建与启动章节 |

---

## 2. 快速开始

```bash
cd im-ui

# 1) 安装依赖（首次约 20 秒，97 个包）
npm install

# 2) 启动开发服务器
npm run dev
```

浏览器访问 **http://localhost:5173** 即可。

> `vite.config.js` 里设了 `strictPort: true`，5173 被占用时会**直接报错**而不是顺延到 5174 ——
> 文档和后端联调说明里写死的都是 5173，静默换端口会让人以为文档过期了。
> 真要换端口，改 `vite.config.js` 的 `server.port`，同时确认代理目标仍然指向后端。

### 手机真机访问

`server.host: true` 让 dev server 监听所有网卡，启动后控制台会多打一行：

```
➜  Local:   http://localhost:5173/
➜  Network: http://<电脑IP>:5173/     ← 手机浏览器用这个
```

手机与电脑处于同一链路即可（USB 共享网络、或连同一个热点/路由器）。
用 `ipconfig` 查电脑 IP；USB 共享网络时对应的是「Remote NDIS Compatible Device」那块网卡，
它的 IP 由手机分配，重插 USB 或手机重启后可能变，届时以控制台的 `Network` 行为准。

代理目标 `target` 必须保持 `localhost:8080` 不要改成局域网 IP：proxy 请求是 vite 进程
自己发的，永远在电脑本机，与浏览器用什么地址访问无关。

真机访问还需要后端把局域网 Origin 加进 CORS 白名单（`im.cors.allowed-origins` 里的
`http://*.*.*.*:5173`），否则页面能打开但登录这类 POST 会全部 403。详见根目录 README
的「手机真机访问」一节。

其他脚本：

```bash
npm run build     # 产出到 dist/，实测 1727 个模块、约 10 秒
npm run preview   # 本地预览 dist/ 构建产物（注意：preview 不带 proxy，接口会 404）
```

---

## 3. 演示账号

种子数据（`sql/im_data.sql`）自带三个账号，密码统一为 `123456`：

| 账号 | 昵称 | 用户 ID | 角色 | 备注 |
| --- | --- | --- | --- | --- |
| `admin` | 系统管理员 | 1000 | admin | 拥有全部 7 个权限点 |
| `alice` | 爱丽丝 | 1001 | user | 与 bob 已是好友，会话已置顶 |
| `bob` | 鲍勃 | 1002 | user | 与 alice 已是好友，有 1 条未读 |

**建议的双端联调方式**：开两个浏览器（或一个普通窗口 + 一个无痕窗口），分别登录 `alice` 与 `bob`，
即可看到消息实时往返、未读角标变化、在线状态更新。

> ⚠️ 同一个账号在两个标签页登录会**互相顶下线**。这不是 bug：
> 后端 `DeviceType` 只认 `web / pc / android / ios / mini` 五种取值，`AuthServiceImpl.kickSameDevice`
> 按 `(userId, device)` 匹配并踢掉旧连接，浏览器端一律归一化成 `web`，所以两个标签页算同一端。
> 被踢的一侧会收到 `kickout` 帧并自动跳回登录页。

登录页有三个 Tab：

- **账号登录** —— 账号 + 密码 + 图形验证码（点击验证码图片可刷新）
- **短信登录** —— 手机号 + 6 位验证码，发送后有 60 秒冷却
- **注册** —— 账号 + 密码 + 图形验证码，手机号选填

开发环境（`--spring.profiles.active=dev`）下后端会把验证码明文回显在响应里，
登录页会在输入框下方直接显示出来，免去肉眼辨认扭曲图片的麻烦。生产 profile 下这两个开关是关的。

---

## 4. 目录结构

```
im-ui/
├── index.html
├── vite.config.js            监听全网卡 + 端口 5173 + /api、/ws 代理
├── package.json
└── src/
    ├── main.js               挂载 Pinia / Router / Element Plus
    ├── App.vue
    ├── api/                  一个后端模块一个文件，只负责拼 URL 与参数
    │   ├── request.js        axios 实例：注入 satoken 头、统一解包 Result、登录态失效跳转
    │   ├── auth.js  user.js  friend.js  conversation.js  message.js  file.js  ws.js
    ├── stores/               Pinia，业务状态与副作用集中在这里
    │   ├── auth.js           登录态、当前用户、权限
    │   ├── conversation.js   会话列表、未读、置顶/免打扰
    │   ├── chat.js           消息列表、发送/撤回/已读、离线消息拉取
    │   ├── friend.js         好友列表、分组、申请
    │   └── index.js          signOut()：跨 store 的统一登出清理
    ├── ws/
    │   ├── socket.js         连接、30 秒心跳、指数退避重连、事件订阅
    │   └── dispatch.js       把推送帧分发给对应 store
    ├── router/index.js       路由表 + 登录守卫
    ├── layout/MainLayout.vue 左侧竖向导航（消息 / 好友 / 申请 / 我的）+ 连接状态
    ├── views/                7 个页面，见下表
    ├── components/           UserAvatar、MessageBubble、EmojiPicker、ContextMenu、
    │                         CaptchaImage、Pagination
    ├── utils/                format（时间/大小/性别/脱敏）、id（雪花 ID 归一化）、
    │                         media（受控地址 → blob）、token、title
    └── styles/index.css      CSS 变量与全局滚动条样式
```

### 页面与路由

| 路由 | 组件 | 说明 |
| --- | --- | --- |
| `/login` | `Login.vue` | 登录 / 短信登录 / 注册，三个 Tab |
| `/chat/:conversationId?` | `ChatHome.vue` + `ChatWindow.vue` | 会话列表 + 聊天窗口；`:conversationId` 可选，刷新与复制链接都能落回同一会话 |
| `/friends` | `FriendList.vue` | 好友列表，按分组展示，支持搜索、备注、分组、拉黑、删除 |
| `/friends/requests` | `FriendRequest.vue` | 搜索用户发起申请、收到的申请（同意/拒绝）、我发出的申请 |
| `/profile` | `Profile.vue` | 个人中心：头像上传、资料编辑、修改密码、退出登录 |
| `/user/:id` | `UserProfile.vue` | 他人资料卡片：资料、在线状态、发起聊天、加好友、备注、拉黑、删除 |

`ChatHome` 被 `<keep-alive>` 缓存，切换页面回来时会话列表与滚动位置都还在。

---

## 5. 与后端的几条约定

这几处是踩过坑之后固化下来的，改代码前请先读一遍：

1. **所有失败都是 HTTP 200 + `Result` 体。**
   后端 `GlobalExceptionHandler` 全类没有 `@ResponseStatus`，因此业务错误的判定必须放在
   axios 响应拦截器的**成功分支**里，按 `body.code` 区分；错误分支只处理网络层问题。
   把这一点搞反，会写出「401 时跳登录页」却永远不触发的代码。

2. **登录态失效码是 `1002`，被顶下线是 `2010`**，两者都要清 token 并回登录页，
   同时带上 `redirect` 参数，登录成功后能回到用户原本在看的那个会话。

3. **ID 一律当字符串处理。** 后端的雪花 ID 超过了 JS `Number.MAX_SAFE_INTEGER`，
   JSON 里虽然以数字下发，但 `===` 比较会失真。所有比较都走 `utils/id.js` 的
   `asId()` / `sameId()` 归一化。

4. **文件地址是受控的。** 头像、图片、语音、文件的下载地址都需要 `satoken` 头，
   不能直接塞进 `<img src>` / `<audio src>`。`utils/media.js` 的 `mediaUrl()`
   会用带鉴权的 axios 把内容取成 blob 并缓存，第一次调用返回空串并触发拉取，
   拉完由响应式更新触发重渲染。

5. **上传走原生 `<input type="file">`，不用 `el-upload`。**
   `el-upload` 内部走自己的 XHR，不经过 `api/request.js` 那个 axios 实例，
   `satoken` 头、统一错误提示、`1002` 跳登录全都要再配一遍。

6. **限额与后端配置保持一致**（改后端配置时记得同步这里）：

   | 前端常量 | 后端配置项 | 值 |
   | --- | --- | --- |
   | `MAX_TEXT_LENGTH` | `im.message.max-text-length` | 5000 |
   | `MAX_UPLOAD_BYTES` | `im.file.max-size` | 20 MB |
   | `MAX_AVATAR_BYTES` | `im.file.max-avatar-size` | 2 MB |
   | 撤回时限提示 | `im.message.recall-limit-seconds` | 7200 秒（2 小时） |

7. **Enter 发送不能用模板上的 `.prevent` 修饰符。**
   中文输入法按回车是「确认候选词」，此时 `event.isComposing` 为真，
   一律拦下会把还没上屏的拼音当消息发出去。`ChatWindow.vue` 的 `onEnter()` 里显式判断了这一点。

---

## 6. 常见问题

**页面能打开但所有请求都失败 / 提示「无法连接服务器」**
后端没启动，或者没跑在 8080。先确认 `http://localhost:8080/doc.html` 能打开。

**登录后立刻被弹回登录页**
多半是同一账号在另一个标签页也登录了，参见上文「演示账号」里的顶号说明。

**图片/头像显示为空白或裂图**
检查 `im.file.storage` 配置：`local` 模式下文件存在 `im.file.local.base-dir`
（默认 `${user.home}/im-files`，可用环境变量 `IM_FILE_DIR` 覆盖），换了目录重新启动会找不到之前上传的文件；
`minio` 模式下要确认 `MINIO_ENDPOINT` 指向的服务可达。

**WebSocket 一直显示「连接中」**
`/ws` 的代理必须带 `ws: true`，否则 Vite 只代理普通 HTTP 请求，Upgrade 握手会被当成 404。
另外 WebSocket 需要先调 `/api/ws/ticket` 换取一次性票据，票据有效期由 `im.jwt.ticket-ttl-seconds` 控制（默认 60 秒），
过期后握手会失败，客户端会自动重新取票。

**改了后端接口但前端没生效**
`npm run dev` 的热更新只覆盖前端源码；后端改动需要重新 `mvn package` 并重启 jar。
