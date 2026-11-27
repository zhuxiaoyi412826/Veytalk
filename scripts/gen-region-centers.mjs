/**
 * 生成「市级 + 区县级」中心点与外包框小表 im-ui/src/data/regionCenters.js。
 *
 * 为什么要这张表：utils/regionLookup.js 判市、判区县要按需拉阿里云 DataV 的边界文件，
 * 拉不到（内网、断网、慢网超时、打包成 EXE/APK 后没外网）就只能回填到省 —— 用户看到的
 * 就是「有的定位只显示省」。省级那一档本来就有随包的 provinceCenters.js 兜着，这张表把
 * 兜底能力补到市与区县：拉不到边界时按「外包框筛候选 → 中心点最近」推断，仍然能填三级。
 *
 * 它是<b>兜底</b>不是主路：能连上 DataV 就用精确多边形判（更准），连不上才用这张表，
 * 且用这张表推断出来的结果一律标 approximate，界面上要说明是推断的。
 *
 * 数据源（免密钥、允许跨域）：
 *   https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json
 *   省一份（35 次请求）给出市或直辖市的区，再对「字典里有下级的市」各拉一份给出区县。
 * 用法：node scripts/gen-region-centers.mjs      （约 400 次请求，几分钟，需要外网）
 * 输出：im-ui/src/data/regionCenters.js（生成文件，勿手改）
 *
 * ★ 这张表按 <b>动态 import</b> 用（只有兜底那条路才加载），所以体积不进首屏包，
 *   160KB 左右是可接受的；真嫌大就把它裁成只有市级，区县继续走公网。
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SOURCE = resolve(ROOT, 'release/省市县三级联动数组.json')
const TARGET = resolve(ROOT, 'im-ui/src/data/regionCenters.js')
const BASE = process.env.REGION_BOUNDARY_BASE || 'https://geo.datav.aliyun.com/areas_v3/bound'

const CONCURRENCY = 8
const TIMEOUT_MS = 30000
const ATTEMPTS = 3

mkdirSync(dirname(TARGET), { recursive: true })

/* ---------------------------------------------------------------- 字典 ---- */

// 与 gen-region-tree.mjs 同一套口径：层级按父码长度判，第四级（419001000 济源市）是同名重复叶子，丢掉
const dict = new Map()
for (const row of JSON.parse(readFileSync(SOURCE, 'utf8').replace(/^\uFEFF/, ''))) {
  const code = String(row.i ?? '').trim()
  const name = String(row.n ?? '').trim()
  const parent = String(row.p ?? '').trim()
  if (!code || !name || code.length === 9) {
    continue
  }
  dict.set(code, { code, name, parent, level: parent === '0' || parent === '' ? 1 : parent.length / 2 + 1 })
}
const childrenOf = (code) => [...dict.values()].filter((row) => row.parent === code)
const provinces = [...dict.values()].filter((row) => row.level === 1 && row.code.length === 2)

/**
 * DataV 的六位 adcode → 本字典的编码（市是四位，区县与省直辖县级单位是六位）。
 *
 * ★ 只有「以 00 结尾」的 adcode 才能截成四位当市码：DataV 的行政区划比字典旧，
 * 撤县设区后旧码（320602 崇川区）在字典里已经不存在，要是让它退到 '3206'（南通市），
 * 就会拿一个区的中心点冒充整个市的中心点，兜底那条路会拿它把邻市的坐标归错市。
 * 宁可让它进不了表（归入 unmapped 报告），也不要给个错的中心点。
 */
function toDictCode(adcode) {
  const six = String(adcode || '')
  if (six.length !== 6) {
    return ''
  }
  if (dict.has(six)) {
    return six
  }
  if (six.endsWith('00')) {
    const four = six.slice(0, 4)
    return dict.has(four) ? four : ''
  }
  return ''
}

/* ------------------------------------------------------------- 取数与几何 ---- */

function sleep(ms) {
  return new Promise((done) => setTimeout(done, ms))
}

async function fetchFull(adcode) {
  for (let attempt = 1; attempt <= ATTEMPTS; attempt += 1) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
    try {
      const response = await fetch(`${BASE}/${adcode}_full.json`, { signal: controller.signal })
      if (response.status === 404) {
        return { adcode, features: [], missing: true }
      }
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      const geo = await response.json()
      return { adcode, features: Array.isArray(geo.features) ? geo.features : [], missing: false }
    } catch (error) {
      if (attempt === ATTEMPTS) {
        console.log(`  放弃 ${adcode}：${error.message}`)
        return { adcode, features: [], missing: false, failed: true }
      }
      await sleep(400 * attempt)
    } finally {
      clearTimeout(timer)
    }
  }
  return { adcode, features: [], missing: true }
}

/** 并发跑一批，保住顺序不重要（后面按编码排序），重要的是别把 DataV 打崩 */
async function pool(tasks) {
  const results = []
  let cursor = 0
  const worker = async () => {
    while (cursor < tasks.length) {
      const index = cursor
      cursor += 1
      results[index] = await tasks[index]()
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, tasks.length) }, worker))
  return results
}

/** 外包框：只取每个面的最外环以外的全部坐标极值，向外扩到 0.01°（约 1km），免得把边界上的点框出去 */
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
  return [
    Math.floor(minX * 100) / 100,
    Math.floor(minY * 100) / 100,
    Math.ceil(maxX * 100) / 100,
    Math.ceil(maxY * 100) / 100
  ]
}

/* ------------------------------------------------------------------ 主流程 ---- */

const centers = new Map() // dictCode -> [lng, lat, minLng, minLat, maxLng, maxLat]
const unmapped = []
const nameMatched = []
let fetched = 0

/**
 * DataV 发的 adcode 字典里没有时（撤县设区换了码），按名字在同一父下找一档。
 *
 * 名字比编码稳：320602 崇川区（旧码）在字典里是 320613 崇川区，按名字就能对上；
 * 对不上的话这个区在兜底表里永远缺位，断网时只能把它猜成邻区。
 */
function codeByNameUnder(parentCodes, name) {
  const target = String(name || '').trim()
  if (!target) {
    return ''
  }
  for (const parentCode of parentCodes) {
    const hit = childrenOf(parentCode).find((row) => row.name === target)
    if (hit) {
      return hit.code
    }
  }
  return ''
}

/**
 * 把一份文件里的 feature 入表。
 *
 * @param {Array} features DataV 的 feature 列表
 * @param {string[]} scope 名字兜底时的候选父码：先试市一级，再试区县一级
 *                         （直辖市与重庆那份省文件给的直接就是区，父是市那一级）
 */
function absorb(features, scope) {
  for (const feature of features) {
    const properties = feature.properties || {}
    const center = Array.isArray(properties.center) ? properties.center : properties.centroid
    const box = bboxOf(feature.geometry)
    let code = toDictCode(properties.adcode)
    let viaName = false
    if (!code) {
      code = codeByNameUnder(scope, properties.name)
      viaName = !!code
    }
    if (!code || !center || !box) {
      if (properties.adcode) {
        unmapped.push(`${properties.adcode}(${properties.name || '?'})`)
      }
      continue
    }
    if (!centers.has(code)) {
      centers.set(code, [Number(center[0].toFixed(4)), Number(center[1].toFixed(4)), ...box])
      if (viaName) {
        nameMatched.push(`${properties.adcode}->${code}(${properties.name})`)
      }
    }
  }
}

// 第一趟：每个省一份，拿到市（直辖市与港澳那份直接给区）
const provinceTasks = provinces.map((province) => async () => {
  const got = await fetchFull(`${province.code}0000`)
  fetched += 1
  if (fetched % 10 === 0) {
    console.log(`  省级进度 ${fetched}/${provinces.length}`)
  }
  // 名字兜底的范围：本市一级优先（普通省），再看市下面的区县（直辖市与重庆）
  absorb(got.features, [province.code, ...childrenOf(province.code).map((row) => row.code)])
})
await pool(provinceTasks)
console.log(`省级 ${provinces.length} 份取完，已入表 ${centers.size} 条`)

// 第二趟：字典里有下级、而下级还没中心点的市（直辖市的区上一趟已经拿到了，不用再拉）
const cityTasks = []
for (const city of [...dict.values()].filter((row) => row.level === 2)) {
  const kids = childrenOf(city.code)
  if (!kids.length || kids.every((kid) => centers.has(kid.code))) {
    continue
  }
  const adcode = city.code.length === 4 ? `${city.code}00` : city.code
  cityTasks.push(async () => {
    const got = await fetchFull(adcode)
    fetched += 1
    if (fetched % 25 === 0) {
      console.log(`  市级进度 ${fetched - provinces.length}/${cityTasks.length}`)
    }
    absorb(got.features, [city.code])
  })
}
console.log(`需要再拉 ${cityTasks.length} 份市级文件`)
await pool(cityTasks)

// 第三趟：字典里有、DataV 却没给的节点（口径差异、新设的区），用「下级中心点的均值 + 外包框并集」补上，
// 补不出来的就不进表 —— 进不了表的节点在兜底那条路上永远选不中，比给个假中心点安全
const derived = []
for (const row of dict.values()) {
  if (row.level < 2 || centers.has(row.code)) {
    continue
  }
  const kids = childrenOf(row.code).map((kid) => centers.get(kid.code)).filter(Boolean)
  if (!kids.length) {
    continue
  }
  const lng = kids.reduce((sum, item) => sum + item[0], 0) / kids.length
  const lat = kids.reduce((sum, item) => sum + item[1], 0) / kids.length
  centers.set(row.code, [
    Number(lng.toFixed(4)),
    Number(lat.toFixed(4)),
    Math.min(...kids.map((item) => item[2])),
    Math.min(...kids.map((item) => item[3])),
    Math.max(...kids.map((item) => item[4])),
    Math.max(...kids.map((item) => item[5]))
  ])
  derived.push(`${row.code}(${row.name})`)
}

const rows = [...centers.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
const cityRows = rows.filter(([code]) => dict.get(code)?.level === 2).length
const districtRows = rows.length - cityRows

const body = rows
  .map(([code, values]) => `  ['${code}',${values.join(',')}],`)
  .join('\n')

const banner = `/**
 * 市级与区县级的中心点、外包框，由 scripts/gen-region-centers.mjs 生成，请勿手改。
 *
 * 数据源：阿里云 DataV 行政区划边界 ${BASE}/{adcode}_full.json（共取 ${fetched} 份文件）
 * 结构：  [字典编码, 中心点经度, 中心点纬度, 最小经度, 最小纬度, 最大经度, 最大纬度]
 *         编码口径与 data/regionRows.js 一致（市四位 4403、区县与省直辖县级单位六位 440309 / 419001）；
 *         外包框已向外扩到 0.01°（约 1km），免得把边界上的点框出去。
 * 规模：  ${rows.length} 条（市级 ${cityRows} / 区县级 ${districtRows}${derived.length ? `，其中 ${derived.length} 条是按下级中心点均值算出来的` : ''}）
 *         DataV 发的旧码在字典里对不上时，有 ${nameMatched.length} 条是按名字对回字典的（320602 崇川区 → 320613 崇川区）。
 *
 * 用途：utils/regionLookup.js 的<b>兜底</b>——拉不到 DataV 边界文件（内网、断网、超时）时，
 *       按「外包框筛候选 → 中心点最近」推断市与区县，让定位不至于只填到省。
 *       ★ 它是动态 import 的，只有走兜底那条路才加载，不进首屏包；推断结果一律标 approximate。
 * 要更新就重跑：node scripts/gen-region-centers.mjs
 */
`
writeFileSync(TARGET, `${banner}export const REGION_CENTERS = [\n${body}\n]\n`, 'utf8')

console.log(`已生成 ${TARGET}`)
console.log(`入表 ${rows.length} 条：市级 ${cityRows} / 区县级 ${districtRows}，均值补出 ${derived.length} 条，按名字对回字典 ${nameMatched.length} 条`)
if (unmapped.length) {
  console.log(`DataV 给了但字典里没这一档、已跳过 ${unmapped.length} 条：${unmapped.slice(0, 12).join('、')}${unmapped.length > 12 ? ' …' : ''}`)
}
const missing = [...dict.values()].filter((row) => row.level >= 2 && !centers.has(row.code))
if (missing.length) {
  console.log(`字典里有但没取到中心点（兜底时选不中）${missing.length} 条：${missing.slice(0, 12).map((row) => `${row.code}(${row.name})`).join('、')}${missing.length > 12 ? ' …' : ''}`)
}
