#!/usr/bin/env bash
# ==============================================================================
# 把本地签好的叶子证书推到部署机并 reload nginx。与 gen-selfca.sh 配对：
# 根私钥只留在签发机，部署机上永远只有叶子（沦陷也签不出第二张受信任证书）。
#
#   bash scripts/push-cert.sh ubuntu@192.168.83.130                    # 推到 /opt/im-docker
#   bash scripts/push-cert.sh ubuntu@192.168.83.130 /srv/im-docker     # 指定部署目录
#   bash scripts/push-cert.sh ubuntu@192.168.83.130 --dry-run          # 只看会推哪些文件，不动远端
#   bash scripts/push-cert.sh ubuntu@192.168.83.130 --no-reload        # 只放文件，生效由你自己控制
#
# 它做四件事：① 逐个域名 scp 到 certs/live/<域名>/（远端权限 644/600）；② 写 certs/.managed-by-push 标记，
# 让部署机上的 bootstrap.sh ⑤ 知道「证书由这边供」，不再现场签自签覆盖掉；③ 远端 nginx -t 后 reload；
# ④ 首次出现的新域名目录会 restart nginx —— ★ bind mount 下新建目录项不一定穿透 `:ro` 挂载，只 reload 有时读不到。
#
# 私钥过网只在 ssh 通道里（scp 走 22），内网/公网都算安全；但别改用 http 上传证书目录，
# web/ 是 nginx 静态根，放进去就等于把私钥公开挂到网站上。
# 免密前提：ssh 密钥登录可用（ssh-copy-id）。密码交互在 cron 里会静默失败，装 cron 前先手动跑通一次。
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$PWD"
LEAF_DIR="certs/live"; CA_DIR="certs/ca"
say()  { printf '\033[1;36m==\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

TARGET=""; DEPLOY_DIR="/opt/im-docker"; DRY=0; NO_RELOAD=0
for a in "$@"; do
  case "$a" in
    --dry-run)   DRY=1 ;;
    --no-reload) NO_RELOAD=1 ;;
    -*) die "不认识参数 $a（用法见文件头注释）" ;;
    *) if [[ -z "$TARGET" ]]; then TARGET="$a"; else DEPLOY_DIR="$a"; fi ;;
  esac
done
[[ -n "$TARGET" ]] || die "用法：bash scripts/push-cert.sh user@host [部署目录]（默认 /opt/im-docker）"
[[ "$TARGET" == *"@"* ]] || warn "TARGET 里没有 user@（收到 $TARGET）：会走 ssh 默认用户，cron 里容易和你手动跑时不一致"

# ---------- 待推清单：本地 certs/live/<域名>/{fullchain,privkey} ----------
[[ -d "$LEAF_DIR" ]] || die "本地没有 $LEAF_DIR/：先在签发机跑 bash scripts/gen-selfca.sh"
NAMES=(); SKIPPED=()
for dir in "$LEAF_DIR"/*/; do
  [[ -d "$dir" ]] || continue
  if [[ -f "${dir}fullchain.pem" && -f "${dir}privkey.pem" ]]; then
    NAMES+=("$(basename "$dir")")
  else
    # ★ 半套也要吭声：静默跳过时推送显示全绿，远端那站的证书其实一直没更新
    SKIPPED+=("$(basename "$dir")")
  fi
done
(( ${#NAMES[@]} > 0 )) || die "$LEAF_DIR/ 里一个完整证书对都没有（要 fullchain.pem + privkey.pem 同目录）"
for d in ${SKIPPED[@]+"${SKIPPED[@]}"}; do
  warn "$d 只有一半（要 fullchain.pem + privkey.pem 都在）：这一站本次不推。要么在签发机 gen-selfca.sh --force 重签，要么把这个残目录删掉"
done

for d in "${NAMES[@]}"; do
  if command -v openssl >/dev/null 2>&1; then
    exp="$(openssl x509 -in "$LEAF_DIR/$d/fullchain.pem" -noout -enddate 2>/dev/null | cut -d= -f2)"
    subj="$(openssl x509 -in "$LEAF_DIR/$d/fullchain.pem" -noout -subject 2>/dev/null | sed 's/^subject=//')"
    iss="$(openssl x509 -in "$LEAF_DIR/$d/fullchain.pem" -noout -issuer 2>/dev/null | sed 's/^issuer=//')"
    say "$d → 有效期至 ${exp:-?}｜颁发者 $iss"
    # ★ subject == issuer 就是 bootstrap 现场签的那张单张自签，推上去客户端照样不信任
    [[ "$subj" != "$iss" ]] || warn "$d 是单张自签（subject == issuer），不是两级 CA 签的叶子：先跑 bash scripts/gen-selfca.sh --force 重签"
  else
    say "$d → 本机无 openssl，跳过有效期与颁发者检查"
  fi
done

if (( DRY )); then
  say "--dry-run：只列清单，未连接 $TARGET"
  for d in "${NAMES[@]}"; do echo "    $LEAF_DIR/$d/{fullchain.pem,privkey.pem}  →  $TARGET:$DEPLOY_DIR/$LEAF_DIR/$d/"; done
  exit 0
fi

# ---------- 连通性 ----------
command -v ssh >/dev/null 2>&1 && command -v scp >/dev/null 2>&1 || die "本机没有 ssh/scp（Windows: 可选功能里装 OpenSSH 客户端）"
ssh -o BatchMode=yes -o ConnectTimeout=8 "$TARGET" true 2>/dev/null \
  || die "ssh 到 $TARGET 不通（免密没配好、主机不可达或端口不对）。先手动 ssh 通了再回来，cron 里遇到同样问题会静默失败"

# ---------- 首次的新域名目录要 restart，其余 reload 就够 ----------
NEED_RESTART=0
for d in "${NAMES[@]}"; do
  ssh "$TARGET" "test -d '$DEPLOY_DIR/$LEAF_DIR/$d/fullchain.pem'" 2>/dev/null \
    || { warn "远端还没有 $LEAF_DIR/$d/ 这个目录：首次建目录，reload 可能看不见新目录项，稍后改用 restart"; NEED_RESTART=1; }
done

for d in "${NAMES[@]}"; do
  ssh "$TARGET" "mkdir -p '$DEPLOY_DIR/$LEAF_DIR/$d' && chmod 755 '$DEPLOY_DIR/$LEAF_DIR' '$DEPLOY_DIR/$LEAF_DIR/$d'"
  scp -q "$LEAF_DIR/$d/fullchain.pem" "$LEAF_DIR/$d/privkey.pem" "$TARGET:$DEPLOY_DIR/$LEAF_DIR/$d/"
  ssh "$TARGET" "chmod 644 '$DEPLOY_DIR/$LEAF_DIR/$d/fullchain.pem' && chmod 600 '$DEPLOY_DIR/$LEAF_DIR/$d/privkey.pem'"
  say "已推 $d"
done

# ★ 这个标记是给 bootstrap.sh ⑤ 看的：见到它就只体检不签发，免得部署机哪天自己签一张单张自签把真证书盖了
ssh "$TARGET" "printf '# 证书由签发机 scripts/gen-selfca.sh + push-cert.sh 供应，本机不要再自签（见指南 §8.4）\n' > '$DEPLOY_DIR/certs/.managed-by-push'"

if (( NO_RELOAD )); then
  warn "--no-reload：文件已落远端，但 nginx 还在用旧证书（内存里那份不会自己换）"
  exit 0
fi

# ---------- 远端生效：先验配置再 reload，验不过就停下报原文 ----------
if ! ssh "$TARGET" "cd '$DEPLOY_DIR' && docker compose exec -T nginx nginx -t" ; then
  die "远端 nginx -t 没过（上面是原文）。证书文件已推过去，但没 reload —— 别继续，先按报错处理"
fi
if (( NEED_RESTART )); then
  ssh "$TARGET" "cd '$DEPLOY_DIR' && docker compose restart nginx" && say "nginx 已 restart（新增了域名目录）"
else
  ssh "$TARGET" "cd '$DEPLOY_DIR' && docker compose exec -T nginx nginx -s reload" && say "nginx 已 reload"
fi

say "验证（在能访问部署机的机器上跑，issuer 必须是本地根 CA 的 CN）："
cat <<EOF
  echo | openssl s_client -connect ${TARGET#*@}:443 -servername ${NAMES[0]} 2>/dev/null \\
    | openssl x509 -noout -issuer -subject -dates
  # 浏览器侧：地址栏锁形图标 → 连接安全 → 证书查看器，「颁发者」应是 CN=$( [[ -f "$CA_DIR/rootCA.pem" ]] && openssl x509 -in "$CA_DIR/rootCA.pem" -noout -subject | sed 's#.*/CN = ##' || echo 'IM Local Root CA' )
EOF
