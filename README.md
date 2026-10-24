# IM 即时通讯系统



> 一套开箱即用的**多模块企业级即时通讯系统**：用 Spring Boot 4 + JDK 21 + Vue 3 实现单聊群聊、社交关系、文件传输、远程控制、AI 面试官与抖音式 HLS 直播，后端最终打成**一个可执行 jar** 全搞定。

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.8-brightgreen) ![JDK](https://img.shields.io/badge/JDK-21-orange) ![Vue](https://img.shields.io/badge/Vue-3.5.42-4FC08D) ![MySQL](https://img.shields.io/badge/MySQL-8.x-4479A1) ![Redis](https://img.shields.io/badge/Redis-5%2B-DC382D) [![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**工程形态**：后端 **14 个 Maven module**（根 `pom.xml`），其中 13 个 repackage 成单 jar（`im-bootstrap/target/im-server.jar`）；被控端 Agent（`im-remote-agent`）是独立 fat jar，跑在被控机器上、不打进后端；前端是**三个独立工程**——聊天端 `im-ui`（Web / 桌面 / 手机浏览器）、管理后台 `im-admin-ui`（只在电脑浏览器用）与官网 `im-website`（纯静态产品展示与下载站，无后端接口依赖），都不参与 Maven 构建，前两个通过 Vite 代理与后端通信。

---

## 特性

**账号与鉴权（im-user）**

- 注册登录（图形 / 短信 / 邮箱验证码）、密码找回（手机短信 / 邮箱双通道）
- JWT 鉴权 + RBAC 权限、资料与头像、在线状态

**会话与消息（im-conversation / im-message）**

- 单聊 / 群聊会话，置顶、免打扰、未读角标、已读位点
- 消息收发（HTTP 提交 + WS 下行）、**撤回、已读/送达回执**
- **引用/回复、转发、@ 提醒**
- 文本 / 图片 / 语音 / 文件消息，敏感词过滤、**图片内容审核（NSFW 拒发，血腥 / 暴力可选开）**
- **离线消息补推**（`im_message` 本身当持久队列，重连/上线补拉）
- **幂等三重设防**（Redis 标记 + 唯一键 + 回查）、断网本地待发送队列重提交
- 消息历史分页、本地优先（端侧 SQLite/sql.js 缓存，本地凑得出整页就零请求）
- **消息互动与沉淀**：表情回应 Reaction（悬停气泡浮出快捷表情，已贴的高亮、再点即撕，末尾「+」开完整表情面板；触屏无 hover 故常显）、投票 / 接龙（`MsgType.CHAIN(14)`，零 DDL，条目存 `im_message.extra.chainItems`）、群精华、常用语快捷回复、聊天记录导出、**我的收藏**（用户维度的私人书签，`im_message_favorite` + 「我的收藏」面板，可跨会话筛选并一键跳回原消息定位高亮）

**社交关系（im-friend）**

- 好友申请 / 同意 / 拒绝、好友管理（备注、分组）
- **黑名单（单向阻断）**：多处拉黑入口 + 集中管理；我把你拉黑后，我主动发消息算和解自动解除

**群组（im-group）**

- 建群 / 改群、成员管理、群主转让、解散群
- 群权限与禁言、@ 提醒

**实时通信（im-websocket）**

- WebSocket 长连接、心跳保活、断线重连
- 在线状态（Redis `im:online`）、多端会话管理、封号/改密全端下线

**文件（im-file）**

- MinIO / 本地双实现，秒传 / 断点续传 / 大文件分片，单文件上限 2GB
- 受控地址 + 一次性票据下载；聊天附件可选 / 可拖 / 可粘，三者都先进「待发送托盘」再手动发送

**远程控制（im-remote / im-remote-agent）**

- 手机/PC **远程控制电脑**：ToDesk 式识别码跨账号接入 + 名下设备邀请
- 人工授权（Swing 弹窗）、权限降档、会话密钥端到端 AES-256-GCM
- **三档直连阶梯**：中继 WS → 局域网 TCP → UDP 打洞（Go-Back-N）
- 屏幕流（H.264 硬编 / JPEG 脏块回落）、鼠标键盘注入、文件传输、exec
- **录屏审计**（桌面端本地 WebM + sidecar）、敏感操作审计台账

**AI 能力（im-ai）**

- **AI 面试官**：本地知识库 BM25 检索增强（RAG）+ SSE 逐 token 流式对话；语音输入（按住说话转写）与**电话面试**（qwen3-asr-flash 识别 + qwen3-omni-flash 朗读，半双工免提连续对话，手机端适配）
- **聊天语音消息 + 长按转文字**：单聊 / 群聊输入区可切「语音」模式按住说话，松手即发语音消息；长按（或右键）语音气泡→「转文字」，结果像微信一样内联贴在语音条下方。ASR 与面试页共用同一个 `qwen3-asr-flash`（`DashScopeSpeechToTextSpi` 按 fileId 直读存储字节），未配 key 时降级为一句提示
- **全网检索**：消息搜索框的「网络」分组（见「十三」）
- 对接阿里云百炼 DashScope（OpenAI 兼容协议），按轮检索、内存索引热更新

**直播（im-live）**

- **抖音式 HLS 直播**：Electron 桌面端 ffmpeg 采集（屏幕 gdigrab / 摄像头 dshow）→ H.264 硬编 → **一份源同时编成多档** → 切 HLS fMP4 1s 分片 → HTTP PUT 上传到媒体服务器 → 观众 hls.js 拉流（见「十四」）
- **多档清晰度阶梯 + 观众端真切档**：推 1440p/1080p/720p/480p（主播只选一个原画，高于采集源的档自动砍掉），网页端与桌面端在直播和回放里都能看到**固定的五个选项「自动 / 480p / 720p / 1080p / 1440p」**（与开播面板同名字同顺序；本场没铺到的档位置灰并标出原因，不藏控件也不给假可选项），选档走 `hls.js currentLevel` 真切码流，不是前端放大像素；播放引擎**先试 hls.js（MSE），只有没 MSE 的浏览器（iOS）才退回原生 HLS**，此时四档置灰并写明原因（★ 顺序不能反，`canPlayType` 回 `maybe` 的浏览器一大把，先判原生就把能切档的客户端推进了没有档位 API 的分支，见 `md/已踩坑.md` 第 19 条）；**零后端改动、零 DDL**（主清单沿用 `index.m3u8` 同名，签名/接口/表结构全不动）；★ 生产 Nginx 需改为**只签主清单**（见「十四」§18 与 `md/直播.md` §10.5）
- 房间管理（开播/关播/撤销 abort）、播放地址签名防盗链、主播心跳超时结算
- **弹幕/点赞 WS `/ws/live`**、在线人数、礼物飘屏、连麦上下麦、**PK 进度帧**
- **弹幕房管**（主播禁言名单 + 自定义屏蔽词）与**连麦 PK**（两个在播房间比热度增量 + 观众投票），均零 DDL 存 Redis（见「十四」§13 / §14）
- **直播回放存档**：关播时由推流端把整场分片拼成**逐档 VOD 清单 + 回放主清单** PUT 到 `{roomId}/replay/`，入口标记与统计走 Redis，**零 DDL、零新增依赖、分片本来就在盘上**；回放同样可切档；房间页「▶ 看回放」与大厅「🎬 可回看」横条两个入口（见「十四」§15 / §18.5）
- **主播数据面板**：`GET /api/live/anchor/stats` 一次换回时间窗内的概览（场次 / 时长 / 人次 / 峰值 / 礼物积分）、逐场明细、金主榜、礼物种类榜，外加此刻正在播那场的实时数据；**零 DDL 纯读聚合**（`im_live_room` + `im_gift_record` + 现有 Redis 计数），刻意**不建汇总表**（见「十四」§16）
- **付费 / 密码直播**：开播可选设「房间密码」或「入场积分价」，未解锁观众拉不到画面、进不了弹幕；**零 DDL**——门禁配置与解锁态都存 Redis（`im:live:gate:{roomId}` / `im:live:unlock:{roomId}:{userId}`），真闸门是 `toVO` 只对已解锁/主播下发 `playUrl`+`danmakuWs`；付费只扣观众积分、不给主播入账（与礼物同口径）（见「十四」§17）
- **ENDED 聊天模式**：关播后画面作废但聊天室保留 60 分钟

**运营与治理（im-admin / im-admin-ui）**

- **举报闭环一期**：聊天端四个入口（消息右键 / 用户资料卡 / 直播间 / 群设置）共用一个**字典驱动**的弹窗（理由、必填标记、字数上限全从 `GET /api/report/config` 取，前端不复制一份码表）→ 提交即由各归属模块的 `ReportTargetSpi` 抓**证据快照**落 `im_report`（对象事后被删、关播都不影响处理）→ 后台工单队列可受理 / 驳回 / 处置 → 结论经 `/ws` 的 `NOTIFY` 帧推回举报人
- **处置执行者分界**：`NONE` / `WARN` 由 im-admin 自己完成，`BAN_USER` → im-user、`RECALL_MESSAGE` → im-message、`CLOSE_ROOM` → im-live 经 SPI 交出去，**im-admin 不 import 任何业务模块的 Mapper / Service**（星型约束）
- **管理后台前端**：独立工程 `im-admin-ui`（登录 + 数据看板 + 用户管理 + 举报队列 + 角色管理 + 视频管理 + 直播管理 + 监控告警 + 日志管理）。二期把「拿到就是全量」的单一 `system:manage` 拆成四个细粒度权限点（看板 / 用户管理 / 举报处置 / 角色分配），菜单与路由按 `auth.can()` 逐屏显隐；登录闸门改为「持有伞权限或任意一个细粒度点即可进门」，低权限角色（审核员 / 运营）不再被挡在登录页外
- **多角色分级与角色分配（二期）**：三个控制器从伞权限 `system:manage` 细化到各自的点（`system:manage` 保留作向后兼容的伞权限，admin 种子里补齐四个新点）；新角色分配经 `AdminUserSpi.setUserRoles`（整体替换 + 三条护栏：拒改自己 / 拒摘最后一个可用管理员 / 拒未知角色，改完 `evictCache` 即时生效），im-admin 不碰 `im_user_role`（星型约束）
- **处置人可见（二期、默认关）**：`im.admin.report-handler-visible` 静态开关，打开后 `GET /api/report/mine` 每行带 `handlerName`、处理结论通知也署名处置人昵称；关着时后台响应根本不含处置人字段（沿用一期隐私口径）
- **数据看板**：平台数字与各模块指标经 `AdminMetricsSpi` 汇聚；**取不到的指标显示「—」而不是 0**，模块缺席就少一个分区，页面同时显示「数据截至」与滞后秒数
- **三期四页（视频管理 / 直播管理 / 监控告警 / 日志管理，2026-10-21 本批交付）**：四个新权限点 `system:video:manage`（112）/ `system:live:manage`（113）/ `system:monitor`（114）/ `system:log`（115）。视频页做下架（带事由进审计日志）/ 恢复 / 逻辑删除（需敲「删除」二次确认）；直播页只有「看」与「掐」——强制关播复用举报处置同一条 ban 路径，封禁是终态无解封接口，响应绝不外泄 streamKey；监控页读进程内 MeterRegistry（与 Prometheus 抓的同一本账）+ actuator 健康聚合；日志页七个逻辑档只尾读不下载、只认白名单 key 不传路径。跨模块取数经新建 `AdminVideoSpi` / `AdminLiveSpi`（im-live 实现，星型约束不变）
- **监控告警基础设施（三期）**：`spring-boot-starter-actuator` + `micrometer-registry-prometheus` 落在 im-admin（指标与暴露只服务于后台侧，不污染 im-bootstrap 依赖面）；management 独立端口 8081 且默认只绑 127.0.0.1，只暴露 health/info/prometheus/metrics；慢接口拦截器三通道（WARN 日志进新建的 SLOW-API 日志档 + 内存环形缓冲供监控页 + Prometheus counter `im.slow.request.total` 供告警规则），阈值 `im.admin.slow-request-millis` 默认 1000ms。部署与告警规则样例见 `md/监控告警部署指南.md`
- 举报可整体关闭（`im.admin.enabled: false`），关闭后聊天端四个入口一起隐藏；配额与去重见「十八、管理后台与举报闭环」

**公共基础设施（im-common / im-bootstrap）**

- **三级缓存**（Caffeine L1 → Redis L2 → MySQL L3，穿透防护 + 降级）
- SPI 契约跨模块解耦、TraceId 全链路追踪、全局异常统一转 `Result`、限流
- **im-bootstrap**：唯一启动模块（main 类 + `application.yml`），依赖全部业务模块并 repackage 成单 jar `im-server.jar`，启动打印生效 profile / 端口 / 存储实现的横幅

---

## 截图 / Demo

> 🚧 **TODO：待补充界面截图与操作 GIF**——登录、会话列表、单聊 / 群聊、远程控制、AI 面试官、直播间。
> 在线 Demo：暂无（自部署系统，需自备 MySQL / Redis 后本地运行）。

---

## 快速开始

> 完整分步流程（环境准备 → 环境变量 → 建库灌数据 → 起后端 → 起前端 → 打包客户端）见 [`md/项目启动流程.md`](md/项目启动流程.md)，或本文「三 ~ 七」章；下面是最短跑通路径。

**环境依赖**：JDK 21、Maven 3.9+、MySQL 8.x、Redis 5+、Node.js 18+（前端）。

**1）起后端**（默认 `dev` profile，端口 `8080`）：

```powershell
mvn clean package -DskipTests
java -jar im-bootstrap/target/im-server.jar
```

启动成功会打印横幅：接口文档 `http://localhost:8080/doc.html`、WebSocket `ws://localhost:8080/ws`。

**2）起前端**（Vite dev server，端口 `5173`，已配 `/api`、`/ws`、`/hls` 代理，**必须先起后端**）：

```powershell
cd im-ui
npm install
npm run dev
```

浏览器打开 **http://localhost:5173**，演示账号见「八、演示账号」。

**发布为桌面 / 移动客户端**：Windows 桌面端（Electron）见 [`md/打包/Electron打包指南.md`](md/打包/Electron打包指南.md)，Windows EXE 与安卓 APK 见 [`md/打包/打包为EXE和Android指南.md`](md/打包/打包为EXE和Android指南.md)（安装包内置被控端 Agent 与裁剪 JRE）。

---

## 文档

| 文档 | 内容 |
|---|---|
| [`md/项目启动流程.md`](md/项目启动流程.md) | 从零跑起来的分步流程（对应本文「三 ~ 七」章） |
| [`md/架构与请求链路图.md`](md/架构与请求链路图.md) | 三层架构、HTTP / WebSocket 链路、登录鉴权、文件上传下载、远程控制、AI、直播，以及**举报与后台链路（第十二节）**等专项时序图 |
| [`md/多端入口与缓存全链路图.md`](md/多端入口与缓存全链路图.md) | 四端入口（手机浏览器 / 安卓 APK / PC 浏览器 / Electron，另加第五个入口管理后台 `im-admin-ui`）+ 端侧 IndexedDB·本地 SQLite·服务端 L1 Caffeine + L2 Redis + L3 MySQL 全链路 |
| [`md/直播.md`](md/直播.md) | 直播选型对比与 HLS 推 / 拉流细节、Nginx 生产配置 |
| [`md/图片内容审核接入指南.md`](md/图片内容审核接入指南.md) | 图片 NSFW 审核：为何把推理放前端、阈值调法、审计日志、血腥 / 暴力模型的投放与三个坑 |
| [`md/监控告警部署指南.md`](md/监控告警部署指南.md) | 三期监控：Actuator/Prometheus 端点保护、Prometheus 抓取与告警规则样例、Grafana 接入、慢接口阈值 |
| [`md/手机真机调试HTTPS配置指南.md`](md/手机真机调试HTTPS配置指南.md) | 手机真机联调远程控制的自签证书配置 |
| [`im-ui/README.md`](im-ui/README.md) | 聊天端前端工程详细说明 |
| [`im-admin-ui/README.md`](im-admin-ui/README.md) | 管理后台前端工程说明（端口与 CORS、登录链路、部署四事） |
| [`im-website/README.md`](im-website/README.md) | 官网前端工程说明（端口 5373、下载产物投放与 fetch-downloads 脚本） |
| `http://localhost:8080/doc.html` | Knife4j 聚合接口文档（入参 / 出参、在线调试；分组按 `@Tag` 增长，不写死个数） |
| [`md/CHANGELOG.md`](md/CHANGELOG.md) | 版本变更记录 |

---

## 许可证

本项目基于 **Apache License 2.0** 开源，完整条款见仓库根目录的 [`LICENSE`](LICENSE) 文件。

> 选 Apache-2.0 是因为它对 Java 生态友好：允许商用 / 修改 / 分发 / 私有使用，附带专利授权与免责条款，再分发时保留版权与许可证声明即可。

---

## 开发指南与贡献

面向想改代码、提 PR 的人。仓库：<https://github.com/zhuxiaoyi412826/Veytalk>。

### 源码构建

- **后端**：整体打包 `mvn clean package -DskipTests`，产物 `im-bootstrap/target/im-server.jar`。改过跨模块代码（如 `im-common`）后若要用 `spring-boot:run` 跑，**必须先 `mvn install` 到本地仓库 `~/.m2`**，否则会**静默**跑旧 jar——`spring-boot:run` 只编译 `im-bootstrap` 一个模块、跨模块依赖从 `~/.m2` 解析（命令与原因见「六、启动后端」「十五、已知坑」）。
- **前端**：`cd im-ui; npm install; npm run dev`（开发，5173）/ `npm run build`（生产，产物 `im-ui/dist`）/ `npm run build:electron`（桌面端）；`npm run dev:lan` 局域网模式，`npm run gen:cert` 生成手机真机联调用的自签证书。
- **管理后台前端**：`cd im-admin-ui; npm install; npm run dev`（开发，5273）/ `npm run build`（产物 `im-admin-ui/dist`）。它是独立工程与 `im-ui` 不共用依赖与构建，登录态却共用同一套 Sa-Token；端口与背后的 CORS 白名单绑死，改一处要改三处（见 `im-admin-ui/README.md`）。
- **官网**：`cd im-website; npm install; npm run dev`（开发，5373）/ `npm run build`（产物 `im-website/dist`，纯静态可投任意服务器）。无后端依赖；下载区的三个安装包由 `npm run fetch:downloads` 从 `electron/release` 收集改名（见 `im-website/README.md`）。

### 测试

项目当前**不含自动化测试用例**（打包用 `-DskipTests` 跳过），验收靠「十七、验证清单」的手动串测；补测试的 PR 欢迎。

### 代码规范

**暂未配置** ESLint / Prettier / Checkstyle / Spotless 等自动化工具，靠以下人工约定（动手前先看周边文件的写法、保持一致）：

- **后端**：严格分 `controller / service / mapper` 三层；跨模块只经 `im-common` 的 **SPI 契约**，不直接依赖别的业务模块实现；接口统一返回 `Result`（失败也 HTTP 200、靠 `code` 区分），错误码按段位分配（1xxx 通用 … 9xxx 远程控制）；`password / token / ticket` 一类敏感参数在日志与慢 SQL 里按字段名打码。
- **前端**：组合式 API + `<script setup>`；网络请求收敛到 `src/api/`、全局状态收敛到 `src/stores/` 的 Pinia store，不在组件里散着调 axios。
- **提交前**：后端至少 `mvn clean package -DskipTests` 过一遍编译；前端 `npm run build` 不报错。

### 提 Issue

到 [Issues](https://github.com/zhuxiaoyi412826/Veytalk/issues) 反馈请带上：**现象 + 复现步骤 + 环境**（生效 profile、JDK 版本、浏览器 / 客户端类型）+ **相关日志**（`D:/rizi1/IM/` 下的 `error.log` / `warn.log`，必要时附 `DEBUG.log` 片段）。

### 提 PR

1. 从 `main` 切特性分支，一个 PR 只做一件事、小步提交；
2. 描述写清**动机、改动点、影响范围**；
3. 涉及数据库变更的，附对应 `sql/` 脚本或 DDL 说明；
4. **每次改动都在 [`md/CHANGELOG.md`](md/CHANGELOG.md) 补一条**（本项目硬性约定，Keep a Changelog 风格）；
5. 合并前 rebase 到最新 `main` 并确保能编译。

### 分支策略

当前只有 `main` 一条主干（单人开发）。多人协作时建议：`main` 保持可发布，特性走 `feature/xxx` + PR 合并，紧急修复走 `hotfix/xxx`。

---

## 路线图 / Roadmap

整理自 [`md/待开发功能.txt`](md/待开发功能.txt)（原始草稿），按状态分组：✅ 已落地、🚧 代码就绪待接外部依赖、📋 规划中。

### ✅ 已完成

- **账号认证**：账号密码 / 邮箱 / 手机验证码登录；密码找回（手机短信 + 邮箱双通道，reset 与登录场景的验证码在 Redis 里隔离、重置后踢掉全部登录态）。
- **短信下发可插拔**：验证码下发走 `SmsSender` 抽象，默认 `MockSmsSender`（只写日志）。
- **AI 面试官**：左侧「面试」入口 → Java 全栈面试，本地知识库 BM25 检索增强（RAG）+ SSE 逐 token 流式、循序渐进按轮追问、面试会话审计（见「十三」）；输入框旁「🎙 按住说话」转写进草稿可再编辑（默认「转完即发」，松手即提交，偏好存 localStorage），「📞 电话面试」进全屏免提通话（VAD 断句自动送、面试官朗读、静音/打断/挂断，系统/神经双音色）。
- **聊天发语音与转文字**：单聊 / 群聊工具栏点「语音」切到按住说话模式（与微信同构，桌面端同样可用），上滑取消、松开即发，录音时长写进气泡；长按（手机端）或右键（桌面）语音气泡选「转文字」，文字展开在语音条下方、可收起，只在本端可见（不落库、不推给对方）。群禁言与无上传权限时不出现录音条，会说明原因。
- **好友备注**：改其他用户在自己这里的显示名，留空即清除、回退对方昵称（`PUT /api/friend/{friendId}/remark`）。
- **图片内容闸门三件套**：图内文字 OCR 敏感词（tesseract.js 全离线识别、服务端遮蔽后存 `extra.ocrText`）、**色情 NSFW 识别**（nsfwjs + TensorFlow.js 本机推理，命中即拒发 + 审计留痕，聊天图 / 头像 / 直播封面同源同闸）、**闪照 + 马赛克**（阅后即焚 + 纯前端打码）。
  - **闪照语义：每人各看一次、互不影响**（仅单聊）：`im_message.extra.flashViewedBy` 存已消费查看的用户名单，`flashViewed` 是下发前按查看者现算的瞬态位（名单含自己即已销毁），**发送端自己看一眼不会吃掉接收端那一次**；只有会话**全员**都看过才触发销毁。销毁 = `im_file` 记录**逻辑删**（`@TableLogic` 置 `deleted=1`，应用层 `download` / 直链均取不到字节），**不物理删存储字节**（秒传共享 `objectKey`，删字节会误伤他人同内容文件），无 DDL。需重启后端生效。
- **血腥 / 暴力图像识别（默认关）**：色情那五类里根本没这两类，所以另接了一个 ViT-Base 的 int8 ONNX 模型走 `onnxruntime-web`（`im-ui/src/utils/violence.js`），`nsfw.js` 升级为**多模型编排层**（按阈值激活、命中早退、单模型挂不拖另一路）；后端零改动、零 DDL，只要 `im.moderation.thresholds` 里出现 `Violent` 就激活。
- **一对一语音视频通话 + 桌面端录屏留存**：**原生 WebRTC**（不依赖 TRTC 等外部 RTC SDK），信令走已认证的用户 WebSocket 长连接（服务端只校验关系 + 转发 + 维护 Redis 会话态），媒体内网点对点直连不经服务器、跨公网自建 STUN/coturn TURN。通话记录复用 `im_message`（`MsgType.CALL`）、录音文件复用 `im_file`（`call_record`），**零新表零新依赖**；桌面端合成上下行音视频落盘 + 上传留存 + 气泡回放（Web 端通话可用但不落盘）。详见 [`md/语音视频通话接入指南.md`](md/语音视频通话接入指南.md)。
- **群通话 / 多方会议（小房间 mesh，零 DDL）**：上一条只有一对一，本条把它扩到多人。**拓扑是 mesh**（每两人一路 P2P，房间里 N 人就有 `N(N-1)/2` 条链路）而不是 SFU：后者要新进程 + 原生编译依赖（Windows 无原生支持），而本仓库大量环境是无外网的 Windows 开发机。服务端只搬 SDP/ICE（新帧类型 `groupcall` / `groupcall-signal`，仍复用主 `/ws`），**媒体一个字节不进 Spring Boot 进程**；花名册只存 Redis（`im:gcall:*` 四键，带 TTL 自回收），**不建表不改表**。协商方向恒定「后进房者对每个先到者发 offer」，否则两侧同时 offer 撞车；本地只采一次 `getUserMedia`，N-1 路共用同一路流（Windows 上重复打开同一摄像头不安全，且一路关闭不能停共享轨）。默认 4 人上限（`im.call.group-max-slots`，服务端硬夹 `[2,6]`），**代价三条写在明处**：每人上行 `N-1` 路编码、TURN 要扛 `N(N-1)/2` 路中继、与 1:1 通话及直播连麦的忙线键是三套（跨类型不并发靠前端拦）。群聊 header 两个入口 + 「群里正在通话·加入」提示条 + 浮层头像墙（静音 / 摄像头 / 邀请 / 离开）；通话记录仍复用 `MsgType.CALL`，只发起人落一条、多一个 `extra.callParticipantCount`；**不落盘录制**（mesh 下要录 N-1 路分立流得先混流）。机理与自测步骤见同一份指南 §13。**未做浏览器多端真机联调**。
- **多档清晰度阶梯（2K 推流 + 观众端真切档，零后端改动，2026-10-20 本批交付）**：主播只选一个「原画」分辨率（新增 `1440p (2K)`），`electron/live.js` 把**一份源用 `split=N` 同时编成 2K/1K/720P/480P**、写成标准 HLS 阶梯（`-var_stream_map` + `-master_pl_name index.m3u8`）；码率按原画的 1 / 0.55 / 0.3 / 0.16 逐档绑定（`-b:v:i`），**高于采集源物理高度的档直接砍掉**（1080p 的屏不出 2K，缩放 150% 的 4K 屏靠 `bounds.height × scaleFactor` 才能正确保留 2K）。网页端与桌面端（共用 `im-ui`）在直播与回放里都能选「自动 / 2K / 1K / 720P / 480P」，走 `hls.levels` + `currentLevel` 真切流（不是前端放大像素），偏好存 `settings.liveQuality`（**存高度不存序号**，本场没那档就回落自动，绝不锁到一个不存在的档上）。**后端零改动**：`LiveUrlSigner` 只签 `index.m3u8`，主清单沿用同名就签名/接口/Redis 键/表结构全不动（`resolution VARCHAR(16)` 装得下 `1440p` 且服务端只存不校验）；回放也升级为**逐档 VOD 清单 + 回放主清单**。**代价与边界写在明处**：四档总码率≈原画 2.01 倍（上行/磁盘/下行同比涨）、阶梯**默认开启不加开关**（本机实测四档 libx264 veryfast 4.20× 实时、NVENC 3.61× 实时）、单档流/连麦 WebM 合成流/Safari 原生 HLS 三种情况**隐藏选择器**（给一个“切了没反应”的假控件比没有更糟）。★ **生产 Nginx 必须改配置**：只对 `~ /index\.m3u8$` 签名，其余 `.m3u8` 不能签（hls.js 拉变体清单不带主清单 query，一律 403 直接黑屏；局域网自测替身不校签名所以自测看不出来）。机理、实测命令要点与已知边界见 [`md/直播.md`](md/直播.md) §18，ffmpeg 四个静默坑见 [`md/已踩坑.md`](md/已踩坑.md) 第 14~16 条。
- **消息提醒（切后台 / 断线 / 离线三路兼顾，零 DDL 零新依赖）**：上一版的「不在线或切后台就收不到消息」有两个成因，本轮一次拆完。**输入三路**：实时 `message` 帧、重连后 `pullOffline` 的一批离线消息（**汇成一条**「离线期间收到 N 条（M 条@我）」而不是逐条弹）、以及实时连接断开时每 15 秒一次的 `GET /conversation/unread/total` 兜底（**只拉一个数**，不拉正文不推已读位点，避免与重连后的补拉撞车）。**输出四路**：Web Audio 现合成提示音（不引音频文件）、系统桌面通知、标签页标题 `(3)` 前缀 + favicon canvas 角标（底色跟主题色）、桌面壳任务栏 overlay / macOS Dock 计数。免打扰与会话级 `muted` 是两个层面，**被 @ 会打破免打扰**（与后端 `MessageExtra.hasMention()` 同口径）；同一会话 4 秒内只提醒一次。开关全部在「设置 → 消息提醒」四个 `localStorage` 项，后端一行未改。**桌面端根因修复**：`electron/main.js` 的 `backgroundThrottling: false`（窗口一隐藏 Chromium 就把定时器压到约 1 次/分钟 → 心跳发不出去 → 服务端 90 秒踢会话），通知下沉到主进程原生 `Notification`（不受浏览器授权约束，并设 `app.setAppUserModelId('com.im.desktop')` 与打包 `appId` 逐字一致）、托盘常驻（关窗口是 `hide()` 不是退进程，`revealWindow()` 同时 `show()+focus()`）、点通知直接跳到那个会话。**★ 两条限制写在明处**：手机浏览器没有后台常驻通道，页面一关就是收不到（要稳定接收得用桌面客户端或 App 厂商推送，后者未开工）；`http://局域网IP` 这类非安全上下文里浏览器直接屏蔽 `Notification`，自动降级为提示音 + 角标。机理与排查见 [`im-ui/README.md`](im-ui/README.md) 第 5 节。
- **管理后台一期 + 举报闭环（2026-10-19 本批交付）**：新建 `im-admin` 模块（第 11 个业务模块）与独立前端工程 `im-admin-ui`，只一张新表 `im_report`。聊天端四个入口（消息右键 / 用户资料卡 / 直播间 / 群设置）→ `POST /api/report` 抓证据快照入库 → 后台 `/api/admin/**` 工单队列可受理 / 驳回 / 处置 → 结论经 `PushSpi` 的 `NOTIFY` 帧推回举报人（举报未成立 / 举报已处理）。
  - **为什么一张表就够**：证据存 JSON 快照而不是事后回查——消息会被撤回、房间会关播、群会解散，**对象事后没了也要能完成处置**；责任人 `reported_user_id` 提交时一次性由 SPI 反查并存下（同一人累计几张工单这个聚合要一直算得出）。
  - **星型约束不破**：im-admin 不 import 任何业务 Mapper，用户封禁 / 消息撤回 / 关播全部经 `im-common` 的 `AdminUserSpi` / `ReportTargetSpi` 交回归属模块执行；看板指标经 `AdminMetricsSpi` 从四个模块汇聚，模块缺席就少一个分区。
  - **未做的部分（不是遗留 bug，是一期刻意不抢范围）**：看板只做一期口径（不建埋点、不拉时间序），**系统配置页未做**（举报开关与配额现在只能改 `application.yml` + 重启）；`GET /api/report/mine` 端点已就绪但**聊天端无 UI**（结论已经由 WS 推给举报人，列表只多不少一个刷新入口而已，排在下一批）；无机器复审（队列全人工）；举报人看不到处置人是谁。
- **管理后台二期 · 多角色分级 + 角色分配 + 处置人可见（2026-10-19 本批交付）**：把一期已存在但只种了 2 角色 1 个后台权限点的 RBAC 底座（`im_role`/`im_permission`/`im_role_permission`/`im_user_role` 四表 + `PermissionProviderImpl` 缓存）真正用起来。**按用户决策「只改代码层」：零新建表**——系统配置页（无 `im_admin_config`）与操作审计表（无 `im_admin_audit`）都不做，处置人可见用 `application.yml` 静态开关。**多角色分级**：`ImConstants` 加 `system:dashboard`/`system:user:manage`/`system:report:handle`/`system:role:manage` 四个细粒度权限点 + `moderator`/`operator` 两个示范角色，三个既有控制器类级 `@SaCheckPermission` 从伞权限 `system:manage` 细化到各自那一个点（`system:manage` 保留为向后兼容伞权限，admin 种子必须补齐 108~111 否则反被拦、四屏全 403）。**角色分配**：`AdminUserSpi` 扩 `listRoles`/`listUserRoleIds`/`setUserRoles`，新建 `AdminRoleController`+`AdminRoleService`，三条硬护栏（拒改自己 / 拒摘最后一个可用管理员 / 拒未知角色）+ 改完 `evictCache` 即时生效，im-admin 经 SPI 不碰 `im_user_role`（星型不破）。**处置人可见**：`im.admin.report-handler-visible` 默认关，打开后 `ReportVO` 才带 `handlerName`（`mine()` 批量解析防 N+1）、驳回/处置通知也署名。前端：`auth.can()` 逐屏判定、路由 `meta.perm` + 守卫回退可访问首屏、菜单按权限显隐、新建 `RolesView`、`UsersView` 加「配角色」弹框。SQL 见 `sql/im_data.sql` 与 `sql/migration_admin_phase2.sql`（纯 DML、幂等，存量库先跑再重启）。**仍缺**：系统配置页 / 操作审计（属建表范畴，本轮刻意不做）、机审复审（与一期同条）；未做重启后端 + 跑迁移后的登录级端到端验证。
- **主播数据面板（零 DDL，2026-10-19 本批交付）**：直播大厅「📊 我的数据」一次请求换回**窗口内概览**（场次 / 累计时长 / 观看人次 / 平均与最高峰值 / 礼物积分 / 送礼人数 / 上次开播）、**逐场明细**（时长、峰值、人次、礼物笔数与人数、关播原因，可直接进房或看回放）、**金主榜**与**收到最多的礼物**，外加此刻正在播那场的实时行（在线 / 峰值 / 人次 / 礼物 / 点赞 / 已播时长）。取数全部复用既有资产——`im_live_room` 每场一行的定格值、`im_gift_record`（它的 `idx_room_time` 与 `idx_receiver_time` 刚好对上「按场次聚合」和「按主播聚合」两种查法）、现有 Redis 在线与点赞热度键，**不建汇总表、不改任何写入链路**；唯一新增的既有类方法是 `LiveDanmakuService.peekStats`（只读不清，与关播落库用的 `drainStats` 必须分开，否则主播刷一次页面就把自己的峰值归零）。面板脚注把三个「做不到」直接印出来：礼物价值是虚拟积分且**不进主播钱包**、不可提现；点赞只存 24 小时所以历史场次没有、弹幕条数服务端从来没存量；不含 PK 战绩（会话按 `pkId` 寻址、无主播维度索引、只活一天）。概览只覆盖窗口内最近 100 场，被截断时前端显式警告（`sessionsCapped`）。机理与取舍见 [`md/直播.md`](md/直播.md) §16。
- **消息收藏（2026-10-19 本批交付）**：给消息加一层**只属自己可见的私人书签**。后端新建 `im_message_favorite`（与 `im_message_essence` 同构，唯一键 `uk_user_message(user_id, message_id)`——同一条消息可被多人各自收藏，A 取消不伤 B），`GET /api/message/favorites` 按收藏时间倒序分页（`conversationId` 为空则跨全部会话）、`PUT /api/message/{id}/favorite` 收藏/取消；整页回填走 `toViewList → attachFavorites`（单次 `IN`，导出聊天记录那种两万条走 `FAVORITE_ATTACH_LIMIT=500` 封顶不标）；`favorite-notify` **只推给操作者本人**（收藏不该像精华那样打扰全群，它的真正语义是同一账号多端同步）；单端删除 / 清空会话 / 账号注销三处级联清行，不给收藏列表留点不开的幽灵行。前端：气泡「★ 已收藏」角标、右键菜单收藏项、⭐「我的收藏」面板（只看本会话筛选 + 分页 + 取消），并新建 `locateTarget → ensureMessageLoaded → flashMessage` 跳链路——从收藏列表一键跳回原会话并定位高亮（必要时向前补拉历史，封顶 10 页）。顺手补掉两个旧洞：全局搜索与会话内搜索结果现在都能点击定位（原先只跳转不定位 / 完全不可点）。**本轮三个功能（回放 / 收藏 / 数据面板）里只有收藏需要 DDL**，而且是刻意的（与它同批的回放、转账红包都做到了零 DDL），理由见「五、数据库初始化 › 已有库的增量升级」与 `md/数据库表解析.md` §7.7。
- **直播回放存档（零 DDL，2026-10-19 本批交付）**：直播的分片本来就存在媒体服务器上，所以回放不需要录制、不需要转码、不需要新表——缺的只是一份**不滚动、带 `ENDLIST` 的 VOD 播放清单**。实现：Electron 上传泵在推流期间给每个 PUT 成功的分片记一份内存台账（序号/时长/字节），关播时排空队列后拼成清单 PUT 到 `{roomId}/replay/index.m3u8`，后端 `LiveReplayService` 只在 Redis 落一个入口标记并用 `LiveUrlSigner` 现签地址；前端房间页黑屏处「▶ 看回放」、hls.js 走 VOD 分支、大厅「🎬 可回看」横条 + 卡片角标 + 关播后 10 秒一次性探测。**保留期（2026-10-20 改）**：回放只留 `im.live.replay.retention-days`（默认 **1 天**），这个值同时管 Redis 入口 TTL 与媒体服务器上源文件的删除线——到期后整个房间目录（分片 / init / 各档清单 / 回放目录）由媒体侧 `electron/live-retention.js` 实删（Java 进不到媒体磁盘，删除只能在媒体面做）。机理、路径与签名口径、已知边界见 [`md/直播.md`](md/直播.md) §15 与 §10.7。
- **聊天小功能与钱包用户间流转（2026-10-18 本批交付）**：**常用语 / 快捷回复**（`GET|PUT /api/conversation/quick-reply`，整表替换、去空去重保序，上限 20 条 / 单条 200 字，**存 Redis 不建表、也不只放 localStorage**——手机与桌面要看到同一份话术）；**会话内关键词检索的前端入口**（后端 `GET /api/message/search?conversationId=` **此前已实现**，旧文档误列为缺口，本轮只在 `ChatWindow.vue` 补搜索条与命中定位）；**用户间转账与红包**（`MsgType.TRANSFER(12)` / `RED_PACKET(13)` + `POST /api/wallet/transfer` / `/packet`，转账走单聊、红包走群聊拼手气，**零 DDL**：钱走 `im_wallet_transaction`、领取态走 Redis Hash，过期余额由账本求和退回，机理见「九、钱包」）。资金类消息只允许服务端内部产生（客户端伪造 `msgType=12/13` 直接被拒），且**不可撤回、不可转发**。
- **消息与群体验一整批**（这些曾列在待办里、实际已交付，2026-10-18 校准路线图时逐项对代码核实）：**表情回应 Reaction**（`im_message_reaction`）、**消息编辑**（`EditMessageRequest`，仅本人 / 时限内 / 未撤回的文本）、**新消息类型**：名片 `CARD(7)` / 位置 `LOCATION(8)` / 聊天记录合并转发 `MERGED_FORWARD(9)` / 投票 `VOTE(10)`（走同一套策略处理器扩展范式）、**语音转文字与消息翻译**（`SpeechToTextSpi` / `TranslationSpi` + 配置门控优雅降级）、**群文件 / 群相册 / 群精华**（前两者是群会话按 `msgType` 的过滤视图零 DDL，精华为 `im_message_essence`）、**进群审批 / 邀请链接 / 群二维码**（`im_group_join_request` + zxing 二维码）、**群机器人 / Webhook**（`im_group_robot`，外部系统凭回调地址推消息，服务端拦截客户端伪造 `SYSTEM|ROBOT`）、**群禁言**、**置顶 / @全体 / 免打扰 / 已读回执**、**聊天记录导出**（本地 JSON 与云端 JSON/CSV，单会话上限 20000 条游标翻页）、**草稿箱**（按会话暂存，刻意不落 localStorage）、**账号注销与数据级联擦除**（`UserDataEraseSpi`）。

### 🚧 代码就绪，待接真实服务商 / 依赖服务器

- **血腥 / 暴力图像识别**：代码已完（见上面「已实现」与指南第九节），**只差把 83MB 的模型投放到 `im-ui/public/models/violence/model.onnx` 再打开 `# Violent: 0.75`**；模型不入库（`.gitignore`），不投放时检测器自动停用、色情那条链路不受影响。真实照片精度待人工验收。
- **真实短信服务商**：新增一个 `SmsSender` 实现、切换生效 provider 即可（当前 Mock 只写日志）。
- **事务邮箱**：真实邮件发信通道（有了服务器再开发）。
- **腾讯防水墙**：人机验证。
- **腾讯 TRTC**：音视频实时通信底座（下面的视频面试官依赖它；一对一通话已用原生 WebRTC 落地，不依赖 TRTC；面试电话模式用 ASR+TTS 半双工，同样不依赖 TRTC）。

### 📋 规划中

- **第三方授权登录**：QQ / 微信 / GitHub OAuth。
- **视频面试官（基于 TRTC 实时音视频）**：🚧 待开发，覆盖移动端与电脑端。**语音部分已以另一种形态落地**：「📞 电话面试」是 ASR（qwen3-asr-flash）+ TTS（qwen3-omni-flash）的半双工免提连续对话，不是 TRTC 实时音视频、无面试视频，两者别混为一谈。
- **图片审核升级成服务端真防线**：现行判定跑在发送端浏览器，绕开页面直接调上传接口即可突破；要成硬防线需服务端补一条推理链路（阈值配置与审计口径可直接复用）。
- **直播右侧内容面板**：文章 / 小说 / 音乐，点击切换展示（**视频板块已落地**：`im_live_video` + `/api/live/video` 分页 / 发布 / 下架 / 播放计数，大厅信息流与 `Live.vue` 已可用）。
- **点播短视频的真多档清晰度**：直播已经有多档阶梯，`im_live_video` 却仍是一行一个原文件、上传时不转码，所以播放条只如实显示 `宽×高`、**不给假选择器**。要真多档得新增列/表存多路产物 + 上传期逐档转码 + N 倍存储与流量，而且转码只有桌面端能做（Web 上传无法起 ffmpeg），机理见 [`md/直播.md`](md/直播.md) §18.8。
- **大会规模（>6 人）与群通话录制**：群通话一期已用 mesh 落地小房间（默认 4 人、硬顶 6，见上一条），要真正开大会需换 SFU（mediasoup / LiveKit）做混流，那不是调大上限数字就行；群通话也不落盘录制（同理得先混流）。**SFU 化已完成选型与分阶段方案（定在 LiveKit：官方 Windows 二进制、单机不需 Docker/Redis），但仍是计划文档、未开工**：[`md/SFU群通话改造计划.md`](md/SFU群通话改造计划.md)。
- **Tauri 2 打包**：安卓端 + 重新打包 exe（现有桌面端为 Electron 方案，见「七」）。
- **日志系统完善**：在现有 logback 分档日志（见「六」）基础上进一步增强。

**🔴 关键短板（企业级 / 合规 / 移动端最该补）**

- **系统级离线推送**：App 被杀时经 APNs / FCM / 个推 / 小米华为通道唤醒（现「离线消息补推」仅重连后补拉）。
- **管理后台（Admin Console）**：**一期已交**（`im-admin` + `im-admin-ui`：数据看板 / 用户管理与封禁恢复 / 举报工单队列与处置）；**二期部分已交 2026-10-19**（多角色分级四个细粒度权限点 + 后台角色分配 + 处置人可见开关，见上面「✅ 已完成」与 CHANGELOG）；**三期已交 2026-10-21**（视频管理 / 直播管理 / 监控告警含 Actuator+Prometheus+慢接口告警 / 日志管理）。**按用户决策「只改代码层」刻意未做（属新建表范畴）**：**系统配置页**（现改参数仍要动 `application.yml` 重启，不落 `im_admin_config`）、后台自身的**操作审计**（改昵称 / 禁用别的后台号仍无痕，不落 `im_admin_audit`）；仍缺：内容审核队列（机审）、日志与监控数据的持久化历史（现监控页是即时快照、日志页只读当前档，历史曲线归 Prometheus/Grafana）。
- **举报 + 内容审核闭环**：**一期已交**（用户侧四入口 + `im_report` 待审队列 + 处置闭环 + 结论回执，见上面「✅ 已完成」）。**人工处置链已通**，缺的是机审复审那一段：`im-file` 的 `ModerationController` / `ModerationService` 只盖文件与图片，弹幕 / 群名 / 公告没有服务端模型；举报内容目前**只过敏感词遮蔽与人工**，没有第二双机器眼睛。
- **聊天端到端加密（E2EE）**：「私密会话」信令级 E2EE（需权衡与服务端检索 / 审核的冲突）。
- **两步验证 2FA / TOTP**：登录二次校验。
- **账号注销 / 数据删除**（已落地，不再列为短板）：`POST /api/user/cancel` 密码二次确认 → 匿名化并逻辑删除本人账号，并经 `UserDataEraseSpi` 级联清除各模块归属本人的数据。
- **监控告警**：Actuator + Prometheus / Grafana 指标暴露、健康检查、慢接口告警（现仅 TraceId + 慢 SQL 日志）。

**🟡 沟通与群组体验（2026-10-18 按代码实况校准：本组旧条目已全部落地，见上面「✅ 已完成」，此处只留真缺口）**

- **接龙**：已落地（2026-10-19）。`MsgType.CHAIN(14)` + `ChainContentHandler`，**零 DDL**——主题与有序条目存 `im_message.extra`（`chainTitle`/`chainItems`/`chainClosed`），服务端 Redis 短锁 `im:msg:chain:lock:{messageId}` 串行 read-modify-write 追加，`chain-notify` 推全量快照给会话全员；`POST /api/message/{id}/chain`。边界：条目上限 200、单条正文 200 字、已截止不再接受、未做删自己那条/撤回某条。
- **消息编辑仅限文本**：现 `EditMessageRequest` 只允许发送者改未撤回的文本消息，富文本 / 附件类不可改。

**🟢 前端体验 & AI / 直播延伸**

- **国际化 i18n / 无障碍 a11y**（现界面为中文硬编码）。**深色模式已落地**，不在此列：`stores/settings.js` 的 `themeMode`（浅色 / 深色 / 跟随系统，含 `matchMedia` 实时联动）由 `apply()` 在 `<html>` 上切 `.dark`，Element Plus 组件深色来自 `main.js` 引入的 `theme-chalk/dark/css-vars.css`，自研变量深色覆盖在 `styles/index.css` 的 `html.dark` 段。
- **AI 未读摘要 / 智能回复建议 / AI 客服机器人**（可复用现有 DashScope 接入）。
- **直播连麦 + 礼物打赏**（已完成：连麦走主播↔嘉宾 1:1 WebRTC P2P + 主播端 Canvas 合成 / WebAudio 混音→WebM 分片→观众 MSE 播放，合成画面为**抖音式左右分屏**（左主播、右嘉宾）并带主播「断开连麦」入口；三方视听出口已打通——观众听混音轨、主播与嘉宾各听界面层未静音的对端 `<video>`；礼物走新建虚拟积分钱包，充值为模拟支付桩；**弹幕房管**——主播禁言名单（5/30/60 分钟与永久）+ 自定义屏蔽词，零 DDL 存 Redis、按 anchorId 跨场次生效，机理见 `md/直播.md` §13）；**连麦 PK** 已落地（两个都在播的房间在时间窗内比**热度增量**，礼物 / 点赞 / 观众投票加权，每秒一帧 `pk` 同时扇出给两个房间、前端按 `roomId` 认领左右，零 DDL 存 Redis，机理见 `md/直播.md` §14）；**直播回放存档**已落地（见上一条与 `md/直播.md` §15）；**主播数据面板**已落地（零 DDL 纯读聚合，见 `md/直播.md` §16）。待办：**付费 / 密码直播**。

---

## 社区 / 联系方式

- **问题反馈 / 功能建议**：[GitHub Issues](https://github.com/zhuxiaoyi412826/Veytalk/issues)
- **讨论**：[GitHub Discussions](https://github.com/zhuxiaoyi412826/Veytalk/discussions)（若仓库已开启）

> 🚧 暂无 QQ 群 / 微信群 / Discord 等即时社区渠道；后续如建立会在此更新。

---

## 贡献者名单

[![Contributors](https://contrib.rocks/image?repo=zhuxiaoyi412826/Veytalk)](https://github.com/zhuxiaoyi412826/Veytalk/graphs/contributors)

- [@zhuxiaoyi412826](https://github.com/zhuxiaoyi412826)

> 头像墙由 [contrib.rocks](https://contrib.rocks) 依据 GitHub 提交记录自动生成（需仓库公开可见）。

---

## 致谢

本项目站在这些优秀开源项目肩上（完整版本见「一、技术栈与版本」）：

- **后端**：[Spring Boot](https://spring.io/projects/spring-boot)、[MyBatis-Plus](https://baomidou.com)、[Sa-Token](https://sa-token.cc)、[Knife4j](https://github.com/xiaoymin/knife4j)、[MinIO](https://min.io)、[Hutool](https://hutool.cn)、[BouncyCastle](https://www.bouncycastle.org)、[OkHttp](https://square.github.io/okhttp/)、[Caffeine](https://github.com/ben-manes/caffeine)
- **前端**：[Vue 3](https://vuejs.org)、[Vite](https://vite.dev)、[Element Plus](https://element-plus.org)、[Pinia](https://pinia.vuejs.org)、[Vue Router](https://router.vuejs.org)、[axios](https://axios-http.com)、[sql.js](https://github.com/sql-js/sql.js)、[hls.js](https://github.com/video-dev/hls.js)、[marked](https://marked.js.org)、[DOMPurify](https://github.com/cure53/DOMPurify)、[spark-md5](https://github.com/satazor/js-spark-md5)、[dayjs](https://day.js.org)
- **桌面端 / 音视频**：[Electron](https://www.electronjs.org)、[electron-builder](https://www.electron.build)、[node-forge](https://github.com/digitalbazaar/forge)、[FFmpeg](https://ffmpeg.org)
- **基础设施**：[MySQL](https://www.mysql.com)、[Redis](https://redis.io)

> 各依赖的版权与许可证归其作者所有；本项目以 Apache-2.0 发布。

