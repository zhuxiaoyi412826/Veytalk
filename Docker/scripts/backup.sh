#!/usr/bin/env bash
# ==============================================================================
# 一键备份（对应 Docker部署指南.md §10.1）：数据库 + 对象存储 + 编排配置 + Redis 快照。
#
#   bash scripts/backup.sh              # 全备
#   bash scripts/backup.sh mysql        # 只备数据库（最常用，也最适合放 cron）
#   bash scripts/backup.sh --install-cron          # 装每天 03:30 的 mysql 备份（幂等，重跑只留一行）
#   bash scripts/backup.sh --install-cron all      # ★ all 含 minio，会 stop/start minio 服务，别在业务高峰挂
#
# 产物都落 ./backup/。两个旋钮读 .env：BACKUP_KEEP_DAYS（默认 14）、BACKUP_SYNC_DIR（默认空）。
# ★ 备份只落在同一块盘上等于没备份：BACKUP_SYNC_DIR 填挂好的 NAS/外接盘目录，或填 user@host:/path 走 rsync。
# 本机实测过的两个坑（代码里的判据就是为它们写的）：
#   ① mysqldump 一行都没输出就失败时，gzip 照样产出一个 **20 字节** 的文件而且 `gzip -t` 通过
#      —— cron 里能连攒十几天空包，看着全绿。所以先写 .partial、验完内容再 mv。
#   ② `find -mtime +14` 删的是 **15 天前** 的（实测：20 天前的被删、10 天前的不删），
#      且 .partial 不匹配 im-*.sql.gz —— 失败残留会永远攒着，得单独清。
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$PWD"
mkdir -p backup logs

# 与 bootstrap.sh 同构：先剔 UTF-8 BOM 再 grep（记事本/PowerShell 存的 .env 第一行多三个字节，grep 碰不到它）
read_env() {
  local line
  line="$(sed '1s/^\xEF\xBB\xBF//' .env 2>/dev/null | grep -m1 "^$1=" || true)"
  [[ -n "$line" ]] || { printf ''; return; }
  printf '%s' "${line#*=}" | tr -d '\r'
}
die() { echo "!! $*" >&2; exit 1; }
warn() { echo "!! $*" >&2; }

CRON=0
WHAT="${1:-all}"
if [[ "$WHAT" == --install-cron ]]; then CRON=1; WHAT="${2:-mysql}"; fi

KEEP_DAYS="$(read_env BACKUP_KEEP_DAYS)"; KEEP_DAYS="${KEEP_DAYS:-14}"
[[ "$KEEP_DAYS" =~ ^[0-9]+$ ]] && (( KEEP_DAYS >= 1 )) || die "BACKUP_KEEP_DAYS 得是 ≥ 1 的天数，现在读到的是「$KEEP_DAYS」"
SYNC_DIR="$(read_env BACKUP_SYNC_DIR)"
STAMP="$(date +%F-%H%M)"

backup_mysql() {
  local OUT="backup/im-${STAMP}.sql.gz" PART="backup/im-${STAMP}.sql.gz.partial"
  # --single-transaction：InnoDB 一致性快照，不锁表（mysqldump 期间业务照常）
  # --routines：漏了它，恢复出来的库没有存储过程；应用若调到就是「本地好、线上报错」
  # --default-character-set=utf8mb4：不加会按连接默认字符集导，中文注释/数据可能变成 ?
  echo "== ① 数据库 -> $OUT"
  rm -f "$PART"
  # ★ 先写 .partial：管道失败时 gzip 已经把目标文件建好了（实测 20 字节、gzip -t 还通过），
  #   直接写正式名就等于「攒了一堆看着成功的空备份」
  if ! docker compose exec -T mysql sh -c \
       'mysqldump -uim -p"$MYSQL_PASSWORD" --single-transaction --routines --default-character-set=utf8mb4 im_db' \
       | gzip > "$PART"; then
    rm -f "$PART"
    die "mysqldump 失败（容器没起、密码不对、或库连不上）。没留下半截备份：$PART 已删"
  fi
  verify_dump "$PART"
  mv -f "$PART" "$OUT"
  ls -lh "$OUT"
  sync_offsite "$OUT"
  # 恢复（★ 先确认目标库再执行，生产库上不要顺手加 --force）：
  #   docker compose cp backup/im-xxx.sql.gz mysql:/tmp/
  #   docker compose exec mysql sh -c 'gunzip -c /tmp/im-xxx.sql.gz | mysql -uim -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 im_db'
}

# 一次解压同时拿到「解压后字节数 / 首行 / 末行」，据此判这份包能不能用来恢复
verify_dump() {
  local f="$1" meta raw rest first last
  meta="$(gzip -dc "$f" | awk '{n+=length($0)+1; if(NR==1)first=$0; last=$0} END{printf "%d|%s|%s", n+0, first, last}')" \
    || die "读不了刚生成的 $f"
  raw="${meta%%|*}"; rest="${meta#*|}"; first="${rest%%|*}"; last="${rest#*|}"
  # 硬判据只认「解压后有多少字节」这条纯事实，不假设 mysqldump 的文案（版本会变，写死了会误杀每天的备份）
  if (( raw < 1024 )); then
    rm -f "$f"
    die "备份解压后只有 $raw 字节（空库也不止这个数）：这不是可用备份，已删掉不留假成功。手工跑一次 bash scripts/backup.sh mysql 看真实报错"
  fi
  # 软判据：对得上就静默，对不上只 WARN（不让备份失败）
  case "$first" in *MySQL\ dump*) ;; *) warn "首行不是「-- MySQL dump …」（实际：${first:0:60}）——包可能不是 mysqldump 的输出，恢复前先手工看一眼" ;; esac
  case "$last"  in *Dump\ completed*) ;; *) warn "末行没有「-- Dump completed」（实际：${last:0:60}）——dump 可能被中途截断，这份别当唯一副本" ;; esac
  echo "   解压后 $raw 字节，首行：${first:0:48}"
}

# 异地同步：本地这份已经成功，所以同步失败只 WARN 不 die（return 总是 0：否则 set -e 会拿它当致命错）
sync_offsite() {
  local src="$1" size1 size2
  if [[ -z "$SYNC_DIR" ]]; then
    warn "没配 BACKUP_SYNC_DIR：这份备份和数据库在同一块盘上，盘挂了等于没备份（.env 里填 NAS 目录或 user@host:/path）"
    return 0
  fi
  echo "== ⑤ 异地同步 -> $SYNC_DIR"
  if [[ "$SYNC_DIR" == *@*:* ]]; then
    command -v rsync >/dev/null 2>&1 || { warn "BACKUP_SYNC_DIR 是远端目标但本机没有 rsync（apt install rsync，并先 ssh-copy-id 免密：cron 里没人输密码）"; return 0; }
    rsync -a "$src" "$SYNC_DIR/" || { warn "rsync 到 $SYNC_DIR 失败：本地这份还在（$src），但异地没更新"; return 0; }
    size2="$(ssh "${SYNC_DIR%%:*}" "stat -c %s '${SYNC_DIR#*:}/$(basename "$src")'" 2>/dev/null || echo '?')"
  else
    mkdir -p "$SYNC_DIR" 2>/dev/null || { warn "建不了 $SYNC_DIR（挂载点掉了？权限？）：异地没更新"; return 0; }
    cp -f "$src" "$SYNC_DIR/" || { warn "拷到 $SYNC_DIR 失败（盘满？）：异地没更新"; return 0; }
    size2="$(stat -c %s "$SYNC_DIR/$(basename "$src")" 2>/dev/null || echo '?')"
  fi
  size1="$(stat -c %s "$src")"
  # ★ 命令返回 0 不等于拷对了：比一下字节数，不一致就别把它当有效副本
  [[ "$size1" == "$size2" ]] || { warn "异地那份大小对不上（本地 $size1 / 异地 $size2）"; return 0; }
  echo "   已同步并核对大小一致（$size1 字节）"
  return 0
}

prune_old() {
  # ★ -mtime +N 是「至少 N+1 天前」（实测），所以留 KEEP_DAYS 天要传 KEEP_DAYS-1
  local m=$((KEEP_DAYS - 1)); (( m < 0 )) && m=0
  echo "== ⑥ 清理：backup/ 里 $KEEP_DAYS 天前的 im-*.sql.gz"
  find backup -maxdepth 1 -name 'im-*.sql.gz' -mtime +"$m" -print -delete
  # 失败残留的 .partial 不被上面那条规则匹配（实测），超过 1 天的一并清掉
  find backup -maxdepth 1 -name '*.partial' -mmin +1440 -print -delete
}

backup_minio() {
  # ★ minio 现在是宿主机上的官方二进制（systemd 服务名 minio），数据在宿主目录、不在 docker 卷里。
  #   数据目录优先取 .env 的 MINIO_DATA_DIR；没配就回落到 systemd 的 /etc/default/minio 里 MINIO_VOLUMES；再兜底 /data/minio。
  #   ★ 停服务再 tar：桶运行中打包会漏掉 in-flight 的分片上传，恢复出来的对象大小对不上。
  #   ★ 需要 root（数据目录与 systemctl 都要）；放 cron 就给 minio 的 stop/start + tar 配 NOPASSWD，或整个脚本用 root 跑。
  local DATA_DIR
  DATA_DIR="$(grep -m1 '^MINIO_DATA_DIR=' .env 2>/dev/null | cut -d= -f2- | tr -d '\r')"
  [[ -z "$DATA_DIR" || "$DATA_DIR" == *"<"* ]] && DATA_DIR="$(grep -m1 '^MINIO_VOLUMES=' /etc/default/minio 2>/dev/null | cut -d= -f2- | tr -d '\r"')"
  DATA_DIR="${DATA_DIR:-/data/minio}"
  [[ -d "$DATA_DIR" ]] || { echo "!! 找不到 minio 数据目录：$DATA_DIR（在 .env 里设 MINIO_DATA_DIR）" >&2; return 1; }
  echo "== ② 对象存储 -> backup/minio-${STAMP}.tar.gz（会短暂停 minio 服务，数据目录 $DATA_DIR）"
  sudo systemctl stop minio
  sudo tar czf "backup/minio-${STAMP}.tar.gz" -C "$DATA_DIR" .
  sudo systemctl start minio
  # 恢复：sudo systemctl stop minio && sudo tar xzf backup/minio-xxx.tar.gz -C "$DATA_DIR" && sudo systemctl start minio
}

backup_conf() {
  # 这三份丢了 = 整套环境重建（密码、域名、CORS 白名单、nginx 路由全在里面）
  echo "== ③ 编排与配置 -> backup/im-docker-conf-${STAMP}.tar.gz"
  tar czf "backup/im-docker-conf-${STAMP}.tar.gz" \
    docker-compose.yml .env conf app/extra nginx scripts sql-init sql-init.disabled 2>/dev/null
  chmod 600 "backup/im-docker-conf-${STAMP}.tar.gz"     # 里面有明文密码
}

backup_redis() {
  # Redis 里是会话/验证码/权限缓存，丢了的表现是「所有人都要重新登录」，不是数据损坏。
  # 已开 appendonly，AOF 本身在卷里；这里额外要一份 dump.rdb 是为了「误删键」能快速回。
  echo "== ④ Redis 快照 -> backup/redis-${STAMP}.rdb"
  docker compose exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning BGSAVE' >/dev/null
  sleep 2
  docker compose cp redis:/data/dump.rdb "backup/redis-${STAMP}.rdb"
}

# ---------- 装 cron（★ 装在**部署机**上：备份靠 docker compose exec mysql，在开发机/签发机上跑必然失败）----------
if (( CRON )); then
  command -v crontab >/dev/null 2>&1 || die "本机没有 crontab（Linux: apt install cron）。Windows 用任务计划程序，写法见下面打印的 schtasks"
  case "$WHAT" in mysql|minio|conf|redis|all) ;; *) die "不认识 $WHAT（要 all|mysql|minio|conf|redis）" ;; esac
  if [[ "$WHAT" == all || "$WHAT" == minio ]]; then
    warn "挂 $WHAT 会每天 stop/start minio（备份那几分钟上传与直播不可用），而且需要 root 或给 sudo 配 NOPASSWD（cron 里没人输密码）"
  fi
  line="30 3 * * * cd $ROOT && bash scripts/backup.sh $WHAT >> $ROOT/logs/backup-cron.log 2>&1"
  # ★ 先剔除旧行再追加：grep -v 没匹配时返回 1，set -e 会掐死这一步，所以必须 || true；
  #   旧 crontab 为空时不能 printf 一个空行进去（会在顶部留空行）
  cur="$(crontab -l 2>/dev/null | { grep -vF 'scripts/backup.sh' || true; })"
  { [[ -n "$cur" ]] && printf '%s\n' "$cur"; printf '%s\n' "$line"; } | crontab -
  echo "== cron 已装（每天 03:30，幂等：重跑 --install-cron 只留一行）"
  echo "   清理与异地同步都在脚本里，不用写进 cron 行：保留 $KEEP_DAYS 天（BACKUP_KEEP_DAYS）、异地 $([[ -n "$SYNC_DIR" ]] && echo "$SYNC_DIR" || echo "未配（BACKUP_SYNC_DIR）")"
  crontab -l | grep -nF 'scripts/backup.sh'
  echo
  echo "Windows 上等价的任务计划程序写法（PowerShell 管理员）："
  cat <<EOF
  schtasks /Create /TN im-backup /SC DAILY /ST 03:30 /RL HIGHEST ^
    /TR "'C:\\Program Files\\Git\\bin\\bash.exe' -lc 'cd /d/你的路径/Docker && bash scripts/backup.sh mysql'"
EOF
  echo "★ 日志在 logs/backup-cron.log（已被 .gitignore 忽略），长了自己截：tail -c 1048576 logs/backup-cron.log > /tmp/x && mv /tmp/x logs/backup-cron.log"
  echo "★ 装完先手工验一次：bash scripts/backup.sh $WHAT —— cron 里失败只会默默写日志，没人看就等于没备"
  exit 0
fi

case "$WHAT" in
  mysql) backup_mysql ;;
  minio) backup_minio ;;
  conf)  backup_conf ;;
  redis) backup_redis ;;
  all)   backup_mysql; backup_minio; backup_conf; backup_redis ;;
  *)     echo "用法：bash scripts/backup.sh [all|mysql|minio|conf|redis]，或 bash scripts/backup.sh --install-cron [what]" >&2; exit 2 ;;
esac

prune_old

echo "== 备份完成，当前 backup/ 内容："
ls -lh backup | tail -n +1
