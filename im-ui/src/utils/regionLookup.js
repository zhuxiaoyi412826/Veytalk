/**
 * 经纬度 → 省 / 市 / 区县三级（定位自动回填）。
 *
 * 数据源是阿里云 DataV 的公开行政区划边界（免密钥、允许跨域），base 地址可用环境变量
 * VITE_REGION_BOUNDARY_BASE 换成自建镜像——内网与打包成 EXE/APK 的场景大概率连不上公网，
 * 换源比改代码靠谱。
 *
 * ★ 定位本身不产生任何网络请求（那是浏览器/操作系统的位置服务），本模块的这一步才是。
 *   所以「我明明定位了却没看到请求」是正常的，而有请求失败也不该影响定位：这里的每一次
 *   失败都只降级成「少填一档」，绝不把已经拿到的坐标丢掉。
 *
 * 三步走的取数策略：
 *  1. 省：查随包带的小表 data/provinceCenters.js（34 条中心点 + 外包框），零请求、离线可用；
 *  2. 市：按需拉 {省码}0000_full.json，用多边形精确判定，只有全国 34 份、单份 30~200KB，
 *     不值得为它撑大首屏包，所以运行时取、内存里存这一次会话；
 *  3. 区县：再拉 {市码}0000_full.json，同样多边形判定。直辖市那份给的就是区，一次到底。
 *
 * 精度取舍：多边形判不出来（点落在全省所有市的边界外，例如省级本身就猜错）时，退到
 * 「按中心点最近」并打上 approximate 标记，由调用方在界面上说明这是推断的。宁可标注也不
 * 要假装精确。
 */
import { PROVINCE_CENTERS } from '@/data/provinceCenters'
import { regionByAdcode } from '@/data/regionTree'
import { getCache, setCache } from '@/utils/localCache'

const BOUND_BASE = (import.meta.env && import.meta.env.VITE_REGION_BOUNDARY_BASE)
  || 'https://geo.datav.aliyun.com/areas_v3/bound'

/** 单次取边界的硬超时：这是给一个输入框预填用的，不是主流程，慢就必须让路 */
const FETCH_TIMEOUT_MS = 6000

/** 反查结果缓存 30 天：行政区划一年也改不了一次，而回填这件事每天都在点 */
const RESULT_TTL_MS = 30 * 24 * 60 * 60 * 1000

/** 会话内的边界文件缓存：adcode -> features（已裁成只含判定要用的字段） */
const boundaryCache = new Map()

/** 约 1km 的网格键：同一个人在同一栋楼里反复保存资料不该每次都拉一遍边界 */
function gridKey(lng, lat) {
  return `region.rev.${lat.toFixed(2)}.${lng.toFixed(2)}`
}

/** 射线法判环：ring 是 [[lng, lat], ...] 的闭合环 */
function inRing(ring, lng, lat) {
  let hit = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const xi = ring[i][0]
    const yi = ring[i][1]
    const xj = ring[j][0]
    const yj = ring[j][1]
    const crosses = (yi > lat) !== (yj > lat)
    if (crosses && lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) {
      hit = !hit
    }
  }
  return hit
}

/** Polygon 与 MultiPolygon 都摊平成「环列表」，只留每个面的外环（内环是湖中岛那种洞，判它反而错） */
function ringsOf(geometry) {
  if (!geometry || !geometry.coordinates) {
    return []
  }
  const polys = geometry.type === 'MultiPolygon' ? geometry.coordinates : geometry.type === 'Polygon' ? [geometry.coordinates] : []
  return polys.map((polygon) => polygon[0]).filter((ring) => Array.isArray(ring) && ring.length > 3)
}

function distanceToCenter(feature, lng, lat) {
  if (!feature.center) {
    return Number.POSITIVE_INFINITY
  }
  const dx = feature.center[0] - lng
  const dy = feature.center[1] - lat
  return dx * dx + dy * dy
}

/** 先按多边形精确判，判不出来才退到最近中心点，并用返回值把这件事告诉调用方 */
function pickFeature(features, lng, lat) {
  const exact = features.find((f) => f.rings.some((ring) => inRing(ring, lng, lat)))
  if (exact) {
    return { feature: exact, approximate: false }
  }
  let best = null
  let bestDistance = Number.POSITIVE_INFINITY
  for (const item of features) {
    const distance = distanceToCenter(item, lng, lat)
    if (distance < bestDistance) {
      best = item
      bestDistance = distance
    }
  }
  return { feature: best, approximate: true }
}

async function fetchBoundaries(adcode) {
  if (boundaryCache.has(adcode)) {
    return boundaryCache.get(adcode)
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
  try {
    const response = await fetch(`${BOUND_BASE}/${adcode}_full.json`, { signal: controller.signal })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const geo = await response.json()
    const features = (geo.features || [])
      .filter((item) => item && item.properties && item.properties.adcode)
      .map((item) => ({
        adcode: String(item.properties.adcode),
        name: item.properties.name,
        level: item.properties.level,
        center: item.properties.center || item.properties.centroid,
        rings: ringsOf(item.geometry)
      }))
      // 市级码一律以 00 结尾，区县级不是；这一步把「省直辖县级单位」那种既当市又当区县的东西分开
      .filter((item) => item.rings.length)
    if (!features.length) {
      throw new Error('没有可用的边界')
    }
    boundaryCache.set(adcode, features)
    return features
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 锁定省级候选。
 *
 * 先按外包框筛（中国陆地外的点一个都框不中），框中有多个就取中心点最近的那个。
 * 省的海域归属与「国外」在随包小表里没几何，所以框不中时<b>直接说无法判断</b>而不是
 * 硬猜一个：给境外用户回填一个国内地址，比让他手动选更坑。
 */
function pickProvince(lng, lat) {
  const boxed = PROVINCE_CENTERS.filter((item) => {
    const [minLng, minLat, maxLng, maxLat] = item.bbox
    return lng >= minLng && lng <= maxLng && lat >= minLat && lat <= maxLat
  })
  if (!boxed.length) {
    return null
  }
  let best = null
  let bestDistance = Number.POSITIVE_INFINITY
  for (const item of boxed) {
    const dx = item.center[0] - lng
    const dy = item.center[1] - lat
    const distance = dx * dx + dy * dy
    if (distance < bestDistance) {
      best = item
      bestDistance = distance
    }
  }
  return best
}

/**
 * 坐标 → 三级。永远 resolve，不 reject：拿不到就是「只填到能确定的那一档」。
 *
 * @returns {Promise<{codes: string[], names: string[], text: string, level: number, approximate: boolean, note: string}>}
 *          level 是确定的档数（1 省 2 市 3 区县）；note 是可直接展示的降级说明，成功时为空串
 */
export async function resolveRegionByCoords(lng, lat) {
  const empty = { codes: [], names: [], text: '', level: 0, approximate: false, note: '' }
  const longitude = Number(lng)
  const latitude = Number(lat)
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    return { ...empty, note: '坐标不完整，无法判断行政区划' }
  }
  const key = gridKey(longitude, latitude)
  const cached = getCache(key)
  if (cached && Array.isArray(cached.codes) && cached.codes.length) {
    return cached
  }
  const province = pickProvince(longitude, latitude)
  if (!province) {
    return { ...empty, note: '这个坐标不在国内行政区划范围内，请手动选择所在地区' }
  }
  let approximate = false
  let adcode = `${province.code}0000`
  try {
    // 第二档：省级文件给的是市；直辖市与港澳那份给的直接就是区（DataV 用 level 区分）
    const children = await fetchBoundaries(`${province.code}0000`)
    const picked = pickFeature(children, longitude, latitude)
    if (!picked.feature) {
      throw new Error('省级文件里没有可用边界')
    }
    approximate = picked.approximate
    adcode = picked.feature.adcode
    if (picked.feature.level === 'city') {
      // 第三档：市级文件给区县。拉不到或判不准就停在市这一档（市码以 00 结尾，换算后正好两级）
      try {
        const districts = await fetchBoundaries(adcode)
        const inner = pickFeature(districts, longitude, latitude)
        if (inner.feature && !inner.approximate) {
          adcode = inner.feature.adcode
        }
      } catch {
        // 区县拉不到不影响市与省，不弹错误
      }
    }
  } catch {
    // 公网取不到边界（内网 / 断网 / 镜像挂了）：只回填到省，并说明原因
    const only = regionByAdcode(`${province.code}0000`) || { codes: [province.code], names: [province.name] }
    return {
      codes: only.codes,
      names: only.names,
      text: only.names.join(''),
      level: only.names.length,
      approximate: false,
      note: '没连上行政区划数据源，只填到省，市与区县请补'
    }
  }
  const matched = regionByAdcode(adcode)
  if (!matched || !matched.codes.length) {
    return {
      codes: [province.code],
      names: [province.name],
      text: province.name,
      level: 1,
      approximate,
      note: `定位落在字典里查不到的编号（${adcode}），请手动选择`
    }
  }
  const result = {
    codes: matched.codes,
    names: matched.names,
    text: matched.names.join(''),
    level: matched.names.length,
    approximate,
    note: approximate ? `按离得最近的位置推断为 ${matched.names.join('')}，如不符请手动改` : ''
  }
  setCache(key, result, RESULT_TTL_MS)
  return result
}
