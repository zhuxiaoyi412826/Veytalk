/**
 * 把扁平的行政区划字典建成三级树，并提供「名称 ↔ 编码」两套互查。
 *
 * 三条设计口径：
 * 1. **对外的存储格式仍然是三个名字**（im_user_profile.province / city / district 是三段文本，
 *    同城筛选按 city 做包含匹配）。编码只活在前端这一层，用来把用户框住成合法的三级联动值；
 *    存编码就得改表，而这三列本来还承载着历史手填数据（「深圳」这种少「市」字的写法）。
 * 2. **名称不唯一，所以按层级查**：全国有几十个「城关区」、上百个「城区」。全局建一张
 *    名称到编码的倒排表会得到一个错的编码，这里一律先定位省再定位市最后定位区县。
 * 3. 编码长度**不能用来判层级**：济源市、海南各县、新疆兵团各市是六位码却挂在省下
 *    （见 gen-region-tree.mjs 的注释），树是按父码建的，判级也就只在这份树上走。
 */
import { REGION_ROWS } from './regionRows'

/** 一个节点：{ code, name, parent, level, children } */
function buildTree() {
  const nodes = new Map()
  for (const [code, parent, name] of REGION_ROWS) {
    nodes.set(code, { code, parent, name, level: parent === '0' ? 1 : parent.length / 2 + 1, children: [] })
  }
  const roots = []
  // REGION_ROWS 是按编码升序的，父行一定先于子行入表，所以这里可以直接挂
  for (const node of nodes.values()) {
    if (node.parent === '0') {
      roots.push(node)
      continue
    }
    const parent = nodes.get(node.parent)
    // 生成脚本已经保证父行存在，真到这一步只能是手改了 regionRows.js
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }
  return { nodes, roots }
}

const { nodes: REGION_NODES, roots: PROVINCES } = buildTree()

/** el-cascader 的 options：只有这一份，别在页面里再拼一遍 */
const REGION_OPTIONS = PROVINCES.map((province) => ({
  value: province.code,
  label: province.name,
  children: province.children.map((city) => ({
    value: city.code,
    label: city.name,
    children: city.children.map((district) => ({ value: district.code, label: district.name }))
  }))
}))

/** 三级联动的可选项（懒建：只有打开资料页才会加载这个 chunk） */
export function regionOptions() {
  return REGION_OPTIONS
}

/** 一个市有没有下级区县：重庆郊县这类到底的档要允许只选到市 */
export function hasDistricts(cityCode) {
  const city = REGION_NODES.get(cityCode)
  return !!city && city.children.length > 0
}

/**
 * 一个节点的直接下级编码（省给市、市给区县）。
 *
 * 定位反查走兜底那条路时要在「这一档的候选里挑最近的」，而不是全国三千多个一起比：
 * 市与区县重名重码前缀的太多，先按父码缩到十几个候选，最近中心点才有意义。
 */
export function childCodes(parentCode) {
  const node = REGION_NODES.get(parentCode)
  return node ? node.children.map((child) => child.code) : []
}

/** 编码数组 → 三级名称数组，认不出的那一档给空串（调用方按名字写库，所以宁可短也不要错） */
export function regionNames(codes) {
  const names = []
  for (const code of codes || []) {
    const node = REGION_NODES.get(code)
    if (!node) {
      break
    }
    names.push(node.name)
  }
  return names
}

/** 编码数组 → 「广东省深圳市南山区」这样的可读文本，只给已确定的那几档 */
export function regionText(codes) {
  return regionNames(codes).join('')
}

/**
 * 三级名称 → 编码数组，用于把库里已有的文本回填到级联框。
 *
 * 逐层缩范围而不是全局查名字（理由见文件头）。某一档匹配不上就<b>停在这一档返回 null</b>：
 * 半截的编码会让级联框显示成一个「看起来选好了」的错误地址，不如让调用方走「原样文本」那条路。
 *
 * @returns {string[]|null} 命中全部已填档时返回编码数组，否则 null
 */
export function regionCodes(province, city, district) {
  const pick = (parent, name) => (parent || []).find((node) => node.name === name)
  const p = pick(PROVINCES, province)
  if (!p) {
    return null
  }
  if (!city) {
    return district ? null : [p.code]
  }
  const c = pick(p.children, city)
  if (!c) {
    return null
  }
  if (!district) {
    return [p.code, c.code]
  }
  const d = pick(c.children, district)
  return d ? [p.code, c.code, d.code] : null
}

/**
 * 行政区划编码（六位，GB/T 2260）→ 本字典里的三级编码。
 *
 * 定位反查那一步拿到的是国家统计局口径的六位 adcode，而本字典的市级码是四位（4403 = 深圳市），
 * 对不上，所以这里做一次口径换算：
 *  - 省 = 前两位；
 *  - 市 = 前四位，查不到再拿整个六位当市码（济源 419001、兵团各市属于这种）；
 *  - 区县 = 整个六位，且它的父码必须正好是上一步定下来的那个市。
 * 直辖市（110101 东城区）走的是同一条路：前四位 1101 在字典里就是「北京市」那一档。
 *
 * @returns {{codes: string[], names: string[]}|null} 连省都对不上时 null
 */
export function regionByAdcode(adcode) {
  const code = String(adcode || '').trim()
  if (code.length < 6) {
    return null
  }
  const province = REGION_NODES.get(code.slice(0, 2))
  if (!province || province.level !== 1) {
    return null
  }
  const city = REGION_NODES.get(code.slice(0, 4)) || REGION_NODES.get(code)
  if (!city || city.parent !== province.code) {
    return { codes: [province.code], names: [province.name] }
  }
  const district = REGION_NODES.get(code)
  if (district && district.parent === city.code && district !== city) {
    return { codes: [province.code, city.code, district.code], names: [province.name, city.name, district.name] }
  }
  return { codes: [province.code, city.code], names: [province.name, city.name] }
}

/** 省这一档的 [{code, name}]，给「按坐标先找最近的省」用 */
export function provinceList() {
  return PROVINCES.map((node) => ({ code: node.code, name: node.name }))
}
