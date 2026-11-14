# Docker 部署指南（Ubuntu 24.04 · Docker Compose）

> 事实来源：`im-bootstrap/src/main/resources/application.yml`（61 个不同的 `${ENV:默认值}` 占位符，口径为 `\$\{([A-Z0-9_]+):` 去重计数；
> 这个数会随版本漂移，所以**引用一律按变量名，不写行号也不写文件总行数**——加两行注释就会让整仓行号引用集体作废）、
> `application-prod.yml`、`im-bootstrap/pom.xml`（`finalName=im-server`，主类 `com.im.bootstrap.ImApplication`）、
> 根 `pom.xml`（`java.version=21`、Spring Boot 4.0.8）、`sql/im_schema.sql` / `sql/im_data.sql`、
> `im-common` 的 `logback-spring.xml`、`im-file` 的 `MinioFileStorage` / `LocalFileStorage`、`md/直播.md` §10.4-10.5、`electron/live-retention.js`。
> 运维脚本口径取 `Docker/scripts/` 的 `bootstrap.sh` / `health.sh` / `import-sql.sh` / `clear-perm-cache.sh` / `backup.sh` / `update.sh`
> 与 `im-docker.service`；服务名、端口与 `MINIO_ENDPOINT` 口径取 `Docker/docker-compose.yml`、`Docker/.env.example`、
> `Docker/conf/im.env.example`（★ minio 已移出主栈，是宿主二进制，容器侧一律经 `host.docker.internal:9000` 访问）。
>
> **与《[服务器部署指南](服务器部署指南.md)》的分工**：那一份讲「这台机器要装哪些软件、进程怎么管」，是**裸机口径**；
> 本一份只讲**同一套东西换成容器之后变了什么**——哪些 `127.0.0.1` 必须改成服务名、哪些默认值在容器里换了含义、
> 哪些坑是 Docker 特有的（UFW 对已发布端口不生效、Actuator 的 loopback 语义、`initdb.d` 只跑一次）。
> 两份冲突时以本份的容器口径为准，但**业务侧的红线（单实例、CORS 白名单、MinIO fail-fast、演示账号）两份完全一致**。

---

## 目录

- [〇、先看结论](#〇先看结论)
- [〇·B、最短部署路径（照抄可跑）](#〇b最短部署路径照抄可跑)
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

**这套主 compose 跑四个必装服务**（`mysql`/`redis`/`im-server`/`nginx`），下面表格另列了两个「按需」和一个「单独部署」的 minio：

| 服务 | 镜像 | 干什么 | 对外发布端口 |
|---|---|---|---|
| `mysql` | `mysql:8.0` | 业务库 `im_db`（43 张表） | ❌ 不发布 |
| `redis` | `redis:7-alpine` | 会话 / 权限缓存 / 直播与通话瞬时态 | ❌ 不发布 |
| `im-server` | 本目录 `Dockerfile` 构建 | 唯一的 Java 进程（HTTP + WebSocket + 定时任务） | ❌ 不发布（只给 nginx 反代） |
| `nginx` | `nginx:1.27-alpine` | 三份前端静态站 + `/api` `/ws` 反代 + 直播推流/播放口 | ✅ `80` `443`，按需 `8088` |
| `minio` | 官方二进制（非镜像） | 对象存储（文件、头像、附件）——**★ 不在主栈、也不进 CI；用 systemd 跑官方二进制，见 §8.3 与 [`md/MinIO部署指南.md`](MinIO部署指南.md)** | ✅ 宿主监听 `9000` `9001`（供 `host.docker.internal`，需放行 docker 子网并限源） |
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

## 〇·B、最短部署路径（照抄可跑）

> 本节是**索引而不是替代**：每步只给「跑什么 + 为什么不能省」，展开的理由与故障口径在括号指向的小节里。
> 目录用 `/opt/im-docker` 代指你实际放 `Docker/` 的那一层（`im-docker.service` 的 `WorkingDirectory` 要跟着改）。
> 命令全部是 **bash**（在部署机上跑）：`chmod` / `systemctl` / `bash scripts/*.sh` 在 Windows PowerShell 里都不存在，
> 只有 ③ 的三条 `scp` 是在 **Windows 构建机**上跑的。

**与 `md/综合.txt`「docker 镜像构建部署」那七步的对应关系**（那七步是速记，本节是它的展开版；两处顺序与写法不同，下表点明）：

| 速记里的步骤 | 本节对应 | 一致 / 差异（★ 是会让服务起不来或数据坏掉的差异） |
|---|---|---|
| 1 前置条件（minio 二进制 + `minioadmin` / `.env` 换真值 / 装 docker + compose 插件 / `docker login ghcr.io` / `bootstrap.sh`） | ① + ② | 一致；`docker login ghcr.io` 只在 **GHCR 包是私有**时才需要，公开仓库免登录 |
| 2 三站 dist 拷进 `Docker/web/` | ③ | 一致；速记的目的路径写的是 `~/桌面/Docker/web/`，本份统一按 `/opt/im-docker/web/` 举例 |
| 3 启动镜像（`pull` → `up -d` → `ps` → `health.sh`） | ⑤ | ★ **顺序相反**：本份把建库（④）排在起全栈之前。理由是「四个容器全 healthy + 空库」是最容易漏的一种静默失败——空库不影响任何 healthcheck，只炸在第一次登录那一下（§6.4） |
| 4 数据库初始化 | ④ + §六 | ★ **写法不同**：速记里 `docker exec -i im-mysql mysql -uroot -p<密码> im_db < ./02_im_data.sql` 是**管道喂 SQL**，中文与 emoji 会静默刷成 `?` 而命令返回 0（§7.1）；本份只给 `initdb.d` 自动导（路线 A）与 `cp` + `-e "source"`（路线 B）两条 |
| 5 访问测试（客户端 hosts 一行 + 三站分流表） | ⑤ 末 | 一致；补一条：★ 手机 / 平板改不了 hosts，正式用必须真域名（① 域名那条） |
| 6 其他 docker 命令 | ⑤ 末「日常命令」+ §十 | 展开 |
| 7 真实服务器上问题（真证书 + 续期 hook / CORS 外挂 / `clear-perm-cache.sh` / live-retention / AI 知识库目录 / 开机自启 / 备份 cron / `initdb.d` 护栏） | ⑥ | 一致；八条里七条在 ⑥ 的收尾表里逐条对应一个具体故障表现，只有 `clear-perm-cache.sh` 位不同——它被提到 ⑤ 的命令块里紧跟建库跑（§7.2） |

**四条顺序红线，倒了必炸**（这一节存在的全部理由，其余步骤都可以按你的习惯重排）：

| 红线 | 倒了会怎样 |
|---|---|
| **宿主 MinIO 二进制** 早于 `docker compose up -d` | `MinioFileStorage` 是**刻意 fail-fast**：连不上桶就抛 `IllegalStateException`，im-server 反复重启（聊天与登录能起，上传与头像全挂） |
| **`bash scripts/bootstrap.sh`** 早于 `up -d` | `nginx/conf.d/im.conf` 与 `ssl_certificate` 都不存在，nginx `[emerg] ... no such file` **启动即退出**；而 `depends_on: service_healthy` 会让后端白起一轮 |
| **建库 + 数表（41）** 早于第一次登录 | 空库不影响**任何** healthcheck，四个容器全绿，只炸在「第一次有人登录」那一下（§6.4） |
| **`.env` 与 `conf/im.env` 同一个值逐字一致** | 表现是「容器都起来了但连不上」；`bootstrap.sh` 第④步就是替你保证这条，所以**只改 `.env` 然后重跑脚本**——手改 `conf/im.env` 的那几项会被下一次 bootstrap 覆盖回去（§三） |

### ① 前置（不上服务器就做不完、上去也补不回来的四件事）

```bash
sudo apt install -y docker.io docker-compose-v2   # 或 §1.2 官方仓库路线，★ 两条不要都装
docker compose version                            # 必须是 v2.x（v1 不支持本份的 depends_on.condition）
cat /etc/docker/daemon.json                       # §1.4：log-opts 必须设，否则 stdout 无上限撑爆 /var/lib/docker
systemctl is-active minio                         # §8.3：官方二进制 + systemd，不在主栈里
curl -sf http://127.0.0.1:9000/minio/health/live  # 通了才算前置完成
```

- **MinIO 要先装本体**：`systemctl start minio` 只对**已安装**的服务有效。下载官方二进制 + 写 systemd unit（步骤见
  [`md/MinIO部署指南.md`](MinIO部署指南.md) 三/四节），且 `/etc/default/minio` 里的 `MINIO_VOLUMES` 必须等于 `.env` 的
  `MINIO_DATA_DIR`——`backup.sh` 靠它找对象存储目录，不一致就是「备份成功但里面没数据」。
- **凭据 `minioadmin/minioadmin` 只许本地测试**，上服务器务必换强口令（≥ 8 位，否则 MinIO 自己拒启），并同步 `.env` 的
  `MINIO_ROOT_USER/PASSWORD`（`bootstrap.sh` 会把它们灌进 `conf/im.env`，两边不用你手工对）。
- **域名 A 记录先生效**（`im.` / `admin.` / `www.` / `live.` → 同一台机器同一个 443）：certbot 要验域名归属，
  解析没通就签不下证书（§8.4）。内网自测可用客户端 hosts 代替，但**手机与平板改不了 hosts**，正式用还是得真域名（§12 的 IP 那两行）。
- **私有 GHCR 包才需要** `docker login ghcr.io`（PAT 只勾 `read:packages`）；仓库公开就免登录（§4.4 / §10.5）。

### ② 两份配置：先把模板拷成正式文件，再跑 bootstrap

```bash
cd /opt/im-docker
cp .env.example .env && chmod 600 .env    # ★ 600 是「仅属主可读写」（rw-------），不是 644：里面是明文密码与两把签名密钥
vi .env                                    # ★ 所有 <...> 必须换掉；bootstrap.sh 第①步就扫残留占位符，留着直接 die
docker compose config -q                   # 插值没配对 / 缩进错 / 引用不存在的卷，在这一步就报出来（§5.1；bootstrap.sh 的 ⑦ 会再跑一遍）
bash scripts/bootstrap.sh                  # 可重复跑（幂等）；加 --force 才覆盖，覆盖前备份 .bak.<时间戳>
```

★ **比填占位符更容易漏的是另一类**：`.env.example` 里的密码与密钥**不是 `<...>` 而是直接写的 dev 真值**
（`MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` / `REDIS_PASSWORD` / `MINIO_ROOT_USER|PASSWORD` / `IM_JWT_SECRET` /
`IM_TICKET_SECRET`），四个域名也是 `*.example.com` 字面值。`bootstrap.sh` 第①步只拒带 `<` `>` 的值（它扫的是
`case "$v" in *"<"*|*">"*)`），**这些一个都抓不到**——不手动改就直接带着 dev 口令与两把开发密钥上线，而
`IM_JWT_SECRET` / `IM_TICKET_SECRET` 泄露等于任何人都能自签 token / 任何人能签文件下载票据。逐个换掉：

```bash
grep -nE '^(MYSQL_|REDIS_|MINIO_ROOT_|IM_JWT_|IM_TICKET_)' .env    # 确认已经没有 dev 值
openssl rand -base64 24 | tr -d '=+/' | head -c 32; echo            # 生成一个：≥ 32 位随机串
```

换完再跑一次 `bootstrap.sh`：它第④步会把上面那几份密码/密钥 `set_kv` 进 `conf/im.env`（两边逐字一致这条红线由脚本保证），
所以不要手抄 `conf/im.env`。**但域名改了是另一回事**：`im.conf` 与 `application-prod-extra.yml` 走的是 `render`，
它对「已存在且与模板渲染结果不同」的目标文件默认**只 WARN、保留现状**，不覆盖——所以改过 `.env` 里的域名后
必须 `bash scripts/bootstrap.sh --force` 重跑（旧文件会先备份成 `.bak.<时间戳>`），再 `docker compose restart nginx`
与 `docker compose restart im-server` 两侧各重读一次（§8.2 / §8.5）。
留着 `*.example.com` 时脚本第①步末尾只会给一条 `域名仍是 example.com` 的 WARN，**不拦启动**，很容易一路跑到上线才发现。
`MINIO_ROOT_USER/PASSWORD` 还得去 `/etc/default/minio` 同步改——那是宿主二进制自己的凭据源（脚本只负责把它们写进
`conf/im.env` 的 `MINIO_ACCESS_KEY/SECRET_KEY`），两边不一致的表现是「minio 健康检查全绿、上传全 403 `SignatureDoesNotMatch`」。

`bootstrap.sh` 干的活（§二、§三）。脚本头注释写的是「做五件事」，实际输出有 **①~⑦**（⑥ 补周边资料、⑦ 静态校验 compose，
是后来加上去的、注释没同步）：校验 `.env` → 建目录并把 `data/` 下各写目录 chown 成 uid 1000（与容器内
`USER 1000:1000` 对齐，不对齐就是静默丢日志）→ 由 example 生成 `nginx/conf.d/im.conf` 与
`app/extra/application-prod-extra.yml`（域名与直播签名密钥灌进 `__TOKEN__`）→ 生成 `conf/im.env` 并同步密码/密钥
→ 证书位缺文件就补 30 天自签。

> ★★ 它还有一个**只在仓库里跑才生效**的第⑥步：放 SQL 进 `sql-init/`。那一段包在 `if [[ -d ../sql ]]` 里，
> 只 `scp -r Docker/` 一层上服务器时条件为假、**一声不吭地跳过**（不报也不 warn），于是建出一个空库。
> 所以下一步开始前先 `ls -l sql-init/*.sql`（§6.4）。

### ③ 三站静态产物（上线清单，不是可选优化）

构建机上 `npm ci && npm run build` 三次，再把 **dist 里的内容**（不是 `dist` 目录本身）拷进 `web/<站>/`：

```powershell
# Windows 构建机，仓库根下执行；目的路径 = 部署根的 web/<站>
scp -r im-ui\dist\*        deploy@server:/opt/im-docker/web/im-ui/
scp -r im-admin-ui\dist\*  deploy@server:/opt/im-docker/web/im-admin-ui/
scp -r im-website\dist\*   deploy@server:/opt/im-docker/web/im-website/
```

只有默认模式的 `npm run build` 能用（`build:electron` 会把 `base` 改成 `./` 并把产物换到 `../electron/dist`）。
空目录的指纹很好认：根路径 **403** + 子路径 **500**，补上 dist 两者一起消失，不用 reload 也不用重启容器（§5.1 ⓪）。

### ④ 建库：首选「空卷首启自动导」，先只起 mysql

```bash
# SQL 没到位就手动补，★ 前缀 01_/02_ 决定执行顺序（字典序：建表必须早于插数据）
cp <仓库>/sql/im_schema.sql sql-init/01_im_schema.sql
cp <仓库>/sql/im_data.sql   sql-init/02_im_data.sql
docker compose up -d mysql
docker compose logs -f mysql      # 看得见 01_im_schema.sql 被执行的过程才算真导入
# ★ 数表（期望 41，不是 0），这比只看 ps 四个绿可靠得多
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"im_db\""'
# 想目视一遍表名（速记里 `docker exec -it im-mysql mysql -e "use im_db; show tables;"` 的等价写法）
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SHOW TABLES FROM im_db"'
```

卷已经非空时 `initdb.d` **不会再跑**（§6.3），走 §6.4 路线 B：`docker compose cp` 进容器再 `-e "source"`。
两条路都不能用管道——速记里 `docker exec -i im-mysql mysql -uroot -p<密码> im_db < ./02_im_data.sql` 与
`cat x.sql | docker compose exec -T mysql ...` 都不行，中文会静默刷成 `?`（§7.1）。
另一个同类写法坑：把密码直接拼在命令行里（`-p<真密码>`）能跑，改成 `-p"$MYSQL_ROOT_PASSWORD"` 后就必须整个放进
`sh -c '…'` 里由**容器内**展开，否则宿主 shell 把它展开成空串（§7.1 末）。

### ⑤ 起全栈与验证

```bash
docker compose pull                     # ★ 先 pull：up 见本地无镜像会退化去 build，等于放弃「与 CI 那一遍完全相同的 jar」（§4.4）
docker compose up -d
docker compose ps                       # mysql / redis / im-server / nginx 四个都到 running(healthy)
bash scripts/health.sh                  # 六段分层诊断（§11.1）；★ 它第一段还会单独探宿主 minio，因为它不在栈里
bash scripts/clear-perm-cache.sh        # 建库 / 导 migration 动过 im_permission 就跑（§7.2）
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -e "SELECT name FROM im_db.im_gift LIMIT 3"'   # 中文抽查
docker compose exec im-server env | grep -E 'IM_|MYSQL_|REDIS_|MINIO_' | sort          # 值里带引号 / 行内注释 = conf/im.env 写错了（§3.3）
```

**拉不动镜像**（内网机 / 国内访问 Docker Hub 超时）时的本地路线：构建机出 jar → `cd Docker/app && docker build -t local/im-server:1.0.0 .`（§4.1），
再把 `.env` 的 `IM_SERVER_TAG` 改成 `local/im-server:1.0.0` 并 `docker compose up -d im-server`；
依赖的 mysql/redis/nginx 用 `docker save/load` 或 CI 的 `mirror-images` 搬到 GHCR（§4.4 / §1.5）。

**浏览器侧的最后一次确认**（这一步是「四个容器全绿但页面全挂」的分水岭，也是唯一必须**在客户端**做的事）：

```text
# 内网自测：客户端 hosts 加一行（★ 每台客户端各自生效，手机 / 平板改不了 hosts —— 见 ① 域名那条）
192.168.83.130   im.example.com admin.example.com www.example.com
```

| 站点 | 浏览器里敲 | 靠哪个 `server_name` 分流 | 磁盘目录 |
|---|---|---|---|
| 聊天端 | `https://im.example.com/` | `__IM_DOMAIN__` | `web/im-ui` |
| 管理后台 | `https://admin.example.com/` | `__ADMIN_DOMAIN__` | `web/im-admin-ui` |
| 静态官网 | `https://www.example.com/` | `__WEBSITE_DOMAIN__` | `web/im-website` |

三个都不通时先用 `curl -s http://127.0.0.1/healthz` 把「nginx 没起来」与「`server_name` 没匹上」分开（应回 `ok`；
`/healthz` 只挂在 80 那个 server 里，compose 给 nginx 做健康检查用的也是它，所以它能回 `ok` 就代表 nginx 已加载完配置）。
本栈在 **80 / 443 上没有任何 `default_server`**（只有 8088 推流那个 server 写了 `server_name _`），Host 匹不上就静默落到该端口第一个 server（= 聊天端），症状是「三个域名打开的都是同一个站」；
而 `http://` 直开页面只会拿到 301，**它只能证明 nginx 进程活着**，证明不了证书、分流与 CORS 中任何一项（§8.3 / §8.5）。

**日常 docker 命令（速记里那步「其他 docker 命令」的展开）**——按「看 / 进 / 改 / 建」分四组：

```bash
# 看：起没起、日志、资源
docker compose ps                                   # STATUS 要带 (healthy)；只显示 Up = healthcheck 压根没配好（§5.1）
docker compose logs -f im-server                    # 应用层：IllegalStateException / 连不上 / 端口占用（§11.1 ②）
docker compose logs --since=30m nginx              # nginx 自己的 error 与访问日志
docker compose top                                  # 容器里真正跑着的进程（验证 JVM 参数真进去了）
docker stats --no-stream                            # 实际内存，验证 deploy.resources.limits（§11.3）
docker system df                                    # 镜像 / 容器 / 卷 / 构建缓存各占多少（§10.4 清理前先看这个）

# 进：容器内部视角（★ 变量一律放进 sh -c '…'，理由见 §7.1 末）
docker compose exec im-server bash                                          # 后端容器（temurin 底座自带 bash）
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" im_db'   # 交互式 mysql
docker compose exec nginx sh -c 'nginx -t -q' && docker compose exec nginx nginx -s reload   # 改完 conf 先语法检查再重载（§8.2）

# 改：★ 两条生效命令分得很清，搞反就是「改了没生效」（§8.5 / ⑥ 末那条）
docker compose up -d                                # 改 .env / conf/im.env / docker-compose.yml / 换镜像 tag → 重建容器
docker compose restart im-server                     # ★ 只在改了挂载进去的文件（app/extra/*.yml）时用；它对 env_file 重读无效（那要走上面那条 up -d）
docker compose pull && docker compose up -d          # 升级镜像（§10.2，自动化在 §10.5）

# 建：拉不到 GHCR 时的本地路线（§4.1 / ⑤ 末），★ 两个构建上下文不要写反
docker build -t local/im-server:1.0.0 Docker/app                                       # 路线 A：仓库根执行，上下文 = Docker/app（jar 先放进那里）
docker build -t local/im-server:1.0.0 -f Docker/app/Dockerfile.multistage .             # 路线 B：上下文 = 仓库根
docker history --human local/im-server:1.0.0                                            # 逐层看体积（§4.3）
```

> ★ 三条删除类命令的边界，按危险程度递增：`docker compose down`（只删容器与网络，**卷留着**，安全）→
> `docker compose down -v`（★ 连 `mysql-data` / `redis-data` 一起删，只有试用环境可以）→
> `docker system prune -a --volumes`（★ 把旧镜像和未引用卷一并扫掉，包括你留着回滚的那个 tag，§10.4）。

### ⑥ 上线收尾（试用环境可跳；正式环境每一条都对应一个具体的故障表现）

| 收尾项 | 不做会怎样 | 详节 |
|---|---|---|
| 真证书（DNS-01）+ `renewal-hooks/deploy/` 里 `nginx -s reload` | 浏览器一直告警；★ 续期后 nginx **不自己重载**；而 `getUserMedia`（语音/视频通话、摄像头、录屏）只在安全上下文可用，只挂 80 = 通话直接不可用 | §8.4 |
| 换真域名后改 `app/extra/application-prod-extra.yml` 的 CORS 白名单 | 站点能打开、**接口全 403 `Invalid CORS request`**（白名单是 yml 字面列表，环境变量改不动） | §8.5 |
| 开直播就起 `live-retention` | 后端只管 Redis 入口标记，**控制面一个媒体字节不碰**；盘上分片没人删，一场 2 小时四档约 8~10GB。★ `--days` 与 `IM_LIVE_REPLAY_RETENTION_DAYS`（默认 1）必须同一个数，否则「后台有这条回放、点进去 404」 | §9.1 |
| `IM_AI_KNOWLEDGE_DIR=/app/knowledge` 并真的放文件 | 默认值是 `D:/资料/知识库/面试官`，Linux 上**不存在也不报错**，只是检索永远空转——这类静默不可用比启动失败难发现得多 | §9.3 |
| `im-docker.service` 开机自启（`WorkingDirectory` 改成实际目录） | 重启机器后服务不回来；★ 它的 `ExecStop` 刻意**没有 `-v`**：`down -v` 会把 `mysql-data` / `redis-data` 两个卷一起删（minio 已移出栈，数据在宿主目录 `MINIO_DATA_DIR`，`down -v` 删不到它，但一条 `rm -rf` 能） | §11.2 |
| `backup.sh mysql` 进 cron + 异步同步 | 备份只落同一块盘等于没备份 | §10.1 |
| 建完库把 `./sql-init:/docker-entrypoint-initdb.d:ro` 这行注释掉并清空该目录 | 留着就是一个定时炸弹：`im_schema.sql` 开头有 43 条 `DROP TABLE IF EXISTS`，一旦卷被重建（`down -v` / 换机器 / 换项目名）就**静默清库** | §6.3 |
| 改演示账号密码 | `im_data.sql` 里是 `admin / 123456`，业务侧红线与容器与否无关 | §十三 |

> ★ 改了配置后的生效命令**分两种，搞反就是「改了没生效」**：改 `conf/im.env`（env_file 是建容器时注入的）必须
> `docker compose up -d` 重建，`restart` 不重读；改 `app/extra/*.yml`（挂载文件）只需 `docker compose restart im-server`，
> 而这里跑 `up -d` 反而没用——compose 认为配置未变，不重建也不重启进程（§8.5）。

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
> | §6 SQL 初始化 | `Docker/sql-init/`（空，由 `bootstrap.sh` 从 `sql/` 拷入并重命名为 `01_/02_`；★ 只拷了 `Docker/` 一层时 `../sql` 不存在，这一步会静默跳过 → 手动补见 §6.4） | `sql-init/` |
> | §7 权限缓存清理 | `Docker/scripts/clear-perm-cache.sh` | `scripts/` |
> | §8 nginx | `Docker/nginx/conf.d/im.conf.example` + `Docker/nginx/snippets/{proxy,ssl}.conf`<br>`Docker/app/extra/application-prod-extra.example.yml` | `nginx/conf.d/im.conf`（token 替换生成） |
> | §9 sidecar / TURN | `Docker/scripts/live-retention.js`（`bootstrap.sh` 从 `electron/` 拷入） | `scripts/` |
> | §10 备份升级 | `Docker/scripts/{import-sql,backup,update}.sh` | `scripts/` |
> | §11 健康检查、自启与日志 | `Docker/scripts/{health.sh,im-docker.service}`；日志布局在 `im-common/src/main/resources/logback-spring.xml`（§11.4） | `scripts/` + `/etc/systemd/system/`；日志落 `data/logs/` |
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
├── web/                          ← ★ 三站 dist 的内容，首次 up -d 之前就得放好（空目录＝站点根 403，见 5.1 ⓪）
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
MINIO_ENDPOINT=http://host.docker.internal:9000   # ★ minio 已移出主栈、单独发布宿主 9000；裸机直跑写 http://127.0.0.1:9000
MINIO_ACCESS_KEY=<同 MINIO_ROOT_USER>
MINIO_SECRET_KEY=<同 MINIO_ROOT_PASSWORD>
MINIO_BUCKET=im-files
# 不想跑 MinIO 就整段换成下面两行（少一个容器，代价是文件绑在这台机器的卷上）
# IM_FILE_STORAGE=local
# IM_FILE_DIR=/app/files                     # ★ 必须是挂载进容器的路径，否则重建容器 = 文件全丢

# ===== 按需 =====
IM_AI_KNOWLEDGE_DIR=/app/knowledge           # 默认 D:/资料/知识库/面试官（Windows 路径，Linux 必须覆盖）
ALI_BABA_API_KEY=<百炼 Key，不设则仅面试功能不可用>
IM_SMS_PROVIDER=mock                         # 默认 mock：只写日志、不发真短信，用户永远收不到码（目前只有 mock 一个 SmsSender 实现，填 aliyun 会发不出码）
MAIL_USERNAME=you@qq.com
MAIL_PASSWORD=<QQ 邮箱的 SMTP 授权码，不是 QQ 登录密码>
IM_LIVE_ENABLED=true
IM_LIVE_PLAY_BASE_URL=/live                  # ★ 生产约定 /live（yml 默认 /hls 是给本地 live-media-server.js 用的），要与 nginx 的 location 同名
IM_LIVE_PUSH_BASE_URL=http://<公网 IP 或主播可达的域名>:8088/live   # ★★ 前缀必须与 nginx 的 location /live/ 同名；且绝不能写 http://nginx:8088/...：那是容器内部 DNS，主播端在公网，解析不到
IM_LIVE_SIGN_SECRET=<与 nginx secure_link_md5 的 secret 逐字一致>
IM_LIVE_REPLAY_RETENTION_DAYS=1              # 与 retention sidecar 的 --days 读同一个值（第九节）
IM_CALL_STUN_URLS=stun:<公网IP>:3478
IM_CALL_TURN_ENABLED=true
IM_CALL_TURN_URLS=turn:<公网IP>:3478?transport=udp
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

# 非 root 运行：数字 uid/gid 1000 与宿主机 ./data/* 的 chown 对齐（第二节）
# ★★ 这段被真实构建打挂两次才改对，教训是：需要的只是「镜像里存在 uid/gid 1000 这两个数字」，
#   数字背后叫什么名字无关 —— USER 1000:1000、chown -R 1000:1000、COPY --chown=1000:1000 全是数字，
#   不查 /etc/passwd。而 eclipse-temurin:21-jre 的底（Ubuntu 24.04）自带一个 ubuntu 用户（uid 1000）
#   和它的同名组（gid 1000）。所以别再新建一个叫 im 的用户去抢 1000：
#     · `groupadd --gid 1000 im` → 退出码 4（GID 已存在；占用者不是网传 adduser 的 gro 占位组，
#       实测就是 ubuntu 组，所以「改名让位 gro」那条分支根本不触发）
#     · 跳过建组后再 `useradd --uid 1000 im` → `useradd: UID 1000 is not unique`
#   正解：数字已存在就复用，不存在才补建（getent 支持按数字查 passwd/group，glibc 特性）。
#   另：RUN 续行里不要投行内注释——行尾反斜杠会把换行吃掉，注释连后一行一起被注掉（症状是命令凭空少一截）。
ARG IM_UID=1000
ARG IM_GID=1000
RUN set -eu; \
    if getent group "${IM_GID}" >/dev/null 2>&1; then \
      echo "[im] gid ${IM_GID} 已存在（$(getent group "${IM_GID}" | head -1 | cut -d: -f1)），复用"; \
    else \
      groupadd --system --gid "${IM_GID}" im; \
    fi; \
    if getent passwd "${IM_UID}" >/dev/null 2>&1; then \
      echo "[im] uid ${IM_UID} 已存在（$(getent passwd "${IM_UID}" | head -1 | cut -d: -f1)），复用，不建 im 用户"; \
    else \
      useradd --system --uid "${IM_UID}" --gid "${IM_GID}" --home-dir /app im; \
    fi; \
    mkdir -p /app/logs /app/upload-tmp /app/files /app/knowledge /app/extra; \
    chown -R "${IM_UID}:${IM_GID}" /app

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
# 建组建用户与 4.1 完全一致（那节有「只复用数字 1000、不新建 im 用户」的原因），这里略成紧凑版：
RUN set -eu; \
    getent group 1000 >/dev/null 2>&1 || groupadd --system --gid 1000 im; \
    getent passwd 1000 >/dev/null 2>&1 || useradd --system --uid 1000 --gid 1000 --home-dir /app im; \
    mkdir -p /app/logs /app/upload-tmp /app/files /app/knowledge /app/extra; \
    chown -R 1000:1000 /app
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

### 4.3 镜像体积 & compose 栈到底涉及几个镜像

**先分清一个容易混的点：上面两条路线构建的都是 `im-server` 这一个单一镜像（只装后端 Java 应用），不是「把整个项目打成一个镜像」。**
`docker compose up` 起来的是**主栈 4 个镜像**（mysql/redis/im-server/nginx），其中只有 `im-server` 由本仓库构建并推 GHCR，其余 3 个都是**官方现成镜像**，本项目的 Dockerfile 不参与它们的构建（CI 的 `mirror-images` job 会把这 3 个原样搬到 GHCR `im-deps/*` 供 `compose pull`）：

> ★ **minio 不在这个表里、也不属主栈**：MinIO 于 2026-09-11 把 `minio/minio` 从 Docker Hub 整仓下架（拉取 404/denied），`quay.io/minio/minio` 又拒 GitHub runner 共享 IP 的匿名拉（401），CI 镜像同步这条路走不通。因此 minio 已从 `mirror-images` 矩阵摘出。
> **★ 统一用官方二进制跑 minIO**（`https://dl.min.io/server/minio/release/linux-amd64/minio` + systemd 常驻，不依赖任何镜像仓库），见 [`MinIO部署指南.md`](../MinIO部署指南.md) 第三/四节与本文 §8.1；im-server 经 `host.docker.internal:9000` 连它。（已不再保留容器退路：`minio/docker-compose.yml` 已删除。）

| 服务 | 镜像 | 来源 | 谁负责构建 |
|---|---|---|---|
| `im-server` | `ghcr.io/<owner>/im-server:<tag>` | **本仓库 Dockerfile 构建** | CI（build-and-package.yml 的 docker-image job） |
| `mysql` | `mysql:8.0` | Docker Hub 官方（CI 同步到 GHCR） | 不构建，直接 pull |
| `redis` | `redis:7-alpine` | Docker Hub 官方（CI 同步到 GHCR） | 不构建，直接 pull |
| `nginx` | `nginx:1.27-alpine` | Docker Hub 官方（CI 同步到 GHCR） | 不构建，直接 pull |
| `minio` | `quay.io/minio/minio:RELEASE.*` | **quay.io（不在 CI 镜像流）** | 不构建，部署机单独 pull+起 |

（compose 里还留了 `node`/`coturn` 两个注释掉的服务，放开直播媒体面/TURN 时才多拉对应镜像。）

**im-server 单镜像体积（估算，以实测为准）**：

| 组成 | 未压缩 | 依据 |
|---|---|---|
| 底座 `eclipse-temurin:21-jre`（Ubuntu 24.04 + JRE21） | 约 250–280 MB | 官方 JRE 版，比 JDK 版小近一半 |
| `im-server.jar`（Spring Boot fat jar，含全部依赖） | 约 73 MB | 即 CI Artifacts 里那个 `im-server-jar`（实测 72.7 MB） |
| `/app` 下几个空目录 + ENV/ENTRYPOINT | 可忽略 | 运行时被 bind mount 覆盖 |
| **合计** | **约 330–360 MB**（未压缩） | GHCR 存/`docker pull` 传的是压缩态，约 **120–160 MB**（jar 已是 zip 压不动多少） |

> 整栈首次 `docker compose pull` 落盘远大于此（光 `mysql:8.0` 未压缩就近 600 MB，minio/redis/nginx 各几十到一两百 MB），
> 但那些是官方镜像、与本项目构建无关，本仓库只对该体积负责。**别为省体积换 `-alpine` 底座**：理由见 4.1 顶部注释（busybox/dash 不认 `/dev/tcp`，健康检查会静默失效）。

**整个 compose 栈的体积估算（5 个服务，均为约值，以实测为准）：**

| 服务 / 镜像 | 未压缩（约） | 压缩 pull（约） | 说明 |
|---|---|---|---|
| `im-server` | 330–360 MB | 120–160 MB | 本仓库唯一自建镜像（拆解见上表） |
| `mysql:8.0` | 约 600 MB | 约 160 MB | 官方，**栈里最大头** |
| `redis:7-alpine` | 约 45 MB | 约 15 MB | 官方 alpine 版，很小 |
| `quay.io/minio/minio:RELEASE.*` | 约 60–150 MB | 约 40 MB | 官方（Go 静态二进制 + 精简底），源在 quay.io |
| `nginx:1.27-alpine` | 约 50 MB | 约 15 MB | 官方 alpine 版 |
| **整栈合计** | **约 1.1–1.2 GB** | **约 350–400 MB** | 首次 `compose pull` 落盘量级 |

> 上表只算镜像本身，**不含数据卷**（`mysql-data`/`redis-data`/minio 存储等运行时才增长）。若把 §五 里注释的 `node`（直播媒体面）与 `coturn`（TURN）放开，再各加几十到上百 MB。

**整栈精确实测（拉完/起完后跑，别信估算）：**

```bash
# 列出本 compose 会用到的镜像及其 SIZE（未拉取的显示 0）
docker compose images
# 看真实落盘（按服务名过滤）
docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' | grep -E 'im-server|mysql|redis|minio|nginx'
# 本机全部镜像/容器/卷的总占用概览
docker system df
```

**im-server 单镜像精确实测（拉完/起完后跑，别信估算）：**

```bash
# 本地已 build 的镜像未压缩总字节数
docker image inspect local/im-server:1.0.0 --format '{{.Size}}'
# 逐层看谁占地方（应就是 base + jar 两块）
docker history --human local/im-server:1.0.0
# GHCR 上压缩后的真实传输体积（读 manifest 各层 size 求和，public 包无需登录）
docker buildx imagetools inspect ghcr.io/<owner>/im-server:<tag> --raw \
  | grep -o '"size":[0-9]*' | awk -F: '{s+=$2} END{printf "%.1f MB\n", s/1024/1024}'
```

> CI 已在 build-push-action 里设 `provenance: false`（见 §10.5），省掉默认 attestation 那两条 `unknown/unknown` 记录，GHCR 版本列表更干净（不影响体积本身）。

### 4.4 整栈一键：把依赖镜像也扫到 GHCR（目标机不碰 Docker Hub）

国内服务器 `docker compose pull` 拉 Docker Hub 经常超时/失败，而 mysql/redis/nginx 是官方镜像、本仓库不构建它们。所以让 CI 把这三个也镜像到 GHCR，compose 默认改从 GHCR 拉，实现整栈一键（minio 不在其中，走宿主二进制）。四处改动配合：

1. **CI**（`build-and-package.yml` 的 `mirror-images` job）：用 `skopeo copy --override-arch amd64` 把三个官方镜像原样复制到 `ghcr.io/<owner>/im-deps/{mysql,redis,nginx}:<同官方 tag>`；先 `skopeo inspect` 比 digest，已同步就秒跳过，不会每次 push 重传 mysql 那 ~160 MB。★ minio 不在此列（官方镜像已下架、quay 拒 CI 匿名拉，改用宿主二进制，见 §8.1）。PR 不跑；main/tag 总跑；手动跑受 `mirror_images` 勾选控制。
2. **compose**：三个依赖镜像改为 `${MYSQL_IMAGE:-mysql}:${MYSQL_TAG:-8.0}` 这种「变量 + Docker Hub 默认值」写法。**不设变量时行为与以前完全一致**，向后兼容。
3. **`.env.example`**：把 `MYSQL_IMAGE`/`REDIS_IMAGE`/`NGINX_IMAGE` 默认值填成 `ghcr.io/<owner小写>/im-deps/*`（minio 无镜像变量，改用 `MINIO_DATA_DIR` 指向宿主二进制数据目录）。
4. **`bootstrap.sh`**（①.5 步）：从 `IM_SERVER_TAG` 里提取 owner，自动回填上面三行的 `<owner小写>`——**owner 只在一处填，四处不会各写各的**。

部署侧流程不变（§二）：`cp .env.example .env` → 填密码与 `IM_SERVER_TAG` 的真 owner → `bash scripts/bootstrap.sh`（自动对齐依赖镜像 owner）→ `docker compose pull && docker compose up -d`，全程只访问 GHCR。

> ★ **首次部署前需先有镜像站**：`im-deps/*` 是由 CI 同步的。若 GHCR 上还没有，先手动跑一次 Actions（Run workflow，勾上 `mirror_images`）把四个镜像推上去；或者临时把 `.env` 里那四个 `*_IMAGE` 改回官方短名先走 Docker Hub。
> ★ **想回落 Docker Hub**：删掉 `.env` 里那四行 `*_IMAGE`（或改回 `mysql`/`redis`/`minio/minio`/`nginx`）即可，compose 默认值就是官方短名。
> ★ **升级依赖版本**：改 `.env` 里对应的 `*_TAG`（如 `MYSQL_TAG=8.4`）**同时**改 `mirror-images` job 矩阵里的 `src` tag，两边一致；下次 CI 跑就会把新版本同步上去。

---

## 五、docker-compose.yml 全文

`/opt/im-docker/docker-compose.yml`。这份是**可以整份抄走**的最小生产形态：直播媒体面与 TURN 已写好但注释掉，按需放开。

> ★ 仓库里的本体是 [`Docker/docker-compose.yml`](../../Docker/docker-compose.yml)，与下面这段同源，但有几处已经在服务器上补过的差异：
> ① `redis` 服务多了 `REDIS_PASSWORD: ${REDIS_PASSWORD:?}` 这个环境变量——它存在的**唯一理由**是让
> `docker compose exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" …'` 能在**容器内**取到密码（`health.sh` 与
> `clear-perm-cache.sh` 都靠这一条）。不加它、或不写 `sh -c` 而直接写 `-a "$REDIS_PASSWORD"`，拿到的一定是空串：
> compose 只插值 yml，**不对 `exec` 的命令行做插值**，那串 `$` 是由你的**宿主 shell** 展开的，而 `.env` 并不进宿主环境（§7.1 末）；
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
      start_period: 40s           # 首次初始化要建 43 张表，别把 start_period 设小了导致误判 unhealthy

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
      # ★ 多一份只是为了让容器内的 `sh -c 'redis-cli -a "$REDIS_PASSWORD" …'` 能用（health.sh / clear-perm-cache.sh 依赖它）
      REDIS_PASSWORD: ${REDIS_PASSWORD:?}
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"${REDIS_PASSWORD}\" ping | grep -q PONG"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 10s

  # ---------------------------------------------------------------- MinIO —— 已移出本栈
  # minio 不再写在主 compose 里：因 MinIO 2026-09-11 从 Docker Hub 整仓删除、quay 又拒 CI 匿名拉（401），
  # CI 搬不动它，遂改由宿主机官方二进制跑（systemd 服务 minio，监听 0.0.0.0:9000/9001，见 §8.1 / MinIO部署指南.md）。
  # 它发布宿主 9000/9001，im-server 经 extra_hosts 的 host.docker.internal:9000 连它（MINIO_ENDPOINT 见 conf/im.env）。
  # ★ minio 在栈外，主栈无法对它做 depends_on: service_healthy——务必先确认二进制 minio 在跑（systemctl is-active minio、curl 127.0.0.1:9000/minio/health/live），再起主栈。

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
      # ★ minio 已移出本栈（单独部署在宿主），compose 无法跨项目对它 service_healthy。
      #   代价：若单独部署的 minio 没先起来，im-server 会因连不上桶 fail-fast 崩重启——先起 minio 再起主栈。
    # 让 im-server 能用 host.docker.internal 解析到宿主机，连上面单独部署的 minio:9000
    extra_hosts:
      - "host.docker.internal:host-gateway"
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
# （minio 已移出本栈改用宿主二进制，数据在宿主目录 .env 的 MINIO_DATA_DIR，不在 compose 卷里）
volumes:
  mysql-data:
  redis-data:
```

### 5.1 起与看

**⓪ `up -d` 之前：三站 dist 必须先就位**（这一步属于上线清单，不是可选优化）。
`./web/` 是挂给 nginx 的静态目录，版本库里**只有一个 `.gitkeep`**，`bootstrap.sh` 也不往里放东西（它只检查 `web/<站>/index.html` 在不在，缺了打一行 `warn` 就继续）。
结果是「四个容器全 healthy，浏览器却 403」——很多人第一反应去查后端和 CORS，方向就偏了。

构建机上先出三份 dist（用 `npm ci` 不用 `npm install`，三站各有各的 lock；口径与坑见 10.5）：

```bash
cd im-ui        && npm ci && npm run build      # → im-ui/dist
cd im-admin-ui  && npm ci && npm run build      # → im-admin-ui/dist
cd im-website   && npm ci && npm run build      # → im-website/dist（官网，可选）
```

★ 只认默认模式的 `npm run build`：`build:electron` / `build:tauri` / `build:capacitor` 是给客户端打包的，
`build:electron` 会把 `base` 改成 `./`、`outDir` 改到 `../electron/dist`，这份产物拷到服务器就是「刷新 404 + 资源路径全错」。
再把 **dist 里的内容**（不是 `dist` 目录本身，否则变成 `web/im-ui/dist/index.html`，照样 403）拷进 `web/<站>/`：

```powershell
# Windows 构建机（Win10+ 自带 OpenSSH 客户端），仓库根下执行；目的路径＝部署根的 web/<站>
scp -r im-ui\dist\*        deploy@server:/opt/im-docker/web/im-ui/
scp -r im-admin-ui\dist\*  deploy@server:/opt/im-docker/web/im-admin-ui/
scp -r im-website\dist\*   deploy@server:/opt/im-docker/web/im-website/
```

```bash
# 落位校验：① 宿主机三个 index.html 都在；② 容器里也看得见（回显为空＝挂的是空目录）
ls /opt/im-docker/web/{im-ui,im-admin-ui,im-website}/index.html
docker compose exec nginx ls /usr/share/nginx/html/im-ui/
```

★ 空目录的指纹很好认，别和别的 403 搞混（详细一行已进第十二节速查表）：
访问站点根回 **403**，nginx error log 是 `directory index of "/usr/share/nginx/html/im-ui/" is forbidden`
——`try_files $uri $uri/ /index.html` 里 `$uri/` 命中了那个空目录，而 `autoindex` 关着；
访问任何子路径（日志里通常是 `/favicon.ico`）回 **500**，`rewrite or internal redirection cycle while internally redirecting to "/index.html"`
——兜底的 `index.html` 同样不存在，内部重定向转满 10 次。**同一个病因的两张脸**，补上 dist 一起消失，既不用 reload 也不用重启容器。

```bash
cd /opt/im-docker
docker compose config -q          # ★ 先静态校验：插值没配对、缩进错、引用了不存在的卷，都在这一步报出来
docker compose up -d
docker compose ps                 # 四个服务（mysql / redis / im-server / nginx）都应是 running(healthy)
docker compose logs -f im-server  # 看到「Started ImApplication」且没有 IllegalStateException 即成
```

`docker compose ps` 的输出值得逐列看：`STATUS` 里 `health: starting` → `healthy` 的迁移说明健康检查真在跑；
只显示 `Up` 没有 health 就是 healthcheck 压根没配好（第八节 nginx 的 `nginx -t -q` 就是这个用途）。

### 5.2 一次网络连通性自检（比读日志快）

```bash
# 后端能不能摸到 mysql / redis / minio（服务名解析对不对，这是容器化第一大坑）
# ★ 用 bash 而不是 sh：/dev/tcp 是 bash 的特性，dash / busybox sh 都不认；
#   也别用变量替把端口凑在一起（${h/mysql/3306} 对 redis 会拼成主机名当端口）
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/mysql/3306   && echo mysql-ok'
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/redis/6379   && echo redis-ok'
# ★ minio 已不在栈里，没有 `minio` 这个服务名可解：它走 extra_hosts 的 host.docker.internal（解到网桥网关 IP）
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/host.docker.internal/9000 && echo minio-ok'
#   上面这条不通而宿主机 `curl 127.0.0.1:9000/minio/health/live` 通 = 十之八九是防火墙没放行 docker 子网（§8.3）
# nginx 能不能摸到后端：nginx:1.27-alpine 里没有 bash，用 busybox 自带的 nc（不写 -z，靠 </dev/null 立刻 EOF）
docker compose exec nginx  sh -c 'nc -w 2 im-server 8080 < /dev/null' && echo upstream-ok
```

---

## 六、首次启动与数据库初始化

### 6.1 ★ 先分清「全新库」和「存量库」，这条在容器形态下更危险

`sql/im_schema.sql` 开头是 **43 条 `DROP TABLE IF EXISTS`**（为了让脚本幂等，与同文件 43 张 `CREATE TABLE` 对得上）。
裸机上误跑一次还能从备份回；容器里如果你把它挂进 `docker-entrypoint-initdb.d` 又执行了
`docker compose down -v`（`-v` 连命名卷一起删），**库和数据一起没了**。

| 场景 | 该怎么做 |
|---|---|
| 全新环境、可以丢数据 | 用 6.2 的 `initdb.d` 自动导入 |
| 已有正式数据的存量库 | **什么自动导入都别挂**。卷已存在时 `initdb.d` 本来也不会跑（见 6.3），migration 走第七节手工执行 |
| 从旧机器迁库 | 宿主机 `mysqldump` → 灌进容器（第十节） |
| **只拷了 `Docker/` 一个目录上服务器** | `sql-init/` 不会自动有文件，得手动补 SQL（**见 6.4**，这一种最容易整块漏掉） |

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
> docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -e "SELECT @@character_set_server, @@collation_server; SELECT name FROM im_db.im_gift LIMIT 3;"'
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
这是一个便宜的防误操作护栏。现成脚本：`bash scripts/guard-sql-init.sh`（把建库脚本挪进 `sql-init.disabled/` + 落 `sql-init/.disabled` 标记，重跑 `bootstrap.sh` 也不会被撤销），要连挂载一起注释再 `--comment-compose`；`--status` 看锁着没有、`--unlock` 解锁。★ 锁是本机状态不随仓库走（`sql-init/` 与 `sql-init.disabled/` 都被 `.gitignore` 排除），新机部署天然无锁、能正常建库。

### 6.4 ★ 只拷了 `Docker/` 目录上服务器：SQL 不会自动到位，必须手动补

**先判据**（`bootstrap.sh` 内部用的就是同一条）：

```bash
cd /opt/im-docker
ls -l sql-init/*.sql        # 只有 .gitkeep、或干脆 No such file → 就是这种情形
[ -d ../sql ] && echo "有 ../sql，bootstrap.sh 会自动拷" || echo "没有 ../sql，得手动补"
```

为什么会踩：`bootstrap.sh` 第⑥步那整段包在 `if [[ -d ../sql ]]` 里。在仓库里跑（`cd 仓库/Docker`）
就能命中 `../sql`，脚本自动 `cp` 并改名成 `01_/02_`；而用 `scp -r Docker/` 或打包 zip 只把 `Docker/`
这一层搬上服务器时，这个判断为假，**一声不吭地跳过**，不报也不 warn。

后果是静默失败，不是启动报错，所以极容易当场漏检：`sql-init/` 空 → `initdb.d` 里没有可执行脚本
→ 镜像照常建出**空库** `im_db`（`MYSQL_DATABASE` 仍然生效）→ mysql 照常 healthy →`depends_on: service_healthy`
照常放行 im-server → `doc.html` 也照常能翻。**直到第一次有人登录**才炸出
`Table 'im_db.im_user' doesn't exist`。因此别只看 `docker compose ps` 四个绿，要数表：

```bash
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"im_db\""'
# 期望 41，不是 0
```

**补救分两条路，按卷里有没有东西选。**

路线 A —— 刚 up 就发现、卷还是空的（试用环境，最省事）：补文件 + 删卷重来。

```bash
# ① 先把两份 SQL 弄上服务器（本机 scp / git clone 仓库后拷 / U 盘，任选）
scp sql/im_schema.sql sql/im_data.sql <部署用户>@<服务器IP>:/opt/im-docker/
cd /opt/im-docker
# ② 改名放好：前缀必须是 01_/02_，字典序保证建表先于插数据（理由见 6.2）
mv im_schema.sql sql-init/01_im_schema.sql
mv im_data.sql   sql-init/02_im_data.sql
# ③ 卷非空时 initdb.d 不会重跑（6.3），所以要删卷
docker compose down -v        # ★ 数据全没，确认这是可以丢的库再敲
docker compose up -d
```

路线 B —— 卷里已经有东西、或者不能删（★ 生产库走这条，不碰 `initdb.d`）：拷进容器再 `source`。

```bash
cd /opt/im-docker
docker compose cp sql-init/01_im_schema.sql mysql:/tmp/01_im_schema.sql
docker compose cp sql-init/02_im_data.sql   mysql:/tmp/02_im_data.sql
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 im_db -e "source /tmp/01_im_schema.sql"'
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 im_db -e "source /tmp/02_im_data.sql"'
docker compose exec mysql sh -c 'rm -f /tmp/01_im_schema.sql /tmp/02_im_data.sql'
```

> **这里偏偏不能用 `bash scripts/import-sql.sh`。** 那脚本第 25 行 grep 到 `^\s*DROP\s+TABLE` 就 `exit 3` 拒绝执行，
> 而 `im_schema.sql` 正是它拦的这一种（设计意图是防你把全量建库脚本灌进生产库）。空库手工补建表是它唯一
> 放行不了的合理场景 —— 用上面那两行 `-e "source"`，**不要为了顺手去改脚本里的护栏**。
> 也别退回 `cat x.sql | docker compose exec -T mysql mysql ...` 那条管道：它会把 UTF-8 按容器 `LANG` 重编码，
> 中文静默刷成 `?`，行数还都对得上（同 7.1 那条教训）。

全量建库会新插 `im_permission` / `im_role_permission`，导完接着清一次权限缓存，
否则表现是「SQL 明明执行了、后台菜单和按钮还是旧的」（`im:auth:perm:{userId}` 有 30 分钟 TTL 且登录不刷新）：

```bash
bash scripts/clear-perm-cache.sh
```

最后回到 6.3 结尾那条护栏：建完库把 compose 里 `./sql-init:/docker-entrypoint-initdb.d:ro` 这行注释掉并清空该目录。

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

> ★ 密码变量一律放进 **`sh -c '…'$VAR…'`** 里让**容器内**的 shell 展开：`docker compose exec` 后面的参数是由你的
> **宿主 shell** 展开的，compose **不会对命令行做插值**（它只插值 yml）。而 `.env` 只是 compose 读的插值文件、
> 并不会进你的 shell 环境，所以宿主侧写 `-p"${MYSQL_ROOT_PASSWORD}"` 会静默展开成**空串**，症状是
> 「密码明明对，却回 Access denied / 让你再输一次」。确实想从宿主取值就先 `set -a; source .env; set +a`（注意这样会把
> 全部密码导进当前会话的环境变量，登出前记得 `unset`）。另外：密码会出现在 `docker inspect` 的命令行里——
> 介意的话给 mysql 挂一份 `extra.cnf`（`[client] user/password`）走 `--defaults-extra-file`。

### 7.2 ★ 铁律没变：新开或收掉权限点，跑完 SQL 必须清权限缓存

`PermissionProviderImpl` 的 L2 缓存 `im:auth:perm:{userId}` TTL 30 分钟，且**登录动作不刷新它**。
不清的表现是「SQL 跑了、后台菜单还是旧的、点进去 403」，很容易误判成代码问题。

```bash
# ① 先看有多少键（只读，安全；★ 同样把变量放进容器内的 shell 展开，理由见 7.1 末）
docker compose exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "im:auth:*" | wc -l'
# ② 再删。容器里可以用 xargs（裸机 PowerShell 那条「管道静默不删」的教训在这里不适用）：
docker compose exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "im:auth:*" | xargs -r -n 50 redis-cli -a "$REDIS_PASSWORD" --no-auth-warning DEL'
# ③ 删完复跑 ①，期望 0
```

> ★ 这三步就是 `bash scripts/clear-perm-cache.sh` 的全部逻辑（多一个可选参数 `… 1000` 只清某个人），
> 实际执行推荐直接跑脚本：它带了第三步的复查与「清不空就再跑一次」的判定。

口径仍是「**先 keys 后同一条 DEL**、删完再 keys 确认残留 = 0」：`--scan` 与 `DEL` 分两次连接执行时，
中间新写入的键会漏，所以清完必须回到 ① 再看一次。若只想清某个人：`redis-cli ... DEL im:auth:perm:1000`。

### 7.3 只读核对

```bash
docker compose exec mysql sh -c 'mysql -uim -p"$MYSQL_PASSWORD" -t im_db -e "SELECT COUNT(*) AS perms FROM im_permission; SELECT id,code FROM im_permission WHERE id BETWEEN 119 AND 122;"'
docker compose exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning info keyspace'
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
    include /etc/nginx/snippets/ssl.conf;      # 协议/会话缓存/HSTS（HSTS 默认注释着，原因见 §8.4）

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

- 主栈只发布 `80` / `443`（按需 `8088`、TURN 的 `3478` 与中继端口段、以及 `8947/udp`）。**主栈里 8080/8081/3306/6379 一律不发布**。
  （minio 不在主栈而是宿主二进制：它的 `9000`/`9001` 是**宿主机自己监听的端口，走 UFW 的 INPUT 链**（不像 Docker 发布端口那样绕过 UFW）。容器经 `host.docker.internal`（网桥网关 IP，如 `172.17.0.1`）访问它，所以要放行 docker 子网：`ufw allow from 172.16.0.0/12 to any port 9000 proto tcp`；对外来源继续靠云安全组挡在公网之外。）
- 要按源 IP 过滤已发布的端口，**UFW 不管用**（Docker 直接动 `DOCKER` 链，绕过 `ufw` 的 `INPUT`）。
  官方给的口子是 `DOCKER-USER` 链：

  ```bash
  sudo iptables -I DOCKER-USER -p tcp --dport 8088 -s <主播出口IP> -j ACCEPT
  sudo iptables -I DOCKER-USER -p tcp --dport 8088 -j DROP
  sudo netfilter-persistent save     # 或写进 systemd 单元，重启后还在
  ```

- 云主机上更省事的一层是**安全组**：只放 443（和按需端口），它在所有情形下都有效。

### 8.4 证书

**先认清 `bootstrap.sh` 补的那份是什么**：它对 `IM` / `ADMIN` / `WEBSITE` / `LIVE` 四个域名各生成一张 **30 天自签证书**
（`openssl req -x509 -nodes -newkey rsa:2048 -days 30 -subj "/CN=$d" -addext "subjectAltName=DNS:$d"`），三件事要记住：

- ★ **只有 DNS SAN、不含 IP SAN**。所以 `https://192.168.83.130/` 这种 IP 直访一定是「证书名称不匹配」，而且**换真证书也治不好**（Let's Encrypt 不给 IP 签域名证书）——要拿掉告警只能走域名。
- 它的唯一作用是**让 nginx 起得来**（缺 `ssl_certificate` 时 nginx 是 `[emerg]` 直接退出，而 `depends_on: service_healthy` 会让后端白起一轮）。浏览器信任、通话安全上下文、公网访问这三件都靠下面的 certbot。另：生成失败时脚本**只 WARN 不阻断**，所以症状是 nginx 反复重启而不是 `bootstrap.sh` 报错（看它的输出末尾有没有那行「已为 … 生成 30 天自签名证书」）。

- ★ **30 天到期后重跑 `bootstrap.sh` 依然不会重签**（现在它会**提醒**，但仍然不动文件）。⑤ 的判据是「**文件在不在**」，
  有效期只用来在不足 7 天时打一行 WARN；而 nginx 不校验自己证书的有效期，照样把这张过期的递出去、握手照常成功。
  于是症状是「四个容器全 healthy、客户端全炸」：`ERR_CERT_DATE_INVALID`、`wss` 握不上、`getUserMedia` 失效。
  临时续命只能先 `sudo rm -rf /opt/im-docker/certs/live/<域名>` 再跑脚本（又是一张 30 天），但那是在把冒烟道具当生产用，
  正经解法是下面那套两级 CA（内网）或再下面的 certbot（公网）；而且 `ssl.conf` 里的 HSTS 一旦下发过，过期期间连「继续访问」按钮都没有，所以测试期那行默认是注释着的（见 §8.2 与 `Docker/nginx/snippets/ssl.conf`）。

#### 内网测试想绿锁：两级 CA，根装一次 + 叶子 30 天自动换

自签的根因是「**颁发者不受信任**」，跟有效期是两回事：把 `-days 30` 改成 `-days 3650`，`ERR_CERT_AUTHORITY_INVALID` 照样在。
真解法是**自己当一个 CA**：客户端只信任根，叶子由根现签 —— 于是换叶子对客户端完全无感，自动续期才第一次有意义。
本项目用 `scripts/gen-selfca.sh`（签发）+ `scripts/push-cert.sh`（推送）这两步做完：

| 谁 | 持有什么 | 跑什么 |
|---|---|---|
| ★ **签发机**（开发机 Git Bash / WSL / 一台内网 Linux） | 根私钥 `certs/ca/rootCA.key`（600，**永不出这台机器**） | `bash scripts/gen-selfca.sh --ip <部署机IP>` |
| **部署机**（跑 nginx 的那台） | 只有叶子 `certs/live/<域名>/` + 标记 `certs/.managed-by-push` | 不签任何东西；`bootstrap.sh` ⑤ 见到标记就绕开 |
| **每台内网测试机 / 手机** | 根证书 `certs/ca/rootCA.pem`（装进「受信任的根证书颁发机构」，10 年一次） | 无 |

```bash
# ① 签发机：建根（已存在就复用）+ 按 .env 的 IM/ADMIN/WEBSITE 三个域名各签一张 30 天叶子
bash scripts/gen-selfca.sh --ip 192.168.83.130        # ★ 把部署机 IP 写进 SAN，否则手机按 IP 直连一定名称不匹配
#   没真域名就用 hosts 假域名（im.test/admin.test/www.test → 部署机 IP）或 nip.io 这类泛解析，.test 是保留 TLD 不会撞公网

# ② 根证书发到每台测试机，只装这一次（★ 能离开的只有 .pem，rootCA.key 一步都不许）
scp <签发机用户>@<签发机地址>:<Docker 目录>/certs/ca/rootCA.pem ./
#   Windows : certutil -addstore -f root rootCA.pem       （或双击→安装证书→本地计算机→受信任的根证书颁发机构）
#   iOS     : 装描述文件后 ★必须★ 去 设置→通用→关于本机→证书信任设置 把开关拨开，不拨等于没装
#   Android : 设置→安全→加密与凭据→安装证书→CA 证书（App 内嵌 WebView 默认不吃用户级 CA，浏览器可用）

# ③ 推叶子到部署机并生效（远端落一个 certs/.managed-by-push，此后 bootstrap.sh ⑤ 不再自签盖掉它）
bash scripts/push-cert.sh ubuntu@192.168.83.130 /opt/im-docker      # 先加 --dry-run 只看会推哪些也行

# ④ 自动续期：每天 05:30 跑一轮「到期前重签 → 推送 → reload」，剩余不足 10 天才真重签（幂等，没到期就是「什么都没改」）
bash scripts/gen-selfca.sh --install-cron ubuntu@192.168.83.130 /opt/im-docker
crontab -l | grep gen-selfca                          # 就这一行；日志落在 Docker/logs/selfca-cron.log
```

- ★ **为什么不在部署机上跑 `gen-selfca.sh`**：那样根私钥和 nginx 同机，测试网里任何一台沦陷＝整条信任链被拿走。
  拆成「签发 + 推送」两步后，部署机上永远只有叶子，被拿下也签不出第二张受信任的证书。
- ★ **叶子为什么只签 30 天而不是 820 天**：根装一次管 10 年，叶子短有效期＝私钥泄露窗口小，而且逼着续期链路真的被跑通
  （从没验证过的自动续期等于没有）。要拉长有硬上限 **825 天**（苹果对 TLS 服务器证书的限制，超了 iOS/macOS 直接拒），脚本已经拦。
- ★ **cron 为什么每天跑而不是每月跑一次**：判据是「剩余 < 10 天」，天天跑意味着签发机停机两周也不会断服务；
  每月一次只要有一次没跑成（笔记本合着盖、IP 换了、免密掉了）就直接裸奔到期。
- 免密前提：先 `ssh-copy-id` 通了再装 cron —— 密码交互在 cron 里会静默失败。`push-cert.sh` 用 `BatchMode=yes`，
  不通就当场报错退出，不会挂在那等输入。
- Windows 上没 cron：`--install-cron` 会在末尾打印等价的 `schtasks /Create /SC DAILY` 写法，照着建即可。
- 同思路的外部工具是 `mkcert`（`mkcert -install` 建的也是本地根），差别是它把根和叶子放在同一台机器上、续期得你自己记着。

正式环境换成真证书，**只能走 DNS-01**（不是「推荐」，而是本项目的 HTTP-01 天生不通：`im.conf` 里虽然留了
`location /.well-known/acme-challenge/ { root /var/www/acme; }`，但 nginx 容器的 `volumes:` 里**没挂载 `/var/www/acme`**
（只挂了 conf.d / snippets / 三站 web / certs / data/live），在宿主机往那个目录写 challenge 文件，容器里读到的是空目录 → 永远 404；
而 `--standalone` 又要去抢已被容器占住的 80。DNS-01 不碰 80、不停容器，还能给 `*.example.com` 签一张泛域名：

```bash
sudo apt install -y certbot python3-certbot-dns-cloudflare     # DNS 插件按你的服务商换
sudo chmod 600 /root/cf.ini                                    # 里面是 API token，权限不收紧等于把凭据放公网

# ★ 域名清单以「conf 里实际有 443 server 块」为准：IM / ADMIN / WEBSITE 三个必须有，live 现在不需要
#   （im.conf 只给这三个写了 ssl_certificate；LIVE_DOMAIN 只出现在 80 的 server_name 上，
#    推流口 8088 那块默认注释且 `server_name _` 不带 TLS）。多签一个 live 无害，少签一个 www 就不是那回事：
#   ★ 少 www 的后果不是「那一站没证书」，而是 nginx `[emerg] cannot load certificate` 直接起不来，
#   而 nginx 又排在 im-server 的 `depends_on: service_healthy` 后面——一个 server 块挂，整栈起不来。
certbot certonly --dns-cloudflare --dns-cloudflare-credentials /root/cf.ini \
  -d im.example.com -d admin.example.com -d www.example.com

# ★ 必须带 -L：Let's Encrypt 的 `live/<域名>/fullchain.pem` 是指向 `../archive/fullchainN.pem` 的**软链接**，
#   而容器只挂了 `./certs` 这一层、没有 archive/。`cp -r` 会把软链接原样拷过去 → 悬空链接 → nginx 加载证书失败。
sudo rm -rf /opt/im-docker/certs/live                            # 先清掉自签那批目录，别新旧混在同一层
sudo cp -rL /etc/letsencrypt/live /opt/im-docker/certs/ && sudo chmod -R a+rX /opt/im-docker/certs
file /opt/im-docker/certs/live/im.example.com/fullchain.pem      # 要输出 "PEM certificate"；出现 "symbolic link" 就是漏了 -L

docker compose -f /opt/im-docker/docker-compose.yml exec nginx nginx -t \
  && docker compose -f /opt/im-docker/docker-compose.yml exec nginx nginx -s reload

# 验收：issuer 必须是 Let's Encrypt。还是 CN=<域名> 就说明仍在用那张自签（或者证书根本没换成功）
echo | openssl s_client -connect im.example.com:443 -servername im.example.com 2>/dev/null \
  | openssl x509 -noout -issuer -dates -ext subjectAltName
```

> 换真域名前要先改 `.env` 的四个域名并**重跑 `bash scripts/bootstrap.sh`**：`im.conf` 的 `server_name`、证书路径
> 与 CORS 白名单那三条 origin 全是生成物，不重跑就还停在老域名上（换域名后必须动的第二处见 §8.5）。
> 另外 `bootstrap.sh` 签证书那一步是「**文件不存在才签**」，所以你放进真证书后再跑多少次 bootstrap 都不会被覆盖（这是好事，脚本可以重复跑）。
> 唯一例外：证书是 `push-cert.sh` 推上来的，远端会留一个 `certs/.managed-by-push`，⑤ 见到它就**连空位都不补**——免得哪天重跑 bootstrap 把签发机推来的叶子盖成单张自签。

**续期是两步，不止 reload。** certbot 的 systemd timer 会自动续，但续期只更新**宿主机**的 `/etc/letsencrypt`，
而容器里那份是拷贝出去的副本，不会自己变 —— 所以 deploy-hook 必须**先拷再 reload**，只写 reload 等于没配（不报错，就是新证书永远不生效）：

```bash
# /etc/letsencrypt/renewal-hooks/deploy/im-nginx.sh  ★ 以下四行就是脚本全文
#!/bin/sh
cp -rL /etc/letsencrypt/live /opt/im-docker/certs/
chmod -R a+rX /opt/im-docker/certs
docker compose -f /opt/im-docker/docker-compose.yml exec -T nginx nginx -s reload
```

```bash
# ★ 以下不是脚本内容，是装上去与自检
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/im-nginx.sh   # 没有执行权限的 hook 会被静默跳过
systemctl list-timers | grep certbot          # 确认续期定时器真的在排
certbot certificates                          # 看每张还剩几天
```

★ 验链路是否真跑通需要重签一次（`sudo certbot renew --force-renewal`），但**有速率限制**：同一组域名每周最多 5 张重复证书、
每域名单周最多 50 次签发。`renew --dry-run` 走的是 Let's Encrypt 的测试环境，**不会触发 deploy-hook**，
所以它只能验「能不能续」，验不了「续完服务器有没有真的换证」—— 后者只能靠上面那一次 force-renewal，跑完就停。

> ★ HTTPS 不只是加密：浏览器 `getUserMedia`（语音/视频通话、摄像头、录屏授权）只在**安全上下文**里可用，
> http 打开的页面点通话会直接拿不到媒体权限。容器化不改变这条，别为了省事只挂 80。

**三个阶段各自用哪张证书（一张表定死，别混着用）**

| 阶段 | 域名哪来 | 谁签 | 叶子多久 | 客户端要做什么 | 到期怎么办 |
|---|---|---|---|---|---|
| 冒烟 / 临时演示 | `.env` 里随手填 | `bootstrap.sh` ⑤ 单张自签 | 30 天 | 每次手动点「继续访问」（所以 HSTS 必须注释着） | `rm -rf certs/live/<域名>` 再跑 bootstrap；光重跑不会重签 |
| ★ 内网长期测试 | hosts 假域名 / 内网 DNS / nip.io | 签发机 `gen-selfca.sh`（两级 CA） | 30 天，cron 自动换 | 只装一次 `rootCA.pem`，之后无感 | 什么都不用做；签发机停两周也不怕（前提：免密 ssh 还通） |
| 公网正式 | 真域名 + DNS 记录 | certbot DNS-01（或云厂商证书） | 90 天，LE timer 自动续 | 无（Let's Encrypt 是公共受信任根） | 自动续，★ 但必须配 deploy-hook 先 `cp -rL` 再 reload |

三行共同前提：证书都落到 `certs/live/<域名>/{fullchain.pem,privkey.pem}`，nginx 只读挂 `./certs`；而且 **im / admin / www 三个域名一个都不能少**（少一个是整栈起不来，不是那一站没证书）。
换真域名时记得 `.env` 四处域名都要改并重跑 `bootstrap.sh`（见 §8.5）。

### 8.5 换域名后必须动的第二处：CORS 白名单（yml 是字面列表，环境变量改不动它）

`im.cors.allowed-origins`（`application.yml` 的 `cors:` 段）**没有 `${}` 占位符**，所以不能靠 `conf/im.env` 覆盖。
容器里的做法是挂一份外挂配置（compose 已经把 `./app/extra` 挂到 `/app/extra`）：

`app/extra/application-prod-extra.yml`：

```yaml
im:
  cors:
    allowed-origins:
      - https://im.example.com
      - https://admin.example.com
      - https://www.example.com
      # ★★ 下面几行不是冗余，而是必须整段搬过来：Spring 绑 List 属性时，外挂配置是**整体替换**而不是追加。
      #   只写上面三行，桌面端与移动端就连不上来了（这些 Origin 来自客户端本身，与你的域名无关）。
      - http://tauri.localhost
      - tauri://localhost
      - https://localhost
      - http://localhost
      - capacitor://localhost
      # 还需要开发/联调用的话，把裸机那份 5173/4173/5273/4273 与 http://*.*.*.*:5173 四条一并抄过来
```

然后在 `conf/im.env` 里取消注释（`bootstrap.sh` 见到 `app/extra/application-prod-extra.yml` 存在会自动帮你打开这一行）：

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/extra/application-prod-extra.yml
```

改完**怎么生效要看改了哪个文件**，这里很容易搞错：

```bash
docker compose restart im-server   # 只改了 extra yml：它是 java 启动时读的外挂文件，重启进程就够
docker compose up -d im-server     # 改了 conf/im.env：env_file 是建容器时注入的，必须重建（restart 不重读）
```

★ 只改 extra yml 时跑 `up -d` 是**没用的**：文件内容变了但 compose 配置没变，compose 不会重建容器，java 也就不会重启、不会重读那份 yml（这一条是「改了没生效」的一个典型假象）。

不改的表现：所有浏览器请求回 `403 Invalid CORS request`，而 `curl` 一切正常（`curl` 不带 `Origin` 头）。

#### ★ 另一个同病因的坑：用 **IP 打开页面**，Origin 就是 IP

上面这份白名单里只有**域名形态**（`https://im.example.com` 等）与 `localhost` 那几条客户端变体，**没有任何一条能匹 IP**。
而前端用的是同源相对路径 `/api`，所以浏览器地址栏里是什么，`Origin` 就是什么：

| 浏览器里打开 | 发出去的 `Origin` | 结果 |
| --- | --- | --- |
| `https://im.example.com/` | `https://im.example.com` | 命中白名单 → 正常 |
| `https://192.168.83.130/` | `https://192.168.83.130` | **403 Invalid CORS request**（页面能打开，登录/接口全挂） |

这个情况特别容易误判成「nginx 挂了 / 后端挂了 / dist 没放」，因为**站点本身确实是能打开的**（静态文件不受 CORS 管），只有 XHR 才 403。

两条路，选一：

```bash
# ① 推荐：改用域名访问（hosts 里要有这一条），不动任何配置
#   192.168.83.130  im.example.com admin.example.com www.example.com
#   页面开 https://im.example.com/ ，Origin 就自动变成白名单里的值

# ② 就是想要 IP 直接可访问（内网自测、暂时没域名）：把 IP 抄进白名单
vi app/extra/application-prod-extra.yml
#   im.cors.allowed-origins 下加两行（http/https 各自一条，因为 Origin 含协议）：
#     - https://192.168.83.130
#     - http://192.168.83.130
docker compose restart im-server
```

★ ② 是**逐字符比对**（`CorsConfiguration` 走 `allowedOriginPatterns`），写 `https://192.168.83.*` 这种带 `*` 的形式虽然能匹，但等于把整段内网放进白名单；
公网正式上线建议删掉 IP 条目，只留真域名（与那四条 `http://*.*.*.*:5173` 一起删）。

验一下就知道了（两个 `Origin` 对照着跑，一眼能看出是白名单问题而不是后端问题）：

```bash
# 带域名 Origin → 不 403（进到业务层，回 code:1002 或参数错误都算正常）
curl -k -si -X POST https://192.168.83.130/api/auth/login -H 'Origin: https://im.example.com' -H 'Content-Type: application/json' -d '{}' | head -12
# 带 IP Origin → 403 + 响应体 Invalid CORS request
curl -k -si -X POST https://192.168.83.130/api/auth/login -H 'Origin: https://192.168.83.130' -H 'Content-Type: application/json' -d '{}' | head -12
```

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

这四类产物已经有脚本包好（口径与它逐字对齐，手写只是为了让你知道它在干什么）：

```bash
bash scripts/backup.sh                       # 全备（mysql + minio + conf + redis）
bash scripts/backup.sh mysql                 # 只备数据库：最常用，也唯一适合放 cron 的一段
bash scripts/backup.sh minio                 # ★ 会 sudo systemctl stop/start minio：给这几个命令配 NOPASSWD，或整个脚本用 root 跑
```

> ★ 装 cron 用现成的：`bash scripts/backup.sh --install-cron`（每天 03:30 备库，幂等，重跑只留一行；装在**部署机**上——靠 `docker compose exec mysql`，日志落 `logs/backup-cron.log`）。清理与异地同步都在脚本里、不用手写进 cron 行：保留天数读 `.env` 的 `BACKUP_KEEP_DAYS`（默认 14）、异地目标读 `BACKUP_SYNC_DIR`（NAS 目录或 `user@host:/path` 走 rsync，同步后比对字节数，不一致只 WARN——本地这份已成功）。
> ★ 备份只落在**同一块盘**上等于没备份：`BACKUP_SYNC_DIR` 务必配上。
> ★ 若要手写清理：`find -mtime +N` 删的是「至少 N+1 天前」（`+14` 删 15 天前的），且 `.partial` 不匹配 `im-*.sql.gz`——脚本内部已按 `KEEP_DAYS-1` 处理并单独清 `.partial` 残留；别与 9.1 那条「不能用 `find -mtime` 删直播分片」混为一谈。

下面是手工等价命令：

```bash
cd /opt/im-docker && mkdir -p backup
# ★ 密码变量必须放进容器里的 shell 去展开（docker compose exec 不带 -T 会报警告；管道在宿主侧做）
# ① 数据库（逻辑备份，唯一能跨镜像版本恢复的路子）
docker compose exec -T mysql sh -c \
  'mysqldump -uim -p"$MYSQL_PASSWORD" --single-transaction --routines --default-character-set=utf8mb4 im_db' \
  | gzip > backup/im-$(date +%F-%H%M).sql.gz
# ② 对象存储：minio 是宿主二进制，停服务后直接 tar 它的数据目录（MINIO_DATA_DIR，默认 /data/minio）
sudo systemctl stop minio
sudo tar czf backup/minio-$(date +%F).tar.gz -C "${MINIO_DATA_DIR:-/data/minio}" .
sudo systemctl start minio
# ③ 编排与配置（这三份丢了等于整套环境重建）
tar czf backup/im-docker-conf-$(date +%F).tar.gz docker-compose.yml .env conf app/extra nginx scripts sql-init sql-init.disabled
chmod 600 backup/im-docker-conf-$(date +%F).tar.gz    # ★ 里面有明文密码（.env 与 conf/im.env）
# ④ Redis：已开 appendonly，AOF 在卷里；要快照就：
docker compose exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning BGSAVE'
docker compose cp redis:/data/dump.rdb backup/redis-$(date +%F).rdb
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
# 4) 前端 dist 同理：三站 dist 的内容覆盖 ./web/<站>/（Windows 构建机见 5.1 ⓪）
scp -r im-ui/dist/*         deploy@server:/opt/im-docker/web/im-ui/
scp -r im-admin-ui/dist/*   deploy@server:/opt/im-docker/web/im-admin-ui/
scp -r im-website/dist/*    deploy@server:/opt/im-docker/web/im-website/
#    静态目录是 bind mount，覆盖即生效：不重启容器、不 reload nginx，浏览器强刷就行
#    ★ CI 的 deploy job 干的是同一件事（tar over ssh，覆盖但不删除，所以 web/ 会攒旧 hash 文件），见 10.5
```

### 10.3 回滚

```bash
sed -i 's|^IM_SERVER_TAG=.*|IM_SERVER_TAG=local/im-server:1.0.0|' .env
docker compose up -d im-server
docker images | grep im-server      # 确认目标 tag 还在；被 prune 过就只能重新 build 旧 commit
```

> ★ **回滚镜像不等于回滚数据库**。跑过的 migration 里，加权限点/改列宽这类**向后兼容**的可以留着；
> 而 `im_schema.sql` 那种带 43 条 `DROP TABLE` 的全量脚本（共 43 张表），永远不要出现在生产回滚流程里。
> 每份 `migration_*.sql` 都自带注释形态的回滚段，用它。

### 10.4 别随手 `docker system prune -a --volumes`

`--volumes` 会把当前没被容器引用的卷一起删掉（主栈的 `im-docker_mysql-data` / `im-docker_redis-data`；minio 已改用宿主二进制，数据不在卷里、不受此影响）；`-a` 会删掉所有「当前没被容器引用」的镜像，包括你留着回滚的旧 tag。
要清理就用精确命令：`docker image prune`（只删悬空层）、`docker builder prune`（删构建缓存）。

### 10.5 用 GitHub Actions 把 10.2 自动化（仓库里已给现成 workflow）

本节上面那堆 `mvn package` / `scp` / `sed .env` / `compose up`，在 [`build-and-package.yml`](../../.github/workflows/build-and-package.yml) 里已经写完。**关键前提：构建不发生在开发机，也不发生在业务机，而是发生在 GitHub 的 runner 上。**

| job | 干什么 | 产物 |
|---|---|---|
| `meta` | 从根 `pom.xml` 里取版本号、把仓库主名转小写（GHCR 只认小写镜像名）、判定本次是否该 push（PR 不推） | job 输出 `version` / `image` / `push` |
| `build-jar` | `setup-java@temurin 21` + Maven 缓存 → `mvn -B -DskipTests clean package` → 验 MANIFEST 里的 **`Start-Class`**（★ 不是 `Main-Class`，见下面坑单最后一条） | artifact `im-server-jar-<sha>`（已用 `root:` 收窄到只剩 jar）+ `im-remote-agent-jar-<sha>` |
| `build-web` | 矩阵跑 `im-ui` / `im-admin-ui` / `im-website`：先过一道 **lockfile 自检** → `npm ci` → `npm run build` | artifact `web-<站>-<sha>`，里面就是干净的 `dist/**` |
| `docker-image` | download jar 到 `Docker/app` → buildx + metadata → build **并 push 到 GHCR** | `ghcr.io/<owner>/im-server:<版本>` / `:latest` / `:sha-<短sha>` |
| `mirror-images` | 用 **skopeo** 把 `mysql`/`redis`/`minio`/`nginx` 四个官方镜像原样（仅 linux/amd64）复制到 GHCR；先 `skopeo inspect` 比 digest，已同步就秒跳过不重传 | `ghcr.io/<owner>/im-deps/{mysql,redis,minio,nginx}:<同官方 tag>`（让目标机 `compose pull` 不碰 Docker Hub） |
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

### 11.1 六层检查，按顺序做（每层能排除掉一类原因）

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

> ★ 六段的读法是「**哪一段开始失败，问题就在它与上一层之间**」（`health.sh` 的分层诊断就是按这个思路写的）：
> ① 就不 healthy → 编排层（镜像 / healthcheck / 依赖服务没起来）；① 过 ② 炸 → 应用自己的启动（密码、fail-fast、端口占用）；
> ② 过 ③ 不通 → 容器内端口与 `MANAGEMENT_ADDRESS`；③ 过 ④ 不通 → 跨容器网络（服务名拼错 / `depends_on` / 没同一网络）；
> ④ 过 ⑤ 不通 → nginx 配置、证书、DNS / hosts、CORS；①~⑤ 全过但行为奇怪 → 就查 ⑥ 变量真值（§3.3 那一坑）。

> 上面这六条就是 `bash scripts/health.sh` 跑的内容（它按同样顺序打印 ①~⑥）；该脚本文件头的注释还写着「五层」，
> 那是 ⑥ 变量真值补进去后没同步的历史口径，以脚本里实际输出的段数为准。
> ① 那一段额外先探宿主 minio（`systemctl is-active minio` + `curl 127.0.0.1:9000/minio/health/live`），因为它不在栈里。

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

### 11.4 日志：两条出口、七档落盘（★ 慢 SQL / 慢接口**不在** `docker compose logs` 里）

路径是一串三级传导，**每一级写错的表现都是「静默丢日志」，不是启动失败**：

```
logback 默认值 D:/rizi1/IM   ←env 覆盖←   IM_LOG_HOME=/app/logs   ←bind mount←   ./data/logs
  logback-spring.xml L54                   conf/im.env（3.2）                    compose L124
```

`im.log.home` 的默认值是个 Windows 路径。Linux 上不改它不会失败——那个名字在 Linux 上就是个普通目录名，
文件落到容器内一个**不在卷上**的角落，宿主机 `data/logs` 全空，而 logback 只打一条 status 错误、不拦启动
（3.3 第 2 条讲的就是它）。所以验收要两头对得上：

```bash
ls -l data/logs/                                # 宿主机
docker compose exec im-server ls -l /app/logs   # 容器内，两边内容应完全一致
```

#### 七档落盘布局

定义在 `im-common/src/main/resources/logback-spring.xml`（它打进 `BOOT-INF/lib/im-common-1.0.0.jar` 内部，**不是运行时可改的东西**）：

| 文件 | 收什么 | 谁在写 |
|---|---|---|
| `DEBUG.log` | 控制台镜像；`append=false` ⇒ **每次启动清空**，只装本次运行 | root 全量（prod 下≈INFO+） |
| `info.log` | 只有 INFO | 业务 `log.info`、`WebLogAspect` 的 `{} {} cost={}ms`、Sa-Token 登录/注销 |
| `warn.log` | 只有 WARN | 防御分支、业务异常（全局异常处理器另有 ERROR） |
| `error.log` | 只有 ERROR，留 **60 天**（其余六档都是 30 天） | `GlobalExceptionHandler` |
| `SQL/slow-sql.log` | 只有慢 SQL | logger `com.im.slowsql` |
| `SLOW-API/slow-api.log` | 只有慢接口 | logger `com.im.slowapi` |
| `MODERATION/moderation.log` | 图片审核命中（**事后追责用，不是排障用**） | logger `com.im.moderation` |

三条规律：

- **级别是严格隔离的**：用 `LevelFilter` + `onMismatch=DENY`，不是「及以上」语义的 `ThresholdFilter`。
  所以一条日志最多落两个文件——`DEBUG.log` 那份镜像 + 它自己那一级，级别之间不交叉。
- **当天文件名固定不带日期**，跨天才滚成 `info-2026-11-05.0.log`，`tail -f` 的路径永远是那一个；
  护栏是单文件 50MB、普通档 30 天、`total-size-cap` 5GB（`im.log.*`，application.yml L972-986）。
- 后三档各是一个 `additivity="false"` 的**专用通道**（不设的话同一条慢 SQL 会既进 `SQL/`、又进 `info.log`，重复打印）。
  它们的内容含 SQL 真实参数、请求路径与图片原始文件名，**按敏感数据对待**，后台因此只给尾读、不给下载。

#### stdout 只是副本，而且天生缺三档

`root` 挂了 `ASYNC_CONSOLE`，所以 `docker compose logs -f im-server` 里有 INFO+ 与错误堆栈。但要说清三件事：

- **慢 SQL / 慢接口 / 审核命中在 `prod` 下不挂控制台**：logback 里只有 `dev` 分支给这三个 logger 加了 `ASYNC_CONSOLE`，
  `!dev` 只挂文件；`additivity=false` 又把向 root 传播的路挡了。于是**这三档在 `docker compose logs` 里一条都找不到**，
  只能读文件。这是刻意的：控制台常被 docker/systemd 再收一份，等于同一内容多落一个副本。
- prod 下 `com.im` 钉在 INFO，所以**没有** MyBatis 的完整 SQL、也没有 `WebLogAspect` 的出入参明细（那两级是 DEBUG，
  里面有消息正文和手机号）；`DEBUG.log` 因此也基本就是 INFO+ 的镜像，别以为它空了就是日志没通。
- stdout 的体积归 docker daemon 管，**默认 `json-file` 无上限**，必须按 §1.4 设 `log-opts`。
  撑爆 `/var/lib/docker` 的从来是这一份，而不是上面那七个文件（那份有 5GB 兜着）。

#### 慢 SQL 与慢接口：两个独立旋钮，改法还不一样

| | 慢 SQL | 慢接口 |
|---|---|---|
| 实现 | `im-common/.../mybatis/SlowSqlInterceptor.java`（MyBatis 拦截器） | `im-admin/.../monitor/SlowRequestInterceptor.java`（HTTP 拦截器） |
| 量的对象 | **单条语句**执行耗时 | **整个请求**端到端耗时 |
| 阈值键 | `im.log.slow-sql-millis`，application.yml L978 是**字面量 100、没有 `${}`** | `im.admin.slow-request-millis` ← **`IM_ADMIN_SLOW_REQUEST_MILLIS`**（默认 1000） |
| 容器里怎么改 | 环境变量动不了它，只能在外挂配置 `app/extra/application-prod-extra.yml` 里加 `im.log.slow-sql-millis: 200`，然后 `docker compose restart im-server` | 写进 `conf/im.env`，然后 `docker compose up -d im-server` |
| 日志形态 | `慢SQL cost=..ms threshold=..ms op=.. mapper=.. sql=..`（SQL 已拼好真实参数，超 4000 字符截断，防 `IN` 万级列表冲花文件） | WARN 进文件，同时进监控页的**最近 200 条环形缓冲**与 Prometheus `im_slow_request_total`（§9.4） |
| 关掉 | 阈值设 0 或负数 | 同上 |

两层都要留着才能定位：一个接口慢，很可能是 N 条各自都不超 SQL 阈值的查询叠出来的，只看其中一边必然误判。

★ 上表的「怎么改」顺带钉住一个方向相反的坑：**改 `conf/im.env` 必须 `up -d`（`restart` 不重读 `env_file`），
改 `app/extra/*.yml` 必须 `restart`（`up -d` 反而没用——compose 配置没变就不会重建容器，进程也就不会重启）**。
外挂 yml 的开关是 `SPRING_CONFIG_ADDITIONAL_LOCATION`，`bootstrap.sh` 生成 CORS 那份时会顺带把它打开（3.2 节末）。

#### 四种看法

```bash
# A. 宿主机读文件（最全，也是唯一能看到慢SQL / 慢接口 / 审核命中的地方）
tail -f data/logs/info.log
tail -f data/logs/SQL/slow-sql.log
tail -n 200 data/logs/SLOW-API/slow-api.log

# B. 怀疑没挂上卷 / uid 不对时，进容器自查
docker compose exec im-server sh -c 'ls -lR /app/logs | head -40'

# C. stdout（只看 INFO+：启动失败、Bean 起不来、连不上 mysql/redis）
docker compose logs --since=10m --tail=200 im-server    # 11.1 的第②层、scripts/health.sh 都是这条

# D. 后台「日志管理」页：/api/admin/log/{files,content,archives}
```

D 这一档只认 7 个白名单 key（`debug` / `info` / `warn` / `error` / `slowSql` / `slowApi` / `moderation`），传路径一定 400；
默认尾读 200 行、字节窗口最大 8MB，单次行数上限走 `im.admin.log-tail-max-lines`（默认 2000）；关键词只在尾读窗口内过滤
（语义是「在最近 N 行里找」，响应的 `filterNote` 里明说着呢，别当成全文检索）；带 `date=yyyy-MM-dd` + `index` 能翻已滚存的归档。
**没有下载端点**——那等于开一条把含手机号的文件外带出境的路。跨多文件的全文深挖仍应在服务器上用 `grep` 做。

临时抬级别排查（Spring relaxed binding，不用重打包）：

```bash
# conf/im.env 追加一行，然后 docker compose up -d im-server
LOGGING_LEVEL_COM_IM=debug
```

★ 只在排查期开着：DEBUG 下 `WebLogAspect` 会把请求体与响应体整体落日志，`DEBUG.log` 与 stdout 一起涨，
消息正文和手机号都在里面，查完就撤。

#### 三条容器特有的坑

1. **`data/logs` 是空的、`docker compose logs` 却很多** → uid 不对。镜像里运行身份固定 `USER 1000:1000`（§4.1），
   宿主目录必须 `chown -R 1000:1000 data/logs upload-tmp`（`bootstrap.sh` 第②步做的就是这个）。第十二节速查表里
   「`/app/logs` 是空的」那一行是这条坑的入口。
2. **日志时间戳差 8 小时** → 容器默认 UTC。`TZ=Asia/Shanghai`（`conf/im.env`）要与 mysqld 的
   `--default-time-zone=+08:00` **两边一起设**，只设一个就长出两种时钟（3.3 第 1 条）。
3. **改了 `logback-spring.xml` 没生效** → 它在 jar 内部，而 `scan` 是刻意关的（嵌套 jar 里的条目做不到热加载，
   开着只会每次启动多一条看着像配置出问题的 WARN）：**必须重新出 jar**，走 §10.2 的升级流程。

---

## 十二、容器化专属故障速查

| 现象 | 第一嫌疑人 | 怎么确认 / 怎么修 |
|---|---|---|
| 后端无限重启，`Unknown database 'im_db'` 或 `Access denied for user 'im'` | 卷是空的 / 两份密码不一致 | `docker compose logs mysql`；确认 `.env` 与 `conf/im.env` 里同一个密码；空库按第六节导 |
| `Communications link failure` / `Connection refused: localhost/127.0.0.1:3306` | **`MYSQL_HOST` 还是 `127.0.0.1`** | `docker compose exec im-server env \| grep MYSQL_HOST`；容器里必须是服务名 |
| 启动即失败：`Parameter 2 of constructor in CaptchaServiceImpl required a bean of type 'com.im.user.sms.SmsSender'` | `IM_SMS_PROVIDER` 填了 `aliyun` / `tencent` 这类**还没有实现类**的服务商名（目前只有 `mock` 一个实现），`MockSmsSender` 的 `@ConditionalOnProperty` 不匹配，一个通道 bean 都装配不上 | `conf/im.env` 里改回 `mock`，然后 `docker compose up -d im-server`（★ `restart` 不重读 `env_file`）；现行代码已把它降为「发码时报《短信服务未配置》」，不再拖垮整个启动 |
| 启动日志 `MinIO 存储桶初始化失败: im-files` | 宿主二进制 minio 没先起来 / endpoint 不对 / 凭据不一致 | 这是**刻意的 fail-fast**（minio 在栈外，compose 无法对它做 `depends_on: service_healthy`，所以起栈时没人替你检查它）：`systemctl is-active minio` + `curl 127.0.0.1:9000/minio/health/live`，再从容器里问一次 `host.docker.internal:9000`；`MINIO_ENDPOINT` 应为 `http://host.docker.internal:9000`（**不是 `http://minio:9000`**，那是已删掉的栈内服务名） |
| minio 服务起不来，日志里 `Password must be at least 8 characters` | `MINIO_ROOT_PASSWORD` 太短（MinIO 自己拒启动，与容器无关） | `/etc/default/minio` 与 `.env` **两处同改**（≥ 8 位），再跑一次 `bootstrap.sh` 把它同步进 `conf/im.env`（§三） |
| `nginx: [emerg] unknown directive "dav_methods"` | 镜像没 dav 模块（或自己压的精简版） | 8.1 的 `nginx -V`；换回官方镜像 |
| 直播能开播、观众黑屏，`index.m3u8` 回 403 | 签名 secret 不一致 / 不是 base64url / 前缀不同名 | 三处一起核：`IM_LIVE_SIGN_SECRET` ↔ `secure_link_md5` ↔ `location` 与 `play-base-url` 的 `/live` |
| 多档直播一律 403，单档正常 | 变体清单也被签名了 | hls.js 拉变体不携 `?expire=&sign=`；`location ~ \.m3u8$` 那段**不能**有 `secure_link` |
| 主播端 PUT 全 404/403，而 nginx 日志里看不到对应请求 | `IM_LIVE_PUSH_BASE_URL` 写了容器内部地址 | 改成主播端公网可达的地址（第五节注释已标 ★★）；`http://nginx:8088/...` 这种服务名只能在容器内解析 |
| nginx 启动即退 `host not found in upstream "im-server"` | 后端未就绪 / 服务名拼错 | 保留 `depends_on.condition: service_healthy`；`docker compose ps` 看 im-server |
| 容器全 healthy，但**站点根 403、任何子路径 500** | `./web/<站>/` 是空的——前端 dist 没拷进去（版本库里只有 `.gitkeep`，`bootstrap.sh` 也不填它） | error log 两行就是指纹：`directory index of "/usr/share/nginx/html/im-ui/" is forbidden`（403，`try_files` 的 `$uri/` 命中空目录且 `autoindex off`）与 `rewrite or internal redirection cycle while internally redirecting to "/index.html"`（500，兜底页也不存在）。按 5.1 ⓪ 把三站 dist 的**内容**拷进 `web/<站>/`，bind mount 即时生效，不用 reload 也不用重启容器 |
| 浏览器全 403 `Invalid CORS request`，`curl` 正常 | CORS 是 yml 字面列表 | 8.5 外挂配置；注意它是**整体替换**，客户端 Origin 要一并抄 |
| **页面能打开，但登录/接口全 403**，且地址栏是 `https://192.168.x.x/` 这种 IP | 静态文件不受 CORS 管，XHR 才受；而白名单里只有**域名形态**，没有 IP 能匹上 | 首选改用域名访问（`https://im.example.com/`，Origin 自动对得上）；确实要 IP 自测就在 8.5 那份 extra 里补 `- https://<部署机IP>` 两行（http/https 各一）后 `docker compose restart im-server` |
| 用 `https://<部署机IP>/` 打开就报证书无效，换成域名就正常 | `bootstrap.sh` 的自签只有 `subjectAltName=DNS:$d`，**不含 IP SAN**；换真证书也一样（证书签的是域名，不是 IP） | 客户端 hosts 把三个域名指过去后用域名访问；正式环境走 8.4 的 DNS-01（★ 手机 / 平板改不了 hosts，所以正式用必须真域名） |
| 手机浏览器正常、桌面端（Tauri/Capacitor/Electron）连不上 | 外挂 CORS 只写了三个域名，把 tauri/capacitor/localhost 顶掉了 | 同上，补齐 |
| `ufw allow/deny` 加了没反应，端口还是公网可达 | **Docker 绕过 UFW** | 别发布该端口；要按 IP 过滤用 `DOCKER-USER` 链或云安全组（8.3） |
| nginx 日志里客户端 IP 全是 `172.x.x.1` | docker-proxy 换了源地址 | `daemon.json` 设 `"userland-proxy": false` 后实测；推流口 `allow` 名单相应改 |
| 大文件上传 413 | nginx 先拦了 | `client_max_body_size 110m`（单请求通道）；分片通道单片 6MB，走 `/api/file/upload/*` 不受 100MB 限 |
| 推流口 PUT 返回 500，nginx 日志 `client body temp ... failed` | `client_body_temp_path` 不可写 | 指到 `/tmp/nginx_body` 或把该 server 的挂载改读写 |
| 改了 `sql-init/` 里的 SQL，重启 mysql 没生效 | `initdb.d` 只在空卷跑一次 | 7.1 手工导；或试用环境 `down -v` 重建（★ 数据全没） |
| `docker compose ps` 四个服务都 healthy，但登录就报 `Table 'im_db.im_user' doesn't exist` | 只拷了 `Docker/` 一层上服务器，`bootstrap.sh` 的 `if [[ -d ../sql ]]` 不成立，`sql-init/` 里根本没有 SQL，于是建出个空库 | 先 `ls sql-init/*.sql` 确认，再按 6.4 补 SQL（判据：数 `information_schema.tables` 应为 41） |
| 镜像构建报 `groupadd: GID '1000' already in use`（退出码 4）或 `useradd: UID 1000 is not unique` | 底座（Ubuntu 24.04）自带 ubuntu 用户/组已占了 uid/gid 1000，再新建一个叫 im 的用户去抢 1000 必冲突 | 4.1 的正确写法：数字 1000 已存在就**直接复用**（getent 按数字查）、不新建用户；USER/chown/COPY --chown 全用数字，不依赖用户叫名 |
| `/app/logs` 是空的，`docker compose logs` 却很多 | 目录 uid 不对，logback **不拦启动只静默丢** | `chown -R 1000:1000 data/logs upload-tmp`；或 `docker compose logs im-server \| grep -i logback`（七档落盘、慢 SQL 为何不在 stdout、两个阈值各自怎么改，见 **§11.4**） |
| 时间差 8 小时（含定时任务不到点跑：红包退款、消息过期清理） | MySQL 容器默认 UTC 而 JDBC 钉了 +08 | 第五节 `--default-time-zone=+08:00` + 应用容器 `TZ=Asia/Shanghai`；12 个 `@Scheduled` 的 cron 按 JVM 时区解释 |
| 中文与 emoji 存进去变 `?` | 管道导入 / 服务端字符集退回 latin1 | 7.1 的 `cp` + `-e "source"`；6.2 的 `SELECT @@character_set_server` |
| `--scale im-server=2` 之后有人收不到消息、定时任务跑两遍 | **本系统不支持多实例** | 立刻缩回 1；扩容先看服务器部署指南第七节 |
| 重启容器后本地存储的文件全丢 | `IM_FILE_STORAGE=local` 但 `IM_FILE_DIR` 没挂在卷上 | 默认 `${user.home}/im-files` 在容器文件系统里；挂 `./data/files:/app/files` 并把变量指过去 |
| Prometheus 抓不到 `:8081` | `MANAGEMENT_ADDRESS=127.0.0.1` 的容器语义 | 9.4；改 `0.0.0.0` 且**不要发布**该端口 |
| 通话对方听不见 / 摄像头权限弹窗消失 | 页面不是 https 安全上下文 | 8.4；顺便确认证书续期 deploy-hook 真跑过 |
| 磁盘 `/var/lib/docker` 涨到爆 | 容器 stdout 的 `json-file` 无上限 | 1.4 的 `log-opts`；应用自己的日志有 `total-size-cap: 5GB` 兜着 |
| `docker compose pull` 拉不动 / 超时 | 国内访问 Docker Hub | 1.5 加速或 `docker save/load` 离线导入 |
| `docker compose up` 报 `multiple networks` 或卷名对不上 | 改了 `name:` 字段 | 卷名前缀就是项目名；改 `name` 等于换了一批空卷 |
| 同一条 `mysql -uroot -p"${MYSQL_ROOT_PASSWORD}"` 回 Access denied / 反复让你输密码，而密码是对的 | 变量被**宿主 shell** 展开成空串（`.env` 不进宿主环境，compose 也不插值 `exec` 的命令行） | 改成 `sh -c '… -p"$MYSQL_ROOT_PASSWORD" …'` 让**容器内**展开（§7.1 末）；或先 `set -a; source .env; set +a` |
| 三个域名打开的**都是聊天端** | Host / SNI 匹不上 `server_name`，而 80/443 上**没有 `default_server`** → 静默落到该端口第一个 server | 客户端 hosts 或 DNS 里的三个名都要指到同一台机的同一个 443（§8.2 的分流写法 + 〇·B ⑤）；`curl -s http://127.0.0.1/healthz` 能回 `ok` 就证明 nginx 本身没问题 |
| 改了配置「没生效」，但命令看着没敲错 | 两条生效命令弄反了 | 改 `conf/im.env`（env_file 建容器时注入）必须 `docker compose up -d` **重建**，`restart` 不重读；改 `app/extra/*.yml`（挂载文件）只需 `docker compose restart im-server`，而这里跑 `up -d` 什么效果都没有（compose 认为配置未变）（§8.5） |

---

## 十三、什么时候不该用 Docker

三条，任一命中就回到《[服务器部署指南](服务器部署指南.md)》的裸机形态：

1. **机器只有 2GB 内存**。四个容器（MySQL 512M 缓冲池 + Redis + JVM 约 1.5G + nginx）再叠上宿主机那个
   MinIO 二进制（约 100~200 MB），挤在 2G 上会互相 OOM，
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
| 43 张表的逐表逐列解析（当前覆盖其中 40 张）、种子数据、两份全量脚本与真实库的对账结论 | `md/数据库表解析.md`（对账在 §14.10） |
| 直播架构与 Nginx 推流/播放口的完整配置 | `md/直播.md`（部署在 §10） |
| MinIO 的下载、启动、桶与凭据、故障排查 | `md/MinIO部署指南.md` |
| WebRTC 通话、STUN/TURN、群通话 mesh、录制留存 | `md/语音视频通话接入指南.md` |
| 监控与告警（Actuator / Prometheus / Grafana） | `md/监控告警部署指南.md` |
| 被控端 Agent 的编译与配置（**不在服务器上跑**，见服务器部署指南 §2.3） | `md/远程控制Agent使用说明.md` |
| **本文对应的成品文件：整套部署包（compose / env 模板 / Dockerfile / nginx / 运维脚本 / bootstrap）** | [`Docker/`](../../Docker/)，上手读 [`Docker/README.md`](../../Docker/README.md) |
| **本文 §10.5 对应的 CI：打 jar + 打三个前端 + 构建并推镜像 + 可选 SSH 部署** | [`.github/workflows/build-and-package.yml`](../../.github/workflows/build-and-package.yml) |
| 行尾统一（`*.sh` / compose / Dockerfile 强制 LF，否则 Windows 上检出就跑不了） | [`.gitattributes`](../../.gitattributes)、仓库根 [`.dockerignore`](../../.dockerignore) |
| 每一次变更的登记 | `md/CHANGELOG.md` |
