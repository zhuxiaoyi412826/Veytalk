# Docker 部署指南（Ubuntu 24.04 · Docker Compose）

> 事实来源：`im-bootstrap/src/main/resources/application.yml`（983 行，60 个 `${ENV:默认值}` 占位符）、
> `application-prod.yml`、`im-bootstrap/pom.xml`（`finalName=im-server`，主类 `com.im.bootstrap.ImApplication`）、
> 根 `pom.xml`（`java.version=21`、Spring Boot 4.0.8）、`sql/im_schema.sql` / `sql/im_data.sql`、
> `im-common` 的 `logback-spring.xml`、`im-file` 的 `MinioFileStorage` / `LocalFileStorage`、`md/直播.md` §10.4-10.5、`electron/live-retention.js`。
>
> **与《[服务器部署指南](服务器部署指南.md)》的分工**：那一份讲「这台机器要装哪些软件、进程怎么管」，是**裸机口径**；
> 本一份只讲**同一套东西换成容器之后变了什么**——哪些 `127.0.0.1` 必须改成服务名、哪些默认值在容器里换了含义、
> 哪些坑是 Docker 特有的（UFW 对已发布端口不生效、Actuator 的 loopback 语义、`initdb.d` 只跑一次）。
> 两份冲突时以本份的容器口径为准，但**业务侧的红线（单实例、CORS 白名单、MinIO fail-fast、演示账号）两份完全一致**。

---

## 目录

- [〇、先看结论](#〇先看结论)
- [一、装 Docker（Ubuntu 24.04）](#一装-dockerubuntu-2404)
- [二、目录结构（先把骨架摆出来）](#二目录结构先把骨架摆出来)
- [三、两份环境变量文件（分工别搞混）](#三两份环境变量文件分工别搞混)
- [四、后端镜像 Dockerfile](#四后端镜像-dockerfile)
- [五、docker-compose.yml 全文](#五docker-composeyml-全文)
- [六、首次启动与数据库初始化](#六首次启动与数据库初始化)
- [七、migration 与权限缓存在容器里怎么跑](#七migration-与权限缓存在容器里怎么跑)
- [八、Nginx 容器：三站 + WS + 直播媒体面](#八nginx-容器三站--ws--直播媒体面)
- [九、按功能追加（回放清理 / TURN / AI / 监控）](#九按功能追加回放清理--turn--ai--监控)
- [十、备份、升级、回滚](#十备份升级回滚)
- [十一、健康检查与开机自启](#十一健康检查与开机自启)
- [十二、容器化专属故障速查](#十二容器化专属故障速查)
- [十三、什么时候不该用 Docker](#十三什么时候不该用-docker)

---

## 〇、先看结论

**这套 compose 里跑七个服务**（前五个必装，后两个按需）：

| 服务 | 镜像 | 干什么 | 对外发布端口 |
|---|---|---|---|
| `mysql` | `mysql:8.0` | 业务库 `im_db`（37 张表） | ❌ 不发布 |
| `redis` | `redis:7-alpine` | 会话 / 权限缓存 / 直播与通话瞬时态 | ❌ 不发布 |
| `minio` | `minio/minio` | 对象存储（文件、头像、附件） | ❌ 不发布 |
| `im-server` | 本目录 `Dockerfile` 构建 | 唯一的 Java 进程（HTTP + WebSocket + 定时任务） | ❌ 不发布（只给 nginx 反代） |
| `nginx` | `nginx:1.27-alpine` | 三份前端静态站 + `/api` `/ws` 反代 + 直播推流/播放口 | ✅ `80` `443`，按需 `8088` |
| `live-retention` | `node:22-alpine` | **只在开直播时**要：按保留期删回放分片目录 | ❌ |
| `coturn` | `coturn/coturn` | **只在公网跨 NAT 通话时**要：STUN/TURN 中继 | ✅ `3478` + 中继端口段 |

**四条容器化专属的改动，每一条都能让服务起不来或静默失效**：

1. **`127.0.0.1` 全部要换成服务名**。`MYSQL_HOST` / `REDIS_HOST` / `MINIO_ENDPOINT` 在裸机上默认 `127.0.0.1` 是对的，
   在容器里 `127.0.0.1` 指**它自己**，改不成服务名就是 `Connection refused`。这是本份文档存在的最大理由。
2. **不能横向扩。** compose 里不要写 `deploy.replicas`、不要 `docker compose up -d --scale im-server=2`。
   理由与裸机版同源且更硬：WS 扇出只认本进程连接表（`WsSessionManager` / `PushSpiImpl` 注释自陈跨节点未实现），
   且 **12 个 `@Scheduled` 没有一个 ShedLock**——两个副本就是「消息只有一半人收到 + 每个定时任务跑两遍」。
   要限资源就用 `deploy.resources.limits`，**副本数恒为 1**。
3. **UFW 挡不住 Docker 发布出去的端口。** Docker 直接改 `iptables`/`nftables` 的 `DOCKER` 链，绕过 `ufw` 的 `INPUT` 规则。
   所以「`ufw deny 3306` 就安全了」在容器形态下**不成立**：想不让外面碰到，就**别在 compose 里 `ports:` 发布它**，
   或者发布时写死前缀 `127.0.0.1:3306:3306`。这是默认口径，本份的 compose 全文照此写。
4. **`MANAGEMENT_ADDRESS=127.0.0.1`（默认值）在容器里意味着「只在容器自己那圈 loopback 上可达」**：
   宿主机 `curl localhost:8081` 即使把 8081 发布了也是拒的。要健康检查就在容器内问，要让外部 Prometheus 抓就把它设成 `0.0.0.0` 并**不发布端口**（同网段容器可抓）。

> 一句话总结：**这份文档不是「把裸机步骤翻译一遍」，而是把裸机上不成问题的三个假设——
> 「127.0.0.1 是本机」「防火墙能挡端口」「loopback 谁都摸得到」——在容器里逐条作废。**

---

## 一、装 Docker（Ubuntu 24.04）

两条路线，选一条即可。**不要两条都装**（`docker.io` 与 `docker-ce` 会互相顶掉 daemon，`apt` 卸载时还会留下两套 compose 插件）。

### 1.1 路线 A：Ubuntu 自带仓库（省依赖、更新跟系统走，推荐内网机）

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2 ca-certificates curl
sudo systemctl enable --now docker
sudo systemctl status docker --no-pager
docker --version && docker compose version     # 后者必须是 v2.x（输出 Compose version v2./v5.）
```

> 24.04 把 compose v2 单独打成 `docker-compose-v2` 包；如果你的源里没有这个包名，说明你实际走的是路线 B
> 或者源被裁剪过——那就直接用路线 B，别去装 `docker-compose`（那是 **v1 的 Python 版**，
> 命令是 `docker-compose` 带横杠、不支持本份文档里的 `depends_on.condition` / `deploy.resources` 等新写法）。

### 1.2 路线 B：Docker 官方仓库（版本新、能用 `docker compose` 全套特性）

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt update && sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

### 1.3 让当前用户免 sudo（可选，只做一次）

```bash
sudo usermod -aG docker $USER
newgrp docker        # 或者重新登录一次，否则组不生效
docker ps            # 不再报 permission denied 就成
```

> 生产上更克制的做法是**保留 sudo**：`docker` 组等价于 root（能挂任意宿主目录、能 `--privileged`）。
> 专用部署账号可以给它 `sudo systemctl restart docker` 这一条命令的 sudoers 白名单，而不是整个 docker 组。

### 1.4 `/etc/docker/daemon.json`（三件事：日志限额、客户端真实 IP、存储驱动）

```json
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "50m", "max-file": "5" },
  "live-restore": true,
  "default-shm-size": "256M"
}
```

```bash
sudo systemctl restart docker && docker info | grep -E "Storage Driver|Logging Driver|Cgroup Version"
```

- **`log-opts` 必须设。** 不设时 `docker compose logs` 那份 stdout 是无上限的 `json-file`，
  本项目 dev/DEBUG 级别会把消息正文往 stdout 打；`/var/lib/docker` 撑爆的正是这个，而不是应用自己的日志文件（那份有 `total-size-cap: 5GB` 兜着）。
- **`userland-proxy`**：如果你的直播推流口要用 Nginx 的 `allow <IP>` 白名单，或后端要拿真实客户端 IP，
  再加一行 `"userland-proxy": false`（Docker 的用户态代理会把源 IP 换成网关注址，`allow 127.0.0.1;` 这类规则于是永远匹配不上）。
  ★ 加完**必须实测**：`docker compose logs nginx` 看 `$remote_addr` 打出来是不是真实客户端地址，别改完就当生效。
- Ubuntu 24.04 默认 cgroup v2，上面的配置无需动 cgroup 驱动；**如果这台机器是 rootless Docker**，
  24.04 的 `kernel.apparmor_restrict_unprivileged_userns=1` 会拦住 rootless 的 namespace 创建，
  要么写 AppArmor 本地策略放行，要么本套部署直接用 rootful（推荐，别为这点安全收益把部署复杂度抬高）。

### 1.5 镜像加速（国内服务器拉不动 Docker Hub 时才需要）

```bash
sudo mkdir -p /etc/docker
# 用编辑器打开 daemon.json，在已有的 JSON 对象里加一行：
#   "registry-mirrors": ["https://<你的加速地址>"]
sudo systemctl restart docker
docker pull mysql:8.0     # 能拉下来才算配好；拉不动就先去云厂商控制台开「容器镜像加速」
```

> 别用 `echo ... >> daemon.json` 往文件里追加——那会写出两个 `{}` 拼接的非法 JSON，`dockerd` 直接起不来（第十二节有条对应的故障）。

> 更快的路子是**离线导入**：在能联网的机器上 `docker save` 六个镜像 → 拷到服务器 `docker load`。
> 内网机、以及「上线窗口只有十分钟」的场合，这条路比调镜像源可靠得多（第十二节速查表里有一条相关的失败表现）。

---

## 二、目录结构（先把骨架摆出来）

> **★ 本份文档已经不再是「只有文档」了：仓库里的 [`Docker/`](../../Docker/) 目录就是下面这棵树的成品，可以整体拷到服务器上。**
> 文档与文件的对应关系（照文档改文件、或照文件回查文档，都从这张表进）：
>
> | 文档章节 | 仓库里的文件 | 落地后在服务器上的路径 |
> |---|---|---|
> | §2 骨架 | `Docker/{data,web,certs,sql-init}/.gitkeep` | 目录本体（内容不进库） |
> | §3.1 `.env` | `Docker/.env.example` | `/opt/im-docker/.env`（复制后填值，不进库） |
> | §3.2 `conf/im.env` | `Docker/conf/im.env.example` | `conf/im.env`（`bootstrap.sh` 生成并同步凭据） |
> | §4 Dockerfile | `Docker/app/Dockerfile`（路线 A：拷 jar）<br>`Docker/app/Dockerfile.multistage`（路线 B：容器内构建） | `app/Dockerfile` |
> | §5 compose 全文 | `Docker/docker-compose.yml` | `docker-compose.yml` |
> | §6 SQL 初始化 | `Docker/sql-init/`（空，由 `bootstrap.sh` 从 `sql/` 拷入并重命名为 `01_/02_`） | `sql-init/` |
> | §7 权限缓存清理 | `Docker/scripts/clear-perm-cache.sh` | `scripts/` |
> | §8 nginx | `Docker/nginx/conf.d/im.conf.example` + `Docker/nginx/snippets/{proxy,ssl}.conf`<br>`Docker/app/extra/application-prod-extra.example.yml` | `nginx/conf.d/im.conf`（token 替换生成） |
> | §9 sidecar / TURN | `Docker/scripts/live-retention.js`（`bootstrap.sh` 从 `electron/` 拷入） | `scripts/` |
> | §10 备份升级 | `Docker/scripts/{import-sql,backup,update}.sh` | `scripts/` |
> | §11 健康检查与自启 | `Docker/scripts/{health.sh,im-docker.service}` | `scripts/` + `/etc/systemd/system/` |
> | §10.5 CI/CD | [`.github/workflows/build-and-package.yml`](../../.github/workflows/build-and-package.yml) | 跑在 GitHub Actions 上，产物推 GHCR |
>
> **落地只需要三步**（细节见 `Docker/README.md`）：把整个 `Docker/` 拷到 `/opt/im-docker` → `cp .env.example .env` 填值 → `bash scripts/bootstrap.sh`。
> `bootstrap.sh` 干的就是本节下面这些「手工摆目录、抄域名、拼凭据」的活，并且**幂等**（重复跑只会覆盖生成物，不会重复追加行）。
> 它不启动容器、不联网、不碰数据库；没装 docker 的机器（比如写这些文件的开发机）也能跑通，只是跳过最后 `docker compose config` 的静态校验。

约定部署根目录 `/opt/im-docker`。**`docker-compose.yml` 里的相对路径全部以它为基准**，别换目录再抄配置。

```
/opt/im-docker/
├── docker-compose.yml            ← 第五节
├── .env                          ← 第三节 A：compose 插值用（镜像 tag、三份密码、域名）
├── .env.example                  ← 同一份、值留空，进版本库
├── app/
│   ├── Dockerfile                ← 第四节
│   ├── im-server.jar             ← 构建机产物（路线 A）
│   └── extra/application-prod-extra.yml   ← CORS 白名单等「不能靠环境变量改」的那批
├── conf/
│   └── im.env                    ← 第三节 B：应用环境变量（喂给 java 进程）
├── sql-init/                     ← 仅首次建库用（第六节，生产建议留空）
│   ├── 01_im_schema.sql
│   └── 02_im_data.sql
├── nginx/
│   ├── conf.d/im.conf            ← 第八节
│   └── snippets/                 ← ssl params、proxy headers 复用片段
├── web/
│   ├── im-ui/                    ← 构建机 npm run build 的 dist 内容
│   ├── im-admin-ui/
│   └── im-website/               （官网，可选）
├── data/                         ← ★ 只有「需要人翻看得懂」的东西放这儿
│   ├── logs/                     ← IM_LOG_HOME，应用日志（info.log/warn.log/error.log/...）
│   ├── upload-tmp/               ← 分片临时目录（storage=minio 也必须有，合并前落在这儿）
│   ├── files/                    ← 只有 IM_FILE_STORAGE=local 时用
│   ├── live/                     ← 直播分片（与 nginx、retention 三方共享）
│   └── knowledge/                ← AI 面试知识库（只读挂载）
├── certs/                        ← 证书（Let's Encrypt 或自购），只 nginx 读
└── scripts/
    ├── import-sql.sh / backup.sh
    └── live-retention.js         ← 从仓库 electron/ 拷来（第九节 sidecar 用）
```

一次建好并摆权限：

```bash
sudo mkdir -p /opt/im-docker/{app/extra,conf,sql-init,nginx/{conf.d,snippets},web/{im-ui,im-admin-ui,im-website},data/{logs,upload-tmp,files,live,knowledge},certs,scripts}
sudo chown -R $USER:$USER /opt/im-docker
# ★ 容器里的 im-server 以 uid 1000 跑（见第四节的 USER 1000），这三个可写目录必须让 uid 1000 能写
sudo chown -R 1000:1000 /opt/im-docker/data/{logs,upload-tmp,live}
sudo chmod 600 /opt/im-docker/.env /opt/im-docker/conf/im.env
```

> **为什么 MySQL / Redis / MinIO 用命名卷而不是 `./data/mysql`**：三个官方镜像各自的运行 uid 不同，
> bind mount 到宿主目录十有八九撞 `Permission denied` 或 `Found 'mysql-data' directory ... but no permission`，
> 修 uid 这件事的性价比极低。用命名卷让镜像 entrypoint 自己 chown，代价只是数据不在 `/opt/im-docker` 下面
> （`docker volume ls` / `docker volume inspect im-docker_mysql-data` 能找到），备份脚本相应改一下即可（第十节）。

```bash
# 想确认卷落在哪台机器的哪个路径（备份要拷它）
docker volume ls
docker volume inspect im-docker_mysql-data -f '{{ .Mountpoint }}'
```

---

## 三、两份环境变量文件（分工别搞混）

| 文件 | 谁读它 | 内容 | 典型变量 |
|---|---|---|---|
| `/opt/im-docker/.env` | **docker compose 自己**（启动时插值进 yml） | 镜像 tag、密码、域名 | `MYSQL_ROOT_PASSWORD`、`NGINX_TAG` |
| `/opt/im-docker/conf/im.env` | **im-server 容器里的 java 进程**（`env_file`） | `application.yml` 里那 60 个 `${}` | `MYSQL_HOST`、`IM_FILE_STORAGE` |

两份可以有重叠的值（比如同一个 redis 密码），但**用途完全不同**：前者是「编排参数」，后者是「应用配置」。
`.env` 会被 compose 自动读取（与 `docker-compose.yml` 同目录），无需 `--env-file`。

### 3.1 `.env`（compose 层）

```bash
# 镜像版本：写死而不是 latest，回滚和「同一份 compose 在两台机器上跑出同一结果」全靠它
MYSQL_TAG=8.0
REDIS_TAG=7-alpine
NGINX_TAG=1.27-alpine
NODE_TAG=22-alpine
IM_SERVER_TAG=local/im-server:1.0.0

# 域名（第八节 nginx 用 ${IM_DOMAIN} 之类插值时才会用到，纯 nginx.conf 里写死也可以）
IM_DOMAIN=im.example.com
ADMIN_DOMAIN=admin.example.com
LIVE_DOMAIN=live.example.com

# ★ 这三份密码在 .env 与 conf/im.env 里必须一致，不一致的表现是「容器起来了但连不上」
MYSQL_ROOT_PASSWORD=<强随机>
MYSQL_PASSWORD=<强随机>
REDIS_PASSWORD=<强随机>
MINIO_ROOT_USER=<不要用 minioadmin，≥3 字符>
MINIO_ROOT_PASSWORD=<不要用 minioadmin，≥8 字符>
TURN_SECRET=<与 conf/im.env 里 IM_TURN_SECRET 同一个>   # 只有放开 coturn 那段时才用得到
```

> MinIO 的 `MINIO_ROOT_PASSWORD` **下限是 8 字符**，短了容器直接退出（不是拒绝登录，是起不来）。
> 桶名 `MINIO_BUCKET` 走 S3 规范：≥3 字符、只允许小写字母/数字/点/连字符；填 `im` 会被 SDK 拒绝并**让后端启动失败**。

### 3.2 `conf/im.env`（应用层，★ 与裸机版的唯一差别就是四个值）

下面这份和《服务器部署指南》第 5.2 节同源，**只有四处被容器改掉**，已用 ★ 标出：

```bash
# ===== 必填 =====
SPRING_PROFILES_ACTIVE=prod                  # dev 会在响应里回显验证码明文
MYSQL_HOST=mysql                             # ★ 裸机是 127.0.0.1，容器里必须是服务名
MYSQL_PORT=3306
MYSQL_DATABASE=im_db
MYSQL_USER=im
MYSQL_PASSWORD=<与 .env 同一个>
REDIS_HOST=redis                             # ★ 同上
REDIS_PORT=6379
REDIS_PASSWORD=<与 .env 同一个>
REDIS_DATABASE=0
IM_JWT_SECRET=<换掉内置开发用长串>            # Sa-Token JWT 签名密钥
IM_TICKET_SECRET=<换掉内置开发用长串>          # WS 连接票据 / 文件访问票据签名密钥
IM_LOG_HOME=/app/logs                        # ★ 默认 D:/rizi1/IM，Linux/容器里不改就是「日志静默丢失」
IM_UPLOAD_TMP_DIR=/app/upload-tmp            # ★ 默认 ${user.home}/im-upload-tmp，不在卷上就会被重建容器时清掉
TZ=Asia/Shanghai                             # 与 MySQL 的 default-time-zone 一致，见 3.3

# ===== 文件存储：装 MinIO（默认）=====
IM_FILE_STORAGE=minio
MINIO_ENDPOINT=http://minio:9000             # ★ 裸机 http://127.0.0.1:9000
MINIO_ACCESS_KEY=<同 MINIO_ROOT_USER>
MINIO_SECRET_KEY=<同 MINIO_ROOT_PASSWORD>
MINIO_BUCKET=im-files
# 不想跑 MinIO 就整段换成下面两行（少一个容器，代价是文件绑在这台机器的卷上）
# IM_FILE_STORAGE=local
# IM_FILE_DIR=/app/files                     # ★ 必须是挂载进容器的路径，否则重建容器 = 文件全丢

# ===== 按需 =====
IM_AI_KNOWLEDGE_DIR=/app/knowledge           # 默认 D:/资料/知识库/面试官（Windows 路径，Linux 必须覆盖）
ALI_BABA_API_KEY=<百炼 Key，不设则仅面试功能不可用>
IM_SMS_PROVIDER=aliyun                       # 默认 mock：只写日志、不发真短信，用户永远收不到码
MAIL_USERNAME=you@qq.com
MAIL_PASSWORD=<QQ 邮箱的 SMTP 授权码，不是 QQ 登录密码>
IM_LIVE_ENABLED=true
IM_LIVE_PLAY_BASE_URL=/live                  # ★ 生产约定 /live（yml 默认 /hls 是给本地 live-media-server.js 用的），要与 nginx 的 location 同名
IM_LIVE_PUSH_BASE_URL=http://<公网 IP 或主播可达的域名>:8088/live   # ★★ 前缀必须与 nginx 的 location /live/ 同名；且绝不能写 http://nginx:8088/...：那是容器内部 DNS，主播端在公网，解析不到
IM_LIVE_SIGN_SECRET=<与 nginx secure_link_md5 的 secret 逐字一致>
IM_LIVE_REPLAY_RETENTION_DAYS=1              # 与 retention sidecar 的 --days 读同一个值（第九节）
IM_CALL_STUN_URLS=stun=<公网IP>:3478
IM_CALL_TURN_ENABLED=true
IM_CALL_TURN_URLS=turn=<公网IP>:3478?transport=udp
IM_TURN_SECRET=<与 coturn 的 use-auth-secret 逐字一致>
IM_DIRECT_ENABLED=false                      # 打开要同时发布 8947/udp 并设 IM_DIRECT_PUNCH_HOST=<公网IP>（同样不能写服务名）
IM_DIRECT_PUNCH_HOST=
MANAGEMENT_ADDRESS=127.0.0.1                 # ★ 容器里的 loopback 只有容器自己能到；外部 Prometheus 抓就改 0.0.0.0 且不发布端口
# SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/extra/application-prod-extra.yml   # 换域名要 CORS 时取消注释（第八节末）
```

### 3.3 ★ 三个「容器里才长出来」的坑，单独说

1. **时区必须两边一起设。** JDBC URL 里写死了 `serverTimezone=Asia/Shanghai`（`application.yml` L62），
   而 `im_schema.sql` 的时间列带 `DEFAULT CURRENT_TIMESTAMP` 兜底——它读的是 **MySQL 会话时区**。
   MySQL 容器默认是 UTC：JVM 按 +08 写、`NOW()` 按 UTC 写，同一列里会长出两种时钟，
   表现是「MyBatis 填的记录时间比数据库默认值填的早/晚 8 小时」。
   解法是**两边都钉死**：`TZ=Asia/Shanghai`（应用容器）+ mysqld `--default-time-zone=+08:00`（第五节的 command）。
2. **`IM_LOG_HOME` / `IM_UPLOAD_TMP_DIR` 这类路径是「容器内路径」，宿主机看不到就等于没挂卷。**
   本份统一用 `/app/logs`、`/app/upload-tmp`、`/app/files`、`/app/knowledge`，并在 compose 里逐个 bind 到 `./data/*`。
   漏挂的代价各有不同：logs 静默丢失（logback 不拦启动）、upload-tmp 丢分片（大文件合并失败）、
   `IM_FILE_STORAGE=local` 时丢**全部已上传文件**。
3. **`env_file` 的值不会经过 shell，别写引号、别写行内注释、别指望 `${}` 展开。**
   `IM_FILE_DIR=/app/files   # 注释` 这种写法会把 `   # 注释` 一起当成值传进进程（`docker exec ... env` 里能看见），
   路径类变量于是指向一个不存在的目录，而 Java 只报「找不到路径」不报「配置读错」。写完用
   `docker compose exec im-server env | grep -E 'IM_|MYSQL_|REDIS_|MINIO_'` 复核一遍值。

---

## 四、后端镜像 Dockerfile

产物只有一个可执行 fat jar（`im-bootstrap/target/im-server.jar`，主类 `com.im.bootstrap.ImApplication`）。
两条路线：

> ★ 两个文件都已在仓库里：[`Docker/app/Dockerfile`](../../Docker/app/Dockerfile)（路线 A）与 [`Docker/app/Dockerfile.multistage`](../../Docker/app/Dockerfile.multistage)（路线 B）。
> 路线 A 的构建上下文就是 `Docker/app`（jar 由 CI 的 `download-artifact` 直接丢进去），路线 B 的上下文是仓库根，两个 `-f` 不要写反。
> 路线 A 的「构建机」在本项目里就是 **GitHub Actions 的 runner**（见 §10.5），不是你手上这台开发机。

### 4.1 路线 A：jar 由构建机产出，镜像只做运行时（**推荐**）

构建机（有 JDK 21 + Maven，不需要 Docker）：

```bash
git clone <你的仓库地址> && cd <仓库>
mvn clean package -DskipTests
scp im-bootstrap/target/im-server.jar deploy@server:/opt/im-docker/app/im-server.jar
```

`/opt/im-docker/app/Dockerfile`：

```dockerfile
# 基础镜像固定到 21-jre（Ubuntu 系）。★ 别用 -alpine 版：那是 busybox sh，
# 本文件后面的健康检查用 bash 的 /dev/tcp，busybox 不支持（要改写成 wget）。
# ★ 另一个半边同类的坑：Ubuntu 底镜像的 /bin/sh 是 dash，它也不认 /dev/tcp，
#   所以 compose 里那条健康检查必须写 CMD ["bash", "-c", …]，不能用 CMD-SHELL（详见第五节）。
FROM eclipse-temurin:21-jre

# 镜像里没有 curl 的可靠保证，健康检查依赖 bash（Ubuntu 基础镜像自带），不额外装包
WORKDIR /app

# 非 root 运行：uid 1000 与宿主机 ./data/* 的 chown 对齐（第二节）
# ★ 踩过的坑（CI 首次构建真挂在这）：不能写裸的 `groupadd --system --gid 1000 im`，
#   Ubuntu 24.04 底座预置了一行 `gro:x:1000:`（adduser 用来占住这段 GID），指定 gid 会让
#   groupadd 以退出码 4「GID already exists」把构建打断。下面是四级阶梯：
#   ① 组已存在→复用 ② 直建 gid=1000 ③ 占用者是 gro→改名让位（gro 不挂文件，改名安全）
#   ④ 被别的组占着→自动分配兜底、不拦构建。续行里一律不要投行内注释：行尾反斜杠会把换行
#   吃掉，注释会连它后面那一行一起注掉（这个错我也犯过，症状是命令凭空少一截）。
ARG IM_UID=1000
ARG IM_GID=1000
RUN set -eu; \
    if getent group im >/dev/null 2>&1; then \
      echo "[im] group im 已存在，复用"; \
    elif groupadd --system --gid "${IM_GID}" im 2>/dev/null; then \
      echo "[im] group im gid=${IM_GID}"; \
    elif occ=$(getent group "${IM_GID}" | head -1 | cut -d: -f1); [ -n "$occ" ] && [ "$occ" = gro ] \
         && groupmod -n im gro; then \
      echo "[im] GID ${IM_GID} 被占位组 gro 拿着，改名让位"; \
    elif groupadd --system im; then \
      echo "[im] !! GID ${IM_GID} 被其它组拿着，im 改用自动分配的 gid（不影响运行，只是组属主不再等于宿主机目录的 1000）"; \
    else \
      echo "[im] !! 建组失败，上面有 groupadd 的原始报错"; exit 1; \
    fi; \
    gid=$(getent group im | head -1 | cut -d: -f3); \
    if id -u im >/dev/null 2>&1; then \
      echo "[im] user im 已存在，复用"; \
    elif useradd --system --uid "${IM_UID}" --gid "${gid:-0}" --create-home --home-dir /app im; then \
      echo "[im] user im uid=${IM_UID}"; \
    else \
      echo "[im] !! 建用户失败（多半是 UID ${IM_UID} 已被别的用户占用，比如带 ubuntu 用户的底座镜像）"; exit 1; \
    fi; \
    mkdir -p /app/logs /app/upload-tmp /app/files /app/knowledge /app/extra; \
    chown -R "$(id -u im)":"$(id -g im)" /app

COPY --chown=1000:1000 im-server.jar /app/im-server.jar

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Dfile.encoding=UTF-8" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
USER 1000:1000

# ★ ENTRYPOINT（不是 CMD）+ exec：java 必须成为 PID 1，否则 docker stop 的 SIGTERM 传不到它，
#   只能等 10s 超时被 SIGKILL——Hikari 连接和 WS 会话就没有优雅关闭的机会
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/im-server.jar"]
```

```bash
cd /opt/im-docker/app && docker build -t local/im-server:1.0.0 .
```

> **裸机上那条「JVM 参数必须放在 `-jar` 之前」的红线（服务器部署指南 §5.3）在这里同样成立**，
> 只是换了位置：它们进 `JAVA_OPTS`、拼在 `-jar` 前面。若你改用 `docker run ... java -jar x.jar -Xmx2g`，
> `-Xmx2g` 会被当成应用命令行参数传给 `main()` 而**静默不生效**——和 systemd 里犯的是同一个错。

### 4.2 路线 B：多阶段构建，服务器上不需要 Maven 也不需要 jar

```bash
# 前提：源码要能在服务器上取到（git clone，或把仓库打包 scp 上来）；构建上下文是整个仓库
cd /opt/im-src
```

`Dockerfile`（放仓库根）：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# BuildKit 的 cache mount 让 ~/.m2 跨次构建复用，第二次能从 3 分钟降到 30 秒量级
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# 建组建用户与 4.1 完全一致（那节有 groupadd 不能指定 --gid 的原因和四级阶梯），这里略成紧凑版：
RUN set -eu; \
    getent group im >/dev/null 2>&1 \
      || groupadd --system --gid 1000 im 2>/dev/null \
      || groupadd --system im; \
    gid=$(getent group im | head -1 | cut -d: -f3); \
    useradd --system --uid 1000 --gid "${gid}" --create-home --home-dir /app im; \
    mkdir -p /app/logs /app/upload-tmp /app/files /app/knowledge /app/extra; \
    chown -R "$(id -u im)":"$(id -g im)" /app
COPY --from=build --chown=1000:1000 /src/im-bootstrap/target/im-server.jar /app/im-server.jar
ENV TZ=Asia/Shanghai JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8" SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
USER 1000:1000
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/im-server.jar"]
```

`.dockerignore`（仓库根，**没有它这个构建会慢到不能用**：`im-ui/node_modules`、`electron` 打包产物、各模块 `target` 都是几个 GB）：

```
**/node_modules
**/target
!**/im-bootstrap/target/im-server.jar
dist
electron/dist
electron/win-unpacked
*.tmp
.git
logs
md/打包
```

```bash
# 需要 BuildKit（24.04 上的 Docker 默认已开；老版本加 DOCKER_BUILDKIT=1）
docker build -t local/im-server:1.0.0 .
```

> **选哪条**：路线 A 的服务器上「一个编译器都没有」，出问题好排查、镜像可复现，
> 与《服务器部署指南》§2.3「不要把 Maven 装到服务器上」的口径一致；
> 路线 B 只在「没有独立构建机」时用。★ 无论哪条，**服务器都不该装 Maven**——构建发生在容器里，不进宿主机。

---

## 五、docker-compose.yml 全文

`/opt/im-docker/docker-compose.yml`。这份是**可以整份抄走**的最小生产形态：直播媒体面与 TURN 已写好但注释掉，按需放开。

> ★ 仓库里的本体是 [`Docker/docker-compose.yml`](../../Docker/docker-compose.yml)，与下面这段同源，但有几处已经在服务器上补过的差异：
> ① `redis` 服务多了 `REDIS_PASSWORD: ${REDIS_PASSWORD:?}` 这个环境变量——不加它，`docker compose exec redis redis-cli -a "$REDIS_PASSWORD"`
> 在容器里拿到的是空串（compose 只在 `command:` 里插值，不会把变量注进容器环境），`health.sh` 与 `clear-perm-cache.sh` 就会鉴权失败；
> 代价是 `docker inspect` 里能看到这个值，而它本来也能从 `command:` 里看到，没有新增泄露面。② 镜像名走 `${IM_SERVER_TAG}`，与 §10.5 的 CI 产出直接对接。
> **以文件为准；下面这段的作用是把“为什么这么写”讲完。**

```yaml
# IM 即时通讯系统 —— Ubuntu 24.04 + Docker Compose 部署
# 用法：docker compose --env-file .env up -d      （compose v2，命令无横杠）
# ★ 不要 --scale im-server=N，也不要加 deploy.replicas：本系统当前必须单实例（理由见 md/部署/服务器部署指南.md 第七节）

name: im-docker            # 卷名前缀，别随便改：改了等于找不到原来的卷

services:
  # ---------------------------------------------------------------- MySQL
  mysql:
    image: mysql:${MYSQL_TAG}
    container_name: im-mysql
    restart: unless-stopped
    # ★ 时区与 JDBC URL 里写死的 serverTimezone=Asia/Shanghai 对齐；不设则 MySQL 侧的
    #   DEFAULT CURRENT_TIMESTAMP 兜底值会比应用写入的时间差 8 小时
    command:
      - --default-time-zone=+08:00
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_general_ci      # 与 sql/im_schema.sql 的 CREATE DATABASE 一致，否则 JOIN 会 Illegal mix of collations
      - --max_connections=300                     # 必须明显大于 Hikari 的 maximum-pool-size(50) × 实例数(1)
      # 认证插件就用镜像默认的 caching_sha2_password：JDBC URL 已带 allowPublicKeyRetrieval=true（application.yml L62），
      # 不需要加 --mysql-native-password / --default-authentication-plugin（不同 8.0 小版本对这个开关的拼写不一致，多写一行反而能让 mysqld 起不来）
      - --innodb-buffer-pool-size=512M
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: im_db
      MYSQL_USER: im
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      TZ: Asia/Shanghai
    volumes:
      - mysql-data:/var/lib/mysql
      # 仅首次建库用：空卷第一次启动时按文件名顺序执行（第六节）。生产上线建议先注释掉这两行、手工导
      - ./sql-init:/docker-entrypoint-initdb.d:ro
    # ★ 不发布 3306：Docker 发布的端口绕过 UFW（第〇节第 3 条）。临时调试用 127.0.0.1:3306:3306
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u root -p\"$$MYSQL_ROOT_PASSWORD\" --silent"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 40s           # 首次初始化要建 37 张表，别把 start_period 设小了导致误判 unhealthy

  # ---------------------------------------------------------------- Redis
  redis:
    image: redis:${REDIS_TAG}
    container_name: im-redis
    restart: unless-stopped
    # ★ 镜像本身没有「从环境变量读密码」这回事，只能走命令行/配置文件；命令行会出现在 docker inspect 里，
    #   更克制的做法是挂一份 redis.conf（把 requirepass 写进去）再 `redis-server /usr/local/etc/redis/redis.conf`
    command: ["redis-server", "--requirepass", "${REDIS_PASSWORD}", "--appendonly", "yes", "--maxmemory", "512mb", "--maxmemory-policy", "noeviction"]
    environment:
      TZ: Asia/Shanghai
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"${REDIS_PASSWORD}\" ping | grep -q PONG"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 10s

  # ---------------------------------------------------------------- MinIO
  minio:
    image: minio/minio:latest        # ★ 生产请把 latest 换成具体 RELEASE 标签（docker inspect 看当前跑的是哪个），latest 让回滚失去意义
    container_name: im-minio
    restart: unless-stopped
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
      TZ: Asia/Shanghai
    volumes:
      - minio-data:/data
    # 9000/9001 都不发布：控制台走 SSH 隧道看（docker compose port minio 9001 → ssh -L）
    healthcheck:
      # 官方镜像自带 mc。若你用的构建里没 mc，换成
      #   test: ["CMD-SHELL", "mc ready local || exit 1"]  →  前先 docker compose exec minio which mc 确认
      test: ["CMD-SHELL", "mc ready local"]
      interval: 15s
      timeout: 5s
      retries: 8
      start_period: 20s

  # ---------------------------------------------------------------- 后端
  im-server:
    image: ${IM_SERVER_TAG}
    build:
      context: ./app                 # 路线 B 的话把 context 指到仓库根、dockerfile 指到那份 Dockerfile
      # 改了 jar 之后：docker compose build im-server && docker compose up -d im-server
    container_name: im-server
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy   # ★ 关键：IM_FILE_STORAGE 默认 minio 且连不上桶会 fail-fast，没有这条就是无限重启循环
    env_file:
      - ./conf/im.env
    volumes:
      - ./data/logs:/app/logs
      - ./data/upload-tmp:/app/upload-tmp
      - ./data/files:/app/files          # 只有 IM_FILE_STORAGE=local 才用得到，留着不亏
      - ./data/knowledge:/app/knowledge:ro
      - ./app/extra:/app/extra:ro        # CORS 之类「不能靠环境变量改」的外挂配置
    # ★ 不发布 8080：nginx 容器通过 http://im-server:8080 走 compose 内部网络访问它。
    #   想在宿主机 curl 调试就临时加 "127.0.0.1:8080:8080"，别写 "8080:8080"（那等于后端直接挂公网）
    expose:
      - "8080"
    deploy:
      resources:
        limits:
          memory: 2g                 # 与 JAVA_OPTS 的 MaxRAMPercentage=75 配套：堆上限约 1.5g
    healthcheck:
      # Actuator 只绑 127.0.0.1，所以只能在容器内问；/dev/tcp 免装 curl/wget
      # ★ 写 CMD ["bash", …] 而不是 CMD-SHELL：CMD-SHELL 走 /bin/sh，eclipse-temurin 是 Ubuntu 底、/bin/sh 就是 dash，
      #   而 dash 不认 /dev/tcp → 健康检查永失败 → 容器一直 unhealthy → nginx 的 service_healthy 卡死，整个栈起不来
      test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && head -c 200 <&3 | grep -q UP"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 90s              # Spring Boot + 虚拟线程 + 建连 + 自动建桶，冷启 40~80s 是正常量级
    # ★ 单实例护栏：compose 没有「禁止 scale」的开关，靠下面这行把端口占用做成硬约束也徒劳（本服务不发布端口），
    #   真正的护栏是这条注释 + 第十一节的「docker compose ps 只看得到一个 im-server」

  # ---------------------------------------------------------------- Nginx
  nginx:
    image: nginx:${NGINX_TAG}
    container_name: im-nginx
    restart: unless-stopped
    depends_on:
      im-server:
        condition: service_healthy   # 先让后端 ready，否则 nginx 起来后 proxy_pass 的上游解析会失败（nginx 对 DNS 解析失败是启动即退出）
    ports:
      - "80:80"
      - "443:443"
      # - "8088:8088"                # 直播推流口（只在主播端要直连 8088 时放开；见第八节关于源 IP 的警告）
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - ./nginx/snippets:/etc/nginx/snippets:ro
      - ./web/im-ui:/usr/share/nginx/html/im-ui:ro
      - ./web/im-admin-ui:/usr/share/nginx/html/im-admin-ui:ro
      - ./web/im-website:/usr/share/nginx/html/im-website:ro
      - ./certs:/etc/letsencrypt:ro
      - ./data/live:/data/live        # ★ 读写：推流口要往这里写分片（播放侧的只读语义由 8.2 的 limit_except 保证）
    healthcheck:
      # ★ 打 8.2 里那个专用的 location = /healthz：打 / 会被 301 带到 443，打 443 又要处理自签证书。
      #   nginx:alpine 自带 busybox wget，不需要 curl
      test: ["CMD-SHELL", "wget -q --spider http://127.0.0.1/healthz || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3

  # ------------------------------------------------ 选装：回放保留期清理
  # live-retention:
  #   image: node:${NODE_TAG}
  #   container_name: im-live-retention
  #   restart: unless-stopped
  #   working_dir: /app
  #   volumes:
  #     - ./scripts/live-retention.js:/app/live-retention.js:ro
  #     - ./data/live:/data/live      # 与 nginx 同一个目录，才能删到真的那批分片
  #   environment:
  #     IM_LIVE_REPLAY_RETENTION_DAYS: 1     # 与后端读同一个变量名，两边不一致会出现「列表有回放、点进去 404」
  #   # ★ 常驻形态用 --interval；要每天跑一次就用宿主 cron 起 `docker compose run --rm live-retention`
  #   command: ["node", "/app/live-retention.js", "--root=/data/live", "--interval=1800"]
  #   depends_on:
  #     - nginx

  # ------------------------------------------------ 选装：coturn（跨 NAT 通话中继）
  # coturn:
  #   image: coturn/coturn:latest
  #   container_name: im-coturn
  #   restart: unless-stopped
  #   network_mode: host              # ★ TURN 几乎必须 host 网络：中继端口是大段 UDP，逐个 ports: 映射既难维护又会改源地址
  #   command:
  #     - --use-auth-secret
  #     - --static-auth-secret=${TURN_SECRET}     # 与 IM_TURN_SECRET 逐字一致，否则客户端拿到 401
  #     - --realm=im.example.com
  #     - --listening-port=3478
  #     - --external-ip=<公网IP>/<内网IP>          # ★ NAT 后面必须写「公网/内网」这一对，否则下发给客户端的地址是内网地址，连不上
  #     - --min-port=49160
  #     - --max-port=49200
  #     - --no-tls
  #     - --no-dtls
  #     - --allowed-peer-ip=0.0.0.0-255.255.255.255

# ---------------------------------------------------------------- 命名卷（★ 必须声明）
# compose 对「服务里用了但顶层没声明」的卷是直接报错的：
#   service "mysql" refers to undefined volume mysql-data: invalid compose project
# 声明为空体即可，卷名会带上项目名前缀（im-docker_mysql-data），驱动与选项由 Docker 默认
volumes:
  mysql-data:
  redis-data:
  minio-data:
```

### 5.1 起与看

```bash
cd /opt/im-docker
docker compose config -q          # ★ 先静态校验：插值没配对、缩进错、引用了不存在的卷，都在这一步报出来
docker compose up -d
docker compose ps                 # 五个服务都应是 running(healthy)
docker compose logs -f im-server  # 看到「Started ImApplication」且没有 IllegalStateException 即成
```

`docker compose ps` 的输出值得逐列看：`STATUS` 里 `health: starting` → `healthy` 的迁移说明健康检查真在跑；
只显示 `Up` 没有 health 就是 healthcheck 压根没配好（第八节 nginx 的 `nginx -t -q` 就是这个用途）。

### 5.2 一次网络连通性自检（比读日志快）

```bash
# 后端能不能摸到 mysql / redis / minio（服务名解析对不对，这是容器化第一大坑）
# ★ 用 bash 而不是 sh：/dev/tcp 是 bash 的特性，dash / busybox sh 都不认；
#   也别用变量替把端口凑在一起（${h/mysql/3306} 对 redis / minio 会拼成主机名当端口）
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/mysql/3306   && echo mysql-ok'
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/redis/6379   && echo redis-ok'
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/minio/9000   && echo minio-ok'
# nginx 能不能摸到后端：nginx:1.27-alpine 里没有 bash，用 busybox 自带的 nc（不写 -z，靠 </dev/null 立刻 EOF）
docker compose exec nginx  sh -c 'nc -w 2 im-server 8080 < /dev/null' && echo upstream-ok
```

---

## 六、首次启动与数据库初始化

### 6.1 ★ 先分清「全新库」和「存量库」，这条在容器形态下更危险

`sql/im_schema.sql` 开头是 **37 条 `DROP TABLE IF EXISTS`**（为了让脚本幂等）。
裸机上误跑一次还能从备份回；容器里如果你把它挂进 `docker-entrypoint-initdb.d` 又执行了
`docker compose down -v`（`-v` 连命名卷一起删），**库和数据一起没了**。

| 场景 | 该怎么做 |
|---|---|
| 全新环境、可以丢数据 | 用 6.2 的 `initdb.d` 自动导入 |
| 已有正式数据的存量库 | **什么自动导入都别挂**。卷已存在时 `initdb.d` 本来也不会跑（见 6.3），migration 走第七节手工执行 |
| 从旧机器迁库 | 宿主机 `mysqldump` → 灌进容器（第十节） |

### 6.2 全新库：挂两个文件，起一次

```bash
cd /opt/im-docker
cp <仓库>/sql/im_schema.sql sql-init/01_im_schema.sql
cp <仓库>/sql/im_data.sql   sql-init/02_im_data.sql
# ★ 文件名前缀决定执行顺序（按字典序）：schema 必须排在 data 前面，否则「表还没建就 INSERT」
# ★ 后缀必须是 .sql / .sh / .sql.gz，其他名字会被静默忽略
docker compose up -d mysql
docker compose logs -f mysql      # 会看到 01_im_schema.sql 被执行的过程
docker compose up -d
```

`im_schema.sql` 自带 `CREATE DATABASE IF NOT EXISTS im_db ... utf8mb4` 与 `USE im_db;`，
`im_data.sql` 也自带 `USE`（L26），所以 `MYSQL_DATABASE=im_db` 与脚本不冲突、不需要 `--database` 参数。

> **中文与 emoji 会不会坏？** `mysql:8.0` 的默认字符集已是 utf8mb4，本份又在 `command:` 里显式钉了
> `--character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci`（与建表语句同一 collation）。
> 导入后必查（任一处出现 `?` 就是链路某段退回 latin1）：
> ```bash
> docker compose exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" --default-character-set=utf8mb4 \
>   -e "SELECT @@character_set_server, @@collation_server; SELECT name FROM im_db.im_gift LIMIT 3;"
> ```
> 这条口径来自裸机版的同一条教训：**管道 / heredoc 喂 SQL 有过把中文刷成问号的实录**，
> 容器里同理，见下一节的 `-e "source"` 写法。

### 6.3 ★ `initdb.d` 只在「数据目录为空」时跑一次

改了 `sql-init/` 里的文件再 `docker compose restart mysql` **不会重新导入**——
entrypoint 看到 `/var/lib/mysql` 非空就直接跳过初始化。想重放的三条路：

```bash
docker compose down -v                        # ① 删卷重来：只有试用环境可以这么干（★ 数据全没）
docker compose exec -T mysql mysql ... -e "source /tmp/x.sql"   # ② 手工导（第七节的正规姿势，推荐）
# ③ 单独把 SQL 拷进容器再执行（避开管道编码问题，见 7.1）
```

上线之后建议**把 `sql-init/` 清空并把那两行 volume 注释掉**，让这份 compose 失去「重建库」的能力，
这是一个便宜的防误操作护栏。

## 七、migration 与权限缓存在容器里怎么跑

执行顺序表（哪份 `migration_*.sql` 在哪个版本之后要跑）**在《[服务器部署指南](服务器部署指南.md)》第六节**，本份不重抄一遍。
容器形态变的只有「命令怎么写」。

### 7.1 ★ 导 SQL：先 `cp` 进容器，再 `-e "source"`，不要用管道

```bash
cd /opt/im-docker
docker compose cp ../sql/migration_gift_icon_width.sql mysql:/tmp/m.sql
docker compose exec mysql sh -c 'mysql -uim -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 im_db -e "source /tmp/m.sql"'
docker compose exec mysql rm -f /tmp/m.sql
```

为什么不写成 `docker compose exec -T mysql mysql ... < migration.sql`：
**管道喂 SQL 有过把中文静默刷成 `?` 的实录**（表注释、列注释、种子数据里的中文一起坏掉，而命令返回 0、不报错），
`-e "source /path"` 是让 mysql 客户端自己读文件，编码由 `--default-character-set` 决定，这条路才稳。
同理别用 heredoc 拼多行 SQL。

> `docker compose exec` 里用 `$MYSQL_PASSWORD` 而不是从 `.env` 插值，是因为这个变量已通过 `environment:` 注入到 mysql 容器；
> 想少一个变量来源也可以直接 `-uroot -p"${MYSQL_ROOT_PASSWORD}"`（由 compose 在跳主机插值，注意外层用双引号）。
> 密码会出现在 `docker inspect` 的命令行里——介意的话给 mysql 挂一份 `extra.cnf`（`[client] user/password`）走 `--defaults-extra-file`。

### 7.2 ★ 铁律没变：新开或收掉权限点，跑完 SQL 必须清权限缓存

`PermissionProviderImpl` 的 L2 缓存 `im:auth:perm:{userId}` TTL 30 分钟，且**登录动作不刷新它**。
不清的表现是「SQL 跑了、后台菜单还是旧的、点进去 403」，很容易误判成代码问题。

```bash
# ① 先看有多少键（只读，安全）
docker compose exec redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning --scan --pattern 'im:auth:*' | wc -l
# ② 再删。容器里可以用 xargs（裸机 PowerShell 那条「管道静默不删」的教训在这里不适用）：
docker compose exec redis sh -c "redis-cli -a \"$REDIS_PASSWORD\" --no-auth-warning --scan --pattern 'im:auth:*' | xargs -r -n 50 redis-cli -a \"$REDIS_PASSWORD\" --no-auth-warning DEL"
# ③ 删完复跑 ①，期望 0
```

口径仍是「**先 keys 后同一条 DEL**、删完再 keys 确认残留 = 0」：`--scan` 与 `DEL` 分两次连接执行时，
中间新写入的键会漏，所以清完必须回到 ① 再看一次。若只想清某个人：`redis-cli ... DEL im:auth:perm:1000`。

### 7.3 只读核对

```bash
docker compose exec mysql sh -c 'mysql -uim -p"$MYSQL_PASSWORD" -t im_db -e "SELECT COUNT(*) AS perms FROM im_permission; SELECT id,code FROM im_permission WHERE id BETWEEN 119 AND 122;"'
docker compose exec redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning info keyspace
```

---

## 八、Nginx 容器：三站 + WS + 直播媒体面

### 8.1 先确认镜像带齐了 `dav` 与 `secure_link` 两个模块

```bash
docker compose exec nginx sh -c "nginx -V 2>&1 | tr ' ' '\\n' | grep -E 'http_dav|secure_link'"
```

官方 `nginx` 镜像的编译参数里含 `--with-http_dav_module` 与 `--with-http_secure_link_module`，理论上不用管；
但**这一条必须实测**：「换成自己压的精简镜像」会静默丢掉模块，表现是 `nginx -t` 报
`unknown directive "dav_methods"`，或者签名根本不校验。

### 8.2 `nginx/conf.d/im.conf`（可直接抄）

> ★ 仓库里已经落盘为 [`Docker/nginx/conf.d/im.conf.example`](../../Docker/nginx/conf.d/im.conf.example)：它把下面这些域名换成了 `__IM_DOMAIN__` 形式的 token，
> 由 `bootstrap.sh` 从 `.env` 里取值替换后生成 `nginx/conf.d/im.conf`。**不用 nginx 自带的 `envsubst`**——那会把 `$host`、`$uri`、`$remote_addr` 这些 nginx 变量一并吃成空串，
> 是一个很难查的坑（表现是 `Host: ` 空头发给后端，接口全 404）。下面这段保留写死的 `im.example.com`，是为了让你能直接读懂结构；两边不同步时**以文件为准**。

```nginx
# ---------- 公共片段：snippets/proxy.conf（下面引它，避免四个 server 块各写一遍）----------
# proxy_http_version 1.1;
# proxy_set_header Host              $host;
# proxy_set_header X-Real-IP         $remote_addr;
# proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
# proxy_set_header X-Forwarded-Proto $scheme;

# ---------- 80：只做 ACME 挑战、健康探针与跳转 ----------
server {
    listen 80;
    server_name im.example.com admin.example.com live.example.com;

    location /.well-known/acme-challenge/ { root /var/www/acme; }
    location = /healthz { access_log off; return 200 "ok\n"; }   # ★ 健康检查打这个，别打 /（会被下面的 301 带走）
    location / { return 301 https://$host$request_uri; }
}

# ---------- 聊天端 im-ui ----------
server {
    listen 443 ssl;
    http2 on;
    server_name im.example.com;

    ssl_certificate     /etc/letsencrypt/live/im.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/im.example.com/privkey.pem;
    include /etc/nginx/snippets/ssl.conf;      # 协议/HSTS/会话缓存，单独一份

    client_max_body_size 110m;                 # 与 spring.servlet.multipart.max-request-size(110MB) 对齐，小了会被 nginx 先 413

    root /usr/share/nginx/html/im-ui;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }        # SPA history 路由回退，少了它刷新子页面全 404
    location ~* \.(js|css|png|jpg|svg|woff2)$ { expires 7d; add_header Cache-Control "public"; }

    # ★ WebSocket：Upgrade 头 + 拉长读写超时，否则长连接几十秒就被默认超时掉断
    location /ws {
        proxy_pass http://im-server:8080;      # ★ 服务名，不是 127.0.0.1
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host       $host;
        proxy_set_header X-Real-IP  $remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
    location /api {
        proxy_pass http://im-server:8080;
        include /etc/nginx/snippets/proxy.conf;
        proxy_read_timeout 120s;               # 大文件合并、AI 流式回答都可能慢
    }

    # 直播播放面与聊天端同域，省掉观众端跨域（比另开 live.example.com 少一整套 CORS 与证书）
    # ★ location 名必须与 IM_LIVE_PLAY_BASE_URL 的最后一段一致（这里 /live）
    location /live/ {
        root /data;                             # URI /live/... → 磁盘 /data/live/...
        types { application/vnd.apple.mpegurl m3u8; video/iso.segment m4s; video/mp4 mp4; }
        limit_except GET HEAD OPTIONS { deny all; }

        location ~ /index\.m3u8$ {              # 主清单：唯一签名的入口，且绝不缓存
            secure_link $arg_sign,$arg_expire;
            secure_link_md5 "${uri}${arg_expire}<与 IM_LIVE_SIGN_SECRET 逐字一致>";
            if ($secure_link = "") { return 403; }
            if ($secure_link = "0") { return 410; }
            add_header Cache-Control "no-store, must-revalidate" always;
        }
        location ~ \.m3u8$ {                      # 变体清单：绝不缓存，但★不能签名（hls.js 按相对路径拉它，不携 ?expire=&sign=）
            add_header Cache-Control "no-store, must-revalidate" always;
        }
        location ~ \.(m4s|mp4|ts)$ {             # 分片：路径里不可猜的 streamKey 兑底，可长缓存/挂 CDN
            add_header Cache-Control "public, max-age=3600" always;
        }
    }
}

# ---------- 管理后台：独立 server 块，别和聊天端共用 root ----------
server {
    listen 443 ssl; http2 on;
    server_name admin.example.com;
    ssl_certificate     /etc/letsencrypt/live/admin.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/admin.example.com/privkey.pem;
    include /etc/nginx/snippets/ssl.conf;
    client_max_body_size 110m;
    root /usr/share/nginx/html/im-admin-ui;
    location / { try_files $uri $uri/ /index.html; }
    location /api { proxy_pass http://im-server:8080; include /etc/nginx/snippets/proxy.conf; }
    # 后台不建长连接也不播流，因此不需要 /ws 与 /live（与 im-admin-ui/vite.config.js 口径一致）；
    # prod profile 已把 knife4j/springdoc 全关，/doc.html 回 404 是设计如此，别在 nginx 上再给它开洞
}

# ---------- 直播推流口（开直播才需要；对应 8088 的 ports 发布）----------
server {
    listen 8088;
    server_name _;
    root /data;
    location /live/ {
        # ★ 裸机那份写的是 allow 127.0.0.1（同机推流）；容器里几乎一定匹配不上：
        #   经 Docker NAT 进来的包，源地址可能是容器网关（172.x）而不是真实客户端。
        #   要么 daemon.json 设 "userland-proxy": false 并实测 remote_addr，要么把这层白名单交给云安全组 / DOCKER-USER 链（8.3）。
        allow <主播出口IP或网段>;
        deny all;
        limit_except PUT { deny all; }
        dav_methods PUT DELETE;
        create_full_put_path on;              # {roomId}/{streamKey}/ 多级目录自动创建
        dav_access user:rw group:r all:r;
        client_max_body_size 32m;             # 单个分片上限
        client_body_temp_path /tmp/nginx_body; # ★ 必须是 nginx 进程可写目录：默认路径在只读挂载下会 500
    }
}
```

> 推流口那个 server 需要**写** `/data/live`，而播放侧只读语义靠 `limit_except GET HEAD OPTIONS { deny all; }` 保证。
> 同一个挂载点不能既 ro 又 rw，所以第五节已经把 `./data/live:/data/live` 写成**读写**（方案 ①）；
> 想要更严格就拆两个 nginx 容器（媒体面独立），播放与推流各挂自己的权限（方案 ②）。

```bash
docker compose exec nginx nginx -t          # 改完先语法检查
docker compose exec nginx nginx -s reload   # 或直接 docker compose restart nginx
```

`conf.d` 是 `:ro` bind mount，宿主机**改已有文件**容器内会立刻看到；但**新建文件**的目录项不一定会穿透缓存，
稳妥做法是 `docker compose restart nginx`。

### 8.3 ★ 容器形态下「谁在监听、怎么挡」这件事的正确答案

- 只发布 `80` / `443`（按需 `8088`、TURN 的 `3478` 与中继端口段、以及 `8947/udp`）。**8080/8081/3306/6379/9000/9001 一律不发布**。
- 要按源 IP 过滤已发布的端口，**UFW 不管用**（Docker 直接动 `DOCKER` 链，绕过 `ufw` 的 `INPUT`）。
  官方给的口子是 `DOCKER-USER` 链：

  ```bash
  sudo iptables -I DOCKER-USER -p tcp --dport 8088 -s <主播出口IP> -j ACCEPT
  sudo iptables -I DOCKER-USER -p tcp --dport 8088 -j DROP
  sudo netfilter-persistent save     # 或写进 systemd 单元，重启后还在
  ```

- 云主机上更省事的一层是**安全组**：只放 443（和按需端口），它在所有情形下都有效。

### 8.4 证书

推荐 **DNS-01**（不用暂露 80、不用停容器、能给 `*.example.com` 一张泛域名）：

```bash
sudo apt install -y certbot python3-certbot-dns-cloudflare     # DNS 插件按你的服务商换
certbot certonly --dns-cloudflare --dns-cloudflare-credentials /root/cf.ini \
  -d im.example.com -d admin.example.com -d live.example.com
sudo cp -r /etc/letsencrypt/live /opt/im-docker/certs/ && sudo chmod -R a+rX /opt/im-docker/certs
```

续期后 nginx 不会自动重载，补一个 deploy-hook：

```bash
# /etc/letsencrypt/renewal-hooks/deploy/im-nginx.sh
#!/bin/sh
docker compose -f /opt/im-docker/docker-compose.yml exec -T nginx nginx -s reload
```

> ★ HTTPS 不只是加密：浏览器 `getUserMedia`（语音/视频通话、摄像头、录屏授权）只在**安全上下文**里可用，
> http 打开的页面点通话会直接拿不到媒体权限。容器化不改变这条，别为了省事只挂 80。

### 8.5 换域名后必须动的第二处：CORS 白名单（yml 是字面列表，环境变量改不动它）

`im.cors.allowed-origins`（`application.yml` L638-681）**没有 `${}` 占位符**，所以不能靠 `conf/im.env` 覆盖。
容器里的做法是挂一份外挂配置（compose 已经把 `./app/extra` 挂到 `/app/extra`）：

`app/extra/application-prod-extra.yml`：

```yaml
im:
  cors:
    allowed-origins:
      - https://im.example.com
      - https://admin.example.com
      - https://www.example.com
      # ★★ 下面几行是「照拄」而不是「多余」：Spring 绑 List 属性时，外挂配置是**整体替换**而不是追加。
      #   只写上面三行，桌面端与移动端就连不上来了（这些 Origin 来自客户端本身，与你的域名无关）。
      - http://tauri.localhost
      - tauri://localhost
      - https://localhost
      - http://localhost
      - capacitor://localhost
      # 还需要开发/联调用的话，把裸机那份 5173/4173/5273/4273 与 http://*.*.*.*:5173 四条一并抄过来
```

然后在 `conf/im.env` 里取消注释：

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/extra/application-prod-extra.yml
```

改完 `docker compose up -d im-server`。不改的表现：所有浏览器请求回 `403 Invalid CORS request`，而 `curl` 一切正常。

## 九、按功能追加（回放清理 / TURN / AI / 监控）

### 9.1 直播回放：`live-retention` sidecar 是**必须**的，不是可选

后端只管 Redis 里那个入口标记（TTL = `im.live.replay.retention-days`），**控制面一个媒体字节都不碰**；
盘上的分片没人删就会一直吃磁盘（`electron/live-retention.js` 头注释给的量级：一场 2 小时四档直播约 8~10GB）。
裸机上是「宿主 cron + node」，容器里两种挂法：

```bash
# A. 常驻 sidecar（第五节那段注释掉的 service 放开即可）：--interval=1800 每 30 分钟扫一次
docker compose up -d live-retention
docker compose logs --since 10m live-retention

# B. 宿主 cron 起一次性容器（每天 04:10），不常驻进程
echo '10 4 * * * cd /opt/im-docker && docker compose run --rm live-retention node /app/live-retention.js --root=/data/live --days=1 >> /var/log/live-retention.log 2>&1' | sudo crontab -
```

★ **两条红线从脚本原样继承，别在容器里「优化」掉**：

1. 判过期看的是**整个房间目录里最新文件的 mtime**，所以**不能**图省事写成
   `find /data/live -mtime +1 -delete`——那会把正在直播的房间的老分片抽走，回放清单立刻残缺。
   换成 `docker run alpine find ...` 只是把同一条错命令换了地方执行，仍然是错的。
2. `--days` 与后端的 `IM_LIVE_REPLAY_RETENTION_DAYS`（默认 **1**）必须是同一个数，两边不一致的表现是
   「后台列表有这条回放、点进去 404」。

> 第五节把它做成了选装 service，是因为**不开直播就不需要它**；一旦 `IM_LIVE_ENABLED=true`，它就是必装项（一个 `node:22-alpine` 容器，几十 MB）。

### 9.2 TURN / STUN / 远控打洞：三类端口，三种做法

| 需求 | 端口 | 容器化怎么做 |
|---|---|---|
| STUN/TURN 信令与中继 | `3478` TCP+UDP + `49160-49200` UDP | coturn 用 `network_mode: host`（中继端口是大段 UDP，逐个 `ports:` 映射既难维护又丢源地址）；NAT 后面必须给 `--external-ip=<公网>/<内网>` 这一对，否则下发给客户端的是内网地址 |
| 远控 UDP 打洞 | **`8947` UDP** | `ports: - "8947:8947/udp"`（**不写 `/udp` 默认是 TCP**，而 TCP 打洞不通且中继照样能用，只有延迟和流量账单会告诉你没生效）；同时 `IM_DIRECT_ENABLED=true` 与 `IM_DIRECT_PUNCH_HOST=<公网IP>` |
| 1:1 与群通话媒体 | 不占服务器端口 | 点对点直连，媒体不过服务器；只有打洞失败才回落 TURN |

```bash
sudo ss -lunp | grep 8947      # 验证 8947 真以 UDP 在监听（-l 列出、-u UDP、-n 不反解、-p 看进程）
```

`IM_CALL_TURN_*` 与 `IM_TURN_SECRET`（与 coturn `use-auth-secret` 逐字一致）的语义，以及「TURN 是流量放大器」这条容量提醒，
与裸机版完全一致（服务器部署指南 §10.2）。群通话是 **mesh 不是 SFU**，服务端硬夹在 `[2,6]` 人，跟容器与否无关。

### 9.3 AI 面试知识库

```bash
# 只读挂载已在 compose 里（./data/knowledge:/app/knowledge:ro），要做的只是把文件放进去并让变量指对
IM_AI_KNOWLEDGE_DIR=/app/knowledge
```

默认值是 `D:/资料/知识库/面试官`：Linux 上这个路径**不存在也不会报错**，只是检索永远空转——
这类「静默不可用」比启动失败难发现得多。`ALI_BABA_API_KEY` 不设时只有面试相关功能回「未配置」，其余照常。
音色名要落在所配 omni 模型的音色表里（`AI_TTS_MODEL_NAME` / `AI_TTS_VOICE`）。

### 9.4 Prometheus 抓容器里的 Actuator

默认 `MANAGEMENT_ADDRESS=127.0.0.1` 在容器里等于「只有本容器能访问」，**Prometheus 抓不到**。两种解法：

```yaml
# ① 绑所有接口，但绝不发布端口（同 compose 网络内的 Prometheus 容器能抓，公网碰不到）
im-server:
  environment:
    MANAGEMENT_ADDRESS: 0.0.0.0
prometheus:
  image: prom/prometheus
  volumes: ["./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro"]
# prometheus.yml 里 targets: ['im-server:8081']，metrics_path: /actuator/prometheus
```

解法 ②：Prometheus 在宿主机上跑，加 `"127.0.0.1:8081:8081"` 发布（**必须带 `127.0.0.1:` 前缀**）+ `MANAGEMENT_ADDRESS=0.0.0.0`。

暴露的端点仍是那四个（`health,info,prometheus,metrics`）；`env`/`configprops`/`heapdump`/`loggers` 不暴露——那些能泄配置、能改运行态。
其余口径照抄 `md/监控告警部署指南.md`。

---

## 十、备份、升级、回滚

### 10.1 备份：三份数据 + 一份编排配置

```bash
cd /opt/im-docker && mkdir -p backup
# ① 数据库（逻辑备份，唯一能跨镜像版本恢复的路子）
docker compose exec -T mysql mysqldump -uim -p"$MYSQL_PASSWORD" \
  --single-transaction --routines --default-character-set=utf8mb4 im_db | gzip > backup/im-$(date +%F-%H%M).sql.gz
# ② 对象存储：MinIO 数据在命名卷里，停容器后 tar（桶内对象与元数据一起走）
docker compose stop minio
docker run --rm -v im-docker_minio-data:/data -v "$PWD/backup":/backup alpine \
  tar czf /backup/minio-$(date +%F).tar.gz -C /data .
docker compose start minio
# ③ 编排与配置（这三份丢了等于整套环境重建）
tar czf backup/im-docker-conf-$(date +%F).tar.gz docker-compose.yml .env conf app/extra nginx
# ④ Redis：已开 appendonly，AOF 在卷里；要快照就：
docker compose exec redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning BGSAVE
docker cp im-redis:/data/dump.rdb backup/redis-$(date +%F).rdb
```

> `docker compose down` **不删命名卷**，`down -v` **删**。这一字之差就是「重启」和「清库」的区别，
> 建议在值班手册里明写：生产只允许 `down`，`down -v` 必须先备份。

### 10.2 升级：换 jar

```bash
# 1) 先备份数据库（10.1 ①）
# 2) 有新 SQL 就先跑 migration（第七节），涉及权限点的记得清 im:auth:*
docker compose cp ../sql/migration_xxx.sql mysql:/tmp/m.sql && \
docker compose exec mysql sh -c 'mysql -uim -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 im_db -e "source /tmp/m.sql"' && \
docker compose exec mysql rm -f /tmp/m.sql
# 3) 换 jar 重建镜像，tag 换号（别覆盖同一个 tag，否则回滚没有目标）
scp im-bootstrap/target/im-server.jar deploy@server:/opt/im-docker/app/im-server.jar
sed -i 's|^IM_SERVER_TAG=.*|IM_SERVER_TAG=local/im-server:1.0.1|' .env
docker compose build im-server && docker compose up -d im-server
docker compose ps            # 确认只有 im-server 被重建，mysql/redis/minio/nginx 没跟着重启
# 4) 前端 dist 同理：scp 覆盖 ./web/*，静态目录是 bind mount，不重启也生效（浏览器强刷）
```

### 10.3 回滚

```bash
sed -i 's|^IM_SERVER_TAG=.*|IM_SERVER_TAG=local/im-server:1.0.0|' .env
docker compose up -d im-server
docker images | grep im-server      # 确认目标 tag 还在；被 prune 过就只能重新 build 旧 commit
```

> ★ **回滚镜像不等于回滚数据库**。跑过的 migration 里，加权限点/改列宽这类**向后兼容**的可以留着；
> 而 `im_schema.sql` 那种带 37 条 `DROP TABLE` 的全量脚本，永远不要出现在生产回滚流程里。
> 每份 `migration_*.sql` 都自带注释形态的回滚段，用它。

### 10.4 别随手 `docker system prune -a --volumes`

`--volumes` 会把 `mysql-data` / `minio-data` / `redis-data` 一起删掉；`-a` 会删掉所有「当前没被容器引用」的镜像，包括你留着回滚的旧 tag。
要清理就用精确命令：`docker image prune`（只删悬空层）、`docker builder prune`（删构建缓存）。

### 10.5 用 GitHub Actions 把 10.2 自动化（仓库里已给现成 workflow）

本节上面那堆 `mvn package` / `scp` / `sed .env` / `compose up`，在 [`build-and-package.yml`](../../.github/workflows/build-and-package.yml) 里已经写完。**关键前提：构建不发生在开发机，也不发生在业务机，而是发生在 GitHub 的 runner 上。**

| job | 干什么 | 产物 |
|---|---|---|
| `meta` | 从根 `pom.xml` 里取版本号、把仓库主名转小写（GHCR 只认小写镜像名）、判定本次是否该 push（PR 不推） | job 输出 `version` / `image` / `push` |
| `build-jar` | `setup-java@temurin 21` + Maven 缓存 → `mvn -B -DskipTests clean package` → 验 MANIFEST 里的 **`Start-Class`**（★ 不是 `Main-Class`，见下面坑单最后一条） | artifact `im-server-jar-<sha>`（已用 `root:` 收窄到只剩 jar）+ `im-remote-agent-jar-<sha>` |
| `build-web` | 矩阵跑 `im-ui` / `im-admin-ui` / `im-website`：先过一道 **lockfile 自检** → `npm ci` → `npm run build` | artifact `web-<站>-<sha>`，里面就是干净的 `dist/**` |
| `docker-image` | download jar 到 `Docker/app` → buildx + metadata → build **并 push 到 GHCR** | `ghcr.io/<owner>/im-server:<版本>` / `:latest` / `:sha-<短sha>` |
| `deploy` | **只在手动触发且勾了 `deploy` 时跑**：`workflow_dispatch`。把三份 dist 打包走 ssh 覆盖到服务器 `web/`，再改远端 `.env` 的 `IM_SERVER_TAG` + `docker compose pull/up -d --no-deps --wait im-server` + 在部署机上 curl 探活 | —— |

需要在仓库 Settings → Secrets and variables → Actions 里配的（**推 GHCR 不需要任何 Secret**，用内置 `GITHUB_TOKEN` 的 `packages: write` 权限）：

| Secret | 必需 | 说明 |
|---|---|---|
| `DEPLOY_HOST` | 要自动部署就必需 | 那台开放机的 IP 或域名 |
| `DEPLOY_USER` | 必需 | 能 `sudo docker` 或在 docker 组里的账号 |
| `DEPLOY_SSH_KEY` | 必需 | **私钥**全文；对应公钥进服务器 `~/.ssh/authorized_keys`，用 `ssh-keygen -t ed25519` 现做一对，不要用你日常开发机的钥匙 |
| `DEPLOY_PORT` | 可选（默认 22） | SSH 端口 |
| `DEPLOY_DIR` | 可选（默认 `/opt/im-docker`） | 就是本文的部署根目录 |

几个已经踩过的坑，改这个文件前先读：

- **`upload-artifact` 不写 `root:` 会坑死下一步。** 默认以工作区根为基准，download 到 `Docker/app` 后会变成 `Docker/app/im-bootstrap/target/im-server.jar`，而 `COPY im-server.jar /app/` 只认前者，直接构建失败。三个上传步骤都显式写了 `root`。
- **GHCR 镜像名必须全小写。** 仓库主名字带大写时，`metadata-action` 会直接报错；所以 `meta` 里先 `tr '[:upper:]' '[:lower:]'`。
- **PR 只构建不推送。** `push` 这个开关在 `meta` 里按事件类型算，别把它写成恒 true——那等于每个 PR 往 GHCR 里堆一堆拉不下来的野镜像。
- **`provenance: false`。** 不关它在 GHCR 上会多两个 `unknown/unknown` 的空壳镜像，看着像多供了一个版本，实际拉不到东西。
- **前端 dist 是“覆盖”而不是“同步”。** `tar` 走 ssh 时故意**不带 `--delete`：带上了，一个 `im-website` 的构建就能把 `web/` 下另外两站扫干净。旧版本的带 hash 文件名会堆积，想清就单独跑一次 `find web -name '*.js' -mtime +30 -delete`，别交给 CI。
- **探活为什么绕到部署机上去 curl**：runner 在公网另一侧，目标机可能只开放内网/回环。所以探活命令是 `ssh ... "DEPLOY_DIR=$DIR bash -s" <<'EOF'`，只认 `HTTP 200`，不是「能连上就算成功」。
- **`deploy` 绑了 `environment: production`**，可以在 Settings → Environments 里给它加「必需审批人」，就能做到「合入 main 自动出镜像，上生产需要人点一下」。
- **★ 第一次真跑就撞上的两条（下面这些只有上 runner 才会暴露，本地静态检查看不到）**：
  1. **`npm ci` 只报一句 `npm error Invalid Version: `（空字符串）就挂了。** 跟 node 版本、跟 Windows/Linux 差异都没关系，**是 lock 本身破了一个条目**：`im-admin-ui/package-lock.json` 里 `node_modules/rollup/node_modules/@rollup/rollup-freebsd-x64` 只剩 `{"dev":true,"optional":true}`，没 `version`（上一次 install 被中断留下的半成品）。所以 `build-web` 里加了一步 `lockfile 自检`（排在 `npm ci` 前面），直接把缺 `version` 的条目路径打印出来。修法：先删那个缺 `version` 的条目（lock 坏着时 `npm install --package-lock-only` 自己也会报同一个错），再跑一次 `npm install --package-lock-only` 让 npm 重建这一段；★ 不要删整份 lock 重做（会把 `^` 范围内全部依赖悄悄升最新版）。
  2. **验 jar 不能 grep `Main-Class: com.im.bootstrap.ImApplication`。** Spring Boot 重打包后 `Main-Class` 是 `org.springframework.boot.loader.launch.JarLauncher`，**我们的主类在 `Start-Class:` 行**；那条 grep 永远匹不到，`set -euo pipefail` 下就是一个好端端的 81M jar 被判失败。另：MANIFEST 行尾是 CRLF，要 `tr -d '\r'` 再用 `grep -x`。

> 反过来讲一句红线：**本仓库的开发机不需要、也不应该装 Maven/JDK/Node/docker 来做这些事。**
> `Docker/app/Dockerfile.multistage` 存在的唯一理由，是那台开放机连 GitHub 都拉不动时当场 `docker compose build` 兜一份；
> 它是兜底路线，不是日常路线。日常只走路线 A（Actions 推镜像 → 服务器 `pull`）。

---

## 十一、健康检查与开机自启

### 11.1 五层检查，按顺序做（每层能排除掉一类原因）

```bash
docker compose ps                                       # ① 编排层：谁没 healthy
docker compose logs --since=10m --tail=200 im-server    # ② 应用层：IllegalStateException / 连不上 / 端口占用
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; cat <&3'   # ③ 容器内自问（★ bash：dash 不认 /dev/tcp）
docker compose exec nginx sh -c 'nc -w 2 im-server 8080 < /dev/null'   # ④ 跨容器：nginx 能不能摸到后端（★ alpine 无 bash，用 busybox nc）
curl -sk -o /dev/null -w "%{http_code}\n" https://im.example.com/api/auth/me -H "Origin: https://im.example.com"   # ⑤ 外部
docker compose exec im-server env | grep -E 'IM_|MYSQL_|REDIS_|MINIO_'   # ⑥ 变量真值（3.3 那条坑的复核手段）
```

第 ⑤ 步的判定值得记住：**未登录也回 HTTP 200 + `Result{code:1002}`**（全局异常处理器不带 `@ResponseStatus`）。
拿到 `1002` 恰恰证明「后端活着、路由对、只是没登录」；`404` 才说明控制器没被加载；
`403 Invalid CORS request` 回到 8.5；`502` 看 ④。

宿主机上还有一份落盘日志（bind mount 出来的，不进容器也能看）：

```bash
ls -l /opt/im-docker/data/logs     # info.log / warn.log / error.log / DEBUG.log / SQL/slow-sql.log / SLOW-API/ / MODERATION/
tail -f /opt/im-docker/data/logs/error.log
```

> 目录里若还出现 `im-server.log`，那是老配置的历史残留，当前 logback 不往这个名字写。

### 11.2 开机自启

```bash
sudo systemctl enable docker
```

有 `restart: unless-stopped` + docker 开机自启，机器重启后整套服务会自动回来。想更明确就补一个 systemd 单元：

```ini
# /etc/systemd/system/im-docker.service
[Unit]
Description=IM Docker Compose Stack
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/im-docker
ExecStart=/usr/bin/docker compose up -d --wait
ExecStop=/usr/bin/docker compose down            # ★ 没有 -v
TimeoutStartSec=300                               # 首次要等 mysql initdb + 后端冷启，别用默认 90s

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now im-docker
systemctl status im-docker --no-pager
```

> `docker compose up -d --wait` 会**等到所有 healthcheck 变 healthy 才返回**，比「起完立刻算成功」适合放脚本里；
> 前提是 11.1 那几个 healthcheck 真能过，否则会一直卡到 `TimeoutStartSec`。

### 11.3 资源限制与容量

```bash
docker stats --no-stream          # 看实际内存占用，验证 deploy.resources.limits 真生效
docker compose exec mysql sh -c 'mysql -uim -p"$MYSQL_PASSWORD" -e "SHOW STATUS LIKE Threads_connected; SHOW VARIABLES LIKE max_connections;"'
```

- Hikari `prod` 是 `maximum-pool-size: 50` / `minimum-idle: 10`，而 compose 里把 `max_connections` 钉到 300（镜像默认 151）。
  约束仍是 **50 × 实例数 必须明显小于 `max_connections`**；单实例下 151 也够，抬到 300 是给运维工具与将来第二个库留余量。
- JVM 侧 `MaxRAMPercentage=75` 与 `limits.memory: 2g` 配套，堆上限约 1.5g；想改硬堆就改 `JAVA_OPTS`（在镜像里，不是 `conf/im.env`）。
- MinIO 与 MySQL 各吃自己的盘：`df -h /var/lib/docker` 与 `du -sh $(docker volume inspect -f '{{ .Mountpoint }}' im-docker_mysql-data)`。

---

## 十二、容器化专属故障速查

| 现象 | 第一嫌疑人 | 怎么确认 / 怎么修 |
|---|---|---|
| 后端无限重启，`Unknown database 'im_db'` 或 `Access denied for user 'im'` | 卷是空的 / 两份密码不一致 | `docker compose logs mysql`；确认 `.env` 与 `conf/im.env` 里同一个密码；空库按第六节导 |
| `Communications link failure` / `Connection refused: localhost/127.0.0.1:3306` | **`MYSQL_HOST` 还是 `127.0.0.1`** | `docker compose exec im-server env \| grep MYSQL_HOST`；容器里必须是服务名 |
| 启动日志 `MinIO 存储桶初始化失败: im-files` | minio 容器未就绪 / endpoint 不对 / 凭据不一致 | 这是**刻意的 fail-fast**；先 `docker compose ps` 看 minio 是否 healthy，再核 `MINIO_ENDPOINT=http://minio:9000` |
| minio 容器直接退出 `Password must be at least 8 characters` | `MINIO_ROOT_PASSWORD` 太短 | 改 `.env`，≥8 位 |
| `nginx: [emerg] unknown directive "dav_methods"` | 镜像没 dav 模块（或自己压的精简版） | 8.1 的 `nginx -V`；换回官方镜像 |
| 直播能开播、观众黑屏，`index.m3u8` 回 403 | 签名 secret 不一致 / 不是 base64url / 前缀不同名 | 三处一起核：`IM_LIVE_SIGN_SECRET` ↔ `secure_link_md5` ↔ `location` 与 `play-base-url` 的 `/live` |
| 多档直播一律 403，单档正常 | 变体清单也被签名了 | hls.js 拉变体不携 `?expire=&sign=`；`location ~ \.m3u8$` 那段**不能**有 `secure_link` |
| 主播端 PUT 全 404/403，而 nginx 日志里看不到对应请求 | `IM_LIVE_PUSH_BASE_URL` 写了容器内部地址 | 改成主播端公网可达的地址（第五节注释已标 ★★）；`http://nginx:8088/...` 这种服务名只能在容器内解析 |
| nginx 启动即退 `host not found in upstream "im-server"` | 后端未就绪 / 服务名拼错 | 保留 `depends_on.condition: service_healthy`；`docker compose ps` 看 im-server |
| 浏览器全 403 `Invalid CORS request`，`curl` 正常 | CORS 是 yml 字面列表 | 8.5 外挂配置；注意它是**整体替换**，客户端 Origin 要一并抄 |
| 手机浏览器正常、桌面端（Tauri/Capacitor/Electron）连不上 | 外挂 CORS 只写了三个域名，把 tauri/capacitor/localhost 顶掉了 | 同上，补齐 |
| `ufw allow/deny` 加了没反应，端口还是公网可达 | **Docker 绕过 UFW** | 别发布该端口；要按 IP 过滤用 `DOCKER-USER` 链或云安全组（8.3） |
| nginx 日志里客户端 IP 全是 `172.x.x.1` | docker-proxy 换了源地址 | `daemon.json` 设 `"userland-proxy": false` 后实测；推流口 `allow` 名单相应改 |
| 大文件上传 413 | nginx 先拦了 | `client_max_body_size 110m`（单请求通道）；分片通道单片 6MB，走 `/api/file/upload/*` 不受 100MB 限 |
| 推流口 PUT 返回 500，nginx 日志 `client body temp ... failed` | `client_body_temp_path` 不可写 | 指到 `/tmp/nginx_body` 或把该 server 的挂载改读写 |
| 改了 `sql-init/` 里的 SQL，重启 mysql 没生效 | `initdb.d` 只在空卷跑一次 | 7.1 手工导；或试用环境 `down -v` 重建（★ 数据全没） |
| 镜像构建报 `groupadd: GID '1000' already exists`（退出码 4） | Ubuntu 24.04 底座预置了占位组 `gro:x:1000:`，裸写 `groupadd --gid 1000` 必失败 | 4.1 的四级阶梯；急着过可以先 `--build-arg IM_GID=1001`，或把 gro 改名让位 |
| `/app/logs` 是空的，`docker compose logs` 却很多 | 目录 uid 不对，logback **不拦启动只静默丢** | `chown -R 1000:1000 data/logs upload-tmp`；或 `docker compose logs im-server \| grep -i logback` |
| 时间差 8 小时（含定时任务不到点跑：红包退款、消息过期清理） | MySQL 容器默认 UTC 而 JDBC 钉了 +08 | 第五节 `--default-time-zone=+08:00` + 应用容器 `TZ=Asia/Shanghai`；12 个 `@Scheduled` 的 cron 按 JVM 时区解释 |
| 中文与 emoji 存进去变 `?` | 管道导入 / 服务端字符集退回 latin1 | 7.1 的 `cp` + `-e "source"`；6.2 的 `SELECT @@character_set_server` |
| `--scale im-server=2` 之后有人收不到消息、定时任务跑两遍 | **本系统不支持多实例** | 立刻缩回 1；扩容先看服务器部署指南第七节 |
| 重启容器后本地存储的文件全丢 | `IM_FILE_STORAGE=local` 但 `IM_FILE_DIR` 没挂在卷上 | 默认 `${user.home}/im-files` 在容器文件系统里；挂 `./data/files:/app/files` 并把变量指过去 |
| Prometheus 抓不到 `:8081` | `MANAGEMENT_ADDRESS=127.0.0.1` 的容器语义 | 9.4；改 `0.0.0.0` 且**不要发布**该端口 |
| 通话对方听不见 / 摄像头权限弹窗消失 | 页面不是 https 安全上下文 | 8.4；顺便确认证书续期 deploy-hook 真跑过 |
| 磁盘 `/var/lib/docker` 涨到爆 | 容器 stdout 的 `json-file` 无上限 | 1.4 的 `log-opts`；应用自己的日志有 `total-size-cap: 5GB` 兜着 |
| `docker compose pull` 拉不动 / 超时 | 国内访问 Docker Hub | 1.5 加速或 `docker save/load` 离线导入 |
| `docker compose up` 报 `multiple networks` 或卷名对不上 | 改了 `name:` 字段 | 卷名前缀就是项目名；改 `name` 等于换了一批空卷 |

---

## 十三、什么时候不该用 Docker

三条，任一命中就回到《[服务器部署指南](服务器部署指南.md)》的裸机形态：

1. **机器只有 2GB 内存**。五个容器（MySQL 512M 缓冲池 + Redis + MinIO + JVM 约 1.5G + nginx）挤在 2G 上会互相 OOM，
   而被杀掉的往往是 JVM——`MaxRAMPercentage` 让它看着「还剩点内存」。
2. **不需要频繁重建环境、也没有多套并存需求**。本项目当前**必须单实例**（第〇节第 2 条），
   于是 Docker 最大的卖点「随便起、起多个」在这里恰好无效，剩下的收益只有环境一致性与迁移便利。
3. **要用 coturn 且宿主机在 NAT 后**。`network_mode: host` + `--external-ip` 这套组合在容器里比裸机多一层调试成本；
   同一条 TURN 配置直接 `apt install coturn` 会好排得多。

反过来说，**这三种场合最适合容器化**：要在两台机器之间搬环境；想同时留一套试用（演示账号）和一套正式库；
以及不想让 MySQL/Redis/MinIO 的 apt 升级在半夜把你的生产服务顺带重启掉。

> 最后一句提醒：**容器化不会改变业务侧的任何约束。** 单实例、CORS 字面列表、MinIO fail-fast、
> `im_data.sql` 里 `admin / 123456` 演示账号上生产必改密码——这四条在两种形态下一模一样。

---

## 延伸文档

| 主题 | 文档 |
|---|---|
| **裸机口径的同一件事（装哪些软件、systemd、Nginx、上线检查清单、migration 顺序表）** | [`服务器部署指南.md`](服务器部署指南.md) |
| 37 张表逐表逐列解析、种子数据、两份全量脚本与真实库的对账结论 | `md/数据库表解析.md`（对账在 §14.10） |
| 直播架构与 Nginx 推流/播放口的完整配置 | `md/直播.md`（部署在 §10） |
| MinIO 的下载、启动、桶与凭据、故障排查 | `md/MinIO部署指南.md` |
| WebRTC 通话、STUN/TURN、群通话 mesh、录制留存 | `md/语音视频通话接入指南.md` |
| 监控与告警（Actuator / Prometheus / Grafana） | `md/监控告警部署指南.md` |
| 被控端 Agent 的编译与配置（**不在服务器上跑**，见服务器部署指南 §2.3） | `md/远程控制Agent使用说明.md` |
| **本文对应的成品文件：整套部署包（compose / env 模板 / Dockerfile / nginx / 运维脚本 / bootstrap）** | [`Docker/`](../../Docker/)，上手读 [`Docker/README.md`](../../Docker/README.md) |
| **本文 §10.5 对应的 CI：打 jar + 打三个前端 + 构建并推镜像 + 可选 SSH 部署** | [`.github/workflows/build-and-package.yml`](../../.github/workflows/build-and-package.yml) |
| 行尾统一（`*.sh` / compose / Dockerfile 强制 LF，否则 Windows 上检出就跑不了） | [`.gitattributes`](../../.gitattributes)、仓库根 [`.dockerignore`](../../.dockerignore) |
| 每一次变更的登记 | `md/CHANGELOG.md` |
