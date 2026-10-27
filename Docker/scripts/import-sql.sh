#!/usr/bin/env bash
# ==============================================================================
# 往容器里的 MySQL 导 SQL（对应 Docker部署指南.md §7.1）。
#
#   bash scripts/import-sql.sh sql/migration_xxx.sql
#   bash scripts/import-sql.sh ../sql/migration_xxx.sql
#
# ★★ 为什么不用管道也不用 heredoc：
#     cat x.sql | docker compose exec -T mysql mysql ... im_db
#   这条路会把 UTF-8 字节流按容器 LANG 重编码，实测结果是**中文静默变成 '?'**——
#   不报错、行数对得上、只有内容坏了，等发现时已经上线。
#   正确姿势是让文件先原样进容器（cp 是纯字节拷贝，不过任何编码），再用 mysql 的 source 读它。
#
# ★ 导完若涉及权限点（im_permission / im_role_permission 的增删），必须接着跑
#   bash scripts/clear-perm-cache.sh —— im:auth:perm:{userId} 有 30 分钟 TTL 且登录不刷新，
#   不清的表现是「SQL 明明执行了、后台菜单和按钮还是旧的」。
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SQL="${1:-}"
[[ -n "$SQL" ]] || { echo "用法：bash scripts/import-sql.sh <sql 文件路径>" >&2; exit 2; }
[[ -f "$SQL" ]] || { echo "文件不存在：$SQL" >&2; exit 2; }
# 全量建库脚本走 sql-init/（空卷首次启动时由镜像自己执行），不走本脚本
if grep -qiE '^\s*DROP\s+TABLE' "$SQL"; then
  echo "★ 这份 SQL 里有 DROP TABLE（看着像全量建库脚本 im_schema.sql）。" >&2
  echo "  本脚本拒绝执行它 —— 在生产库上跑一次就等于清库。" >&2
  echo "  全新建库请走 sql-init/（见指南 §6.2），或确认你确实知道自己在做什么后再手工执行。" >&2
  exit 3
fi

TMP="/tmp/im-import-$(date +%s).sql"
echo "== 复制进容器：$SQL -> mysql:$TMP"
docker compose cp "$SQL" "mysql:$TMP"

echo "== 执行（--default-character-set=utf8mb4 是关键，缺了中文会坏）"
docker compose exec mysql sh -c \
  'mysql -uim -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 im_db -e "source '"$TMP"'"'

docker compose exec mysql sh -c 'rm -f "'"$TMP"'"'
echo "== 完成。涉及权限点的话记得：bash scripts/clear-perm-cache.sh"
