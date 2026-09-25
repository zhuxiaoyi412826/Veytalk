/**
 * 开发期自签 HTTPS 证书生成脚本（npm run gen:cert）。
 *
 * 为什么需要它：浏览器只在「安全上下文」暴露 WebCrypto（crypto.subtle），
 * http://<局域网IP>:5173 不是安全上下文，手机用 IP 访问时远程控制的
 * AES-GCM 解密直接不可用。既然后缀没法是 localhost，就把协议升成 https。
 *
 * 产物（im-ui/certs/，已 gitignore，含私钥绝不入库）：
 *  - ca.pem / ca.key          自签根 CA（CA:TRUE，10 年）——手机只需要信任这一张
 *  - server.pem / server.key  服务器证书（820 天，逼近 iOS 对 TLS 证书的 825 天红线）：
 *                             SAN 含 localhost、127.0.0.1 与本机全部非回环 IPv4，
 *                             由根 CA 签发。装了根 CA 后地址栏无警告，
 *                             不需要也不存在 thisisunsafe 之类的兜底。
 *
 * 用法：
 *   npm run gen:cert                  # 生成 / 重新生成（覆盖）
 *   node scripts/gen-cert.cjs 192.168.1.8   # 额外追加 IP/域名到 SAN（换网段的调试机）
 *
 * 直接用 node-forge 构造证书：selfsigned 这层薄封装对 extensions/key/cert
 * 透传不可靠（签出来的证书 SAN 丢失、CA 参数不生效），扩展项多就自己写了。
 */
const fs = require('fs')
const os = require('os')
const path = require('path')
const forge = require('node-forge')

const CERT_DIR = path.join(__dirname, '..', 'certs')

const args = process.argv.slice(2)
const extraAlts = args.filter((a) => !a.startsWith('--'))

// 本机全部非回环 IPv4：USB 共享网络 / 热点 / 有线各自的网段都一并写进 SAN，
// 省得插拔一次网线就要重签一张证书
const localIPv4s = Object.values(os.networkInterfaces())
  .flat()
  .filter((i) => i && i.family === 'IPv4' && !i.address.startsWith('127.'))
  .map((i) => i.address)

const alts = [...new Set(['localhost', '127.0.0.1', ...localIPv4s, ...extraAlts])]
const isIPv4 = (s) => /^\d+\.\d+\.\d+\.\d+$/.test(s)

// 回拨 1 小时生效，抵消手机与电脑间的时钟偏差
const notBefore = new Date(Date.now() - 60 * 60 * 1000)
const daysLater = (days) => new Date(notBefore.getTime() + days * 86400 * 1000)

function makeKey() {
  return forge.pki.rsa.generateKeyPair({ bits: 2048, e: 0x10001 })
}

const caSubjectAttrs = [{ name: 'commonName', value: 'IM Dev Root CA' }]
const serverSubjectAttrs = [{ name: 'commonName', value: 'im-dev-server' }]

// ---- 1) 根 CA：CA:TRUE + 可签证书 ----
const caAttrs = caSubjectAttrs
const ca = (() => {
  const { privateKey, publicKey } = makeKey()
  const cert = forge.pki.createCertificate()
  cert.publicKey = publicKey
  cert.serialNumber = Date.now().toString(16)
  cert.validity.notBefore = notBefore
  cert.validity.notAfter = daysLater(3650)
  cert.setSubject(caAttrs)
  cert.setIssuer(caAttrs) // 自签：签发者就是自己
  cert.setExtensions([
    { name: 'basicConstraints', cA: true },
    { name: 'keyUsage', keyCertSign: true, cRLSign: true, digitalSignature: true }
  ])
  cert.sign(privateKey, forge.md.sha256.create())
  return { privateKey, cert }
})()
const caKey = ca.privateKey

// ---- 2) 服务器证书：SAN 齐活、serverAuth 用途、由根 CA 签发 ----
const server = (() => {
  const { privateKey, publicKey } = makeKey()
  const cert = forge.pki.createCertificate()
  cert.publicKey = publicKey
  cert.serialNumber = Date.now().toString(16) + 'a1'
  cert.validity.notBefore = notBefore
  cert.validity.notAfter = daysLater(820)
  cert.setSubject(serverSubjectAttrs)
  cert.setIssuer(caAttrs)
  cert.setExtensions([
    { name: 'basicConstraints', cA: false },
    { name: 'keyUsage', digitalSignature: true, keyEncipherment: true },
    { name: 'extKeyUsage', serverAuth: true },
    {
      name: 'subjectAltName',
      altNames: alts.map((a) =>
        isIPv4(a) ? { type: 7, ip: a } : { type: 2, value: a }
      )
    }
  ])
  cert.sign(caKey, forge.md.sha256.create())
  return { privateKey, cert }
})()

fs.mkdirSync(CERT_DIR, { recursive: true })
const toPem = (cert) => forge.pki.certificateToPem(cert)
const keyToPem = (key) => forge.pki.privateKeyToPem(key)
const written = {
  'ca.pem': toPem(ca.cert),
  'ca.key': keyToPem(ca.privateKey),
  'server.pem': toPem(server.cert),
  'server.key': keyToPem(server.privateKey)
}
for (const [name, content] of Object.entries(written)) {
  fs.writeFileSync(path.join(CERT_DIR, name), content)
}

console.log('证书生成完成 →', CERT_DIR)
console.log('SAN 覆盖：', alts.join(', '))
console.log('下一步：把 certs/ca.pem 发到手机上安装为「CA 证书」，再用 https://<IP>:5173 访问')
