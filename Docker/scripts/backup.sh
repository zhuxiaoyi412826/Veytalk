#!/usr/bin/env bash
# ==============================================================================
# 一键备份（对应 Docker部署指南.md §10.1）：数据库 + 对象存储 + 编排配置 + Redis 快照。
#
#   bash scripts/backup.sh              # 全备
#   bash scripts/backup.sh mysql        # 只备数据库（最常用，也最适合放 cron）
#
# 产物都落 ./backup/。★ 备份只落在同一块盘上等于没备份，至少把 *.sql.gz 同步到异地/对象存储。
# cron 例子（每天 03:30，保留 14 天）：
#   30 3 * * * cd /opt/im-docker && bash scripts/backup.sh mysql && find backup -name 'im-*.sql.gz' -mtime +14 -delete
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p backup
WHAT="${1:-all}"
STAMP="$(date +%F-%H%M)"

backup_mysql() {
  # --single-transaction：InnoDB 一致性快照，不锁表（mysqldump 期间业务照常）
  # --routines：漏了它，恢复出来的库没有存储过程；应用若调到就是「本地好、线上报错」
  # --default-character-set=utf8mb4：不加会按连接默认字符集导，中文注释/数据可能变成 ?
  echo "== ① 数据库 -> backup/im-${STAMP}.sql.gz"
  docker compose exec -T mysql sh -c \
    'mysqldump -uim -p"$MYSQL_PASSWORD" --single-transaction --routines --default-character-set=utf8mb4 im_db' \
    | gzip > "backup/im-${STAMP}.sql.gz"
  ls -lh "backup/im-${STAMP}.sql.gz"
  # 恢复（★ 先确认目标库再执行，生产库上不要顺手加 --force）：
  #   docker compose cp backup/im-xxx.sql.gz mysql:/tmp/
  #   docker compose exec mysql sh -c 'gunzip -c /tmp/im-xxx.sql.gz | mysql -uim -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 im_db'
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
    docker-compose.yml .env conf app/extra nginx scripts sql-init 2>/dev/null
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

case "$WHAT" in
  mysql) backup_mysql ;;
  minio) backup_minio ;;
  conf)  backup_conf ;;
  redis) backup_redis ;;
  all)   backup_mysql; backup_minio; backup_conf; backup_redis ;;
  *)     echo "用法：bash scripts/backup.sh [all|mysql|minio|conf|redis]" >&2; exit 2 ;;
esac

echo "== 备份完成，当前 backup/ 内容："
ls -lh backup | tail -n +1
