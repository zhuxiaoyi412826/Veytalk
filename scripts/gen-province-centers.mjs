/**
 * 由 DataV 的全国行政区划边界（100000_full.json）生成「省级中心点 + 外包框」小表。
 *
 * 这张表是给「定位坐标自动回填三级」用的第一步：拿到经纬度后先在 35 个省级单位里
 * 锁定属哪一个，再去运行时按需拉那一省的边界文件判市、拉市的文件判区县。
 * 省级这一步不需要边界文件——整张表才 3KB 左右，直接打进包里，离线也能定到省。
 *
 * 数据源（浏览器打开即可下载，无需密钥）：
 *   https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json
 * 用法：node scripts/gen-province-centers.mjs <上面那份 GeoJSON 的本地路径>
 * 输出：im-ui/src/data/provinceCenters.js（生成文件，勿手改）
 *
 * 为什么不把省的多边形也一起打进来（那样第一步就是精确判定）：全国省级边界 568KB，
 * 交友资料页一个字段吃掉首屏包体一半不值得；而省级这一步用外包框 + 最近中心点已经够准，
 * 真正容易错位的是市与区县，那两级走运行时拉回来的精确多边形。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const source = process.argv[2]
if (!source) {
  console.error('用法：node scripts/gen-province-centers.mjs <100000_full.json 的路径>')
  process.exit(1)
}
const geo = JSON.parse(readFileSync(resolve(source), 'utf8').replace(/^\uFEFF/, ''))
const features = geo && Array.isArray(geo.features) ? geo.features : []
if (!features.length) {
  throw new Error('不是预期的 GeoJSON：没有 features')
}

/** MultiPolygon / Polygon 都要吃得下：把最外环以外的坐标一路摊平取极值即可 */
function bboxOf(geometry) {
  if (!geometry || !geometry.coordinates) {
    return null
  }
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  const walk = (node) => {
    if (typeof node[0] === 'number') {
      const [x, y] = node
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
      return
    }
    node.forEach(walk)
  }
  walk(geometry.coordinates)
  if (!Number.isFinite(minX)) {
    return null
  }
  // 保留 2 位小数（约 1 km），省级外包框不需要更高精度
  return [minX, minY, maxX, maxY].map((v) => Number(v.toFixed(2)))
}

const rows = []
for (const feature of features) {
  const p = feature.properties || {}
  const adcode = String(p.adcode ?? '')
  const name = String(p.name ?? '')
  // 省码取前两位：900000（国外）这类没有几何的在下面一起跳过
  if (adcode.length !== 6 || !name) {
    continue
  }
  const center = Array.isArray(p.center) ? p.center : Array.isArray(p.centroid) ? p.centroid : null
  const box = bboxOf(feature.geometry)
  if (!center || !box) {
    console.log(`跳过 ${adcode} ${name}：没有中心点或几何（国外与海域归属就是这种）`)
    continue
  }
  rows.push({
    code: adcode.slice(0, 2),
    name,
    center: [Number(center[0].toFixed(4)), Number(center[1].toFixed(4))],
    bbox: box
  })
}
rows.sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0))
if (!rows.length) {
  throw new Error('一份可用的省级记录都没取到')
}

const body = rows
  .map((r) => `  { code: '${r.code}', name: '${r.name}', center: [${r.center.join(',')}], bbox: [${r.bbox.join(',')}] },`)
  .join('\n')

const banner = `/**
 * 省级单位的中心点与外包框，由 scripts/gen-province-centers.mjs 生成，请勿手改。
 *
 * 数据源：阿里云 DataV 行政区划边界 https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json
 * 用途：  utils/regionLookup.js 的第一步——按经纬度锁定省，再按需拉省级文件判市、拉市级文件判区县。
 * 规模：  ${rows.length} 条（没有几何的「国外」等已被剔除）
 *
 * center 是政府驻地，bbox 是该省全部陆地坐标的外包框 [最小经度, 最小纬度, 最大经度, 最大纬度]。
 * 这两个值都只用来<b>缩小候选</b>，最终归属由省级文件的精确多边形判定，所以这里的粗一点不影响正确性。
 */
`
writeFileSync(
  resolve(process.cwd(), 'im-ui/src/data/provinceCenters.js'),
  `${banner}export const PROVINCE_CENTERS = [\n${body}\n]\n`,
  'utf8'
)
console.log(`已生成 im-ui/src/data/provinceCenters.js，共 ${rows.length} 条`)
