# Ubuntu 上镜像使用教程（HTTPS 部署）

> 本篇是**照着做就能跑**的实操教程：在 Ubuntu 服务器上，用 GitHub Actions 推到 GHCR 的镜像，
> 通过 `docker compose` 一键拉起 IM 全栈，并对外提供 **HTTPS**（80/443）访问。
>
> 讲「为什么这样设计」的长篇原理见同目录 [`Docker部署指南.md`](Docker部署指南.md)；
> 可直接执行的部署包在仓库 [`Docker/`](../../Docker/) 目录。**两边冲突时以 `Docker/` 里的真实文件为准。**

---

## 〇、先建立一个正确认知：端口与"三站"是怎么对外提供的

- 整个栈 5 个容器（mysql / redis / minio / im-server / nginx），但**对外只发布 nginx 的 80 和 443**。
  其余端口（3306/6379/9000/8080/8081…）都只在容器内部网络可达，**不映射到宿主机**——这是刻意的安全约束
  （Docker 发布的端口会绕过 UFW）。
- 前端 / 管理后台 / 官网是**同一个 nginx、同一对 80/443**，靠**域名（`server_name`）分流**：
  `IM_DOMAIN` → im-ui，`ADMIN_DOMAIN` → 管理后台，`WEBSITE_DOMAIN` → 官网。
  所以你需要的是**多个域名/子域名解析到同一台机器的同一个 443**，而不是多个端口。
- **HTTPS 不是可选项**：语音/视频通话、摄像头、录屏用的 `getUserMedia` 只在安全上下文可用，
  纯 http 打开页面点通话会直接拿不到媒体权限。别为了省事只挂 80。

> `docker compose up -d` 命令本身**不带端口参数**——端口映射写在 `docker-compose.yml` 的 `ports:` 字段里，
> compose 启动时自动读。`up -d` 的 `-d` 是后台运行（detached），不是端口。

---

## 一、前置条件

| 项 | 要求 |
|---|---|
| 系统 | Ubuntu 22.04 / 24.04 x86_64 |
| 权限 | 一个有 `sudo` 的账号 |
| 端口 | 安全组 / 防火墙放行 **80、443**（TCP）；开直播再放 8088，用 TURN 再放 3478 与 49160-49200/udp |
| 域名 | 准备 `im.example.com`、`admin.example.com`、`www.example.com`（按需 `live.example.com`），**A 记录解析到本机公网 IP** |
| 镜像 | GHCR 上已有 `ghcr.io/<owner小写>/im-server:<版本>`（由 CI 推）；依赖镜像走 GHCR 的 `im-deps/*`（见 §五，可选） |

> ★ 域名必须解析生效后再签证书：Let's Encrypt 要验证域名归属，解析没通 `certbot` 会失败。
> 快速验证：`dig +short im.example.com` 应返回你的服务器 IP。

---

## 二、安装 Docker 与 compose 插件

```bash
# 官方一键脚本（Ubuntu 24.04 适用），会装 docker + docker compose 插件
curl -fsSL https://get.docker.com | sudo sh

# 让当前用户免 sudo 用 docker（★ 执行后要重新登录一次 shell 才生效）
sudo usermod -aG docker $USER

# 验证：都应有版本号输出
docker --version
docker compose version          # 注意是 "docker compose"（V2 子命令），不是 docker-compose
```

可选但推荐——限制容器日志占用、让容器看到真实来源 IP（编辑 `/etc/docker/daemon.json`，没有就新建）：

```json
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
```

```bash
sudo systemctl restart docker
```

---

## 三、把部署包拷到服务器

```bash
sudo mkdir -p /opt/im-docker
# 从开发机整个拷 Docker/ 目录内容过去（compose 里的相对路径全以这个目录为基准，别换目录）
scp -r Docker/* deploy@<服务器IP>:/opt/im-docker/

ssh deploy@<服务器IP>
cd /opt/im-docker
```

> 也可以 `git clone` 仓库后 `cd 仓库/Docker`，`bootstrap.sh` 能顺手把 `sql-init/`、`live-retention.js` 一并补齐
> （见 §六）。整目录 `scp` 过来时这些已在包内。

---

## 四、准备前端静态产物（三站的 dist）

nginx 直接把三个前端目录当静态站挂载，**必须是已构建的 dist**，不是源码：

| 站点 | 目标目录 | 来源 |
|---|---|---|
| 前端 | `web/im-ui/` | `im-ui` 的 `npm run build` 产物 dist 内容 |
| 管理后台 | `web/im-admin-ui/` | 同上 |
| 官网 | `web/im-website/` | 同上 |

```bash
# 本地分别构建三个前端，把各自 dist 里的内容（不是 dist 目录本身）拷进对应目录
# 例：前端
cd im-ui && npm ci && npm run build
scp -r dist/* deploy@<服务器IP>:/opt/im-docker/web/im-ui/
```

> CI（`build-and-package.yml` 的 `deploy` job）会自动把三个 dist 打包走 SSH 覆盖到这里；
> 手工部署就按上表自己放。`bootstrap.sh` 会在最后一步检查每个 `web/<站>/index.html` 在不在，缺了提醒你。

---

## 五、填配置（只需手填 `.env` 一个文件）

```bash
cp .env.example .env && chmod 600 .env
vi .env
```

`.env` 里**必须改的几项**：

```ini
# —— 后端镜像：CI 推的 GHCR 地址（owner 与 repo 名全小写，GHCR 拒绝大写）——
IM_SERVER_TAG=ghcr.io/<你的owner小写>/im-server:1.0.0

# —— 域名：四个 server_name、证书路径、CORS 白名单都从这几个值灌进生成物 ——
IM_DOMAIN=im.example.com
ADMIN_DOMAIN=admin.example.com
WEBSITE_DOMAIN=www.example.com
LIVE_DOMAIN=live.example.com          # 不开直播可留占位，但别留 <...>

# —— 凭据：三条密码 + 两串签名密钥，别复用、别留空、别提交 ——
MYSQL_ROOT_PASSWORD=<强随机>
MYSQL_PASSWORD=<强随机>
REDIS_PASSWORD=<强随机>
MINIO_ROOT_USER=<≥3字符，别用 minioadmin>
MINIO_ROOT_PASSWORD=<≥8字符，别用 minioadmin>
IM_JWT_SECRET=<换掉内置开发默认>
IM_TICKET_SECRET=<换掉内置开发默认>
LIVE_SIGN_SECRET=<开直播才需要，≥16位随机>
```

生成强随机串的一条命令：

```bash
openssl rand -base64 24 | tr -d '=+/\n' | head -c 24; echo
```

**（可选）让依赖镜像也不碰 Docker Hub**：国内服务器拉 Docker Hub 慢/失败时，CI 的 `mirror-images` job
会把 mysql/redis/minio/nginx 原样搬到 `ghcr.io/<owner小写>/im-deps/*`。`.env` 里这四行默认就是 GHCR 路径，
`<owner小写>` 占位符 **不用手改**——`bootstrap.sh` 会从 `IM_SERVER_TAG` 里取同一个 owner 自动回填。
若你的机器能正常访问 Docker Hub，也可把值改回官方短名：`mysql` / `redis` / `minio/minio` / `nginx`。

---

## 六、编排：跑 `bootstrap.sh`

```bash
bash scripts/bootstrap.sh
```

它按顺序做完这些事（幂等，可重复跑；要按 example 重建覆盖加 `--force`）：

1. **校验 `.env`**：拦下所有还留着 `<...>` 占位符的必填项，缺一项直接报错退出。
2. **依赖镜像 owner 对齐**：把 §五 的四个 `<owner小写>` 自动回填。
3. **建目录 + 摆权限**：`data/*` 等 chown 成 `1000:1000`（与容器内运行身份对齐，否则日志静默丢失）。
4. **生成正式配置**：由 example 生成 `nginx/conf.d/im.conf`（`server_name` + 443 + 证书路径已填好你的域名）、
   `app/extra/application-prod-extra.yml`（CORS 白名单）、`conf/im.env`，并把 `.env` 的密码/密钥同步进 `conf/im.env`
   （保证 compose 层与应用层两边逐字一致）。
5. **证书位补自签名**：`certs/live/<域名>/` 缺证书时，自动生成 **30 天自签名**证书。
   > ⚠️ 自签名只为让 nginx 能起来做**冒烟测试**，浏览器会告警。**正式上线前必须换成真证书**（§七 方案 A）。
6. **静态校验** `docker compose config -q`（不启动、不联网）。

全新建库：`bootstrap.sh` 在仓库里跑时会自动把 `sql/im_schema.sql`、`sql/im_data.sql`
复制成 `sql-init/01_im_schema.sql`、`sql-init/02_im_data.sql`（数字前缀保证建表先于插数据）。
**建库完成后建议注释掉 compose 里 `./sql-init` 那行挂载并清空目录**——`01_im_schema.sql` 开头有 37 条
`DROP TABLE IF EXISTS`，一旦卷被重建就会静默清库。

---

## 七、HTTPS 证书：三种来源，选一种

证书最终都要落到 **`/opt/im-docker/certs/live/<域名>/fullchain.pem` + `privkey.pem`**
（compose 把 `./certs` 只读挂进 nginx 的 `/etc/letsencrypt`，`im.conf` 就按这个路径引用）。

### 方案 A（生产推荐）：Let's Encrypt 真证书，走 DNS-01

DNS-01 不用暂露 80、不用停容器，还能给 `*.example.com` 签一张泛域名。完整流程见
[`Docker部署指南.md` §8.4](Docker部署指南.md)，要点：

```bash
sudo apt install -y certbot python3-certbot-dns-cloudflare     # DNS 插件按你的服务商换
certbot certonly --dns-cloudflare --dns-cloudflare-credentials /root/cf.ini \
  -d im.example.com -d admin.example.com -d www.example.com -d live.example.com

# certbot 产物在宿主机 /etc/letsencrypt/live/，拷进部署包目录供 nginx 只读挂载
sudo cp -r /etc/letsencrypt/live/* /opt/im-docker/certs/live/
sudo chmod -R a+rX /opt/im-docker/certs
```

> certbot 生成的自签兜底会被这里覆盖。换完真证书后重载：`docker compose exec nginx nginx -s reload`。
> 续期不会自动重载 nginx，配一个 deploy-hook（指南 §8.4 有）。

### 方案 B（内网 / 本地联调）：mkcert 生成本机可信证书

自签名浏览器会告警；想让本地/内网**绿锁**，用 mkcert 建一个自己的根 CA 并信任它：

```bash
sudo apt install -y libnss3-tools
# 装 mkcert（去 releases 下二进制，或 git clone 后 go build）
mkcert -install                                   # 在客户端机器上信任本地根 CA
mkcert -cert-file im.example.com.pem -key-file im.example.com-key.pem \
  im.example.com admin.example.com www.example.com live.example.com

# 放到 nginx 引用的路径
mkdir -p /opt/im-docker/certs/live/im.example.com
cp im.example.com.pem /opt/im-docker/certs/live/im.example.com/fullchain.pem
cp im.example.com-key.pem /opt/im-docker/certs/live/im.example.com/privkey.pem
```

> 三个站共用一张多 SAN 证书也行：把同一对 pem 拷进每个 `certs/live/<域名>/` 即可。
> 用「本地虚拟域名」（`im.test` 等）联调时，在**客户端** `/etc/hosts`（Windows 的
> `C:\Windows\System32\drivers\etc\hosts`）加 `127.0.0.1 im.test admin.test www.test`，`.test` 是保留顶级域、不会撞公网。

### 方案 C：云厂商 / 已有证书

把供应商给你的证书（fullchain + 私钥）改名成 `fullchain.pem` / `privkey.pem`，放进
`certs/live/<域名>/` 即可，路径与上面一致。

> ★ 证书的 CN/SAN **必须与 `.env` 里的域名逐字一致**，否则浏览器报 `NET::ERR_CERT_COMMON_NAME_INVALID`。

---

## 八、启动（一键）

先登录 GHCR（**镜像是私有包才需要**；public 包可跳过）：

```bash
echo '<GitHub PAT（只需 read:packages）>' | docker login ghcr.io -u <你的用户名> --password-stdin
```

拉起整栈——**这一条命令就够了，端口映射来自 compose 文件（80/443），命令里不用写**：

```bash
docker compose pull              # 拉 5 个镜像（im-server + 依赖；全走 GHCR 则不碰 Docker Hub）
docker compose up -d             # 后台启动；首次要等 mysql initdb + 后端冷启，约 1~2 分钟
docker compose ps                # 看状态：等 im-server 变 healthy（冷启 40~80s 正常）
```

---

## 九、验证

```bash
# ① 容器都 healthy
docker compose ps

# ② 五层健康检查（容器/端口/依赖/接口/证书）
bash scripts/health.sh

# ③ nginx 配置语法（换证书或改 im.conf 后跑一次）
docker compose exec nginx nginx -t

# ④ 本机直连 HTTPS，忽略告警看是否通
curl -kI https://im.example.com
curl -kI https://admin.example.com
curl -kI https://www.example.com

# ⑤ 证书到期时间（生产确认真证书生效、不是自签名那张 30 天的）
echo | openssl s_client -connect im.example.com:443 -servername im.example.com 2>/dev/null \
  | openssl x509 -noout -dates -subject
```

浏览器访问 `https://im.example.com` 应打开前端，`https://admin.example.com` 打开管理后台，
`https://www.example.com` 打开官网——**同一个 443，按域名分流**。方案 A 的真证书会是绿锁。

---

## 十、日常运维

### 升级 / 回滚后端镜像

```bash
bash scripts/update.sh 1.0.1          # 换 tag（先自动备份数据库）；★ 绝不覆盖同名 tag
bash scripts/update.sh --rollback 1.0.0
```

### 更新前端（三站是 bind mount，覆盖 dist 即生效，不用重启容器）

```bash
# 覆盖 web/im-ui/ 内容后，浏览器强刷即可；nginx 无需 reload（静态文件）
```

### 备份

```bash
bash scripts/backup.sh               # 数据库 / MinIO 卷 / 编排配置 / Redis 快照
```

### 改权限点后清缓存

```bash
bash scripts/clear-perm-cache.sh     # 后台菜单改了 SQL 还是旧的 → 权限缓存没清
```

### 开机自启（systemd）

```bash
sudo cp scripts/im-docker.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now im-docker
```

> `im-docker.service` 用 `up -d --wait`，且 `ExecStop` 特意**不带 `-v`**——
> `docker compose down -v` 一字之差就是**清库**，别手滑。

---

## 十一、常见坑速查

| 症状 | 根因 / 处置 |
|---|---|
| nginx 起不来、`nginx -t` 报证书文件不存在 | `certs/live/<域名>/` 缺 fullchain/privkey。跑 `bootstrap.sh` 补自签名，或按 §七 放真证书 |
| 浏览器 `NET::ERR_CERT_COMMON_NAME_INVALID` | 证书 CN/SAN 与访问域名不一致（§七 末尾） |
| im-server「起来→崩→无限重启」，日志同一句 | 连不上 minio/mysql：`depends_on` 未满足或 `conf/im.env` 里密码与 `.env` 不一致 |
| 点通话/摄像头没反应、拿不到媒体权限 | 页面是 http（非安全上下文）。必须走 HTTPS（§〇） |
| 日志目录 `data/logs` 空的 | 没挂卷或 uid 不是 1000（`bootstrap.sh` 第③步；必要时 `sudo chown -R 1000:1000 data/*`） |
| 浏览器全量 403 `Invalid CORS request`，curl 正常 | 没启用外挂 CORS 配置（`app/extra/application-prod-extra.yml`，见指南 §8.5） |
| 观众端直播黑屏 403，推流/列表正常 | `IM_LIVE_SIGN_SECRET` 与 nginx `secure_link_md5` 不一致（`.env` 只填一次即为防这个） |
| 换镜像 tag 后行为没变 | 覆盖用了同名 tag。升级到新版本号，或 `docker compose pull` 确认拉到新 digest |
| `docker compose down -v` | ★ 一字之差清库。systemd 的 ExecStop 里特意没有 `-v` |

---

## 十二、一页速查（生产全新机，最小路径）

```bash
# 1 装 docker
curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER   # 重登一次

# 2 拷包 + 填值
sudo mkdir -p /opt/im-docker && scp -r Docker/* deploy@<IP>:/opt/im-docker/
ssh deploy@<IP> && cd /opt/im-docker
cp .env.example .env && chmod 600 .env && vi .env        # 域名/密码/GHCR tag

# 3 编排（生成 im.conf/im.env、补自签证书、静态校验）
bash scripts/bootstrap.sh

# 4 真证书（生产）：certbot DNS-01 后拷进 certs/live/（见 §七 方案 A / 指南 §8.4）

# 5 一键起（端口 80/443 由 compose 文件决定，命令行不写端口）
docker compose pull && docker compose up -d

# 6 验证
docker compose ps && bash scripts/health.sh && curl -kI https://im.example.com
```
