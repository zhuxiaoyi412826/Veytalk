#!/usr/bin/env bash
# 补齐 im/admin 证书文件（im 的 fullchain 已对，不动）+ 三域配对校验 + reload + 三域验收
# 用法：sed -i 's/\r$//' /root/ssl/fixcert2.sh && bash /root/ssl/fixcert2.sh 2>&1 | tee /root/fixcert2.log
set -uo pipefail
D=/opt/im-docker/certs/live

echo '== 0 中转件盘点 =='
ls -l /root/ssl/ 2>&1
for f in im.dsaol.asia.key admin.dsaol.asia_bundle.crt admin.dsaol.asia.key; do
  [[ -f /root/ssl/$f ]] || { echo "FATAL: /root/ssl/$f 缺失，先本地 scp 补传"; exit 1; }
done

echo '== 1 补齐被吞的三个文件（绝对路径覆盖） =='
cp -f /root/ssl/im.dsaol.asia.key           "$D/im.dsaol.asia/privkey.pem"
cp -f /root/ssl/admin.dsaol.asia_bundle.crt "$D/admin.dsaol.asia/fullchain.pem"
cp -f /root/ssl/admin.dsaol.asia.key        "$D/admin.dsaol.asia/privkey.pem"
chmod 644 "$D/im.dsaol.asia/fullchain.pem" "$D/admin.dsaol.asia/fullchain.pem"
chmod 600 "$D/im.dsaol.asia/privkey.pem" "$D/admin.dsaol.asia/privkey.pem"

echo '== 2 配对校验：每域两行 md5 必须相同（cert 公钥 == key 公钥） =='
for d in www.dsaol.asia im.dsaol.asia admin.dsaol.asia; do
  c=$(openssl x509 -in "$D/$d/fullchain.pem" -noout -pubkey | md5sum | cut -d' ' -f1)
  k=$(openssl pkey -in "$D/$d/privkey.pem" -pubout | md5sum | cut -d' ' -f1)
  printf '%-18s cert=%s key=%s %s\n' "$d" "$c" "$k" "$([ "$c" = "$k" ] && echo PAIRED || echo MISMATCH)"
done

echo '== 3 reload（目录已存在，reload 足够；-t 不过会自动拒 reload 保线上） =='
cd /opt/im-docker || exit 1
docker compose exec nginx nginx -t && docker compose exec nginx nginx -s reload

echo '== 4 三域生效验收：三行 issuer 应全 TrustAsia =='
for s in www im admin; do
  printf '%-6s ' "$s"
  echo | openssl s_client -connect 127.0.0.1:443 -servername "$s.dsaol.asia" 2>/dev/null \
    | openssl x509 -noout -subject -issuer | tr '\n' ' '
  echo
done
echo '== 完成：三域 PAIRED + 三行 TrustAsia 即成功；随后手动 rm -rf /root/ssl =='
