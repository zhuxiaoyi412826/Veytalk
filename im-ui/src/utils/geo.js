/**
 * 位置消息的地图工具：把经纬度换算成瓦片地址、拼外部地图跳转链接。
 *
 * 纯前端、零依赖：瓦片按标准 Web Mercator 切片号计算，默认走 OpenStreetMap 的免密钥公共瓦片，
 * 可通过环境变量 VITE_TILE_TEMPLATE 覆盖成高德 / Google 等带密钥的瓦片模板（形如
 * `https://.../{z}/{x}/{y}.png?key=xxx`）。服务端只存坐标，不生成地图图片，离线/内网都能渲染。
 */

// 默认 OSM 标准瓦片；`{z}/{x}/{y}` 为切片占位符
const DEFAULT_TILE_TEMPLATE = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'

const TILE_TEMPLATE = (import.meta.env && import.meta.env.VITE_TILE_TEMPLATE) || DEFAULT_TILE_TEMPLATE

/**
 * 经纬度 → 某缩放级下的瓦片地址。
 * @param {number} lat 纬度
 * @param {number} lng 经度
 * @param {number} zoom 缩放级（默认 15，街景级）
 */
export function tileUrl(lat, lng, zoom = 15) {
  const n = 2 ** zoom
  const x = Math.floor(((Number(lng) + 180) / 360) * n)
  const latRad = (Number(lat) * Math.PI) / 180
  const y = Math.floor(((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n)
  // 越界钳制，避免 NaN 或负号把 URL 拼坏
  const cx = Math.max(0, Math.min(n - 1, x))
  const cy = Math.max(0, Math.min(n - 1, y))
  return TILE_TEMPLATE.replace('{z}', zoom).replace('{x}', cx).replace('{y}', cy)
}

/**
 * 外部地图跳转：高德 / 百度 / 谷歌三家可选，由点位置卡片时弹窗选（或设置里钉一家）。
 *
 * ★ 坐标系：浏览器定位给的是 WGS-84，而高德用 GCJ-02、百度用 BD-09，直接拿 WGS-84
 * 当对方的坐标用，标记点会偏几十到几百米（国内越往西偏越多）。这种偏移不报错、
 * 只是「点不准」，是最难查的一类问题，所以前端**不自己算转换**：高德 `coordinate=wgs84`、
 * 百度 `coord_type=wgs84` 都支持声明原始坐标系并由对方服务端转换，谷歌本来就吃 WGS-84。
 *
 * ★ 两家的经纬度顺序是**反的**：高德 position=经度,纬度；百度 location=纬度,经度。
 * 改这里必须对着官方文档核一遍，弄反了不会报错，只会把人标到另一个地方。
 */

/** 百度的 src 是必选统计参数（规则 webapp.公司.应用），不传不保证服务 */
const BAIDU_SRC = 'webapp.im.location'

/**
 * 可选地图清单，数组顺序就是弹窗里的展示顺序（国内两家在前）。
 * key 会存进设置项 mapProvider，改 key 等于让所有人已记住的选择失效，别随手改。
 */
export const MAP_PROVIDERS = [
  {
    key: 'amap',
    label: '高德地图',
    build: (lat, lng, name) =>
      `https://uri.amap.com/marker?position=${Number(lng)},${Number(lat)}` +
      `&name=${encodeURIComponent(name || '位置')}&src=im&coordinate=wgs84` +
      // callnative=1 会在手机上跳出浏览器去拉起高德 App。这里钉 0：「在新页面打开」
      // 是这个功能对用户承诺的行为，不该由对方 App 装没装来决定。
      '&callnative=0'
  },
  {
    key: 'baidu',
    label: '百度地图',
    // title/content 都是必选；content 放坐标原文，便于对方页面上核对
    build: (lat, lng, name) =>
      `https://api.map.baidu.com/marker?location=${Number(lat)},${Number(lng)}` +
      `&title=${encodeURIComponent(name || '位置')}&content=${encodeURIComponent(formatCoord(lat, lng))}` +
      `&output=html&coord_type=wgs84&src=${BAIDU_SRC}`
  },
  {
    key: 'google',
    label: '谷歌地图',
    // q= 是老写法，但它是本功能一直在用且已验证可用的链接，不为「更规范」去动它。
    // 谷歌这条刻意不带 name：q 里塞标签是另一种未文档化的写法，不值得为它换掉可用链接。
    build: (lat, lng) => `https://www.google.com/maps?q=${Number(lat)},${Number(lng)}`
  }
]

/** 按 key 找地图；未识别（设置里存了旧值或被改坏）时退回清单第一个 */
export function resolveMapProvider(key) {
  return MAP_PROVIDERS.find((item) => item.key === key) || MAP_PROVIDERS[0]
}

/**
 * 外部地图跳转链接，无密钥、点击即在新标签页打开定位。
 * @param {number} lat 纬度（WGS-84）
 * @param {number} lng 经度（WGS-84）
 * @param {string} [providerKey] MAP_PROVIDERS 里的 key，缺省或非法一律用第一个（高德）
 * @param {string} [name] 地点名称，作为标注标题；谷歌那家忽略它
 */
export function externalMapUrl(lat, lng, providerKey, name) {
  return resolveMapProvider(providerKey).build(lat, lng, name)
}

/** 坐标可读文本，保留 6 位小数 */
export function formatCoord(lat, lng) {
  return `${Number(lat).toFixed(6)}, ${Number(lng).toFixed(6)}`
}
