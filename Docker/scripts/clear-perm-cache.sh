#!/usr/bin/env bash
# ==============================================================================
# 清权限缓存（对应 Docker部署指南.md §7.2）。
#
#   bash scripts/clear-perm-cache.sh            # 清全部 im:auth:*
#   bash scripts/clear-perm-cache.sh 1000       # 只清 userId=1000 那个人的
#
# ★ 为什么必须有这一步：PermissionProviderImpl 的 L2 缓存 im:auth:perm:{userId} TTL 30 分钟，
#   而且「登录」这个动作不刷新它。所以新开/收掉权限点、跑完 migration 之后，
#   不清缓存的表现是「SQL 明明执行了，后台菜单还是旧的、点进去 403」——极易误判成代码问题。
#
# ★ 三步口径：先数（只读）→ 再删 → 删完回头再数一次确认是 0。
#   不能省第三步：--scan 与 DEL 分两次连接执行，中间新写进去的键会漏。
# ==============================================================================
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

UID_ARG="${1:-}"
if [[ -n "$UID_ARG" ]]; then
  PATTERN="im:auth:perm:$UID_ARG"
else
  PATTERN='im:auth:*'
fi

count() {
  docker compose exec redis sh -c \
    'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "'"$PATTERN"'" | wc -l'
}

BEFORE="$(count)"
echo "== 匹配 $PATTERN 的键：$BEFORE 个"
[[ "$BEFORE" -eq 0 ]] && { echo "无需清理。"; exit 0; }

# -n 50 是让一条 DEL 带 50 个键（键多时比逐个删快得多）；-r 让空输入不执行 DEL
docker compose exec redis sh -c \
  'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "'"$PATTERN"'" | xargs -r -n 50 redis-cli -a "$REDIS_PASSWORD" --no-auth-warning DEL' >/dev/null

AFTER="$(count)"
echo "== 清理后剩余：$AFTER 个"
if [[ "$AFTER" != "0" ]]; then
  echo "!! 还有残留（多半是这几十毫秒里业务又写回去的）。再跑一次本脚本；连续两次都清不空就去看是谁在反复加载权限。" >&2
  exit 1
fi
echo "== 完成。让用户刷新后台页面（前端也缓存了一份菜单，光清后端不够）。"
