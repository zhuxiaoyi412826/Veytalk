#!/usr/bin/env bash
# ==============================================================================
# 五层健康检查（对应 Docker部署指南.md §11.1），按顺序做，每层能排除掉一类原因。
#
#   bash scripts/health.sh
#
# 读法：从外往里看是「用户视角」，从这里往外看是「运维视角」。
# 哪一层开始失败，问题就在与上一层之间的那段（DNS？容器网络？进程？配置？）。
# ==============================================================================
set -uo pipefail          # 不用 -e：某一层失败也要继续把后面几层跑完，一次性看全
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

IM_DOMAIN="$(grep -m1 '^IM_DOMAIN=' .env | cut -d= -f2- | tr -d '\r')"

hr() { printf '\n\033[1;36m—— %s —————————————————————————————————\033[0m\n' "$1"; }

hr "① 编排层：谁没 healthy"
docker compose ps
# minio 不在栈里：它是宿主二进制 systemd 服务，单独确认服务活着 + API 端口应答
if systemctl is-active minio >/dev/null 2>&1; then echo "   minio(service): active"; else echo "   !! minio 二进制服务未运行：systemctl is-active minio 失败"; fi
curl -sf -o /dev/null http://127.0.0.1:9000/minio/health/live && echo "   minio:9000 /minio/health/live OK" || echo "   !! minio API 9000 无响应（二进制没起 / 端口不对 / 防火墙）"
echo "   → 有 unhealthy 就先看它的 logs（下一步），别直接 restart 反复试"

hr "② 应用层：最近 10 分钟的报错"
docker compose logs --since=10m --tail=120 im-server 2>&1 | tail -n 120
echo "   → 常见三种：IllegalStateException（配置缺 / Bean 起不来）、Communications link failure（连不上 mysql）、"
echo "     Unable to connect to MinIO（minio 是宿主二进制：先 systemctl is-active minio、curl 127.0.0.1:9000/minio/health/live，"
echo "       再确认容器能到 host.docker.internal:9000（防火墙没拦 docker 子网）、凭据与 MINIO_ROOT_USER/PASSWORD 一致）"

hr "③ 容器内自问：Actuator 是否 UP"
# ★ 这里是 bash 不是 sh：/dev/tcp 是 bash 特性，而 eclipse-temurin 镜像的 /bin/sh 是 dash，dash 不认
docker compose exec im-server bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; head -c 300 <&3' || true
echo "   → MANAGEMENT_ADDRESS 默认 127.0.0.1，所以这一问只能在容器里问；宿主机 curl localhost:8081 是拒的"

hr "④ 跨容器：nginx 能不能摸到后端"
# ★ nginx:1.27-alpine 里没有 bash（busybox sh 也不支持 /dev/tcp），用 busybox 自带的 nc；
#   不写 -z（busybox 的 nc 不一定编了这个选项），靠 </dev/null 立刻 EOF 当作探活
docker compose exec nginx sh -c 'nc -w 2 im-server 8080 < /dev/null' && echo "upstream-ok" || echo "!! 不通：nginx 与后端不在同一网络？后端容器没起来？"
docker compose exec nginx sh -c 'nginx -t' 2>&1 | tail -n 5 || true

hr "⑤ 外部：走完整链路（DNS → TLS → nginx → Spring）"
URL="https://${IM_DOMAIN:-127.0.0.1}/api/auth/me"
CODE="$(curl -sk -o /dev/null -w '%{http_code}' -H "Origin: https://${IM_DOMAIN}" "$URL" || echo 000)"
echo "   GET $URL -> HTTP $CODE"
cat <<'EOF'
   判定（★ 这条最容易记错）：
     200 + body 里 code:1002   = 一切正常，只是没登录（全局异常处理器不带 @ResponseStatus，所以仍是 200）
     404                       = 控制器没被加载（jar 版本不对 / 包没扫到）
     403 Invalid CORS request  = CORS 白名单没含这个 Origin → 见 app/extra 与指南 §8.5
     502                       = nginx 到了不了后端 → 回 ④
     000 / TLS 报错            = 证书或端口没通 → 检查 compose 的 ports 与安全组（Docker 发布的端口绕过 UFW）
EOF

hr "⑥ 变量真值：容器里 java 进程实际看到的（.env / im.env 拼错的唯一可靠发现手段）"
docker compose exec im-server env | grep -E 'IM_|MYSQL_|REDIS_|MINIO_|SPRING_PROFILES' | sort
echo "   → 值里带引号、带空格、带 # 的都是 conf/im.env 写错了（env_file 不做 shell 解析）"

hr "落盘日志（宿主机直接看，不用进容器）"
ls -l data/logs 2>/dev/null | head -n 20
echo "   → 目录空的 = ./data/logs 没挂上或权限不对（重跑 scripts/bootstrap.sh 修 chown）"
