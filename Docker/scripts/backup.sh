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
  # ★ minio 已移出主栈，是 minio/docker-compose.yml 单独一个项目（name: im-minio），
  #   数据在命名卷 im-minio_minio-data 里（不是 ./data 下），所以用一个临时容器挂卷来 tar。
  #   停/起都走它自己的 compose（带 --env-file .env），主栈目录里已没有 minio 服务，直接 `docker compose stop minio` 会报 no such service。
  # ★ 停容器再 tar：桶运行中打包会漏掉 in-flight 的分片上传，恢复出来的对象大小对不上。
  echo "== ② 对象存储 -> backup/minio-${STAMP}.tar.gz（会短暂停 minio 容器）"
  docker compose -f minio/docker-compose.yml --env-file .env stop minio
  docker run --rm -v im-minio_minio-data:/data -v "$PWD/backup":/backup alpine \
    tar czf "/backup/minio-${STAMP}.tar.gz" -C /data .
  docker compose -f minio/docker-compose.yml --env-file .env start minio
  # 卷名 = <项目名>_minio-data，minio compose 顶层 name: im-minio。
  # 换了目录名又不想改 name 的话，用 docker volume ls 确认实际名字
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
