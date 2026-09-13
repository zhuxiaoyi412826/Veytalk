# MinIO 部署指南

> 事实来源：`pom.xml`（依赖版本）、`im-bootstrap/src/main/resources/application.yml` L254-274（配置项）、
> `im-file` 的 `MinioClientConfig` / `MinioFileStorage` / `FileServiceImpl`（运行行为）、根 `README.md` 第十一节。
> 适用环境：Windows + 本机 MinIO。其他系统仅启动命令不同，配置项完全一致。

---

## 目录

- [一、先厘清：两个 MinIO 别搞混](#一先厘清两个-minio-别搞混)
- [二、这步是可选的](#二这步是可选的)
- [三、下载 MinIO Server](#三下载-minio-server)
- [四、启动 MinIO Server](#四启动-minio-server)
- [五、项目侧配置](#五项目侧配置)
- [六、凭据对应关系（最易配错）](#六凭据对应关系最易配错)
- [七、三个必须知道的行为](#七三个必须知道的行为)
- [八、验证部署成功](#八验证部署成功)
- [九、常见问题排查](#九常见问题排查)
- [十、配置项速查表](#十配置项速查表)

---

## 一、先厘清：两个 MinIO 别搞混

「MinIO」在本项目里指两个不同的东西，你要**手动下载并启动的只有服务端**：

| 组件 | 是什么 | 你要不要管 |
|---|---|---|
| **MinIO Java SDK** `io.minio:minio:8.6.0`（+ `com.squareup.okhttp3:okhttp-jvm:5.1.0`） | 客户端库，已在 `im-file/pom.xml` 声明 | **不用下载**，Maven 构建时自动拉取，已打进 `im-server.jar` |
| **MinIO Server** | 独立运行的对象存储服务（一个可执行文件） | **这才是你要下载并启动的** |

> SDK 与 Server 通过标准 S3 API 通信，二者版本无需严格对齐——SDK 8.6.0 兼容任意近期 Server 版本。

---

## 二、这步是可选的

**默认 `local` 存储已能跑通上传、下载、头像、附件全部功能**，文件落在 `${user.home}/im-files`，不装 MinIO 也不影响任何业务。

装 MinIO 的唯一理由是：将来**分布式/多实例部署**时，文件不能绑在某一台机器的本地磁盘上，需要独立的对象存储。若只是本机开发或单机部署验证功能，**可以完全跳过本文档**。

---

## 三、下载 MinIO Server

MinIO Server 采用日期版本号（形如 `RELEASE.2024-XX-XX...`），**直接下最新稳定版即可**，无需特意找旧版本。

Windows 官方直链（单个 `.exe`，免安装）：

```
https://dl.min.io/server/minio/release/windows-amd64/minio.exe
```

下载后放到一个固定目录，例如 `D:\minio\minio.exe`。

> 其他平台：Linux/macOS 从 `https://dl.min.io/server/minio/release/` 对应目录下载；也可用 Docker 镜像 `minio/minio`。配置项与本文完全一致，仅启动命令不同。

---

## 四、启动 MinIO Server

在 PowerShell 中执行（**先设凭据，再启动**）：

```powershell
$env:MINIO_ROOT_USER     = "minioadmin"
$env:MINIO_ROOT_PASSWORD = "minioadmin"
D:\minio\minio.exe server D:\minio\data --console-address ":9001"
```

- `D:\minio\data`：文件实际落盘目录，MinIO 会把对象存到这里，自行指定一个有写权限的路径。
- **`9000` = API 端口**：项目连接的就是它（endpoint 默认 `http://127.0.0.1:9000`）。
- **`9001` = Web 控制台端口**：浏览器打开 `http://127.0.0.1:9001`，用上面设置的 `minioadmin / minioadmin` 登录，可视化管理桶与对象。

> 密码至少 8 位，`minioadmin` 正好满足下限。生产环境务必换成强凭据，并同步更新第五节的项目配置。

启动成功后控制台会打印 API 与 Console 两个监听地址，保持这个窗口开着（关掉即停服）。

---

## 五、项目侧配置

所有 MinIO 配置项都是 `${环境变量:默认值}` 形式（见 `application.yml` L254-274），因此有两种等效做法：

### 方式 A：设环境变量后重启后端（推荐，不改任何文件）

```powershell
$env:IM_FILE_STORAGE = "minio"                       # 唯一必须改的开关：local → minio
$env:MINIO_ENDPOINT  = "http://127.0.0.1:9000"       # 与第四节 API 端口一致
$env:MINIO_ACCESS_KEY = "minioadmin"                 # = MINIO_ROOT_USER
$env:MINIO_SECRET_KEY = "minioadmin"                 # = MINIO_ROOT_PASSWORD
$env:MINIO_BUCKET     = "im-files"                         # 桶名，启动时自动创建

java -jar im-bootstrap\target\im-server.jar
```

由于这些默认值本就与 MinIO 出厂凭据一致，严格来说**只需设 `IM_FILE_STORAGE=minio` 这一个**，其余不设也能连上本机默认实例。显式写出是为了让你一眼看清全部可调项。

### 方式 B：改配置文件后重新打包

把 `application.yml` L257 的：

```yaml
storage: ${IM_FILE_STORAGE:local}
```

改为：

```yaml
storage: ${IM_FILE_STORAGE:minio}
```

然后 `mvn package -DskipTests` 重新打包。除非要把 minio 固化为默认，否则优先用方式 A。

---

## 六、凭据对应关系（最易配错）

MinIO Server 的启动变量与项目配置项**名字不同但值必须一致**，这是最常见的踩坑点：

| MinIO Server 启动变量 | 项目配置项（`application.yml`） | 环境变量 | 默认值 |
|---|---|---|---|
| `MINIO_ROOT_USER` | `im.file.minio.access-key` | `MINIO_ACCESS_KEY` | `minioadmin` |
| `MINIO_ROOT_PASSWORD` | `im.file.minio.secret-key` | `MINIO_SECRET_KEY` | `minioadmin` |

即：Server 端的「ROOT_USER」= 项目端的「access-key」；Server 端的「ROOT_PASSWORD」= 项目端的「secret-key」。两边填一样的值即可。

---

## 七、三个必须知道的行为

1. **桶不用手动建。** `MinioFileStorage#ensureBucket()` 在后端启动时自动检查 `MINIO_BUCKET`（默认 `im-files`），不存在就创建。你会在启动日志看到 `[MinIO] 存储桶创建成功: im-files` 或 `[MinIO] 存储桶已存在: im-files`。
   > **桶名必须符合 S3 命名规范：至少 3 个字符**，仅含小写字母/数字/点/连字符。像 `im` 这种 2 字符名会被 MinIO SDK 的 `validateBucketName` 直接拒绝，抛 `IllegalArgumentException` 导致后端启动失败——这是本项目早期默认值踩过的坑，现已改为 `im-files`。

2. **连不上会直接启动失败（fail-fast）。** 这是刻意设计：既然显式选了 MinIO，连不上就让它在启动阶段暴露，而不是拖到第一次上传才报错——那时排查方向更难定位。因此**务必先启动 MinIO Server，再启动后端**。失败时抛 `IllegalStateException`，提示「MinIO 存储桶初始化失败……请确认服务已启动、endpoint 与凭据正确」。

3. **图片直链默认关闭。** `im.file.use-presigned-url: false`，文件访问走后端受控下载地址 `/api/file/download/{id}`，每次访问都会校验登录态与会话成员身份，并签发一个 30 分钟有效的文件票据（`im.jwt.file-ticket-ttl-seconds`，默认 1800 秒）。只有在确认桶不可公开、且需要给应用服务器/CDN 减压时，才把它打开改用 MinIO 预签名直链。

---

## 八、验证部署成功

按顺序确认三件事：

1. **后端启动日志**出现 MinIO 初始化痕迹（说明配置已生效、连接成功）：
   ```
   [MinIO] 初始化客户端: endpoint=http://127.0.0.1:9000, bucket=im-files
   [MinIO] 存储桶已存在: im-files        （首次启动为「存储桶创建成功: im-files」）
   ```
   若日志里是 `[本地存储] 初始化完成`，说明 `IM_FILE_STORAGE` 没设成 `minio`，回到第五节。

2. **上传测试**：登录后在前端上传一张头像或聊天图片。开启 DEBUG 日志时会看到：
   ```
   [MinIO] 写入成功: bucket=im-files, key=2026/09/13/xxxx.png, bytes=...
   ```

3. **控制台核对**：打开 `http://127.0.0.1:9001` → 进入 `im-files` 桶 → 应能看到按 `yyyy/MM/dd/` 分目录存放的对象。同时数据库 `im_file` 表新增一行，`storage_type=minio`、`bucket=im-files`、`object_key` 与控制台路径一致。

> 切换存储后，**旧的本地文件不会自动迁移**。`im_file` 每行都记了 `storage_type`，下载时会校验「记录所属存储」与「当前装配存储」是否一致，不一致返回 `FILE_STORAGE_UNAVAILABLE`。因此从 local 切到 minio 后，历史 local 文件仍需要旧实现才能取到——生产切换前应做数据迁移，或保持存储实现不变。

---

## 九、常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 后端启动即失败，提示「MinIO 存储桶初始化失败」 | Server 没启动 / endpoint 写错 / 凭据不匹配 | 确认第四节窗口在运行、`9000` 端口可访问、access-key/secret-key 与 ROOT_USER/PASSWORD 一致 |
| 日志仍是 `[本地存储] 初始化完成` | `IM_FILE_STORAGE` 未设为 `minio` | 按方式 A 重设环境变量后重启，或按方式 B 改配置重新打包 |
| 编译/运行报 `cannot access okhttp3.HttpUrl` 或 `NoClassDefFoundError` | OkHttp 5.x 的 Gradle 多平台空壳 jar 问题 | 项目已在 `im-file/pom.xml` 排除空壳 `okhttp`、显式引入 `okhttp-jvm:5.1.0`，正常不会遇到；若自行改动依赖需保留这两处 |
| 上传报 `MaxUploadSizeExceededException` 而非 `UPLOAD_TOO_LARGE` | 只调了 `im.file.max-size` 没调 multipart 上限 | `im.file.max-size`（默认 20MB）必须与 `spring.servlet.multipart.max-file-size` 一起改，否则请求先被 multipart 拦截 |
| 图片能存但前端显示不出来 | 票据过期或访问未带登录态 | 受控地址靠 URL 上的短时票据渲染 `<img>`，确认 `im.jwt.file-ticket-ttl-seconds`（默认 1800）够用；跨域部署时确认前端能访问 `/api/file/download` |

---

## 十、配置项速查表

`application.yml` 中与文件存储相关的全部配置项（L254-274）：

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `im.file.storage` | `IM_FILE_STORAGE` | `local` | 存储实现：`local` \| `minio`，切换总开关 |
| `im.file.access-url-prefix` | — | `/api/file/download` | 受控下载地址前缀 |
| `im.file.use-presigned-url` | — | `false` | 是否对图片返回 MinIO 预签名直链 |
| `im.file.max-size` | — | `20MB` | 聊天附件大小上限，需与 multipart 上限一起改 |
| `im.file.max-avatar-size` | — | `2MB` | 头像大小上限 |
| `im.file.local.base-dir` | `IM_FILE_DIR` | `${user.home}/im-files` | 本地存储根目录，按 `yyyy/MM/dd/uuid.ext` 落盘 |
| `im.file.minio.endpoint` | `MINIO_ENDPOINT` | `http://127.0.0.1:9000` | MinIO API 地址 |
| `im.file.minio.access-key` | `MINIO_ACCESS_KEY` | `minioadmin` | = Server 的 `MINIO_ROOT_USER` |
| `im.file.minio.secret-key` | `MINIO_SECRET_KEY` | `minioadmin` | = Server 的 `MINIO_ROOT_PASSWORD` |
| `im.file.minio.bucket` | `MINIO_BUCKET` | `im-files` | 桶名（须≥3字符符合 S3 规范），启动时自动创建 |
| `im.jwt.file-ticket-ttl-seconds` | — | `1800` | 受控下载票据有效期（秒） |

---

### 一句话流程

**下载 `minio.exe` → 设 ROOT 凭据启动（API 9000 / 控制台 9001）→ 项目设 `IM_FILE_STORAGE=minio` 后重启后端 → 看日志出现 `[MinIO] 存储桶创建成功` 即成功。** 桶自动建，连不上会 fail-fast，默认走受控下载不暴露裸直链。
