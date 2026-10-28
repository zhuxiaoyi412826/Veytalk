#!/usr/bin/env bash
# ==============================================================================
# Docker/ 部署包的首次编排脚本（在部署机上跑，不是在这台写文件的机器上）。
# 对应 Docker部署指南.md 第二、三、八节。可重复执行（幂等）。
#
#   bash scripts/bootstrap.sh            # 只补缺失的生成物，已存在且不同则保留并提示
#   bash scripts/bootstrap.sh --force    # 用 example 重新生成，覆盖前先备份 .bak.<时间戳>
#
# 它做五件事：
#   ① 校验 .env 填全了（拦下所有还留着 <...> 占位符的项）
#   ② 建目录 + 把 data/ 下要写的目录 chown 成 uid 1000（与容器内 USER 1000:1000 运行身份对齐）
#   ③ 由 example 生成 nginx/conf.d/im.conf、app/extra/application-prod-extra.yml，
#      并把 .env 里的域名与直播签名密钥灌进占位符
#   ④ 由 conf/im.env.example 生成 conf/im.env，并把 .env 的密码/密钥同步进去（两边逐字一致这条红线由脚本保证）
#   ⑤ 证书位缺文件时补一张自签名（只为让 nginx 能起来做冒烟测试，上线前必须换成 certbot 签发的）
# ==============================================================================
set -euo pipefail

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$PWD"
STAMP="$(date +%Y%m%d-%H%M%S)"
say()  { printf '\033[1;36m==\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 读 .env（不 source：值里可能带 $ ` " 这些会让 shell 重新解释的字符） ----------
[[ -f .env ]] || die "缺 .env。先 cp .env.example .env 并填好密码/域名/tag，再重跑本脚本。"
read_env() {
  local line
  line="$(grep -m1 "^$1=" .env || true)"
  [[ -n "$line" ]] || { printf ''; return; }
  printf '%s' "${line#*=}" | tr -d '\r'
}

# 写 key=value 进目标文件：存在就整行替换，不存在就追加
# ★ 两个细节都是踩过的：
#   ① 值走 ENVIRON 而不是 awk -v：-v 会对反斜杠转义（密码里一个 \ 就被吃了）
#   ② 用 `$0 = 新内容` 而不是 sub(repl)：sub() 的替换文本里 & 代表「整段匹配」，
#      密码/密钥里一个 & 就会把原行插回去（IM_JWT_SECRET 含 & 时实测就是这么坏的）
set_kv() {
  local file="$1" key="$2" val="$3"
  KEY="$key" VAL="$val" awk 'BEGIN{k=ENVIRON["KEY"]; v=ENVIRON["VAL"]; hit=0}
    $0 ~ "^"k"=" { $0 = k "=" v; hit=1 } {print}
    END{if(!hit) print k "=" v}' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
}

# 由模板生成正式文件：把 __TOKEN__ 逐个换成 .env 里的真值（不用车床的 sed/envsubst，理由见 im.conf.example 头部）
# ★★ 不用 ${body//token/value}：那种写法会把值里的 & 展开成「匹配到的那串」（实测：
#    LIVE_SIGN_SECRET=liveSign/Secret&1 会变成 …/Secret__LIVE_SIGN_SECRET__1），而密码与密钥里出现
#    & 十分常见。下面的拼接式替换对 & / \ / / 都不敏感，只认完整 token。
#    ★ 内部变量一律带 __ra_ 前缀：nameref 指向调用方的变量名，若本函数里又声明同名 local 就会被遮蔽（改了等于没改）
replace_all() {
  local -n __ra_target="$1"
  local __ra_tok="$2" __ra_val="$3" __ra_rest="${!1}" __ra_head
  __ra_target=""
  while [[ "$__ra_rest" == *"$__ra_tok"* ]]; do
    __ra_head="${__ra_rest%%"$__ra_tok"*}"     # 第一个 token 之前的部分
    __ra_target+="$__ra_head$__ra_val"
    __ra_rest="${__ra_rest#*"$__ra_tok"}"      # 跳掉已处理部分（含这个 token）
  done
  __ra_target+="$__ra_rest"
}

render() {
  local src="$1" dst="$2" body
  [[ -f "$src" ]] || die "缺模板：$src"
  body="$(cat "$src")"
  replace_all body __IM_DOMAIN__      "$IM_DOMAIN"
  replace_all body __ADMIN_DOMAIN__   "$ADMIN_DOMAIN"
  replace_all body __WEBSITE_DOMAIN__ "$WEBSITE_DOMAIN"
  replace_all body __LIVE_DOMAIN__    "$LIVE_DOMAIN"
  replace_all body __LIVE_SIGN_SECRET__ "$LIVE_SIGN_SECRET"
  if [[ -f "$dst" ]]; then
    if printf '%s\n' "$body" | cmp -s - "$dst"; then
      say "已最新：${dst#$ROOT/}"
      return
    fi
    if [[ $FORCE -eq 1 ]]; then
      cp "$dst" "$dst.bak.$STAMP"; warn "已覆盖：${dst#$ROOT/}（旧文件备份为 $(basename "$dst").bak.$STAMP）"
    else
      warn "已存在且与模板不同，保留现状：${dst#$ROOT/}（要按模板重建请加 --force）"
      return
    fi
  fi
  printf '%s\n' "$body" > "$dst"
  say "已生成：${dst#$ROOT/}"
}

# ==============================================================================
say "① 校验 .env"
IM_DOMAIN="$(read_env IM_DOMAIN)"
ADMIN_DOMAIN="$(read_env ADMIN_DOMAIN)"
WEBSITE_DOMAIN="$(read_env WEBSITE_DOMAIN)"
LIVE_DOMAIN="$(read_env LIVE_DOMAIN)"
LIVE_SIGN_SECRET="$(read_env LIVE_SIGN_SECRET)"
IM_SERVER_TAG="$(read_env IM_SERVER_TAG)"

MISSING=()
for k in IM_DOMAIN ADMIN_DOMAIN WEBSITE_DOMAIN MYSQL_ROOT_PASSWORD MYSQL_PASSWORD REDIS_PASSWORD \
         MINIO_ROOT_USER MINIO_ROOT_PASSWORD IM_JWT_SECRET IM_TICKET_SECRET IM_SERVER_TAG; do
  v="$(read_env "$k")"
  [[ -n "$v" ]] || MISSING+=("$k（.env 里没这一项）")
  case "$v" in *"<"*|*">"*) MISSING+=("$k（还是占位符：$v）");; esac
done
# 直播签名密钥允许留占位（不开直播就整段用不到），但一旦留着 <...> 就别写进 nginx
case "$LIVE_SIGN_SECRET" in ""|*"<"*|*">"*) LIVE_SIGN_SECRET="REPLACE_ME_LIVE_SECRET"; warn "LIVE_SIGN_SECRET 未填：nginx 与 im.env 里都会先写占位串 REPLACE_ME_LIVE_SECRET，开直播前必须两边改齐（改齐后重跑本脚本）";; esac
[[ ${#MISSING[@]} -eq 0 ]] || die ".env 没填全：
    - ${MISSING[*]}"

for d in "$IM_DOMAIN" "$ADMIN_DOMAIN" "$WEBSITE_DOMAIN"; do
  [[ -n "$d" ]] || die ".env 里有域名为空"
  case "$d" in *example.com*) warn "域名仍是 example.com（$d）：冒烟测试没问题，上线前要换成真域名并重新签发证书";; esac
done
case "$IM_SERVER_TAG" in *"local/im-server"*|*"localhost"*) warn "IM_SERVER_TAG 是本地构建 tag（$IM_SERVER_TAG）：compose 会走 build: 而不是 pull";; esac
say "IM_SERVER_TAG=$IM_SERVER_TAG / IM_DOMAIN=$IM_DOMAIN"

# ==============================================================================
say "② 建目录与权限"
mkdir -p data/logs data/upload-tmp data/files data/knowledge data/live \
         web/im-ui web/im-admin-ui web/im-website \
         certs/live nginx/conf.d app/extra conf sql-init scripts backup
# ★ uid 1000 与镜像里的 im 用户（Dockerfile 里 --uid 1000）对齐。不对齐的表现不是报错，
#   而是「日志写不进卷 / 上传分片落不进目录」——logback 与 Tomcat 都不会拦住启动。
CHOWN_TARGETS=(data/logs data/upload-tmp data/files data/live)
if [[ $EUID -eq 0 ]]; then
  chown -R 1000:1000 "${CHOWN_TARGETS[@]}"; say "已 chown 1000:1000（当前是 root）"
elif command -v sudo >/dev/null 2>&1; then
  sudo chown -R 1000:1000 "${CHOWN_TARGETS[@]}"; say "已 sudo chown 1000:1000"
else
  warn "既不是 root 也没有 sudo：跳过 chown。若 docker logs 目录里没有文件，回来手工执行
      sudo chown -R 1000:1000 ${CHOWN_TARGETS[*]}"
fi
chmod 600 .env
[[ -f conf/im.env ]] && chmod 600 conf/im.env

# ==============================================================================
say "③ 生成 nginx 与 CORS 配置"
render nginx/conf.d/im.conf.example            nginx/conf.d/im.conf
render app/extra/application-prod-extra.example.yml app/extra/application-prod-extra.yml

# ==============================================================================
say "④ 生成 conf/im.env 并同步凭据"
if [[ ! -f conf/im.env ]]; then
  cp conf/im.env.example conf/im.env; chmod 600 conf/im.env; say "已从 example 复制出 conf/im.env"
fi
# 单一事实来源：密码与两串签名密钥只在 .env 里填一次，这里负责写进应用侧
set_kv conf/im.env MYSQL_PASSWORD             "$(read_env MYSQL_PASSWORD)"
set_kv conf/im.env REDIS_PASSWORD             "$(read_env REDIS_PASSWORD)"
set_kv conf/im.env MINIO_ACCESS_KEY           "$(read_env MINIO_ROOT_USER)"
set_kv conf/im.env MINIO_SECRET_KEY           "$(read_env MINIO_ROOT_PASSWORD)"
set_kv conf/im.env IM_JWT_SECRET              "$(read_env IM_JWT_SECRET)"
set_kv conf/im.env IM_TICKET_SECRET           "$(read_env IM_TICKET_SECRET)"
set_kv conf/im.env IM_LIVE_SIGN_SECRET        "$LIVE_SIGN_SECRET"
# ★ 只扫非注释行：example 的头注释里本来就写着「把所有 <...> 换成真值」，连注释一起报会刷一屏噪声
LEFT="$(grep -n '^[^#]*<' conf/im.env || true)"
[[ -z "$LEFT" ]] || warn "conf/im.env 里仍有占位符（脚本只覆盖了上面这几项，剩下的自己填）：
$LEFT"
# ★ env_file 的值不能带引号/行内注释（指南 3.3：`X=/app/files  # 注释` 会把注释一起当成值传进进程），扫一遍常见误写
if grep -nE "^[A-Z0-9_]+=[^#]*[\"']" conf/im.env; then
  warn "conf/im.env 里有值带了引号（↑ 这些行）：env_file 不去引号，java 会拿到带引号的字符串"
fi
if grep -nE '^[A-Z0-9_]+=.*[[:space:]]+#' conf/im.env; then
  warn "conf/im.env 里有行内注释（↑ 这些行）：注释会被当成值的一部分"
fi

# CORS 白名单是 yml 字面列表、环境变量改不动，只能靠外挂配置；生成了就得把开关也打开
if [[ -f app/extra/application-prod-extra.yml ]]; then
  if grep -q '^# *SPRING_CONFIG_ADDITIONAL_LOCATION' conf/im.env; then
    set_kv conf/im.env SPRING_CONFIG_ADDITIONAL_LOCATION "file:/app/extra/application-prod-extra.yml"
    say "已启用 SPRING_CONFIG_ADDITIONAL_LOCATION（CORS 外挂配置）"
  fi
fi

# ==============================================================================
say "⑤ 证书位（缺文件就补自签名，只为让 nginx 起得来）"
mkdir -p certs/live
for d in "$IM_DOMAIN" "$ADMIN_DOMAIN" "$WEBSITE_DOMAIN" "$LIVE_DOMAIN"; do
  [[ -n "$d" ]] || continue
  if [[ ! -f "certs/live/$d/fullchain.pem" ]]; then
    if command -v openssl >/dev/null 2>&1; then
      mkdir -p "certs/live/$d"
      # ★ 失败要当场说出来：set -e 下 openssl 非零退出会静默终止整个脚本（前面所有步骤都白跑，还没有一句报错）
      if ! openssl req -x509 -nodes -newkey rsa:2048 -days 30 \
           -subj "/CN=$d" -addext "subjectAltName=DNS:$d" \
           -keyout "certs/live/$d/privkey.pem" -out "certs/live/$d/fullchain.pem"; then
        warn "为 $d 生成自签名证书失败（上面有 openssl 的报错）：不阻断流程，但 nginx 会因缺 ssl_certificate 起不来，"
        warn "→ 把真证书放进 certs/live/$d/{fullchain.pem,privkey.pem} 后再 docker compose up -d nginx"
      else
        warn "已为 $d 生成 30 天自签名证书（浏览器会警告，上线前换 certbot 的真证书，见指南 §8.4）"
      fi
    else
      die "缺 openssl，且 certs/live/$d/ 没有证书：nginx 会因 ssl_certificate 文件不存在直接退出"
    fi
  fi
done

# ==============================================================================
say "⑥ 补齐周边资料（只有从仓库里跑才有；整目录 scp 上来时这些已经在了）"
if [[ -d ../sql ]]; then
  # 用「有没有 *.sql」而不是「目录是否为空」做判据 —— 目录里有一个进库的 .gitkeep 占位
  if ! ls sql-init/*.sql >/dev/null 2>&1; then
    # ★ 必须重命名成 01_/02_ 前缀：镜像 entrypoint 按文件名字典序执行，
    #   原名 im_data.sql 排在 im_schema.sql 前面（d < s）→ 往不存在的表插数据，建库直接失败
    [[ -f ../sql/im_schema.sql ]] && cp ../sql/im_schema.sql sql-init/01_im_schema.sql && say "已放 sql-init/01_im_schema.sql"
    [[ -f ../sql/im_data.sql   ]] && cp ../sql/im_data.sql   sql-init/02_im_data.sql   && say "已放 sql-init/02_im_data.sql"
    warn "★ sql-init/ 仅在「空卷首次启动」时执行一次（指南 §6.1/§6.3）。库建好后建议：
      注释掉 compose 里那行 ./sql-init 挂载并清空该目录 —— 01_im_schema.sql 开头有 37 条 DROP TABLE IF EXISTS，
      一旦卷被重建就会静默清库"
  fi
fi
if [[ -f ../electron/live-retention.js && ! -f scripts/live-retention.js ]]; then
  cp ../electron/live-retention.js scripts/live-retention.js; say "已拷 live-retention.js（开直播时把 compose 里那段 service 取消注释）"
fi
for app in im-ui im-admin-ui im-website; do
  [[ -f "web/$app/index.html" ]] || warn "web/$app 里没有 index.html：这一站现在会 404。CI 产物或本地 npm run build 的 dist 拷进来即可"
done

# ==============================================================================
say "⑦ 静态校验 compose（不启动、不联网拉镜像）"
if command -v docker >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    docker compose config -q && say "docker compose config -q 通过"
  else
    warn "本机 docker 没有 compose 子命令（这脚本跑在部署机上时不该出现）"
  fi
else
  warn "本机没有 docker：跳过 compose 校验（这台只是写文件的机器时，属正常）"
fi

say "完成。下一步："
cat <<'EOF'
  docker compose pull            # 从 CI 推的镜像拉；若 tag 是 local/... 就 docker compose build im-server
  docker compose up -d           # 首次会等 mysql initdb（约 1~2 分钟）
  docker compose ps              # 全 healthy 再往外看
  bash scripts/health.sh         # 五层检查（指南 §11.1）
EOF
