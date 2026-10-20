# im-admin-ui —— 管理后台前端

Vue 3 + Vite + Element Plus 的**独立 npm 工程**，对应后端的 `im-admin` 模块。
它**不参与 Maven 构建**（根 `pom.xml` 里没有它），和 `im-ui` 一样是纯前端目录。

一期只有三个页面：**数据看板 / 用户管理 / 举报工单**。

---

## 1. 起步

```powershell
cd im-admin-ui
npm install
npm run dev        # http://127.0.0.1:5273
```

前提是后端已经在 `8080` 上跑着（`im-bootstrap` 的 `ImApplication`）。
浏览器打开上面那个地址，用**持有 `system:manage` 权限点的账号**登录，
种子数据里就是 `admin` 角色那一个（账号密码见 `md/账号密码.txt`）。

构建与本地预览：

```powershell
npm run build      # 产物在 dist/
npm run preview    # http://127.0.0.1:4273
```

> `npm install` 需要能访问 npm 源。装不上时 `npm run build` 也一定跑不起来，
> 这不是本工程的问题——本 README 末尾的「本批验证状态」里如实记了当时的情形。

## 2. 为什么是**独立工程**而不是 `im-ui` 里的一个路由

| 考虑 | 结论 |
| --- | --- |
| 权限边界 | 后台要求 `system:manage`，普通用户永远进不去。如果做成 `im-ui` 里的几个路由，聊天端的登录页、注册页、WebSocket、IndexedDB 消息库这些代码会一并打进后台用户手里的那个包，白下载几百 KB |
| 依赖 | `im-ui` 里有 `hls.js`、`onnxruntime-web`、`tesseract.js`、`ffmpeg` 相关的东西，后台一个都不需要 |
| 部署 | 后台应该只在内网可达、只监听 `127.0.0.1` 或内网域名；和面向用户的聊天端打成同一个包就没法分开加访问控制 |
| 登录态 | 两个工程各存各的 token（`im_token` / `im_admin_token`），互不干扰；同一台机器上开两个标签页各登一边不会出现「聊天端把后台身份也带上了」 |

代价是**两份要同步的公共件**：`api/request.js`、`utils/token.js`、`utils/format.js` 这三份在两个工程里
各有一份实现。它们都只有百来行，且行为差异是有意的（后台多拦一道 `1003`、不做 blob 持久缓存），
真要合并就得引入 workspace / monorepo 工具链——为三个小文件不值得，所以每个文件的头部注释里
都写清了「与另一侧的差异以及为什么」。

## 3. 端口、CORS 与代理（改一处要改三处）

本工程 dev 用 **5273**、preview 用 **4273**（`vite.config.js`，`strictPort: true`，端口被占直接报错不顺延）。
`application.yml` 的 `im.cors.allowed-origins` 里已经成对放了
`http|https://localhost|127.0.0.1:5273|4273` 这 8 条。

**改端口必须同时改 `vite.config.js` 与 `application.yml`**，只改一边会得到
「页面打得开、接口全 403 Invalid CORS request」——这个错在浏览器控制台里只表现为 CORS，
看不出是端口对不上。

代理只配了 `/api` 一条：后台是纯拉模型（看板与工单队列都靠刷新），
既不建 WebSocket 也不播 HLS，多两条代理只是多两个没人走的路径。

`server.host` 钉在 `127.0.0.1`：**后台不该在局域网里对任何设备敞开**。
真要临时给同事看效果，显式加 `npm run dev -- --host` 覆盖，不要把默认值改掉。

## 4. 登录链路（和聊天端不同的地方）

```
POST /api/auth/login  { loginType:'username', account, password, deviceId:'web' }
     → LoginVO { token, tokenName, expiresIn, deviceType, userInfo }
GET  /api/auth/me     → UserVO（含 roles / permissions），刷新页面后靠它恢复身份
POST /api/auth/logout
```

三条规矩：

1. **没有注册、没有短信 / 邮箱验证码登录、没有找回密码**。后台账号只能由管理员在库里加，
   把注册入口做进界面等于自己把门拆了。
2. **没有图形验证码**。后端 `AuthServiceImpl.login` 对账号密码这条路**根本不校验**
   `captchaKey / captchaCode`（那两个字段只用于「发短信、发邮件之前的闸门」）。
   照抄聊天端那块验证码图只会多一个没人校验的输入框。
3. **登录成功还要再验一道权限**：`userInfo.permissions` 里必须有 `system:manage` 或 `*`，
   没有就**不写 token**，直接停在登录页并提示「该账号没有管理后台权限」。
   如果先存 token 再清，中间那次跳转会让拦截器带着一个「有身份但没权限」的 token 去打管理接口，
   登录页上会闪出一串 `1003` 红条。

会话中途权限被收回的情况由 `src/api/request.js` 兜：它除了 `1002`（未登录）之外
**还额外拦 `1003`（无权限）**，两种都清凭证回登录页。这一点和 `im-ui` 那份 request 是
唯一的行为差异。

## 5. 后端接口清单（前端严格对齐，一个都不自作主张）

| 页面 | 方法 | 说明 |
| --- | --- | --- |
| 看板 | `GET /api/admin/dashboard?refresh=false` | `refresh=true` 绕过服务端 60s 缓存真跑一遍聚合，**只能人手点，不做定时轮询** |
| 用户 | `GET /api/admin/user/page` | 入参 `keyword / status / current / size`，**没有排序参数**，口径固定注册时间倒序 |
| 用户 | `GET /api/admin/user/{userId}` | 单个用户的后台视角档案 |
| 用户 | `POST /api/admin/user/{userId}/disable?reason=…` | `reason` 是 **query 参数不是 JSON body**；必填（服务端校验），会即时踢掉该用户全部长连接 |
| 用户 | `POST /api/admin/user/{userId}/enable` | 恢复后不会自动重连，需要用户重新登录 |
| 工单 | `GET /api/admin/report/page` | 入参 `status / targetType / reasonCode / keyword / current / size` |
| 工单 | `GET /api/admin/report/{id}` | 含完整证据快照与本行可执行的处置动作 |
| 工单 | `POST /api/admin/report/{id}/accept` | 待处理 → 已受理，不写处理时间 |
| 工单 | `POST /api/admin/report/{id}/reject` | body `{ remark }`，只改状态不动对象 |
| 工单 | `POST /api/admin/report/{id}/handle` | body `{ action, remark }`，执行动作并置为已处置 |
| 字典 | `GET /api/report/config` | 理由 / 对象类型 / 处置动作三份码表，**后台与用户侧共用这一个接口** |

三个 `/api/admin/**` 控制器类上都有 `@SaCheckLogin + @SaCheckPermission("system:manage")`。
`/api/report/**`（提交举报、我的举报）是**任何登录用户都能调**的用户侧接口，
后台界面不调它们——路径前缀就是那道权限边界。

## 6. 三个页面各自在做什么

**数据看板**（`DashboardView.vue`）
分区数字来自各业务模块的 `AdminMetricsSpi`（用户 / 消息 / 直播 / 连接四个），
**模块没装配就少一个分区，而不是补一个 0**；`value` 为 `null` 显示成「—」而不是 0
（0 会被读成「今天真的没人注册」。）每格旁边那个小问号是后端给的**口径说明**（如
「仅统计今日零点起」「仅统计当前实例」），这句话是这份看板最重要的信息，别嫌它啰嗦。
趋势与理由分布用 `div` + `el-progress` 画，**没有引入 echarts**：7 根柱子和几条进度带
不值得为它多背几百 KB 的首包。定时轮询走缓存路径（`refresh=false`），间隔 60s 与后端缓存对齐。

**用户管理**（`UsersView.vue`）
找人（账号 / 昵称 / 手机号前缀）、看状态与积分、禁用与恢复。
`phone` 是后端脱敏后的值。`pendingReportCount` 那一列可以直接点到工单队列（带 `keyword=用户ID`）。
**没有代改资料与代改密码的入口**：后端就没有这两个端点（原因写在 `AdminUserController` 的类注释里，
要做得先补审计表）。当前登录的自己那一行不给「禁用」按钮——后端本来就会拒。

**举报工单**（`ReportsView.vue`）
筛选 + 队列 + 详情抽屉。抽屉里能看到**提交那一刻抓的证据快照**（`evidence.title / excerpt / meta`），
`meta` 的键是可直接显示的中文标签，后台只按键值对展示、**不按键取值**（要按键取值请改成结构化字段）。
处置下拉的选项来自 `allowedDispositions`：那是归属模块在提交时报上来的可执行动作集合，
所以不会出现「一个注定失败的按钮」；执行失败时工单停在原状态、原因原样回显，可以改了再重试。
`ban_user / recall_message / close_room` 三个动作会二次确认——它们直接作用于线上对象。

## 7. 目录

```
im-admin-ui/
├─ index.html
├─ package.json                # 不参与 Maven，根 pom.xml 里没有这个模块
├─ vite.config.js              # 127.0.0.1:5273 / preview 4273 / 只代理 /api
└─ src/
   ├─ main.js                  # pinia → router → element-plus（顺序有讲究，见文件注释）
   ├─ App.vue                  # 只一层 router-view
   ├─ api/{request,auth,admin}.js
   ├─ components/AvatarCell.vue  # 头像要先换 blob 才能显示，逻辑收在这一个组件里
   ├─ layout/AdminLayout.vue
   ├─ router/index.js          # 守卫里 await /auth/me 确认权限（动态 import store 断循环）
   ├─ stores/auth.js           # 权限闸门在这一层，与 request.js 的 1003 处理成对
   ├─ styles/index.css         # 只放跨页面复用的少数几条
   ├─ utils/{token,format}.js
   └─ views/{Login,Dashboard,Users,Reports}View.vue
```

## 8. 上线部署（还没做，步骤留在这）

一期只跑到本地 dev / preview，下面这些是真要对外时**必须**处理的：

1. **别用 vite preview 当生产服务器**。把 `dist/` 交给 nginx 或后端静态资源，
   并且要配 `try_files $uri /index.html`——`createWebHistory` 模式下刷新 `/users` 会 404。
2. **同源部署**。本工程只请求相对路径 `/api`，所以部署时后台页面必须和后端在同一个域名下，
   否则就要回到「改 `axios` 的 `baseURL` + 把新 origin 加进 CORS 白名单」这条路。
   推荐放在内网域名的 `/admin/` 子路径下（那样 `createWebHistory('/admin/')` 要传 base）。
3. **加访问限制**。真正的门是后端的 `system:manage`，但网络层的内网白名单、
   登录失败次数限制、以及「后台只对运维网段开放」仍然是该有的第二层。
4. **`admin` 账号的默认密码必须改**。种子数据里的口令在 `md/账号密码.txt` 里是明文可见的，
   公网部署还用它等于把后台钥匙贴在门上。

## 9. 本批验证状态（如实记录）

- **构建已实测通过**：`npm install` 成功，`npm run build` EXIT=0（built in 8.86s，主包
  `dist/assets/index-*.js` 1,189.29 kB / gzip 382.81 kB）。这只能证明代码能编译打包、
  模板指令与 import 不报错。
- **仍然没有做浏览器实测**：登录、看板数字、举报处置二次确认这些交互路径，
  需要一个有 `system:manage` 的后台账号 + 跑着的后端 + 已建 `im_report` 表，
  本批不具备这个条件。接口形状是照 Controller / VO 源码抄的，不是猜的。
- 后端 `mvn -q -o compile` 全量通过（14 个模块）。
- 已知会踩的第一个坑：如果 `sql/migration_report.sql` 没跑就直接启动后端，
  登录仍正常，但一进后台就会看到「Table 'im_db.im_report' doesn't exist」——
  那张表只有 `im-admin` 用，别的模块不受影响。
