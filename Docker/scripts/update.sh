#!/usr/bin/env bash
# ==============================================================================
# 升级 / 回滚后端镜像（对应 Docker部署指南.md §10.2、§10.3）。
#
#   bash scripts/update.sh 1.0.1                 # 升到 ghcr.io/<owner>/im-server:1.0.1（保留仓库前缀）
#   bash scripts/update.sh ghcr.io/x/im-server:1.0.1 --skip-backup
#   bash scripts/update.sh --rollback 1.0.0      # 回到旧 tag（旧 tag 不能被 prune 过）
#
# ★ 三条规矩：
#   ① 换版本 = 换 tag。绝不用 docker compose build 覆盖同一个 tag，否则回滚失去目标。
#   ② 只动 im-server：命令里显式点名服务，不写裸 up -d（那会顺带重建其它配置变过的容器）。
#   ③ 回滚镜像 ≠ 回滚数据库。跑过的 migration 里「加权限点/改列宽」这类向后兼容的可留着；
#      带 DROP TABLE 的全量脚本永远不出现在回滚流程里。
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ROLLBACK=0; SKIP_BACKUP=0; ARG=""
for a in "$@"; do
  case "$a" in
    --rollback)    ROLLBACK=1 ;;
    --skip-backup) SKIP_BACKUP=1 ;;
    *)             ARG="$a" ;;
  esac
done
[[ -n "$ARG" ]] || { echo "用法：bash scripts/update.sh <新版本号或完整镜像ref> [--rollback] [--skip-backup]" >&2; exit 2; }

read_env() { local l; l="$(grep -m1 "^$1=" .env || true)"; [[ -n "$l" ]] && printf '%s' "${l#*=}" | tr -d '\r'; }
CUR="$(read_env IM_SERVER_TAG)"
[[ -z "$CUR" ]] && { echo ".env 里读不到 IM_SERVER_TAG，先跑过 bootstrap.sh 了吗？" >&2; exit 2; }

# 只给版本号时沿用当前 tag 的仓库前缀；带了 / 就当完整镜像 ref 原样用
if [[ "$ARG" == *"/"* ]]; then
  NEW="$ARG"
else
  NEW="${CUR%:*}:$ARG"
fi
echo "== 当前 tag：$CUR"
echo "== 目标 tag：$NEW"

if [[ $SKIP_BACKUP -eq 0 && $ROLLBACK -eq 0 ]]; then
  echo "== 先备份数据库（升级失败要回滚时，这份是唯一能救的路；--skip-backup 可跳过）"
  bash scripts/backup.sh mysql
fi

echo "== 拉取镜像"
# 先改 .env 再 pull（compose 插值需要新 tag），pull 失败就把 .env 改回去 ——
# 否则一个拼错的 tag 会把 .env 留在“指向不存在的镜像”的状态，下次 up 直接起不来
sed -i "s|^IM_SERVER_TAG=.*|IM_SERVER_TAG=$NEW|" .env
if ! docker compose pull im-server; then
  echo "!! 拉取失败，已把 .env 恢复为 $CUR" >&2
  sed -i "s|^IM_SERVER_TAG=.*|IM_SERVER_TAG=$CUR|" .env
  exit 1
fi

echo "== 只重建 im-server（--wait 等它 healthy 才返回，不 healthy 就直接失败退出）"
docker compose up -d --no-deps --wait im-server || {
  echo "!! 新容器没起来。看日志：docker compose logs --tail=200 im-server"
  echo "   要回滚就：bash scripts/update.sh --rollback ${CUR#*:}"
  exit 1
}

echo "== 现在跑的版本"
docker compose ps im-server
docker compose exec im-server sh -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; head -c 200 <&3' || true
echo
[[ $ROLLBACK -eq 1 ]] && echo "== 回滚完成。记得确认数据库里那些不兼容的 migration 是否需要人工处理。"
echo "== 旧 tag 还在本地，别清理它（docker image prune -a 会把回滚目标一起删掉）"
echo "== 前端同理由 CI 覆盖 ./web/*，bind mount 不需重启容器，浏览器强刷即可"
