set -eu
if getent group "${IM_GID}" >/dev/null 2>&1; then
  echo "[im] gid ${IM_GID} 已被组（$(getent group "${IM_GID}" | head -1 | cut -d: -f1)）占用，直接复用这个数字";
elif groupadd --system --gid "${IM_GID}" im; then
  echo "[im] 新建 group im gid=${IM_GID}";
else
  echo "[im] !! 建组失败"; exit 1;
fi
if getent passwd "${IM_UID}" >/dev/null 2>&1; then
  echo "[im] uid ${IM_UID} 已被用户（$(getent passwd "${IM_UID}" | head -1 | cut -d: -f1)）占用，直接复用这个数字，不建 im 用户";
elif useradd --system --uid "${IM_UID}" --gid "${IM_GID}" --home-dir /app im; then
  echo "[im] 新建 user im uid=${IM_UID}";
else
  echo "[im] !! 建用户失败"; exit 1;
fi
mkdir -p /app/logs /app/upload-tmp /app/files /app/knowledge /app/extra
chown -R "${IM_UID}:${IM_GID}" /app
echo "[im] OK"
