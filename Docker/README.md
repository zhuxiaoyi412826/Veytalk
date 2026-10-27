# Docker/ —— Ubuntu 24.04 容器化部署包

这份目录是 [`md/部署/Docker部署指南.md`](../md/部署/Docker部署指南.md) 的**可执行版本**：文档解释「为什么这样写」，
这里负责「拷到服务器上就能跑」。**两边不一致时以本目录为准**——被 `docker compose`、`nginx`、`java` 真正读的是这里的文件，
文档里那些代码块只是讲解用的快照（指南 §二 顶部有一张逐章对照表）。

> ★ **只想速查「docker 部署 / nginx 前端 / 打包 / 域名」这四件事**，看 [`开发必备.txt`](开发必备.txt)，那一页是本目录的结论版。

> ★ **这台写文件的机器上不执行任何构建与部署**。
> jar、前端 dist、镜像都由 GitHub Actions 在 runner 上产出（[`.github/workflows/build-and-package.yml`](../.github/workflows/build-and-package.yml)），
> 部署由 Actions 通过 SSH 推到那台开放机，或者你手工把本目录拷过去执行下面的步骤。

---

## 一、目录里有什么

| 路径 | 进版本库 | 作用 |
|---|---|---|
| `docker-compose.yml` | ✅ | 五个必选服务（mysql / redis / minio / im-server / nginx）+ 两个注释掉的选装（live-retention / coturn） |
| `.env.example` | ✅ | compose 层参数模板：镜像 tag、密码、域名。`cp .env.example .env` 后填 |
| `conf/im.env.example` | ✅ | 应用层环境变量模板：37 个键，逐个与 `application.yml` 的 `${}` 占位符对过账 |
| `app/Dockerfile` | ✅ | **路线 A**（推荐）：只装运行时，jar 由 CI 放进 `app/` |
| `app/Dockerfile.multistage` | ✅ | **路线 B**：在构建机上从源码编译（上下文必须是仓库根） |
| `app/extra/application-prod-extra.example.yml` | ✅ | CORS 白名单外挂配置模板（yml 里是字面列表，环境变量改不动它） |
| `nginx/conf.d/im.conf.example` | ✅ | 三站 + `/api` + `/ws` + 直播播放/推流面 |
| `nginx/snippets/{proxy,ssl}.conf` | ✅ | 反代头与 TLS 参数（放 snippets 不放 conf.d，避免被当 http 上下文 include） |
| `scripts/bootstrap.sh` | ✅ | 首次编排：校验 `.env` → 建目录摆权限 → 生成正式配置 → 补自签名证书 → `compose config -q` |
| `scripts/import-sql.sh` | ✅ | 导 migration SQL（`cp` 进容器再 `-e "source"`，★ 不用管道） |
| `scripts/clear-perm-cache.sh` | ✅ | 清 `im:auth:*` 权限缓存（改权限点后必跑） |
| `scripts/backup.sh` | ✅ | 数据库 / MinIO 卷 / 编排配置 / Redis 快照 |
| `scripts/health.sh` | ✅ | 五层健康检查 + 容器内变量真值 |
| `scripts/update.sh` | ✅ | 升级与回滚后端镜像（换 tag，绝不覆盖同名 tag） |
| `scripts/im-docker.service` | ✅ | systemd 单元（`up -d --wait`，ExecStop 无 `-v`） |
| `.env`、`conf/im.env`、`app/im-server.jar`、`app/extra/application-prod-extra.yml`、`nginx/conf.d/im.conf`、`web/*`、`data/*`、`certs/*`、`sql-init/*`、`backup/` | ❌ | 凭据、产物、运行时数据。**由 bootstrap.sh / CI 生成**，`.gitignore` 已逐条忽略 |

## 二、部署机上的一次性准备（全新机器）

```bash
# 1. 装 Docker（Ubuntu 24.04，两种路线见指南 §1.1/§1.2）+ 配 daemon.json（日志限额、容器真实 IP）
sudo mkdir -p /opt/im-docker
# 2. 把本目录整体拷过去（scp -r / git clone 都行；★ compose 里的相对路径全以这个目录为基准，别换目录再抄配置）
scp -r Docker/ deploy@server:/opt/im-docker/
ssh deploy@server
cd /opt/im-docker

# 3. 填两份值：只有 .env 需要人手填（密码与域名），conf/im.env 由 bootstrap.sh 从 .env 同步生成
cp .env.example .env && chmod 600 .env && vi .env

# 4. 编排：建目录、摆 uid 1000 权限、由 example 生成 im.conf / application-prod-extra.yml / conf/im.env
bash scripts/bootstrap.sh

# 5. 拉镜像并起来（首次要等 mysql initdb + 后端冷启，约 2 分钟）
docker compose pull
docker compose up -d
bash scripts/health.sh
```

全新建库：把 `sql/im_schema.sql`、`sql/im_data.sql` 改名成 `sql-init/01_im_schema.sql`、`sql-init/02_im_data.sql`
再 `up`（`bootstrap.sh` 在仓库里跑时会自动这么放并提醒你）。★ 建完库就该注释掉 compose 里那行 `./sql-init` 挂载。

## 三、和 Actions 的衔接

| Actions 产出 | 落到哪里 | 服务器怎么拿到 |
|---|---|---|
| `im-server.jar` | 塞进 `Docker/app/` 作为 build context → 镜像 `ghcr.io/<owner小写>/im-server:<版本>` | `docker compose pull im-server` |
| `im-ui` / `im-admin-ui` / `im-website` 的 dist | artifact `web-<app>-<sha>` | deploy job 把 dist 打 tar 走 ssh 覆盖到 `./web/<app>`（bind mount，不用重启容器） |

需要在仓库 Settings → Secrets 里配的只有部署那几项：`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`，
可选 `DEPLOY_PORT`、`DEPLOY_DIR`（默认 `/opt/im-docker`）。**推 GHCR 不需要 Secret**，用内置 `GITHUB_TOKEN`。

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
2. **只发布 80/443**（按需 8088、3478、8947/udp）。3306/6379/9000/9001/8080/8081 一律不写 `ports:`。
   Docker 发布的端口**绕过 UFW**，要按源 IP 过滤得走 `DOCKER-USER` 链或云安全组（指南 §8.3）。
3. **应用连中间件必须用服务名**（`mysql` / `redis` / `minio`）。容器里的 `127.0.0.1` 指的是它自己。
4. **两边时钟与时区要一起设**：MySQL 的 `--default-time-zone=+08:00` 与 `TZ=Asia/Shanghai` 只设一个，
   就会出现「应用写的时间」和 `DEFAULT CURRENT_TIMESTAMP` 兜底写的时间差 8 小时。

## 五、常见坑（都写在对应文件的注释里，这里只做索引）

| 症状 | 位置 |
|---|---|
| 容器起来了→崩→无限重启，日志同一句 | `minio` 没 healthy，缺 `depends_on`（compose 里 im-server 那段） |
| 日志目录空的，宿主机看不到日志 | `data/logs` 没挂或 uid 不是 1000（`bootstrap.sh` 第 ② 步） |
| 观众端黑屏 403，推流和列表都正常 | `IM_LIVE_SIGN_SECRET` 与 nginx `secure_link_md5` 不一致（`.env` 只填一次就是为防这个） |
| 浏览器全量 403 `Invalid CORS request`，curl 正常 | 没启用外挂 CORS 配置（`app/extra`，指南 §8.5） |
| 后台菜单点了 SQL 还是旧的 | 权限缓存没清，`scripts/clear-perm-cache.sh`（指南 §7.2） |
| 导入的 SQL 里中文变 `?` | 用了管道/heredoc，`scripts/import-sql.sh` 的头部注释 |
| `docker compose down -v` | ★ 一字之差就是清库。systemd 的 ExecStop 里特意没有 `-v` |
