# Docker/ —— Ubuntu 24.04 容器化部署包

这份目录是 [`md/部署/Docker部署指南.md`](../md/部署/Docker部署指南.md) 的**可执行版本**：文档解释「为什么这样写」，
这里负责「拷到服务器上就能跑」。**两边不一致时以本目录为准**——被 `docker compose`、`nginx`、`java` 真正读的是这里的文件，
文档里那些代码块只是讲解用的快照（指南 §二 顶部有一张逐章对照表）。

> ★ **只想速查「docker 部署 / nginx 前端 / 打包 / 域名」这四件事**，看 [`开发必备.txt`](开发必备.txt)，那一页是本目录的结论版。
>
> ★ **准备动某个文件之前**（想知道它到底干什么、跟谁衔接、改完要做什么动作），看 [`文件说明.md`](文件说明.md)：
> 逐个文件的说明书，含「模板 → 生成物 → 谁读它」的生成关系图、九个脚本的职责表、以及「改了怎么生效」那张表。

> ★ **要在 Ubuntu 服务器上跑 HTTPS 部署**（GHCR 镜像 + `docker compose` 一键 + 80/443 + 三站域名分流 + 证书），
> 照着 [`../md/部署/Ubuntu镜像HTTPS部署教程.md`](../md/部署/Ubuntu镜像HTTPS部署教程.md) 从上往下做即可，那份是本目录的可执行操作流程版。

> ★ **这台写文件的机器上不执行任何构建与部署**。
> jar、前端 dist、镜像都由 GitHub Actions 在 runner 上产出（[`.github/workflows/build-and-package.yml`](../.github/workflows/build-and-package.yml)），
> 部署由 Actions 通过 SSH 推到那台开放机，或者你手工把本目录拷过去执行下面的步骤。

---

## 一、目录里有什么

| 路径 | 进版本库 | 作用 |
|---|---|---|
| `docker-compose.yml` | ✅ | 四个必选服务（mysql / redis / im-server / nginx）+ 两个注释掉的选装（live-retention / coturn）。★ minio 不在此栈：改用宿主机官方二进制+systemd（见 `md/MinIO部署指南.md` 三/四节、`md/部署/Ubuntu镜像HTTPS部署教程.md` §8.1），im-server 经 host.docker.internal:9000 连它。mysql/redis/nginx 默认写 Docker Hub 短名，国内机拉不动时把 `.env` 的三个 `*_IMAGE` 改指 CI 同步好的 `ghcr.io/<owner小写>/im-deps/*` |
| `文件说明.md` | ✅ | 逐个文件的作用、彼此衔接与生效方式（本表的详细版，动文件前看它） |
| `.env.example` | ✅ | compose 层参数模板：镜像 tag、密码、域名。`cp .env.example .env` 后填 |
| `conf/im.env.example` | ✅ | 应用层环境变量模板：38 个生效键 + 3 个注释掉的可选键（一半是 `IM_*` 占位符，一半是 `MYSQL_HOST` / `MINIO_ENDPOINT` 这类 Spring 松散绑定键）。★ **别当成「和占位符一一对过账」**：`application*.yml` 里共 37 个 `${IM_*}`，其中 **17 个在本模板里连注释行都没有**（钱包、审核、通话、连麦、PK、回放、智能报告这些开关），它们靠 yml 的默认值生效（全是 `true`），要关只能手工往 `conf/im.env` 加一行 |
| `app/Dockerfile` | ✅ | **路线 A**（推荐）：只装运行时，jar 由 CI 放进 `app/` |
| `app/Dockerfile.multistage` | ✅ | **路线 B**：在构建机上从源码编译（上下文必须是仓库根） |
| `app/extra/application-prod-extra.example.yml` | ✅ | CORS 白名单外挂配置模板（yml 里是字面列表，环境变量改不动它） |
| `nginx/conf.d/im.conf.example` | ✅ | 三站 + `/api` + `/ws` + 直播播放/推流面 |
| `nginx/snippets/{proxy,ssl}.conf` | ✅ | 反代头与 TLS 参数（放 snippets 不放 conf.d，避免被当 http 上下文 include） |
| `scripts/bootstrap.sh` | ✅ | 首次编排：校验 `.env` → 建目录摆权限 → 生成正式配置 → 补自签名证书 → `compose config -q` |
| `scripts/gen-selfca.sh` | ✅ | ★ 在**签发机**跑（不是部署机）：建本地根 CA（10 年）+ 按 `.env` 三个域名各签 30 天叶子；`--install-cron` 装每日自动续期（指南 §8.4 方案 B） |
| `scripts/push-cert.sh` | ✅ | ★ 同在签发机：scp 叶子到部署机 + 远端 `nginx -t` 后 reload，并留下 `certs/.managed-by-push` 让 bootstrap ⑤ 不再自签盖掉它 |
| `scripts/import-sql.sh` | ✅ | 导 migration SQL（`cp` 进容器再 `-e "source"`，★ 不用管道） |
| `scripts/clear-perm-cache.sh` | ✅ | 清 `im:auth:*` 权限缓存（改权限点后必跑） |
| `scripts/backup.sh` | ✅ | 数据库 / MinIO 卷 / 编排配置 / Redis 快照；`--install-cron` 装每天 03:30 的库备份（清理与异地同步都在脚本里，读 `.env` 的 `BACKUP_KEEP_DAYS`/`BACKUP_SYNC_DIR`） |
| `scripts/guard-sql-init.sh` | ✅ | ★ 建完库后上护栏：把 `sql-init/` 里的建库脚本挪进 `sql-init.disabled/` 并落 `.disabled` 标记，让「卷被重建时静默清库」这个能力失效（`--status` 看状态 / `--unlock` 解锁 / `--comment-compose` 顺手注释 compose 那行挂载） |
| `scripts/health.sh` | ✅ | 六层健康检查（①编排含宿主 minio → ②近 10 分钟报错 → ③容器内问 Actuator → ④nginx 跨容器探后端 → ⑤外部走完整链路 → ⑥容器里 java 实际看到的变量真值）+ 落盘日志核对 |
| `scripts/update.sh` | ✅ | 升级与回滚后端镜像（换 tag，绝不覆盖同名 tag） |
| `scripts/im-docker.service` | ✅ | systemd 单元（`up -d --wait`，ExecStop 无 `-v`） |
| `.env`、`conf/im.env`、`app/im-server.jar`、`app/extra/application-prod-extra.yml`、`nginx/conf.d/im.conf`、`web/*`、`data/*`、`certs/*`、`sql-init/*`、`scripts/live-retention.js`、`backup/` | ❌ | 凭据、产物、运行时数据。**由 bootstrap.sh / CI 生成**（★ 例外：`web/*` 三站 dist——`bootstrap.sh` 只建空目录不填它，CI 也只在手动勾选 `deploy` 时才推，手工部署必须自己 `scp`，见下面第二步半），`.gitignore` 已逐条忽略 |

## 二、部署机上的一次性准备（全新机器）

```bash
# 1. 装 Docker（Ubuntu 24.04，两种路线见指南 §1.1/§1.2）+ 配 daemon.json（日志限额、容器真实 IP）
sudo mkdir -p /opt/im-docker
# 2. 把本目录整体拷过去（scp -r / git clone 都行；★ compose 里的相对路径全以这个目录为基准，别换目录再搬配置）
scp -r Docker/ deploy@server:/opt/im-docker/

# 2.5 ★ 三站 dist 拷进 web/<站>/（本目录进库的只有 web/.gitkeep，bootstrap.sh 也不填它）
#     构建机上先 npm ci && npm run build（只认默认模式，别用 build:electron），再在仓库根执行：
scp -r im-ui/dist/*        deploy@server:/opt/im-docker/web/im-ui/
scp -r im-admin-ui/dist/*  deploy@server:/opt/im-docker/web/im-admin-ui/
scp -r im-website/dist/*   deploy@server:/opt/im-docker/web/im-website/
#     Windows 构建机把分隔符换成反斜杠即可：scp -r im-ui\dist\* deploy@server:/opt/im-docker/web/im-ui/
#     漏了这一步不会报错，但站点根回 403（directory index ... is forbidden）、子路径回 500（重定向循环）

ssh deploy@server
cd /opt/im-docker

# 3. 填两份值：只有 .env 需要人手填（密码与域名），conf/im.env 由 bootstrap.sh 从 .env 同步生成
cp .env.example .env && chmod 600 .env && vi .env

# 4. 编排：建目录、摆 uid 1000 权限、由 example 生成 im.conf / application-prod-extra.yml / conf/im.env
bash scripts/bootstrap.sh

# 5. 先起 minio（主栈前，它不在 docker-compose.yml 里）——官方二进制+systemd（镜像已下架，见 md/MinIO部署指南.md 三/四节）：
sudo systemctl enable --now minio

# 6. 拉主栈镜像并起来（首次要等 mysql initdb + 后端冷启，约 2 分钟）
docker compose pull
docker compose up -d
bash scripts/health.sh
```

> ★ **证书这一步补的是「让 nginx 起得来」的单张自签**，浏览器一定告警，而且只活 30 天。内网测试想绿锁，回签发机（开发机就行）跑
> `bash scripts/gen-selfca.sh --ip <部署机IP>` → `bash scripts/push-cert.sh <user>@<部署机IP> /opt/im-docker`，
> 再把 `certs/ca/rootCA.pem` 装进每台测试机一次；之后 `--install-cron` 每日自动换叶子，客户端无感（见指南 §8.4）。

> ★ **两个配置文件的生效命令方向相反，是最常搞反的一处**：改 `conf/im.env` 要 `docker compose up -d im-server`
> （`env_file` 只在**重建容器**时读，`restart` 不重读）；改 `app/extra/application-prod-extra.yml` 只要
> `docker compose restart im-server`（它是 java 启动时读的文件，compose 认定配置没变就不会重建，`up -d` 反而没用）。
> 完整的「改什么 → 跑什么」表在 [`文件说明.md`](文件说明.md) 第八节。

全新建库：把 `sql/im_schema.sql`、`sql/im_data.sql` 改名成 `sql-init/01_im_schema.sql`、`sql-init/02_im_data.sql`
再 `up`（`bootstrap.sh` 在仓库里跑时会自动这么放并提醒你）。★ 建完库就上护栏：`bash scripts/guard-sql-init.sh`（把建库脚本挪走 + 落标记，重跑 bootstrap 也不会被撤销），要更彻底再 `--comment-compose` 注释掉那行 `./sql-init` 挂载。

## 三、和 Actions 的衔接

| Actions 产出 | 落到哪里 | 服务器怎么拿到 |
|---|---|---|
| `im-server.jar` | 塞进 `Docker/app/` 作为 build context → 镜像 `ghcr.io/<owner小写>/im-server:<版本>` | `docker compose pull im-server` |
| `im-ui` / `im-admin-ui` / `im-website` 的 dist | artifact `web-<app>-<sha>` | deploy job 把 dist 打 tar 走 ssh 覆盖到 `./web/<app>`（bind mount，不用重启容器） |
| `mirror-images` job 同步的 mysql / redis / nginx | `ghcr.io/<owner小写>/im-deps/*`（skopeo 原样只复制 linux/amd64，digest 相同就秒跳过） | 把 `.env` 的三个 `*_IMAGE` 改指过去，目标机全程不碰 Docker Hub |

需要在仓库 Settings → Secrets 里配的只有部署那几项：`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`，
可选 `DEPLOY_PORT`、`DEPLOY_DIR`（默认 `/opt/im-docker`）。**推 GHCR 不需要 Secret**，用内置 `GITHUB_TOKEN`。
另有一对**可选**的 `QUAY_USERNAME` / `QUAY_PASSWORD`：只给 `mirror-images` job **拉** quay.io 用（不配就匿名拉，
Actions 那批共享 IP 常被 Quay 回 401），与推镜像无关，不配也不影响本目录跑完。

私有包在服务器上第一次拉取前要登录一次（PAT 只需 `read:packages`）：

```bash
echo '<PAT>' | docker login ghcr.io -u <你的GitHub用户名> --password-stdin
```

之后升级就一条命令（它会先备份数据库）：

```bash
bash scripts/update.sh 1.0.1          # 或 bash scripts/update.sh --rollback 1.0.0
```

## 四、四条不能破的红线（改这套文件前先看这段）

1. **单实例**。不要 `--scale im-server=N`、不要加 `deploy.replicas`：WebSocket 扇出只认本进程的连接表，
   且那 12 个 `@Scheduled` 任务没有一个 ShedLock —— 起两个副本等于消息半丢 + 定时任务跑两遍。
2. **主栈只发布 80/443**（按需 8088、3478、8947/udp）。主栈里 3306/6379/8080/8081 一律不写 `ports:`；
   minio 的 9000/9001 由宿主机二进制监听（供 host.docker.internal）；宿主机自己的端口受 UFW 管，需放行 docker 子网访问 9000，并靠安全组/`DOCKER-USER` 限源。
   Docker 发布的端口**绕过 UFW**，要按源 IP 过滤得走 `DOCKER-USER` 链或云安全组（指南 §8.3）。
3. **应用连栈内中间件必须用服务名**（`mysql` / `redis`）；minio 不在本栈（而是宿主二进制），走 `host.docker.internal:9000`。容器里的 `127.0.0.1` 指的是它自己。
4. **两边时钟与时区要一起设**：MySQL 的 `--default-time-zone=+08:00` 与 `TZ=Asia/Shanghai` 只设一个，
   就会出现「应用写的时间」和 `DEFAULT CURRENT_TIMESTAMP` 兜底写的时间差 8 小时。

## 五、常见坑（都写在对应文件的注释里，这里只做索引）

| 症状 | 位置 |
|---|---|
| 容器起来了→崩→无限重启，日志同一句 | 宿主机二进制 minio 没先起（主栈已无对它的 `depends_on`）：先 `systemctl is-active minio`、`curl 127.0.0.1:9000/minio/health/live` 确认，再起主栈 |
| 日志目录空的，宿主机看不到日志 | `data/logs` 没挂或 uid 不是 1000（`bootstrap.sh` 第 ② 步） |
| 观众端黑屏 403，推流和列表都正常 | `IM_LIVE_SIGN_SECRET` 与 nginx `secure_link_md5` 不一致（`.env` 只填一次就是为防这个） |
| 浏览器全量 403 `Invalid CORS request`，curl 正常 | 没启用外挂 CORS 配置（`app/extra`，指南 §8.5） |
| 后台菜单点了 SQL 还是旧的 | 权限缓存没清，`scripts/clear-perm-cache.sh`（指南 §7.2） |
| 导入的 SQL 里中文变 `?` | 用了管道/heredoc，`scripts/import-sql.sh` 的头部注释 |
| 四个容器全 healthy，但浏览器 `ERR_CERT_DATE_INVALID`、wss 握不上、通话按钮没反应 | ① `bootstrap.sh` ⑤ 签的那张自签到期了（★ 重跑它**不会**重签，判据只看文件在不在）；② 走两级 CA 后只有叶子会过期，在签发机跑 `gen-selfca.sh --force && push-cert.sh`。原因都是 nginx 不校验自己证书的有效期（指南 §8.4） |
| `docker compose down -v` | ★ 一字之差就是清库。systemd 的 ExecStop 里特意没有 `-v`；建完库再跑 `scripts/guard-sql-init.sh` 上护栏，卷被重建也无脚本可跑 |
