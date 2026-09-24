# 远程控制被控端 Agent 使用说明

本文档说明如何编译、启动、配置被控端 `im-remote-agent`（Agent），以及一次远程会话的完整授权流程。

## 一、它是什么

Agent 是运行在**被控制那台电脑**上的独立 Java 程序（纯 JDK 实现，零第三方依赖：
截屏用 `java.awt.Robot`、界面用 Swing、长连接用 `java.net.http.WebSocket`、加密用 `javax.crypto`）。

整体拓扑是**服务端中继**，不做 P2P：

```
被控端 Agent ──WS──> 后端 im-remote 中继 <──WS── 控制端（im-ui 浏览器 / Electron）
     │                        │
 Robot 截屏/键鼠/文件/系统   会话状态机 + 一次性票据 + 审计落库
```

两端都主动连服务器（出站连接），天然穿越 NAT/防火墙。中继服务器只按会话转发帧、
不落任何解密内容——开启 AES 时它只见密文与路由元数据。

## 二、环境要求

- JDK 21（与后端一致）。Agent 需要**图形环境**，不能以 `-Djava.awt.headless=true` 运行；
  无显示器的服务器不适合作为被控端。
- 能访问后端 HTTP / WebSocket 地址的网络。

## 三、编译打包

在仓库根目录执行（会连带编译其依赖的父 pom，但 Agent 不依赖任何 im-* 业务模块）：

```powershell
mvn -q -pl im-remote-agent -am clean package -DskipTests
```

产物为 fat jar：

```
im-remote-agent/target/im-remote-agent-jar-with-dependencies.jar
```

拷到任意装有 JRE 21 的机器，进入一个**固定工作目录**后运行即可
（`config.properties`、本地操作日志都落在该工作目录下）。

## 四、启动

```powershell
java -jar im-remote-agent-jar-with-dependencies.jar
```

装的是 IM 桌面端安装包的话不用手动跑：桌面端启动时会自动用内置 JRE 后台拉起 Agent
（已在运行则跳过），关 IM 窗口也不会杀 Agent；只想跑被控端不开 IM 主程序时双击
`resources\启动被控端.bat` 即可。两种方式的配置都落在 `%USERPROFILE%\im-remote-agent`。

首次启动会：

1. 在工作目录生成 `config.properties`，并写入一个随机持久化的 `deviceId`
   （重装系统前不变，服务端据此识别「这台机器」，同一账号下多台设备靠它区分）
   与一个 6 位随机 `accessCode` 识别码（窗口上可直接改，改后以窗口为准）。
2. 弹出主窗口。**两种接入模式**：
   - **账号模式**：填 `server.url` / `account.username` / `account.password`，Agent 自动登录并连上中继，
     设备会出现在该账号的「我的设备」列表，同账号可直接点选连接。
   - **识别码模式（免账号，ToDesk 式）**：账号密码留空，Agent 以匿名身份连上中继，
     把窗口上显示的识别码报给控制方即可；设备不进任何账号的设备列表，只凭识别码路由。
     被控方仍是「谁来都要我同意才能控」——弹窗确认机制两种模式完全一致。

> exe 化打包（jpackage 生成免 JRE 的原生可执行）参见同目录《打包为EXE和Android指南.md》，
> 把主 jar 换成本文档的 fat jar、主类换成 `com.im.remote.agent.AgentApplication` 即可。

## 五、config.properties 配置项

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `server.url` | `http://127.0.0.1:8080` | 后端 HTTP 基址；WS 地址默认由它推导（http→ws、https→wss） |
| `server.ws` | 空 | 显式覆盖 WS 基址（仅在 HTTP 与 WS 不同域名/端口时填） |
| `account.username` | 空 | 被控端登录账号（与 IM 系统同一套账号体系）；**留空则走识别码模式** |
| `account.password` | 空 | 登录密码；留空同上 |
| `accessCode` | 自动生成 | 识别码：6-12 位大写字母数字，控制方凭它跨账号找到本机；主窗口可改可随机 |
| `local.infoPort` | `18923` | 本机识别码只读接口端口（仅绑 127.0.0.1）：本机浏览器的「远程」页靠它展示绿色「本机识别码」面板；端口被占时接口静默不启，不影响主流程 |
| `deviceId` | 自动生成 | 设备唯一标识，首次启动生成后持久化，请勿手动改动 |
| `device.name` | 取机器名 | 设备展示名（控制端设备列表里看到的名字） |
| `file.allowRoots` | 本机所有可读盘 | 允许远程访问的目录根，**分号分隔**，如 `C:\Users\demo\Desktop;D:\work`。越界路径会被 Agent 拒绝 |
| `security.allowDanger` | `false` | 高危操作总开关：删除文件、结束进程、cmd 执行、关机/重启。关掉后这些指令一律回 error |
| `security.refuse` | `false` | 拒绝接入（免打扰）：开启后收到任何远程邀请都直接回「拒绝」，设备状态置为「拒绝接入」 |

> **安全提醒**：`config.properties` 含明文账号密码，是「无人值守自启动重连」这一演示场景的刻意取舍。
> 请把它存放在受控目录、不要提交到版本库；生产部署应收窄 `file.allowRoots`、默认关闭 `security.allowDanger`。

主窗口上也提供了这些开关的勾选框（拒绝接入、允许高危操作）、账号/服务器地址输入框、
连接/断开按钮和实时日志区，改勾选会立即写回 `config.properties`。

## 六、一次远程会话的流程

1. **控制端发起**（二选一，控制端自己必须登录账号）：
   - 设备列表点「发起远程」：限同账号设备；
   - 或输入对方识别码点「凭识别码连接」：跨账号/免账号设备均可，后端限流每分钟 5 次，
     识别码错误与设备不在线回同一提示（防探测）。
   后端生成一次性 ticket 与 AES 密钥，向对应 Agent 推送 `invite`。
2. **被控端弹窗**：Agent 弹出 Swing 确认框，显示控制方昵称与请求的权限。
   - 可选择**允许（可操作）**、**允许（只读）**或**拒绝**——授权时**只能降档不能升档**（请求可操作、你可只放行只读）。
   - 只有点允许，会话才会进入 `active`。这满足需求「必须被控方同意才能远程」。
3. **控制端建连**：控制端轮询到会话 `active` 后拿到 ticket，用 ticket 建立数据面 WS，开始收画面、发输入。
4. **会话期间**：
   - 被控端屏幕上有一条**置顶悬浮告警条**「正在被 XX 控制」，点击可**立即结束**会话。
   - 只读模式下，任何鼠标键盘输入都被服务端拦截并回 `READONLY`。
   - 文件、系统、剪贴板操作按配置开关放行；高危操作全部写审计并上报。
5. **结束**：任一方结束、或**空闲超时**（默认 5 分钟无帧往来）、或连接断开，会话收尾落库，
   记录结束原因与转发总字节数。控制端「审计」抽屉可查看本会话的操作明细。

## 七、断线与重连

- Agent 与后端都靠心跳保活（建议 30s，服务端 90s 判离线）。
- Agent 掉线后按**指数退避**（1s 起、上限 30s）自动重连并恢复在线状态；杀后端重启，Agent 会自动重新上线。
- 一台被控机同一时刻只服务一个控制端：会话存续期间收到的新邀请会被直接拒绝。

## 八、常见问题

- **启动报 headless / 无法弹窗**：确认没有 `-Djava.awt.headless=true`，且运行在有桌面的会话里。
- **连不上 / 一直重连**：核对 `server.url` 端口是否可达、账号密码是否正确（识别码模式则核对识别码是否为 6-12 位字母数字）。后端需已加载 `im-remote` 模块且 `im.remote.enabled=true`。
- **收到邀请无弹窗、直接失败**：检查是否开启了「拒绝接入」，或设备正被其他会话占用（忙）。
- **文件操作被拒**：路径超出了 `file.allowRoots`；高危操作被拒则是 `security.allowDanger=false`。
- **画面花屏/丢块**：属增量截屏的丢帧现象，Agent 每 5 秒会补发全屏关键帧自愈；持续异常可下调画质/帧率。
- **用内置 JRE 时一直不在线 / 窗口显示「启动失败」**：裁剪 JRE 缺加密模块（`jdk.crypto.ec`、`jdk.crypto.mscapi`）或宿主机 `JAVA_TOOL_OPTIONS` 污染内置 JRE；按《Electron打包指南》十二节的模块集重跑 jlink。bat 与桌面端自启已主动清空该环境变量。
- **控制端提示「会话结束」但被控端明明点了同意**：后端还跑着旧代码（会话 ticket/密钥内存容器修复未生效），重启后端再试。
