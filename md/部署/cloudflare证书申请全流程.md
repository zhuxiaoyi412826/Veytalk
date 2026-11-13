# dsaol.asia 泛域名 SSL 证书申请全流程（Cloudflare DNS 验证）

> 文档版本：v1.0
> 适用场景：腾讯云域名 + 阿里云/腾讯云 ECS（Ubuntu 22.04）+ Cloudflare DNS 托管 + Let's Encrypt 免费证书
> 主机：`iZwz9ia0zujtbvbms4sqvxZ`（公网 IP `120.78.126.132`）
> 目标域名：`admin.dsaol.asia`、`www.dsaol.asia`、`im.dsaol.asia`

---

## 一、整体链路总览

| 序号 | 阶段 | 核心动作 | 关键产物 | 状态 |
|---|---|---|---|---|
| 1 | Cloudflare 账号准备 | 注册/验证账号邮箱 | 可登录的 CF 账号 | ✅ 已完成 |
| 2 | 创建 API Token | `Edit zone DNS` 模板 | `cloudflare.ini` 中的 Token | ✅ 已完成 |
| 3 | 添加站点 | CF 后台 Add a domain → `dsaol.asia` | Zone + 2 条 NS 地址 | ✅ 已完成 |
| 4 | 切换 NS | 腾讯云域名后台改 DNS 服务器 | `jerry.ns / tiffany.ns` | ✅ 已提交 |
| 5 | NS 全网生效 | 等待注册局/运营商缓存刷新 | `dig` 返回 Cloudflare NS | ⏳ 等待中（TTL 6h） |
| 6 | 申请证书 | `certbot` + dns-cloudflare 或 standalone | `/etc/letsencrypt/live/*` | ⏳ 待执行 |
| 7 | 部署证书 | Nginx 443 配置 | HTTPS 可访问 | ⏳ 待执行 |
| 8 | 自动续期 | `certbot renew` + systemd timer | 长期有效 | ⏳ 待执行 |

---

## 二、前置条件清单

| 检查项 | 要求 | 说明 |
|---|---|---|
| 域名归属 | 域名在腾讯云（DNSPod）注册 | 才能改 NS |
| Cloudflare 账号 | 与 API Token 同一账号 | 跨账号必然报 `zone_id` 找不到 |
| 服务器 80/443 端口 | 安全组放行 | standalone 模式必须 80 空闲 |
| 服务器出站 UDP 53 | 允许 | DNS 查询、插件写记录依赖 |
| DNSSEC | **必须关闭** | 开启会导致 NS 切换后解析失效 |
| certbot 版本 | ≥ 2.x，且装 `certbot-dns-cloudflare` | `certbot --version` 查看 |
| 邮箱 | 可用邮箱（如 `zhuxiaoyizxy2022@163.com`） | 接收到期提醒，也用于 CF 验证 |

### 安装 certbot 与 Cloudflare 插件

```bash
apt update
apt install -y certbot python3-certbot-nginx
pip3 install certbot-dns-cloudflare   # 或 apt install python3-certbot-dns-cloudflare
certbot --version
```

---

## 三、阶段一：创建 Cloudflare API Token

### 3.1 操作路径

| 步骤 | 页面位置 | 操作 |
|---|---|---|
| 1 | CF 首页 → 右上角头像 → **My Profile** | 进入个人资料 |
| 2 | 左侧菜单 → **API 令牌（API Tokens）** | 进入令牌列表 |
| 3 | 点 **创建令牌（Create Token）** | 选择模板 |
| 4 | 选模板 **Edit zone DNS** | 编辑区域 DNS |
| 5 | 令牌名称 | `certbot-dns` |
| 6 | 权限（Permissions） | `Zone` → `DNS` → `Edit` |
| 7 | 区域资源（Zone Resources） | `Include` → `Specific zone` → `dsaol.asia` |
| 8 | IP 过滤（可选） | `120.78.126.132/32` |
| 9 | TTL / 过期时间 | 无期限（或按需） |
| 10 | 继续 → 创建令牌 | **Token 只显示一次，立即复制** |

### 3.2 Token 权限对照表

| 字段 | 必须值 | 备注 |
|---|---|---|
| Permissions | `Zone : DNS : Edit` | 只需 DNS 编辑，无需 Zone 全权限 |
| Zone Resources | `Include / Specific zone / dsaol.asia` | 精确到单域名最安全 |
| Account Resources | `Include / All accounts`（或指定） | 配合上面单 Zone 即可 |
| IP 过滤 | 服务器公网 IP `/32` | 减少泄露风险 |
| Token 格式 | 40 位随机串 | 形如 `xxxxxxxx...` |

> ⚠️ 创建令牌前必须先把**邮箱验证完成**，否则 `API令牌` 菜单不可用。

### 3.3 写入服务器凭据文件

```bash
# 创建凭据文件（推荐放 secrets 目录）
mkdir -p /etc/letsencrypt/.secrets
vim /etc/letsencrypt/.secrets/cloudflare.ini
```

文件内容（**仅一行，等号两侧各有一个空格**）：

```ini
dns_cloudflare_api_token = 这里粘贴你的Token
```

```bash
# 权限必须 600，否则 certbot 直接报错退出
chmod 600 /etc/letsencrypt/.secrets/cloudflare.ini
cat /etc/letsencrypt/.secrets/cloudflare.ini   # 核对内容
```

> 你的实际路径目前是 `~/cf.ini`（`/root/cf.ini`）。两种路径都能用，**关键是 certbot 命令里的 `--dns-cloudflare-credentials` 必须与真实路径一致**。

### 3.4 Token 快速自检

```bash
curl -X GET "https://api.cloudflare.com/client/v4/zones" \
  -H "Authorization: Bearer 你的Token" \
  -H "Content-Type: application/json"
```

| 返回 | 含义 |
|---|---|
| `"success": true` 且能列出 `dsaol.asia` | Token 正常、Zone 已接入 |
| `"success": false` + `code 9109` | Token 无效/权限不足 |
| `success: true` 但`result: []` | Token 可以，但**域名还没添加到该账号** |

---

## 四、阶段二：添加站点与切换 NS

### 4.1 Cloudflare 侧添加站点

| 步骤 | 操作 | 注意 |
|---|---|---|
| 1 | 点左上角 CF Logo 回到站点列表 | — |
| 2 | 点 **Add a domain / 添加站点** | 空账号直接显示该按钮 |
| 3 | 输入 **根域名** `dsaol.asia` | ❌ 不要填 `im.dsaol.asia` 子域名 |
| 4 | 选套餐 **Free** | 免费套餐足够 |
| 5 | 核对自动扫描出的 DNS 记录 | 缺 `im` 的 A 记录后续补 |
| 6 | 继续前往激活 | 进入 NS 提示页 |
| 7 | **抄下 2 条 NS 地址** | 下一步要用 |

本次分配到的 NS：

```
jerry.ns.cloudflare.com
tiffany.ns.cloudflare.com
```

原有 DNSPod NS（需删除）：

```
brick.dnspod.net
database.dnspod.net
```

### 4.2 腾讯云侧修改 DNS 服务器

> ❗ Cloudflare 的【DNS 记录页】只能改 A/CNAME 记录，**改 NS 必须去域名注册商后台**。

| 步骤 | 页面 | 操作 |
|---|---|---|
| 1 | 腾讯云控制台 → 域名注册 → 我的域名 | 找到 `dsaol.asia` |
| 2 | 点击域名 → **DNS 服务器**（或"修改DNS服务器"） | 进入修改页 |
| 3 | 删除原有 `brick.dnspod.net` / `database.dnspod.net` | 保留空的两条输入框 |
| 4 | 填入 `jerry.ns.cloudflare.com`、`tiffany.ns.cloudflare.com` | 顺序无所谓 |
| 5 | 保存 | 状态显示为"其他"即提交成功 |
| 6 | 域名安全 → DNSSEC | **确认关闭** |
| 7 | 回到 CF 页面点 **我已更新名称服务器** | 触发 CF 检测 |

### 4.3 验证 NS 是否生效

| 命令 | 用途 |
|---|---|
| `nslookup -type=NS dsaol.asia` | 查看本地解析器结果 |
| `dig @8.8.8.8 NS dsaol.asia` | 绕过本地缓存，看上游根域是否刷新 |
| `dig @1.1.1.1 NS dsaol.asia` | Cloudflare 自家 DNS，最权威参考 |
| `while true; do echo "==== $(date) ===="; dig @8.8.8.8 NS dsaol.asia +short; sleep 15; done` | 循环监测 |

判定标准：

| 现象 | 结论 | 能否跑 certbot |
|---|---|---|
| 返回 `brick.dnspod.net` | 注册局权威记录仍是旧值 | ❌ 不能 |
| 返回 `jerry.ns / tiffany.ns` | NS 已切换成功 | ✅ 可以 |
| CF 后台状态 = 待激活 | 插件无法写入 TXT | ❌ 不能 |
| CF 后台状态 = **活动（Active）** | 正常 | ✅ 可以 |

> 📌 本次卡点：注册局 TTL = **21600 秒（6 小时）**，`dig @8.8.8.8` 持续返回 dnspod 旧 NS，属正常缓存周期，需耐心等待。

---

## 五、阶段三：申请证书（两种方案）

### 5.1 方案对比

| 对比项 | 方案 A：`--standalone`（HTTP-01） | 方案 B：`--dns-cloudflare`（DNS-01） |
|---|---|---|
| 是否需要等 NS 生效 | ❌ 不需要，立即可跑 | ✅ 必须等 NS 生效 + CF 状态活动 |
| 端口要求 | 必须 **80 端口空闲并放行** | 不需要开 80 |
| 是否依赖 Token | 不需要 | 需要 |
| 适合泛域名 `*.dsaol.asia` | 不支持 | 支持 |
| 续期便利性 | 每次续期都要腾出 80 端口 | 全自动，无端口冲突 |
| 推荐度 | 应急首选 | 长期首选 |

### 5.2 方案 A：standalone 立即申请（推荐现在执行）

```bash
# 1. 检查 80 端口是否空闲
ss -tulnp | grep :80
# 若有 nginx 占用，先临时停掉
systemctl stop nginx

# 2. 申请证书（三域名写在同一张证书里）
certbot certonly --standalone \
  -d admin.dsaol.asia \
  -d www.dsaol.asia \
  -d im.dsaol.asia \
  --agree-tos \
  -m zhuxiaoyizxy2022@163.com \
  --non-interactive

# 3. 恢复 nginx
systemctl start nginx
```

### 5.3 方案 B：dns-cloudflare 申请（等 NS 生效后执行）

```bash
certbot certonly \
  --dns-cloudflare \
  --dns-cloudflare-credentials ~/cf.ini \
  --dns-cloudflare-propagation-seconds 60 \
  -d admin.dsaol.asia \
  -d www.dsaol.asia \
  -d im.dsaol.asia \
  --cert-name dsaol.asia \
  --non-interactive --agree-tos \
  -m zhuxiaoyizxy2022@163.com
```

> `--dns-cloudflare-propagation-seconds 60`：默认仅 10 秒，国内 DNS 传播慢，务必加大到 60~120。

### 5.4 泛域名写法（通配符必须用方案 B）

```bash
certbot certonly \
  --dns-cloudflare \
  --dns-cloudflare-credentials ~/cf.ini \
  -d dsaol.asia -d *.dsaol.asia \
  --cert-name wildcard.dsaol.asia \
  --non-interactive --agree-tos -m zhuxiaoyizxy2022@163.com
```

### 5.5 证书产物路径

| 文件 | 路径 | 用途 |
|---|---|---|
| 全链证书 | `/etc/letsencrypt/live/dsaol.asia/fullchain.pem` | Nginx `ssl_certificate` |
| 私钥 | `/etc/letsencrypt/live/dsaol.asia/privkey.pem` | Nginx `ssl_certificate_key` |
| 证书链 | `/etc/letsencrypt/live/dsaol.asia/chain.pem` | 一般无需单独使用 |
| 单证书 | `/etc/letsencrypt/live/dsaol.asia/cert.pem` | 调试用 |

---

## 六、阶段四：Nginx 部署 HTTPS

### 6.1 站点配置模板

```nginx
server {
    listen 80;
    server_name admin.dsaol.asia www.dsaol.asia im.dsaol.asia;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name admin.dsaol.asia www.dsaol.asia im.dsaol.asia;

    ssl_certificate     /etc/letsencrypt/live/dsaol.asia/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/dsaol.asia/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 10m;

    location / {
        proxy_pass http://127.0.0.1:8080;   # 改成你的后端端口
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
nginx -t && systemctl reload nginx
```

### 6.2 自动续期

| 项目 | 内容 |
|---|---|
| 续期命令 | `certbot renew --dry-run`（先演练） |
| 正式续期 | `certbot renew` |
| 自动任务 | `/etc/cron.d/certbot` 或 `systemctl list-timers \| grep certbot` |
| Nginx 重载钩子 | 在 `/etc/letsencrypt/renewal-hooks/deploy/` 添加脚本 `systemctl reload nginx` |

```bash
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh <<'EOF'
#!/bin/sh
systemctl reload nginx
EOF
chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
certbot renew --dry-run
```

> ⚠️ 若用 standalone 申请，续期时 certbot 会临时占 80 端口，与 nginx 冲突。建议续期前用 `--pre-hook "systemctl stop nginx"`、`--post-hook "systemctl start nginx"`，或续期时改装 dns-cloudflare 方式。

---

## 七、故障排查对照表

| 报错信息 | 根因 | 解决方案 |
|---|---|---|
| `Unable to determine zone_id for xxx` | 域名未添加到当前 CF 账号 / Token 账号不匹配 | CF 后台 Add a domain 接入该根域名，确认状态活动 |
| `NXDOMAIN looking up TXT for _acme-challenge...` | NS 仍指向 DNSPod，CA 查不到 CF 写入的 TXT | 等 NS 生效；或临时改用 `--standalone` |
| `Waiting 10 seconds...` 后失败 | 传播时间太短 | 加 `--dns-cloudflare-propagation-seconds 60` |
| `The client lacks sufficient authorization` | Token 权限不足 / Zone 选错 | Token 改为 `Zone:DNS:Edit` + 指定 zone |
| `Permission denied` 读 `cloudflare.ini` | 文件权限过宽 | `chmod 600` |
| `Could not find zone` / `Invalid domain` | 填写了子域名而非根域名 | 只能填 `dsaol.asia` |
| `Too many failed authorizations` | 触发 LE 速率限制（5 次/小时） | 等待 1 小时后再试 |
| `Problem binding to port 80` | nginx 占用 80 | `systemctl stop nginx` 后重试 |
| 域名解析失效 | DNSSEC 未关闭 | 腾讯云关闭 DNSSEC 并等待 |
| 证书申请成功但浏览器不信任 | 只配了 `cert.pem` 而非 `fullchain.pem` | 改用 `fullchain.pem` |

查看详细日志：

```bash
tail -f /var/log/letsencrypt/letsencrypt.log
certbot certonly -v --dns-cloudflare --dns-cloudflare-credentials ~/cf.ini -d www.dsaol.asia
```

---

## 八、本次实际操作时间线（踩坑记录）

| 时间点 | 操作 | 结果 |
|---|---|---|
| 步骤 1 | CF 邮箱验证 | ✅ 通过 |
| 步骤 2 | 创建 `certbot-dns` Token | ✅ 完成，写入 `~/cf.ini` |
| 步骤 3 | 循环申请 `im/admin/www` 三张证书 | ❌ 报 `Unable to determine zone_id` |
| 排查 1 | 确认域名未接入 CF 账号 | 定位根因 |
| 步骤 4 | CF 后台 Add a domain `dsaol.asia` | ✅ 状态待激活 |
| 步骤 5 | 腾讯云修改 NS 为 `jerry/tiffany` | ✅ 提交成功，页面显示"其他" |
| 步骤 6 | 首次跑 certbot dns 验证 | ❌ 报 `NXDOMAIN _acme-challenge...` |
| 排查 2 | `nslookup / dig @8.8.8.8` 仍返回 dnspod NS | 注册局 TTL 21600s 未刷新 |
| 当前状态 | 等待 NS 全网生效 | ⏳ 进行中 |

### 经验总结

| 序号 | 结论 |
|---|---|
| 1 | CF 后台的"DNS 记录页"≠"NS 修改页"，NS 只能在域名注册商处改 |
| 2 | `zone_id` 报错 100% 是域名没接入该 CF 账号，与 Token 权限无关 |
| 3 | NS 提交后必须用 `dig @8.8.8.8` 验证，别信注册商页面的"已修改" |
| 4 | 注册局 TTL 可达 6 小时，等不及就切 `--standalone` |
| 5 | 国内环境 `propagation-seconds` 一定要 ≥60 |
| 6 | 证书只依赖域名可达性，与域名最终托管在哪家 DNS 无关 |

---

## 九、常用命令速查

| 用途 | 命令 |
|---|---|
| 查 NS | `dig @8.8.8.8 NS dsaol.asia +short` |
| 查 TXT | `dig @8.8.8.8 TXT _acme-challenge.www.dsaol.asia +short` |
| 查 A 记录 | `dig @8.8.8.8 A www.dsaol.asia +short` |
| 查 80 端口 | `ss -tulnp \| grep :80` |
| 列出已有证书 | `certbot certificates` |
| 申请证书 | `certbot certonly --standalone -d xxx` |
| 强制续期 | `certbot renew --force-renewal` |
| 删除证书 | `certbot delete --cert-name dsaol.asia` |
| 模拟续期 | `certbot renew --dry-run` |
| 查看日志 | `tail -f /var/log/letsencrypt/letsencrypt.log` |
| 校验 Token | `curl -H "Authorization: Bearer <token>" https://api.cloudflare.com/client/v4/zones` |
| 校验证书 | `openssl x509 -in fullchain.pem -noout -text \| grep -A1 "Subject Alternative Name"` |

---

## 十、下一步行动清单

| 优先级 | 待办 | 命令/操作 |
|---|---|---|
| P0 | 确认 NS 是否已生效 | `dig @8.8.8.8 NS dsaol.asia +short` |
| P0 | 确认 CF 站点状态为"活动" | CF 后台首页查看 |
| P1 | 生效后申请证书 | `certbot certonly --dns-cloudflare ... --propagation-seconds 60` |
| P1 | 或直接走 standalone 立即拿证 | `certbot certonly --standalone -d admin... -d www... -d im...` |
| P2 | 补 `im` 的 A 记录（指向 `120.78.126.132`，橙色云） | CF DNS 页添加 |
| P2 | 配置 Nginx 443 + 301 跳转 | 见第六章模板 |
| P3 | 配置续期钩子并 `--dry-run` 验证 | 见 6.2 |

> （注：部分内容由豆包 AI 生成）