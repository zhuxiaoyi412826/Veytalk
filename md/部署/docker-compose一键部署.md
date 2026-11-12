# 部署过程

## 一、虚拟机部署

**1.前置条件**  

-  MinIO 二进制本体要先装好  账号密码
-  装 Docker + compose 插件
-    后端打好jar包

**2.拷贝docker 目录到服务器  **

- cd Docker    进入到docker目录
- jar包复制到  app目录下                                        
-  .env.example 里所有 <...> 换成真值                                    
- cp .env.example .env && chmod 600 .env     # 复制文件.env.example 为 .env 并加600权限 仅属主可读写
- systemctl is-active minio || sudo systemctl start minio   # 确认启动二进制 minio，   
- docker login ghcr.io                                      # 向 GitHub 容器仓库认证，之后 compose pull 才拉得动
- bash scripts/bootstrap.sh                                  # 生成 conf/im.env、nginx 配置、自签证书

**3.把三站 dist 拷进 Docker/web/**

cd d:\daima\Lianshi\spring-boot-duomokuia

把你本地资源上传到对应的容器挂载目录

scp -r im-ui\dist\*        zhuxiaoyi@192.168.83.130:~/桌面/Docker/web/im-ui/
scp -r im-admin-ui\dist\*  zhuxiaoyi@192.168.83.130:~/桌面/Docker/web/im-admin-ui/
scp -r im-website\dist\*   zhuxiaoyi@192.168.83.130:~/桌面/Docker/web/im-website/

**4.启动镜像**
docker compose pull                                        # ★ 先拉，避开本地 build
docker compose up -d                                       # 启动
docker compose ps                                          # 查看每个容器的状态  
bash scripts/health.sh                                     # 六段分层诊断，按「哪层开始失败，问题就在它与上一层之间」来读

**5.数据库初始化**  

- 手动执行 SQL 文件  账号 root zhuxiaoyi// im  412826zxyZXY  在SQL-int文件里 把SQL拷贝到这个文件夹里，并先执行01 在执行02
-  docker exec -i im-mysql mysql -uroot -pzhuxiaoyi im_db < ./02_im_data.sql  和 01_im_schema.sql  
- docker exec -it im-mysql mysql -uroot -pzhuxiaoyi -e "use im_db; show tables;"  查看导入的表

**6.访问测试**
本机 hosts 加上这一行    这个ip和域名改为自己设置的

192.168.83.130  im.example.com admin.example.com www.example.com
站点	在浏览器里敲	落到哪个 server	磁盘目录
聊天端	https://im.example.com/	        server_name __IM_DOMAIN__	    web/im-ui
管理后台	https://admin.example.com/	server_name __ADMIN_DOMAIN__	web/im-admin-ui
静态官网	https://www.example.com/	server_name __WEBSITE_DOMAIN__	web/im-website

**7. 其他docker命令** 
docker build -t local/im-server:1.0.0 Docker/app   # 构建命令
docker logs 查看日志  
docker

**8.测试** 

信息  单聊 群聊  视频  语音  AI  直播  视频  设置功能  多客户端测试

## 二、真实上线：真域名 + certbot

### 第 0 步，先确认前提

- 域名的 **A 记录**指到服务器公网 IP（四个都要解析，哪怕 LIVE 暂时不上 TLS）；云控制台**安全组放行 80/443**（UFW 挡不住 Docker 发布的端口，但安全组能）。
- Let's Encrypt **不给 IP 签证书**。所以 `https://192.168.x.x/` 那种访问，换真证书也治不好，只能走域名。

### 第 1~2 步，改 `.env` 并重跑 bootstrap（顺序不能反）

```
cd /opt/im-docker
vi .env        # IM_DOMAIN / ADMIN_DOMAIN / WEBSITE_DOMAIN / LIVE_DOMAIN 换成真域名
bash scripts/bootstrap.sh
```

不重跑的后果（就是上一轮聊的那条规矩）：`im.conf` 的 `server_name` 与 `/etc/letsencrypt/live/<域名>/` 路径、CORS 白名单里的三条 origin 全是生成物，**还停在老域名上**。

### 第 3 步，选签发方式

★ **这个项目里 HTTP-01 天生不通，别照着网上抄 `--webroot -w /var/www/acme`**：`im.conf` L23 确实留了 `location /.well-known/acme-challenge/ { root /var/www/acme; }`，但 nginx 容器的 volumes 里**没有挂载 `/var/www/acme`**（只挂了 conf.d / snippets / web 三站 / certs / data/live）。你在宿主机往 `/var/www/acme` 写 challenge 文件，容器里那个目录是空的 → 永远 404。`--standalone` 又要抢 80 端口，跟容器冲突。

所以正路是 **DNS-01**（不碰 80、不停容器、还能一次签泛域名）：

```
sudo apt install -y certbot python3-certbot-dns-cloudflare   # 插件按你的 DNS 服务商换：dnspod/alidns/route53…
sudo mkdir -p /root && printf 'dns_cloudflare_api_token = <你的token>\n' | sudo tee /root/cf.ini
sudo chmod 600 /root/cf.ini

sudo certbot certonly --dns-cloudflare --dns-cloudflare-credentials /root/cf.ini \
  -d im.example.com -d admin.example.com -d www.example.com --rsa-key-size 2048 --agree-tos -m 你@example.com
```

★★ **注意是哪三个域名**：`im.conf` 里只有 **3 个 443 server 块**引用证书（IM / ADMIN / WEBSITE）。`LIVE_DOMAIN` 只出现在 80 的 `server_name` 上，推流口 8088 那块是注释掉的且 `server_name _` 无 TLS —— 所以 **live 现在不需要证书，而 www 必须有**。仓库里指南 §8.4 那条示例命令写的是「im / admin / live」，**少 www、多 live，照抄会让 nginx `[emerg] cannot load certificate` 起不来**（www 那块引用的文件不存在，一个 server 块挂，整个 conf 挂）。

### 第 4 步，把证书搬进容器读的那份目录

关键是这份挂载：`./certs:/etc/letsencrypt:ro`，而 conf 里写的是 `/etc/letsencrypt/live/<域名>/fullchain.pem`。certbot 在宿主机签的证书落在宿主的 `/etc/letsencrypt/`，**容器看不见**，必须拷：

```
sudo rm -rf /opt/im-docker/certs/live          # 先把自签那几行清掉（避免新旧混在一个目录）
sudo cp -rL /etc/letsencrypt/live /opt/im-docker/certs/     # ★ 必须带 -L，见下
sudo chmod -R a+rX /opt/im-docker/certs
```

★ **`-L` 不能省**：Let's Encrypt 的 `live/<域名>/fullchain.pem` 是**指向 `../archive/fullchainN.pem` 的软链接**。`cp -r`（不带 `-L`）会把软链接原样拷过去，而容器里只拷了 `live/`、没有 `archive/` → 悬空链接 → nginx 加载证书失败。`-L` 才会把它解引用成真实文件。

顺手验证一下到底是不是真文件：`file /opt/im-docker/certs/live/im.example.com/fullchain.pem` 应输出 `PEM certificate`，而不是 `symbolic link`。

### 第 5 步，重载并验收

```
docker compose exec nginx nginx -t
docker compose exec nginx nginx -s reload

# 拿到的是真证书、且 SAN 齐
echo | openssl s_client -connect im.example.com:443 -servername im.example.com 2>/dev/null \
  | openssl x509 -noout -issuer -dates -ext subjectAltName
```

`issuer` 必须是 `Let's Encrypt …`（还是 `CN=<域名>` 就是仍在那张自签上）。浏览器、通话、`wss` 一起就都干净了——公信 CA 不需要在任何客户端装证书。

### 第 6 步，自动续期（这一步最容易配成「看着有、实际不生效」）

`apt` 装的 certbot 自带 systemd timer，续期本身会自动跑；**但你这个项目是「拷贝出来的副本」，续期只更新宿主 `/etc/letsencrypt`，容器里那份不会自己变**。所以 deploy-hook 必须**两步都做**，指南 §8.4 里那份只写了 reload，是个静默失效配置：

```
sudo tee /etc/letsencrypt/renewal-hooks/deploy/im-nginx.sh > /dev/null <<'EOF'
#!/bin/sh
cp -rL /etc/letsencrypt/live /opt/im-docker/certs/
chmod -R a+rX /opt/im-docker/certs
docker compose -f /opt/im-docker/docker-compose.yml exec -T nginx nginx -s reload
EOF
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/im-nginx.sh

sudo certbot renew --dry-run        # 只演一遍，不真换；会触发上面的 hook 吗？不会，dry-run 不跑 deploy-hook
```

补两句判据：`sudo certbot renew --force-renewal` 可以真跑一次来验 hook；平时用 `systemctl list-timers | grep certbot` 确认 timer 在排。

# 原理解析

### im.env   `app/extra/application-prod-extra.yml`

|                | `conf/im.env`                                                | `app/extra/application-prod-extra.yml`                       |
| :------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
| **读者**       | **docker compose（宿主机 CLI）**                             | **容器里的 Java 进程**                                       |
| **读的时机**   | **创建容器那一刻**，把值烙进容器的 Env 配置                  | **Spring 启动那一刻**，读一次                                |
| 文件改了之后   | 容器不会自己去重读 env_file                                  | bind mount 是实时映射，容器内**立刻**能看到新内容            |
| 要让它生效     | **必须重建容器**（Env 数组创建后不可变）→ `up -d`            | **只要重启进程** → `restart`                                 |
| 用错命令的后果 | 跑 `restart`：停一下再起来，**还是那批旧 Env**，看起来重启成功了但值没变 | 跑 `up -d`：compose 比对配置指纹，发现镜像、挂载路径、env 列表**都没变** → 什么都不做（"up to date"）→ Java 进程压根没重启，你改的内容没人读 |

记法就一句：**改了谁的文件，就叫谁重读。** env 是 compose 读的，yml 是 java 读的。



修改.env.example 文件   修改域名的话需要 重建容器 up -d    而且需要证书初始化  *这四个值必须与你自己签发的证书 CN/SAN 一致*

```
IM_DOMAIN=im.example.com

ADMIN_DOMAIN=admin.example.com

WEBSITE_DOMAIN=www.example.com

LIVE_DOMAIN=live.example.com
```



## 修改MySQL密码

换 mysql 密码的正路是四步：

```sql
ALTER USER 'im'@'%' IDENTIFIED BY '<新密码>';
ALTER USER 'root'@'%' IDENTIFIED BY '<新root密码>';   -- 需要时
FLUSH PRIVILEGES;
```

然后 `.env` 改成同一个值 → 重跑 `bootstrap.sh` → `docker compose up -d im-server`（`restart` 不重读 `env_file`）。

## scripts/bootstrap.sh

它是 **Ubuntu 部署机上的「首次编排」脚本**：把仓库里那些 `.example` 模板，变成 `docker compose` 真正要读的正式文件。没有它，compose 起来会直接缺 `conf/im.env`、`nginx/conf.d/im.conf`、证书这三样而起不来

每次 `.env` 改值后（幂等，可反复跑）

**改了 `.env` 里任何值都必须重跑**（密码、域名、tag 都靠它往三份生成物里灌）。不重跑的表现是「`.env` 改了但 nginx 和 `conf/im.env` 还在用旧值」。

重新构建容器

**它按顺序干的七步**

| 步                              | 干什么                                                       | 为什么不能省                                                 |
| :------------------------------ | :----------------------------------------------------------- | :----------------------------------------------------------- |
| ① 校验 `.env`                   | 十项必填（四域名、三份密码、两串密钥、镜像 tag）逐项查，值里还带 `<...>` 就当场报错退出 | 占位符漏到后面＝密码写成 `<你的密码>` 还一路灌进容器         |
| ①.5 依赖镜像 owner 对齐         | 从 `IM_SERVER_TAG` 里抠出 owner，回填 `.env` 的 `MYSQL_IMAGE`/`REDIS_IMAGE`/`NGINX_IMAGE` 里的 `<owner小写>` | 同一个 owner 只填一次，四处各写容易写歪                      |
| ② 建目录 + 权限                 | 建 `data/{logs,upload-tmp,files,knowledge,live}`、`web/*`、`certs`、`conf` 等，并把要写的目录 `chown 1000:1000`、`chmod 600 .env` | uid 不对齐的表现**不是报错**，而是日志写不进卷、上传分片丢——logback 和 Tomcat 都不拦启动 |
| ③ 生成两份正式配置              | `im.conf.example` → `nginx/conf.d/im.conf`；`application-prod-extra.example.yml` → `app/extra/...yml`，把域名和直播签名密钥灌进 `__TOKEN__` | CORS 白名单在 yml 里是字面列表，环境变量动不了，只能靠这份外挂文件 |
| ④ 生成 `conf/im.env` 并同步凭据 | 没有就从 example 拷一份，再把 `.env` 里的密码/密钥 `set_kv` 写进去；顺手打开 `SPRING_CONFIG_ADDITIONAL_LOCATION` 开关；最后扫残留占位符、带引号的值、行内注释 | 「两边密码逐字一致」这条红线由脚本保证，不靠人抄。`env_file` 不做 shell 解析，值带引号或行内注释会被原样当值传给 java |
| ⑤ 补证书                        | `certs/live/<域名>/` 缺文件就用 openssl 现签一张 30 天自签名 | 只为让 nginx 能起来做冒烟测试，上线必须换 certbot 的真证书   |
| ⑥ 补周边资料                    | 从 `../sql` 拷 `sql-init/01_im_schema.sql`、`02_im_data.sql`；拷 `electron/live-retention.js`；检查三站 `web/<站>/index.html` 在不在 | ★ 必须重命名成 `01_`/`02_`：mysql entrypoint 按文件名字典序跑，原名 `im_data.sql` 排在 `im_schema.sql` 前面（d<s）→ 往不存在的表插数据，建库直接失败 |
| ⑦ 校验 compose                  | `docker compose config -q`，纯静态语法检查，不启动不联网     | 语法错在这一步暴露，比 `up -d` 起一半崩了好查                |

最后打印下一步命令清单（先确认宿主机的二进制 minio 在跑 → `pull` → `up -d` → `ps` → `health.sh`）。

## 真实上线证书问题

