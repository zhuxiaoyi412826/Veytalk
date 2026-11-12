#!/usr/bin/env bash
# ==============================================================================
# sql-init 护栏：把「卷被重建时静默清库」这个能力从这台机器上摘掉。
#
#   bash scripts/guard-sql-init.sh                    # 上锁：建库脚本挪进 sql-init.disabled/ + 写标记
#   bash scripts/guard-sql-init.sh --status           # 看现在锁着没有
#   bash scripts/guard-sql-init.sh --unlock           # 解锁（确实要重建库时）：挪回来 + 删标记
#   bash scripts/guard-sql-init.sh --comment-compose  # 上锁，并顺手注释掉 compose 里那行挂载（先备份 yml）
#
# 为什么值得做（三条事实）：
#  ① sql-init/ 挂在 mysql:/docker-entrypoint-initdb.d:ro，只在「空卷首次启动」时执行；而
#     01_im_schema.sql 开头有 43 条 DROP TABLE IF EXISTS —— 卷一旦被重建（docker compose down -v、
#     迁移时漏了卷、或有人给 im-docker.service 的 ExecStop 补上 -v），43 张表先删后建，数据静默清零。
#  ② 光把目录清空不够：bootstrap.sh ⑥ 的判据是「sql-init/ 里没有 *.sql 就从 ../sql 拷一份」，
#     重跑一次 bootstrap 护栏就被悄悄撤销。所以这里落一个 sql-init/.disabled 标记，⑥ 见到就不拷。
#  ③ 挪走而不是删掉：解锁要能原样恢复，且 backup.sh 的 conf 备份会把 sql-init.disabled 一起打包。
#
# ★ 锁是「这台机器」的状态，不随仓库走：sql-init/ 整个目录被 .gitignore 排除，所以新机部署时天然无锁、
#   能正常建库；建完库在这台上跑一次本脚本即可。
# ★ 本脚本不碰数据库、不碰卷、不重启容器：它只挪文件与（可选）注释一行 yml。
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$PWD"
INIT_DIR="sql-init"
PARK_DIR="sql-init.disabled"
MARK="$INIT_DIR/.disabled"
COMPOSE="docker-compose.yml"
STAMP="$(date +%F-%H%M)"
# entrypoint 只认这三类后缀，别的文件它自己就 ignoring，不用挪
PATTERNS=(-name '*.sql' -o -name '*.sql.gz' -o -name '*.sh')
say()  { printf '\033[1;36m==\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

list_init() { find "$INIT_DIR" -maxdepth 1 -type f \( "${PATTERNS[@]}" \) -printf '    %f\n' 2>/dev/null | sort; }
list_park() { find "$PARK_DIR" -maxdepth 1 -type f \( "${PATTERNS[@]}" \) -printf '    %f\n' 2>/dev/null | sort; }
compose_state() {
  # 输出 live / commented / absent 三态之一
  if grep -qE "^[[:space:]]*-[[:space:]]*\./$INIT_DIR:/docker-entrypoint-initdb\.d:ro[[:space:]]*$" "$COMPOSE" 2>/dev/null; then
    echo live
  elif grep -qE "^[[:space:]]*#.*\./$INIT_DIR:/docker-entrypoint-initdb\.d" "$COMPOSE" 2>/dev/null; then
    echo commented
  else
    echo absent
  fi
}

do_status() {
  say "sql-init 护栏状态"
  if [[ -f "$MARK" ]]; then
    echo "  已上锁（$MARK）："; sed 's/^/    /' "$MARK"
  else
    echo "  未上锁：卷被重建时 sql-init/ 里的建库脚本会照跑（含 43 条 DROP TABLE）"
  fi
  echo "  $INIT_DIR/ 里会被 entrypoint 执行的文件："; list_init || true
  [[ -n "$(list_init)" ]] || echo "    （无）"
  echo "  $PARK_DIR/（挪走的）："; list_park || true
  [[ -n "$(list_park)" ]] || echo "    （无）"
  echo "  compose 里那行挂载：$(compose_state)"
}

do_lock() {
  if [[ -f "$MARK" ]]; then
    say "已经上锁了，不重复操作（要改先看 --status，要撤先 --unlock）"
    return 0
  fi
  [[ -d "$INIT_DIR" ]] || die "没有 $INIT_DIR/ 目录：这台机器还没跑过 bootstrap.sh，没什么可锁的"
  mkdir -p "$PARK_DIR"
  local moved=0 f base
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    base="$(basename "$f")"
    mv -f "$f" "$PARK_DIR/$base"
    say "已挪走 $INIT_DIR/$base -> $PARK_DIR/$base"
    moved=$((moved + 1))
  done < <(find "$INIT_DIR" -maxdepth 1 -type f \( "${PATTERNS[@]}" \) 2>/dev/null | sort)
  cat > "$MARK" <<EOF
上锁时间：$STAMP
含义：本机的 sql-init/ 已被清空（建库脚本挪到 ../$PARK_DIR/），mysql 容器即使碰上「空卷首次启动」
      也没有脚本可跑，不会执行 im_schema.sql 里那 43 条 DROP TABLE IF EXISTS。
为什么：docker compose down -v、迁移漏卷、ExecStop 被加上 -v，任何一种都会让卷重建 = 静默清库。
解锁：  bash scripts/guard-sql-init.sh --unlock   （确实要重建库时；解锁后记得再锁回来）
EOF
  if (( moved == 0 )); then
    warn "$INIT_DIR/ 里本来就没有建库脚本（可能已经手工清过）。标记照写 —— 否则重跑 bootstrap.sh ⑥ 会再从 ../sql 拷一份回来，护栏等于没上"
  fi
  say "已上锁：$INIT_DIR/ 现在对 mysql entrypoint 是空的"
  case "$(compose_state)" in
    live)
      warn "compose 里那行挂载还活着（$(grep -nE "^[[:space:]]*-[[:space:]]*\./$INIT_DIR:" "$COMPOSE" | head -1)）"
      warn "目录空了其实已经安全；要再加一道保险就跑：bash scripts/guard-sql-init.sh --comment-compose"
      ;;
    commented) say "compose 里那行挂载已注释，双保险到位" ;;
    absent)    warn "compose 里找不到 ./sql-init 挂载行：确认这份 yml 是不是本项目的（$COMPOSE）" ;;
  esac
}

do_unlock() {
  [[ -d "$PARK_DIR" ]] || { [[ -f "$MARK" ]] || die "本来就没上锁（没有 $MARK，也没有 $PARK_DIR/）"; }
  mkdir -p "$INIT_DIR"
  local back=0 f base
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    base="$(basename "$f")"
    [[ -e "$INIT_DIR/$base" ]] && die "$INIT_DIR/$base 已经存在，不敢覆盖：先手工处理再解锁"
    mv -f "$f" "$INIT_DIR/$base"
    say "已挪回 $INIT_DIR/$base"
    back=$((back + 1))
  done < <(find "$PARK_DIR" -maxdepth 1 -type f \( "${PATTERNS[@]}" \) 2>/dev/null | sort)
  rm -f "$MARK"
  rmdir "$PARK_DIR" 2>/dev/null || true
  say "已解锁（挪回 $back 个文件）"
  warn "★ 解锁后 bootstrap.sh ⑥ 又会往 $INIT_DIR/ 补建库脚本，这是预期的"
  if [[ "$(compose_state)" == commented ]]; then
    warn "compose 里那行挂载还是注释状态，卷重建时不会执行建库脚本。要恢复挂载请手工取消注释（或改回来后再 --comment-compose 上锁）"
  fi
}

do_comment_compose() {
  [[ -f "$COMPOSE" ]] || die "找不到 $COMPOSE（当前目录 $ROOT）"
  local n
  n="$(grep -cE "^[[:space:]]*-[[:space:]]*\./$INIT_DIR:/docker-entrypoint-initdb\.d:ro[[:space:]]*$" "$COMPOSE" || true)"
  if [[ "$(compose_state)" == commented ]]; then say "compose 里那行已经是注释状态，不用改"; return 0; fi
  (( n == 1 )) || die "$COMPOSE 里匹配到 $n 行挂载（期望 1 行）：不敢自动改，请手工注释后重跑 --status 确认"
  cp -f "$COMPOSE" "$COMPOSE.bak-$STAMP"
  say "已备份 $COMPOSE -> $COMPOSE.bak-$STAMP"
  # ★ 替换里必须把 "- " 写回去：compose 的 volumes 是 YAML 列表项，少了这个短横线，
  #   日后手工取消注释（删掉 "# "）得到的就不是合法 YAML 了
  sed -i -E "s|^([[:space:]]*)-[[:space:]]*(\./$INIT_DIR:/docker-entrypoint-initdb\.d:ro)[[:space:]]*$|\1# - \2   # 建库完成后由 guard-sql-init.sh 注释（$STAMP）：卷重建时不再静默清库|" "$COMPOSE"
  [[ "$(compose_state)" == commented ]] || die "改完自检不过：那行没变成注释状态。已备份在 $COMPOSE.bak-$STAMP，可 cp 回去"
  say "compose 里那行挂载已注释（双保险到位）"
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    docker compose config -q && say "docker compose config -q 通过"
  else
    warn "本机没有 docker compose，跳过静态校验（这脚本跑在部署机上时不该出现）"
  fi
  warn "★ 生效：下次 docker compose up -d 时 mysql 容器会因挂载变化被重建（数据在命名卷 mysql-data 里，不受影响）"
}

ACTION="lock"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --status)           ACTION="status"; shift ;;
    --unlock)           ACTION="unlock"; shift ;;
    --comment-compose)  ACTION="lock+comment"; shift ;;
    -*)                 die "不认识参数 $1（用法见文件头注释）" ;;
    *)                  die "本脚本不接位置参数，收到「$1」" ;;
  esac
done

case "$ACTION" in
  status)       do_status ;;
  unlock)       do_unlock; do_status ;;
  lock)         do_lock; do_status ;;
  lock+comment) do_lock; do_comment_compose; do_status ;;
esac
