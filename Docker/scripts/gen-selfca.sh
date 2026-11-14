#!/usr/bin/env bash
# ==============================================================================
# 本地根 CA + 叶子证书签发（内网测试 HTTPS 的来源）。对应 Docker部署指南.md §8.4、
# Ubuntu镜像HTTPS部署教程.md 方案 B。★ 这一份跑在【签发机】（开发机 Git Bash / WSL / 一台内网 Linux），
# 根私钥不进部署机 —— 部署机上只有 push-cert.sh 推过去的叶子，服务器被拿下也签不出第二张受信任证书。
#
#   bash scripts/gen-selfca.sh                          # 建根（已存在就复用）+ 按 .env 三个域名各签一张叶子
#   bash scripts/gen-selfca.sh --ip 192.168.83.130      # ★ 部署机 IP 进 SAN：手机按 IP 直连时必须有这一条
#   bash scripts/gen-selfca.sh --ip auto                # 采签发机自己的 IP（只在你从签发机访问时才有意义）
#   bash scripts/gen-selfca.sh im.test nip.io           # 位置参数＝追加进 SAN 的域名或 IP（hosts 假域名、nip.io 都行）
#   bash scripts/gen-selfca.sh --leaf-days 90           # 叶子有效期，默认 30；上限 825（苹果）
#   bash scripts/gen-selfca.sh --force                  # 无视剩余有效期强制重签（根不动 → ★ 客户端不用重装）
#   bash scripts/gen-selfca.sh --fresh-ca               # ★ 重建根：所有装过旧根的电脑/手机都要删旧重装，最后手段
#   bash scripts/gen-selfca.sh --reload                 # 签完顺手 reload 本机 nginx（只在部署机上跑时有意义）
#   bash scripts/gen-selfca.sh --install-cron ubuntu@192.168.83.130 /opt/im-docker
#                                                       # 每天一次：到期前重签 → 推送 → 远端 reload
#
# 两个反直觉但刻意的点：
#  ① 叶子只签 30 天而不是 820 天：根装一次管 10 年，叶子的作用只剩「证明这台机器持有根的签名」，
#     短有效期＝私钥泄露窗口小，且逼着续期链路真的被跑通（从没验证过的自动续期等于没有）。
#     配 cron 每天跑 + 剩余不足 10 天才重签，所以签发机停机两周也不用补证书，服务不会断。
#  ② 不在部署机上跑本脚本：那样根私钥与 nginx 同机，内网一台机器沦陷＝整个测试网的信任被拿走。
#     部署机上要现场凑一张，那是 bootstrap.sh ⑤ 的单张自签（浏览器告警，只够冒烟）。
#
# ★ 产物路径沿用现有约定：certs/live/<域名>/{fullchain.pem,privkey.pem} —— nginx 配置与 compose 挂载一行都不用改。
# ★ 跑在哪都行（Linux / WSL / macOS / Windows 的 Git Bash），但【不要】跑在部署机上，理由见上面②。
# ★ 只签 IM / ADMIN / WEBSITE：im.conf 里只有这三个有 443 server 块。LIVE_DOMAIN 不签（它只出现在 80 的
#   server_name，推流口 8088 默认注释且不带 TLS），多一张文件只会让人误以为要装第四张根。
# ★ 能外传的只有 certs/ca/rootCA.pem；rootCA.key 权限 600，且 .gitignore 里 `Docker/certs/*` 已把整目录排除。
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$PWD"
CA_DIR="certs/ca"; LEAF_DIR="certs/live"
CA_CN="IM Local Root CA"
CA_DAYS=3650
LEAF_DAYS=30
RENEW_AHEAD=$((10 * 86400))          # 剩余不足 10 天才重签（cron 天天跑，所以永远赶在到期前）
STAMP="$(date +%Y%m%d-%H%M%S)"
say()  { printf '\033[1;36m==\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

command -v openssl >/dev/null 2>&1 || die "缺 openssl（Ubuntu: apt install openssl；Windows: 装 Git for Windows 后在 Git Bash 里跑本脚本）"

# ---------- 参数 ----------
FORCE=0; FRESH_CA=0; IP_AUTO=0; RELOAD=0
SAN_DNS=(); SAN_IP=(); RAW_ARGV=(); CRON_TARGET=""; CRON_DIR=""
is_ipv4() { [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)  FORCE=1;  RAW_ARGV+=("$1") ;;
    --fresh-ca) FRESH_CA=1; RAW_ARGV+=("$1") ;;
    --reload) RELOAD=1; RAW_ARGV+=("$1") ;;
    --ip) [[ $# -ge 2 ]] || die "--ip 后面要跟地址（或 auto）"
          if [[ "$2" == auto ]]; then IP_AUTO=1; else SAN_IP+=("$2"); fi
          RAW_ARGV+=("$1" "$2"); shift ;;
    --leaf-days) [[ $# -ge 2 ]] || die "--leaf-days 后面要跟天数"
          LEAF_DAYS="$2"; RAW_ARGV+=("$1" "$2"); shift ;;
    --install-cron) [[ $# -ge 3 ]] || die "--install-cron 要两个参数：user@host 与部署目录，例：ubuntu@192.168.83.130 /opt/im-docker"
          CRON_TARGET="$2"; CRON_DIR="$3"; shift 2 ;;   # ★ 不写进 RAW_ARGV：否则 cron 每天又重装一遍自己
    -*) die "不认识参数 $1（用法见文件头注释）" ;;
    *) if is_ipv4 "$1"; then SAN_IP+=("$1"); else SAN_DNS+=("$1"); fi; RAW_ARGV+=("$1") ;;
  esac
  shift
done
[[ "$LEAF_DAYS" =~ ^[0-9]+$ ]] || die "--leaf-days 要是正整数，收到：$LEAF_DAYS"
(( LEAF_DAYS >= 1 && LEAF_DAYS <= 825 )) || die "叶子 $LEAF_DAYS 天不合理：1~825（苹果对 TLS 服务器证书的上限，超了 iOS/macOS 直接拒）"

# ---------- 域名读 .env（不 source：值里可能带 $ ` " 这些会让 shell 重新解释的字符） ----------
# ★ 先剔 UTF-8 BOM 再 grep，否则 Windows 上记事本/PowerShell 存的 .env 第一行（常常就是 IM_DOMAIN）会被整行漏读：
#   grep "^IM_DOMAIN=" 碰不到前面多三个字节的那一行，表现为「域名没填」，而肉眼去看 .env 是填好的
read_env() {
  local line
  line="$(sed '1s/^\xEF\xBB\xBF//' .env | grep -m1 "^$1=" || true)"
  [[ -n "$line" ]] || { printf ''; return; }
  printf '%s' "${line#*=}" | tr -d '\r'
}
[[ -f .env ]] || die "缺 .env（三个域名要从里面读）：先 cp .env.example .env 填好，再跑本脚本"
DOMAINS=()
for k in IM_DOMAIN ADMIN_DOMAIN WEBSITE_DOMAIN; do
  v="$(read_env "$k")"
  if [[ -z "$v" || "$v" == *"<"* ]]; then warn "$k 没填或仍是占位符，这一站跳过"; continue; fi
  DOMAINS+=("$v")
done
(( ${#DOMAINS[@]} > 0 )) || die "IM_DOMAIN / ADMIN_DOMAIN / WEBSITE_DOMAIN 三个都没填，没有可签的域名"

if (( IP_AUTO )); then
  # hostname -I 是 Linux，Git Bash 里落到 ipconfig。★ 这些采的是【签发机】的 IP：客户端连的是部署机，
  # 要用 --ip <部署机IP> 明确加进来，别指望自动采到（这条坑在 dev 端 gen-cert.cjs 上也踩过一轮）。
  while read -r ip; do
    [[ -n "$ip" ]] && SAN_IP+=("$ip")
  done < <( { hostname -I 2>/dev/null || ipconfig 2>/dev/null || true; } \
            | tr ' \r' '\n\n' | grep -Eo '^([0-9]{1,3}\.){3}[0-9]{1,3}$' | grep -v '^127\.' | sort -u | head -5 )
fi

san_for() {
  local san="DNS:$1" n
  for n in ${SAN_DNS[@]+"${SAN_DNS[@]}"}; do san="$san,DNS:$n"; done
  for n in ${SAN_IP[@]+"${SAN_IP[@]}"}; do san="$san,IP:$n"; done
  printf '%s' "$san"
}

# 剩余整天数。★ openssl 的 notAfter 是 "Mar  5 12:00:00 2027 GMT" 这种英年月日格式：
# GNU date 用 -d，BSD/macOS 没有 -d 要换 -j -f，两条都不行就返回空（宁可少一句话也不要脚本在这算挂）
left_days() {
  local ep iso
  ep="$(openssl x509 -in "$1" -noout -enddate 2>/dev/null | cut -d= -f2)"
  [[ -n "$ep" ]] || { printf ''; return; }
  iso="$(date -d "$ep" +%s 2>/dev/null || date -j -f '%b %e %T %Y %Z' "$ep" +%s 2>/dev/null || true)"
  [[ -n "$iso" ]] && printf '%s' $(( (iso - $(date +%s)) / 86400 ))
}

# ---------- 根 CA：已存在且没临近到期就复用 ----------
mkdir -p "$CA_DIR" "$LEAF_DIR"
TMP="$(mktemp -d "$ROOT/certs/.gen.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
CA_CREATED=0
if (( FRESH_CA )) && [[ -f "$CA_DIR/rootCA.pem" ]]; then
  mkdir -p "$CA_DIR/old"
  mv -f "$CA_DIR/rootCA.pem" "$CA_DIR/old/rootCA.$STAMP.pem"
  [[ -f "$CA_DIR/rootCA.key" ]] && mv -f "$CA_DIR/rootCA.key" "$CA_DIR/old/rootCA.$STAMP.key"
  warn "旧根挪进了 $CA_DIR/old/。★ 重建根＝所有装过旧根的电脑/手机都要删旧重装，没装的机器现在会立刻报错"
fi
if [[ ! -f "$CA_DIR/rootCA.pem" || ! -f "$CA_DIR/rootCA.key" ]] \
   || ! openssl x509 -in "$CA_DIR/rootCA.pem" -checkend "$((90 * 86400))" -noout >/dev/null 2>&1; then
  say "签发根 CA：$CA_CN（$CA_DAYS 天）"
  # ★ pathlen:0 ＝只能签叶子、签不出下一级 CA：万一私钥泄露，危害圈在这张证书本身
  cat > "$TMP/ca.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
C = CN
O = IM Local Test
CN = $CA_CN
[v3_ca]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF
  if ! openssl req -x509 -newkey rsa:4096 -sha256 -days "$CA_DAYS" -nodes \
       -keyout "$TMP/rootCA.key" -out "$TMP/rootCA.pem" -config "$TMP/ca.cnf"; then
    die "根 CA 签发失败（上面有 openssl 的报错）。注意：Git Bash 里 RSA-4096 要几秒，别以为卡住了"
  fi
  mv -f "$TMP/rootCA.pem" "$CA_DIR/rootCA.pem"
  mv -f "$TMP/rootCA.key" "$CA_DIR/rootCA.key"
  chmod 644 "$CA_DIR/rootCA.pem"; chmod 600 "$CA_DIR/rootCA.key"
  CA_CREATED=1
  warn "★ 把 $CA_DIR/rootCA.pem 发给每台内网测试机装进「受信任的根证书颁发机构」，10 年内不用再动；rootCA.key 绝不外传"
else
  say "根 CA：复用已有（换叶子不需要重装客户端）"
fi

# ---------- 逐个域名签叶子 ----------
sign_leaf() {
  local d="$1" san dir tmp
  san="$(san_for "$d")"
  dir="$LEAF_DIR/$d"; tmp="$TMP/$d"; mkdir -p "$dir" "$tmp"
  cat > "$tmp/leaf.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no
[dn]
CN = $d
[v3_leaf]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $san
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
EOF
  openssl req -new -newkey rsa:2048 -sha256 -nodes -keyout "$tmp/privkey.pem" -out "$tmp/leaf.csr" \
    -config "$tmp/leaf.cnf" 2>/dev/null \
    || die "为 $d 生成 CSR 失败"
  openssl x509 -req -in "$tmp/leaf.csr" -CA "$CA_DIR/rootCA.pem" -CAkey "$CA_DIR/rootCA.key" -CAcreateserial \
    -days "$LEAF_DAYS" -sha256 -extfile "$tmp/leaf.cnf" -extensions v3_leaf -out "$tmp/leaf.pem" 2>/dev/null \
    || die "用根 CA 签 $d 失败（若刚跑过 --fresh-ca，旧 rootCA.srl 会与新根串号，删掉 certs/ca/rootCA.srl 再跑）"
  # fullchain = 叶子 + 根：链里带自签根合法、正常客户端会忽略，但部分老安卓/WebView 只吃完整链，多带一张更稳
  cat "$tmp/leaf.pem" "$CA_DIR/rootCA.pem" > "$tmp/fullchain.pem"
  # ★ 同盘 mv 才是原子替换：mktemp 若落 /tmp 就跨设备了，mv 退化成先删后拷，nginx 可能读到半截文件
  mv -f "$tmp/fullchain.pem" "$dir/fullchain.pem"
  mv -f "$tmp/privkey.pem" "$dir/privkey.pem"
  chmod 644 "$dir/fullchain.pem"; chmod 600 "$dir/privkey.pem"
  rm -rf "$tmp"
  say "已签 $d：$LEAF_DAYS 天｜SAN $san"
}

REISSUED=0
for d in ${DOMAINS[@]+"${DOMAINS[@]}"}; do
  f="$LEAF_DIR/$d/fullchain.pem"
  if (( FORCE == 0 )) && [[ -f "$f" && -f "$LEAF_DIR/$d/privkey.pem" ]] \
     && openssl x509 -in "$f" -checkend "$RENEW_AHEAD" -noout >/dev/null 2>&1; then
    left="$(left_days "$f")"
    say "$d：剩余 ${left:-?} 天，还没到 $((RENEW_AHEAD / 86400)) 天的重签线，跳过（要改 SAN 加 --force）"
    continue
  fi
  sign_leaf "$d"
  REISSUED=1
done
(( REISSUED || CA_CREATED )) || say "什么都没改：根与叶子都还新着（这是幂等的正常结果，cron 天天跑也是这个输出）"

# ---------- 本机 reload（可选） ----------
if (( REISSUED && RELOAD )); then
  if command -v docker >/dev/null 2>&1 && docker compose exec -T nginx nginx -t >/dev/null 2>&1; then
    docker compose exec -T nginx nginx -s reload && say "nginx 已 reload"
  else
    warn "--reload 没生效：nginx 容器没在跑或 nginx -t 没过。证书已落盘，栈起来后自然生效"
  fi
fi

# ---------- 装 cron ----------
if [[ -n "$CRON_TARGET" ]]; then
  [[ -x "$ROOT/scripts/push-cert.sh" || -f "$ROOT/scripts/push-cert.sh" ]] || die "缺 scripts/push-cert.sh（推送与远端 reload 靠它）"
  command -v crontab >/dev/null 2>&1 || die "本机没有 crontab（Linux: apt install cron）。Windows 用任务计划程序，见脚本末尾打印的 schtasks 写法"
  ARG_STR=""; (( ${#RAW_ARGV[@]} )) && ARG_STR="${RAW_ARGV[*]} "   # ★ 末尾这个空格只在有参数时才加，否则 cron 行里会空两格
  line="30 5 * * * cd $ROOT && bash scripts/gen-selfca.sh ${ARG_STR}&& bash scripts/push-cert.sh $CRON_TARGET $CRON_DIR >> $ROOT/logs/selfca-cron.log 2>&1"
  # ★ 剔除旧行时就地做完：grep -v 在没有匹配时返回 1，set -e 会当场掐死这一步，所以必须 || true
  # ★ 老写法先 printf 再 grep，crontab 本来是空的就会在顶部留下一个空行（首次装必现）——现在空就不输出
  cur="$(crontab -l 2>/dev/null | { grep -vF 'scripts/gen-selfca.sh' || true; })"
  { [[ -n "$cur" ]] && printf '%s\n' "$cur"; printf '%s\n' "$line"; } | crontab -
  say "cron 已装（每天 05:30，幂等：重复跑 --install-cron 只留一行）"
  crontab -l | grep -nF 'scripts/gen-selfca.sh'
  [[ -d logs ]] || mkdir -p logs
  echo
  say "Windows 上等价的任务计划程序写法（PowerShell 管理员）："
  cat <<EOF
  schtasks /Create /TN im-selfca /SC DAILY /ST 05:30 /RL HIGHEST ^
    /TR "'C:\\Program Files\\Git\\bin\\bash.exe' -lc 'cd /d/你的路径/Docker && bash scripts/gen-selfca.sh ${ARG_STR}&& bash scripts/push-cert.sh $CRON_TARGET $CRON_DIR'"
EOF
fi

# ---------- 收尾提示 ----------
PUSH_HINT="bash scripts/push-cert.sh ${CRON_TARGET:-user@部署机IP}${CRON_DIR:+ $CRON_DIR}"
echo
say "下一步"
cat <<EOF
  ① 装根（每台内网测试机一次，10 年不用再管；只传 .pem，rootCA.key 一步都不许离开这台机器）
       根证书就在本机：$(pwd -W 2>/dev/null || echo "$ROOT")/certs/ca/rootCA.pem
       取走它：scp <签发机用户>@<签发机地址>:$CA_DIR/rootCA.pem ./     （主机名 $(hostname 2>/dev/null || echo '<未取到>')）
     Windows : certutil -addstore -f root rootCA.pem        （或双击→安装证书→本地计算机→受信任的根证书颁发机构）
     iOS     : 设置→已下载的描述文件→安装，然后 ★必须★ 到 设置→通用→关于本机→证书信任设置 把开关打开
     Android : 设置→安全→加密与凭据→安装证书→CA 证书（App 内 WebView 默认不吃用户级 CA，浏览器可用）
  ② 推送到部署机并 reload（服务器上会写 certs/.managed-by-push，此后 bootstrap.sh ⑤ 就不再自己签自签证书）
       $PUSH_HINT
  ③ 验证真的换成根签发的了：
       openssl x509 -in $LEAF_DIR/${DOMAINS[0]}/fullchain.pem -noout -issuer -enddate
     issuer 应是 CN=$CA_CN；若还是 subject==issuer，说明这张仍是 bootstrap 的单张自签
EOF
(( ${#SAN_IP[@]} )) || warn "SAN 里一个 IP 都没有：手机若用 http://<部署机IP>/ 直连会报名称不匹配，加一条 --ip <部署机IP> 重跑"
