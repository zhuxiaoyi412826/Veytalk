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
 * ★ 第 2、3 步拉不到（内网、断网、慢网超时、EXE/APK 里没外网）时走<b>随包兜底表</b>
 *   data/regionCenters.js（市与区县的中心点 + 外包框，动态 import，不进首屏包），
 *   按「外包框筛候选 → 中心点最近」照样把三级填出来——不再出现「有的定位只显示省」。
 *   兜底推断的结果一律标 approximate 并且<b>不进缓存</b>：缓存了的话，等网络恢复也拿不到精确结果。
 *
 * 精度取舍：多边形判不出来（点落在全省所有市的边界外，例如省级本身就猜错）时，退到
 * 「按中心点最近」并打上 approximate 标记，由调用方在界面上说明这是推断的。宁可标注也不
 * 要假装精确。
 */
import { PROVINCE_CENTERS } from '@/data/provinceCenters'
import { regionByAdcode, regionNames, childCodes, hasDistricts } from '@/data/regionTree'
import { getCache, setCache } from '@/utils/localCache'

const BOUND_BASE = (import.meta.env && import.meta.env.VITE_REGION_BOUNDARY_BASE)
  || 'https://geo.datav.aliyun.com/areas_v3/bound'

/**
 * 单次取边界的硬超时：这是给一个输入框预填用的，不是主流程，慢就必须让路。
 *
 * <p>原来是 6 秒只试一次——手机上 DNS + TLS + 200KB 很容易吃满 6 秒，一超时就直接降到
 * 「只填到省」，用户看到的就是「有的定位只显示省」。放宽到 10 秒并再试一次，真连不上还有兜底表。
 */
const FETCH_TIMEOUT_MS = 10000

/** 尝试次数：只有「可能重试就好」的错才重试（超时、断网、5xx）；404 是这一档本来就没有下级文件 */
const FETCH_ATTEMPTS = 2

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

/** 拉一次：把 GeoJSON 裁成只留判定要用的字段（rings 是大头，不裁的话一份文件几百 KB 压在内存里） */
async function fetchOnce(adcode) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
  try {
    const response = await fetch(`${BOUND_BASE}/${adcode}_full.json`, { signal: controller.signal })
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`)
      // 404 是「这一档本来就没有下级文件」（济源市这种省直辖县级市、兵团各市就是），重试也还是 404
      error.retryable = response.status >= 500
      throw error
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
      // 没几何的条目（海域归属这类）直接丢：没环就判不了点在不在里面，留着只会让 pickFeature 退到最近中心点
      .filter((item) => item.rings.length)
    if (!features.length) {
      throw new Error('没有可用的边界')
    }
    return features
  } catch (error) {
    // abort（超时）、网络断、JSON 解析失败都属于「再试一次可能就好」
    if (error.retryable === undefined) {
      error.retryable = true
    }
    throw error
  } finally {
    clearTimeout(timer)
  }
}

async function fetchBoundaries(adcode) {
  if (boundaryCache.has(adcode)) {
    return boundaryCache.get(adcode)
  }
  let lastError = null
  for (let attempt = 1; attempt <= FETCH_ATTEMPTS; attempt += 1) {
    try {
      const features = await fetchOnce(adcode)
      boundaryCache.set(adcode, features)
      return features
    } catch (error) {
      lastError = error
      if (!error.retryable) {
        break
      }
    }
  }
  throw lastError || new Error('取不到边界')
}

/** 兜底表索引：字典编码 -> [编码, 中心经度, 中心纬度, 最小经, 最小纬, 最大经, 最大纬]，只在真连不上 DataV 时才加载 */
let centerIndex = null

async function centerTable() {
  if (!centerIndex) {
    const { REGION_CENTERS } = await import('@/data/regionCenters')
    centerIndex = new Map(REGION_CENTERS.map((row) => [row[0], row]))
  }
  return centerIndex
}

/**
 * 兜底判一档：在 parentCode 的下级里挑一个（省给市、市给区县）。
 *
 * 先用外包框筛：框中一个就用它，框中多个取中心点最近；一个都没框中（字典比 DataV 新、
 * 或点落在飞地）就在全省下级里取最近的——这时候最近中心点也比空着强。
 * 中心点最近不等于边界判定，所以这一档一律算推断，由调用方在界面上说清。
 *
 * @returns {Promise<string|null>} 这一档没有可用中心点（台湾、兵团新设市）时 null
 */
async function pickByCenter(parentCode, lng, lat) {
  try {
    const codes = childCodes(parentCode)
    if (!codes.length) {
      return null
    }
    const table = await centerTable()
    const candidates = codes.map((code) => table.get(code)).filter(Boolean)
    if (!candidates.length) {
      return null
    }
    const near = (row) => (row[1] - lng) ** 2 + (row[2] - lat) ** 2
    const boxed = candidates.filter((row) => lng >= row[3] && lng <= row[5] && lat >= row[4] && lat <= row[6])
    const pool = boxed.length ? boxed : candidates
    let best = pool[0]
    for (const row of pool) {
      if (near(row) < near(best)) {
        best = row
      }
    }
    return best[0]
  } catch {
    // 兜底表这个 chunk 没部署上去（dist 覆盖不完整）也不能把整个反查弄崩，退回「只填到省」
    return null
  }
}

/**
 * 名字 → 字典编码（在 parentCode 的下级里找）。
 *
 * <p>★ DataV 的行政区划比随包字典旧：撤县设区换了码以后，它还在发旧码（坐标在崇川区，
 * 它给 320602，而字典里崇川区已经是 320613），regionByAdcode 按编码就查不到，只能退回两级——
 * 这也是「明明连上了网却填不满三级」的一个真因。名字比编码稳（改名远比改码少），
 * 所以编码对不上时再按名字找一次，找到的仍然是精确多边形的结果，不算推断。
 *
 * @returns {string|null} 这一档里没有同名的就 null（不猜，猜错一个区比空着难收拾）
 */
function codeByName(parentCode, name) {
  const target = String(name || '').trim()
  if (!target) {
    return null
  }
  for (const code of childCodes(parentCode)) {
    if (regionNames([code])[0] === target) {
      return code
    }
  }
  return null
}

/**
 * 第三档（区县）：先拉市级文件用多边形精确判，拉不到才退回随包中心点表。
 *
 * <p>两种失败要分开：① 拉不到（内网 / 超时）——连「这个点在哪个市」都没法确证，用中心点表
 * 推断一档也比只填到市强；② 拉到了但编码对不上字典——这是版本差，先按名字找（上面那个函数），
 * 名字也对不上就不猜：手里握着精确边界还猜错一个区，比老实少填一档坑得多。
 *
 * @param {string} cityAdcode DataV 口径的市 adcode（六位，以 00 结尾）
 * @param {string} cityCode   字典口径的市编码（四位，省直辖县级单位是六位）
 * @returns {Promise<{code: string, guessed: boolean}|null>}
 */
async function pickDistrict(cityAdcode, cityCode, lng, lat) {
  try {
    const districts = await fetchBoundaries(cityAdcode)
    const inner = pickFeature(districts, lng, lat)
    if (!inner.feature) {
      return null
    }
    const matched = regionByAdcode(inner.feature.adcode)
    // 市那一档必须对得上：省级文件把坐标归到 A 市、市级文件却判出 B 市的区，宁可不要这一档
    if (matched && matched.codes.length === 3 && matched.codes[1] === cityCode) {
      return { code: matched.codes[2], guessed: inner.approximate }
    }
    const byName = codeByName(cityCode, inner.feature.name)
    return byName ? { code: byName, guessed: inner.approximate } : null
  } catch {
    const code = await pickByCenter(cityCode, lng, lat)
    return code ? { code, guessed: true } : null
  }
}

/**
 * 零请求定省：给「点了定位先把省填上」用。
 *
 * <p>判市判区要等公网边界文件，慢网下是几秒的事；省级这一档查的是随包的 34 条外包框，
 * 同步就能出结果。先填一档，用户至少立刻看到框里有东西，而不是盯着空框等。
 *
 * @returns {{code: string, name: string}|null} 境外与海域（一个省的外包框都框不中）返回 null
 */
export function provinceByCoords(lng, lat) {
  const longitude = Number(lng)
  const latitude = Number(lat)
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    return null
  }
  const province = pickProvince(longitude, latitude)
  return province ? { code: province.code, name: province.name } : null
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
 * <p>三档分开降级：公网边界能用就用（多边形精确判），用不了就退到随包中心点表，
 * 连中心点表也没有（台湾、兵团新设市）才停在省——目的就是让「只显示省」成为少见情况。
 *
 * @returns {Promise<{codes: string[], names: string[], text: string, level: number, approximate: boolean, note: string}>}
 *          level 是确定的档数（1 省 2 市 3 区县）；note 是可直接展示的降级说明，精确命中时为空串
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
  let codes = [province.code]
  let approximate = false
  /** 有任意一档是靠随包中心点表推出来的：结果不进缓存，note 要说清这是推断的 */
  let guessed = false
  try {
    // 第二档：省级文件给的是市；直辖市与港澳那份给的直接就是区（一次就出三级）
    const children = await fetchBoundaries(`${province.code}0000`)
    const picked = pickFeature(children, longitude, latitude)
    if (!picked.feature) {
      throw new Error('省级文件里没有可用边界')
    }
    approximate = picked.approximate
    const matched = regionByAdcode(picked.feature.adcode)
    if (matched && matched.codes.length >= 2) {
      codes = matched.codes
    } else {
      // 省级文件给的 adcode 字典里查不到（同样是版本差）：按名字找市这一档
      const byName = codeByName(province.code, picked.feature.name)
      codes = byName ? [province.code, byName] : [province.code]
    }
    // 只到市这一档、而这个市在字典里还有区县（济源市这类省直辖县级单位到底了，不拉）
    if (codes.length === 2 && hasDistricts(codes[1])) {
      const third = await pickDistrict(picked.feature.adcode, codes[1], longitude, latitude)
      if (third) {
        codes = [...codes, third.code]
        guessed = guessed || third.guessed
        approximate = approximate || third.guessed
      }
    }
  } catch {
    // 省级文件都拉不到（内网 / 断网 / 超时 / 镜像挂了）：不弹错，交给下面「只剩省」那段走兜底表
    guessed = true
  }
  if (codes.length === 1) {
    // 公网那一趟没给出市（拉不到，或 DataV 发的 adcode 字典里没有）：用随包中心点表把市与区县补上
    const cityCode = await pickByCenter(province.code, longitude, latitude)
    if (cityCode) {
      codes = [province.code, cityCode]
      guessed = true
      approximate = true
      const districtCode = hasDistricts(cityCode) ? await pickByCenter(cityCode, longitude, latitude) : null
      if (districtCode) {
        codes = [...codes, districtCode]
      }
    }
  }
  let names = regionNames(codes)
  if (names.length !== codes.length) {
    // 理论上到不了（codes 全都来自字典）；真到了就是字典与中心点表对不上，宁可只报省也不要报半截错地址
    codes = [province.code]
    names = [province.name]
  }
  const text = names.join('')
  let note = ''
  if (guessed && names.length === 1) {
    note = '没连上行政区划数据源，只填到省，市与区县请补'
  } else if (guessed) {
    note = `没连上行政区划边界数据，按随包的中心点表推断为 ${text}，如不符请手动改`
  } else if (approximate) {
    note = `按离得最近的位置推断为 ${text}，如不符请手动改`
  }
  const result = {
    codes,
    names,
    text,
    level: names.length,
    approximate,
    note
  }
  // 推断出来的不缓存：缓存 30 天的话，等网络恢复了也拿不到精确结果
  if (!guessed) {
    setCache(key, result, RESULT_TTL_MS)
  }
  return result
}
