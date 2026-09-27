/**
 * 高风险文件类型判定（安装包 / 可执行脚本 / 证书私钥 / 凭据库）。
 *
 * 为什么只提示不拦截：这些格式本身是正当的传输需求（给同事发个 apk、
 * 换服务器传一套证书），一刀切拒掉只会把人逼去微信/QQ 传；真正的风险是
 * 「接收方不知情地双击」，所以做法是发送前明确告知 + 气泡上常驻警示标签，
 * 让用户在知情的前提下继续传输。
 *
 * 名单与后端 FileBizType.CHAT_FILE 的白名单要配套：后端放行但这里没列出来，
 * 就变成静默传输；这里列了后端没放行，则会 confirm 完再被后端拒，两头都难受。
 */

/** 分类 -> 扩展名（不含点，小写） */
const RISK_CATEGORIES = [
  {
    label: '安装包 / 可执行文件',
    hint: '接收方下载后运行有中毒或被勒索的风险，请确认来源可信',
    exts: ['apk', 'apks', 'exe', 'msi', 'appx', 'msix', 'deb', 'rpm', 'jar', 'scr', 'vbs', 'bat', 'cmd']
  },
  {
    label: '证书 / 私钥文件',
    hint: '私钥与证书一旦泄露等同于交出对应身份，请确认必须通过聊天通道传输',
    exts: ['cer', 'crt', 'pem', 'key', 'p12', 'pfx', 'jks', 'keystore', 'truststore']
  },
  {
    label: '凭据库 / 系统配置文件',
    hint: '这类文件通常装着密码或系统设置，泄露会直接扩大影响面',
    exts: ['kdbx', 'reg']
  }
]

const CATEGORY_BY_EXT = new Map()
RISK_CATEGORIES.forEach((category) => {
  category.exts.forEach((ext) => CATEGORY_BY_EXT.set(ext, category))
})

function extOf(fileName) {
  if (!fileName) {
    return ''
  }
  const dot = fileName.lastIndexOf('.')
  return dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase()
}

/**
 * 命中的风险分类，未命中返回 null。
 *
 * 返回分类对象而不是布尔：调用方（发送前确认框、气泡警示标签）都要用到
 * 「哪一类、该怎么措辞」，只回布尔会逼它们各自再查一遍表。
 */
export function riskCategoryOf(fileName) {
  return CATEGORY_BY_EXT.get(extOf(fileName)) || null
}

export function isRiskFile(fileName) {
  return riskCategoryOf(fileName) !== null
}

/** 发送前确认框的正文：把文件名、类别、风险说明一次说清（单行，ElMessageBox 不渲染换行） */
export function riskConfirmText(fileName) {
  const category = riskCategoryOf(fileName)
  if (!category) {
    return ''
  }
  return `${fileName || '该文件'} 属于「${category.label}」。${category.hint}。确认仍要发送？`
}
