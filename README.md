# IM 即时通讯系统

> 一套开箱即用的**多模块企业级即时通讯系统**：用 Spring Boot 4 + JDK 21 + Vue 3 实现社交关系、单聊群聊、电话语音、文件传输、远程控制、AI 面试官、抖音式 HLS 直播，后端最终打成**一个可执行 jar** 全搞定。

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.8-brightgreen) ![JDK](https://img.shields.io/badge/JDK-21-orange) ![Vue](https://img.shields.io/badge/Vue-3.5.42-4FC08D) ![MySQL](https://img.shields.io/badge/MySQL-8.x-4479A1) ![Redis](https://img.shields.io/badge/Redis-5%2B-DC382D) [![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**工程形态**：后端项目，采用 Maven 多模块结构，共 14 个子模块，由根 pom.xml 统一管理,[模块详细内容]()

其中 13 个 repackage 成单 jar（`im-bootstrap/target/im-server.jar`）；

被控端 Agent（`im-remote-agent`）是独立 fat jar，跑在被控机器上、不打进后端；

前端是**三个独立工程**

聊天端 `im-ui`（Web / 桌面 / 手机浏览器）

管理后台 `im-admin-ui`（只在电脑浏览器用）

官网 `im-website`（纯静态产品展示与下载站，无后端接口依赖），都不参与 Maven 构建，前两个通过 Vite 代理与后端通信

<u>*<u>***未实现**文件传输助手 (不用下载PC端和登录网页端)，通过APP扫码登录传输文件*</u>*</u>

## 目录

- [特性](#特性)
- [截图 / Demo](#截图--demo)
  - [聊天](#聊天)
  - [面试](#面试)
  - [远程控制](#远程控制)
  - [视频](#视频)
  - [直播](#直播)
  - [手机端](#手机端)
  - [后台管理](#后台管理)
- [快速开始](#快速开始)
- [许可证](#许可证)
- [开发指南与贡献](#开发指南与贡献)
  - [源码构建](#源码构建)
  - [测试](#测试)
  - [代码规范](#代码规范)
  - [提 Issue](#提-issue)
  - [提 PR](#提-pr)
  - [分支策略](#分支策略)
- [路线图 / Roadmap](#路线图--roadmap)
  - [🚧 代码就绪，待接真实服务商 / 依赖服务器](#-代码就绪待接真实服务商--依赖服务器)
  - [📋 规划中](#-规划中)
- [社区 / 联系方式](#社区--联系方式)
- [贡献者名单](#贡献者名单)
- [致谢](#致谢)

## 特性

**账号与鉴权（im-user）**

- 注册登录（图形 / 短信 / 邮箱验证码）、密码找回（手机短信 / 邮箱双通道）
- JWT 鉴权 + RBAC 权限、资料与头像、在线状态

**会话与消息（im-conversation / im-message）**

- 单聊 / 群聊会话，置顶、免打扰、未读角标、已读位点、消息收发、**撤回、已读/送达回执**、引用/回复、 **离线消息补推**
- 文本 / 图片 / 语音 / 文件消息，敏感词过滤、**图片内容审核**
- **幂等三重设防**（Redis 标记 + 唯一键 + 回查）、断网本地待发送队列重提交
- 消息历史分页、本地优先（端侧 SQLite/sql.js 缓存，本地凑得出整页就零请求）
- 信息检索搜索框

**社交关系（im-friend）**

- 好友申请 / 同意 / 拒绝、好友管理（备注、分组）
- **黑名单（单向阻断）**：多处拉黑入口 + 集中管理

**群组（im-group）**

- 建群 / 改群、成员管理、群主转让、解散群
- 群权限与禁言、@ 提醒

**实时通信（im-websocket）**

- WebSocket 长连接、心跳保活、断线重连
- 在线状态（Redis `im:online`）、多端会话管理、封号/改密全端下线
- 电话-视频通话

**文件（im-file）**

- MinIO / 本地双实现，秒传 / 断点续传 / 大文件分片，单文件上限 2GB
- 受控地址 + 一次性票据下载；聊天附件可选 / 可拖 / 可粘，三者都先进「待发送托盘」再手动发送

**远程控制（im-remote / im-remote-agent）**

- 手机/PC **远程控制电脑**：ToDesk 式识别码跨账号接入 + 名下设备邀请
- **三档直连阶梯**：中继 WS → 局域网 TCP → UDP 打洞（Go-Back-N）
- **录屏审计**（桌面端本地 WebM + sidecar）、敏感操作审计台账

**AI 能力（im-ai）**

- **AI 面试官**：本地知识库 BM25 检索增强（RAG）+ SSE 逐 token 流式对话；语音输入（按住说话转写）与**电话面试**（qwen3-asr-flash 识别 + qwen3-omni-flash 朗读，半双工免提连续对话，手机端适配）
- **聊天语音消息 + 长按转文字**：单聊 / 群聊输入区可切「语音」模式按住说话，松手即发语音消息；长按（或右键）语音气泡→「转文字」，结果像微信一样内联贴在语音条下方。ASR 与面试页共用同一个 `qwen3-asr-flash`（`DashScopeSpeechToTextSpi` 按 fileId 直读存储字节），未配 key 时降级为一句提示
- **全网检索**：消息搜索框的「网络」分组（见「十三」）
- 对接阿里云百炼 DashScope（OpenAI 兼容协议），按轮检索、内存索引热更新

**直播（im-live）**

- **抖音式 HLS 直播**：Electron 桌面端 ffmpeg 采集（屏幕 gdigrab / 摄像头 dshow）→ H.264 硬编 → **一份源同时编成多档** → 切 HLS fMP4 1s 分片 → HTTP PUT 上传到媒体服务器 → 观众 hls.js 拉流（见「十四」）
- **多档清晰度阶梯 + 观众端真切档**：推 1440p/1080p/720p/480p（主播只选一个原画，高于采集源的档自动砍掉）
- **弹幕/点赞 WS `/ws/live`**、在线人数、礼物飘屏、连麦上下麦、**PK 进度帧**
- **弹幕房管**（主播禁言名单 + 自定义屏蔽词）与**连麦 PK**（两个在播房间比热度增量 + 观众投票），
- **主播数据面板**：`GET /api/live/anchor/stats` 一次换回时间窗内的概览（场次 / 时长 / 人次 / 峰值 / 礼物积分）、
- **付费 / 密码直播**：开播可选设「房间密码」或「入场积分价」，未解锁观众拉不到画面、进不了弹幕；
- **ENDED 聊天模式**：关播后画面作废但聊天室保留 60 分钟

**公共基础设施（im-common / im-bootstrap）**

- **三级缓存**（Caffeine L1 → Redis L2 → MySQL L3，穿透防护 + 降级）
- SPI 契约跨模块解耦、TraceId 全链路追踪、全局异常统一转 `Result`、限流
- **im-bootstrap**：唯一启动模块（main 类 + `application.yml`），依赖全部业务模块并 repackage 成单 jar `im-server.jar`，启动打印生效 profile / 端口 / 存储实现的横幅

---

## 截图 / Demo

> 🚧 **TODO：待补充界面截图与操作 GIF**——登录、会话列表、单聊 / 群聊、远程控制、AI 面试官、直播间。

---

### 聊天

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025073710.png)

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025073829.png)

### 面试

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025074120.png)

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/image-20261025074611170.png)



![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025074459.png)

### 远程控制

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025075030.png)

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/image-20261025074956478.png)

### 视频

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025075246.png)

### 直播

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025081633.png)

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025081522.png)

### 手机端

<img src="https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/31374.jpg" style="zoom:25%;" />

<img src="https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/31376.jpg" style="zoom:25%;" />

<img src="https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/31377.jpg" style="zoom:25%;" />



<img src="https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/31378.jpg" style="zoom:25%;" />

<img src="https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/31379.jpg" style="zoom:25%;" />

### 后台管理

![](https://zhuxiaoyi-1300958454.cos.ap-guangzhou.myqcloud.com/img/20261025080152.png)

## 快速开始

> 完整分步流程（环境准备 → 环境变量 → 建库灌数据 → 起后端 → 起前端 → 打包客户端）见 [`README详细开发流程.md`](md/项目启动流程.md)；下面是最短跑通路径。

**环境依赖**：JDK 21、Maven 3.9+、MySQL 8.x、Redis 5+、Node.js 18+、MiniIO、 ffmpeg7.1

**0）环境变量**

**数据库凭据只从环境变量读取，代码与配置文件里不含任何明文密码。**
所有变量都有默认值，本地开发只需设置 `MYSQL_USER` 和 `MYSQL_PASSWORD` 两个。

| 变量                     | 默认值                      | 说明                                                         |
| ------------------------ | --------------------------- | ------------------------------------------------------------ |
| `MYSQL_USER`             | `root`                      | **建议显式设置**                                             |
| `MYSQL_PASSWORD`         | 空                          | **必须设置**（除非本地 root 真的无密码）                     |
| `REDIS_HOST`             | `127.0.0.1`                 |                                                              |
| `REDIS_PORT`             | `6379`                      |                                                              |
| `SERVER_PORT`            | `8080`                      |                                                              |
| `SPRING_PROFILES_ACTIVE` | `dev`                       | `dev` 会在响应里回显验证码明文，**生产必须改成 `prod`**      |
| `IM_JWT_SECRET`          | 内置开发用长串              | Sa-Token 的 JWT 签名密钥，**生产必须替换**                   |
| `IM_FILE_STORAGE`        | `local`                     | `local` 或 `minio`                                           |
| `IM_SMS_PROVIDER`        | `mock`                      | 短信下发通道。`mock` 只写日志不发真短信；接真实服务商时改成对应实现名（如 `aliyun`），**生产必须替换** |
| `MAIL_USERNAME`          | 空                          | 完整邮箱地址（如 `123456@qq.com`），邮箱验证码的发件人       |
| `MAIL_PASSWORD`          | 空                          | QQ 邮箱的 **SMTP 授权码**，不是 QQ 登录密码（用登录密码会 535 认证失败）。两者都不配时邮箱登录与邮箱找回密码不可用 |
| `MINIO_ACCESS_KEY`       | `minioadmin`                |                                                              |
| `MINIO_SECRET_KEY`       | `minioadmin`                |                                                              |
| `IM_LOG_HOME`            | `D:/rizi1/IM`               | 日志目录，见「六、启动后端 → 日志输出与目录」；Linux 下必须覆盖成对应路径（如 `/data/logs/im`） |
| `ALI_BABA_API_KEY`       | 空                          | AI 面试官的百炼（DashScope）API Key；未设置时仅面试功能不可用，其余功能照常 |
| `AI_MODEL_NAME`          | `qwen-flash`                | 百炼模型名（也可换 qwen-plus / qwen-max）                    |
| `AI_ASR_MODEL_NAME`      | `qwen3-asr-flash`           | 面试语音转写（ASR）模型名，与主管线同 key 不同模型           |
| `AI_TTS_MODEL_NAME`      | `qwen3-omni-flash`          | 面试电话模式朗读（TTS）模型名；神经音色按输出 token 计费     |
| `AI_TTS_VOICE`           | `Cherry`                    | 神经音色名（必须在所配 omni 模型的音色表内：`qwen3-omni-flash` 实测认 Cherry/Serena/Ethan/Chelsie/Bella，不认 Tina）；系统音色档不经这里 |
| `IM_AI_KNOWLEDGE_DIR`    | `D:/资料/知识库/面试官`     | AI 面试知识库目录，递归扫 `.md`/`.txt`，内容增删改后自动重建索引 |
| `IM_LIVE_ENABLED`        | `true`                      | 直播总开关；关掉则开播/观看接口与 `/ws/live` 弹幕端点全部拒绝服务 |
| `IM_LIVE_PUSH_BASE_URL`  | `http://127.0.0.1:8088/hls` | ffmpeg `-method PUT` 推流基址（写回环的前提是「推流端与媒体服务器同机」；桌面端装在另一台机器时，前端 `remapPushUrl()` 会把回环 host 换成后端 host，也可以直接在这里配成绝对地址） |
| `IM_DIRECT_PUNCH_PORT`   | `8947`                      | 反射服务监听的 UDP 端口，需单独放行 UDP 入站                 |

**1）数据库初始化**

`sql/` 下两个脚本，**先 `im_schema.sql` 建表、后 `im_data.sql` 灌数据**，各跑一次即可：

| 脚本            | 作用                            | 规模                                      | 重跑安全性                                     |
| --------------- | ------------------------------- | ----------------------------------------- | ---------------------------------------------- |
| `im_schema.sql` | 建库 + 建表 + 索引 + 虚拟生成列 | `im_db` 库、**34 张表**（分属 10 个模块） | ⚠️ 头部 `DROP TABLE` 会清空数据，**仅限全新库** |
| `im_data.sql`   | 灌演示种子数据                  | 写入 **16 张表**，其余 18 张建好即空      | ✅ 只删自己的种子 ID 段，**可安全重跑**         |

> ⚠️ **`im_schema.sql` 头部是 `DROP TABLE IF EXISTS` + 重建，重跑一次就会清空全库数据**。
> 它的「幂等」指的是重复执行得到同一套表结构，不是「不会动你的数据」。
> 已经有数据的库不要重跑这个脚本，改用本节末尾的「已有库的增量升级」方式。

**2）起后端**（默认 `dev` profile，端口 `8080`）：

```powershell
mvn clean package -DskipTests                    
java -jar im-bootstrap/target/im-server.jar
# springboot   im-bootstrap 模块
mvn spring-boot:run   
# 如果改动了后端代码先执行这个
mvn -f ..\pom.xml clean install -DskipTests
```

启动成功会打印横幅：接口文档 `http://localhost:8080/doc.html`、WebSocket `ws://localhost:8080/ws`。

| 地址                              | 用途                                                         |
| --------------------------------- | ------------------------------------------------------------ |
| http://localhost:8080/doc.html    | Knife4j 接口文档，按模块分成 9 个分组（01-09，含 AI 面试与远程控制） |
| http://localhost:8080/v3/api-docs | OpenAPI 3 原始 JSON                                          |
| ws://localhost:8080/ws            | WebSocket 端点（需先取票据）                                 |
| http://localhost:8080/api/        | 全部 REST 接口                                               |

**3）起前端**（Vite dev server，端口 `5173`，已配 `/api`、`/ws`、`/hls` 代理，**必须先起后端**）：

```powershell
cd im-ui
# 前端
npm install
npm run dev
cd im-admin-ui
npm install
npm run dev
# 后台管理
cd im-website
# 官网页面
npm install
npm run dev
```

浏览器打开 **http://localhost:5173**

浏览器打开 **http://localhost:5273**

浏览器打开 **http://localhost:5373**

**4) 起直播** 

```
cd electron
node live-media-server.js
```

**5）桌面 / 移动客户端**：Windows 桌面端（Electron）见 [`md/打包/Electron打包指南.md`](md/打包/Electron打包指南.md)，Windows EXE 与安卓 APK 见 [`md/打包/打包为EXE和Android指南.md`](md/打包/打包为EXE和Android指南.md)

## 许可证

本项目基于 **Apache License 2.0** 开源，完整条款见仓库根目录的 [`LICENSE`](LICENSE) 文件。

> 选 Apache-2.0 是因为它对 Java 生态友好：允许商用 / 修改 / 分发 / 私有使用，附带专利授权与免责条款，再分发时保留版权与许可证声明即可。

---

## 开发指南与贡献

面向想改代码、提 PR 的人。仓库：<https://github.com/zhuxiaoyi412826/Veytalk>。**分支模型 / 开发流程 / Commit 与 PR 规范 / Tag 与版本号 / 安全上报**的细则见 [`CONTRIBUTING.md`](CONTRIBUTING.md)（本节只给概览，冲突时以 CONTRIBUTING 为准）。

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

---

## 社区 / 联系方式

- **问题反馈 / 功能建议**：[GitHub Issues](https://github.com/zhuxiaoyi412826/Veytalk/issues)
- **讨论**：[GitHub Discussions](https://github.com/zhuxiaoyi412826/Veytalk/discussions)（若仓库已开启）
- **QQ:**2927883590  **微信:**zhuxiaoyizxyZXY

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

