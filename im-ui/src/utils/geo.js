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
 * 外部地图跳转链接：Google 通用，无密钥、点击即在系统地图/浏览器里打开定位。
 */
export function externalMapUrl(lat, lng) {
  return `https://www.google.com/maps?q=${Number(lat)},${Number(lng)}`
}

/** 坐标可读文本，保留 6 位小数 */
export function formatCoord(lat, lng) {
  return `${Number(lat).toFixed(6)}, ${Number(lng).toFixed(6)}`
}
