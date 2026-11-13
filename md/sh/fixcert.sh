#!/usr/bin/env bash
# 证书修复一次性脚本：三域目录补齐（www=阿里云真证，im/admin 缺失才签自签占位）+ restart nginx + 三域验收
# 用法：sed -i 's/\r$//' /root/ssl/fixcert.sh && bash /root/ssl/fixcert.sh 2>&1 | tee /root/fixcert.log
# 全绝对路径、不依赖工作目录；不依赖 bootstrap.sh 的 .managed-by-push 分支；
# 新建目录按 push-cert.sh 约定用 restart（:ro bind mount 下 reload 可能看不见新目录项）
set -uo pipefail
D=/opt/im-docker/certs/live

echo '== 0 盘点 =='
ls -l "$D" 2>&1
ls -l /root/ssl/ 2>&1
if [[ ! -f /root/ssl/dsaol.asia_bundle.crt || ! -f /root/ssl/dsaol.asia.key ]]; then
  echo 'FATAL: /root/ssl 缺真证两件套，先在本地 scp 上传再跑本脚本'
  exit 1
fi

echo '== 1 www 真证落位 =='
mkdir -p "$D/www.dsaol.asia"
cp -f /root/ssl/dsaol.asia_bundle.crt "$D/www.dsaol.asia/fullchain.pem"
cp -f /root/ssl/dsaol.asia.key       "$D/www.dsaol.asia/privkey.pem"

echo '== 2 im/admin 占位补齐（已有证书对就不动） =='
for d in im.dsaol.asia admin.dsaol.asia; do
  if [[ -f "$D/$d/fullchain.pem" && -f "$D/$d/privkey.pem" ]]; then
    echo "$d: 已有证书对，不动"
  else
    mkdir -p "$D/$d"
    if openssl req -x509 -nodes -newkey rsa:2048 -days 30 -subj "/CN=$d" \
         -addext "subjectAltName=DNS:$d" \
         -keyout "$D/$d/privkey.pem" -out "$D/$d/fullchain.pem" 2>/dev/null; then
      echo "$d: 已签 30 天自签占位"
    else
      echo "FATAL: $d 自签失败（看上面 openssl 报错）"
    fi
  fi
done
chmod -R a+rX /opt/im-docker/certs
chmod 600 "$D"/*/privkey.pem

echo '== 3 磁盘侧核验（www 必须 TrustAsia） =='
for d in www.dsaol.asia im.dsaol.asia admin.dsaol.asia; do
  printf '%-18s ' "$d"
  openssl x509 -in "$D/$d/fullchain.pem" -noout -issuer 2>&1
done

echo '== 4 restart nginx（新目录项 reload 可能看不见，按 push-cert.sh 约定 restart） =='
cd /opt/im-docker || exit 1
docker compose restart nginx
sleep 3
if docker compose exec nginx nginx -t; then
  echo 'CONFIG-OK'
else
  echo 'FATAL: restart 后 nginx -t 仍失败 → 手动跑 docker compose up -d --force-recreate nginx 再重跑本脚本第 5 段'
  exit 1
fi

echo '== 5 三域生效验收（www 应 issuer=TrustAsia；im/admin 应 subject==issuer 自签） =='
for s in www im admin; do
  printf '%-6s ' "$s"
  echo | openssl s_client -connect 127.0.0.1:443 -servername "$s.dsaol.asia" 2>/dev/null \
    | openssl x509 -noout -subject -issuer | tr '\n' ' '
  echo
done
echo '== 完成：以上三行齐且 www issuer=TrustAsia 即成功；随后手动 rm -rf /root/ssl 清中转件 =='
