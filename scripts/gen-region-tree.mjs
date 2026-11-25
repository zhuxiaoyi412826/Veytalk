/**
 * 由 release/省市县三级联动数组.json 生成前端用的扁平行政区划字典 regionRows.js。
 *
 * 为什么要生成而不是直接 import 那份 JSON：
 *  1. 源文件带 UTF-8 BOM，且字段名是 n / i / p / y 这种缩写，直接给 cascader 用还得在
 *     运行时再遍历一遍 3638 行去改键名；生成一次就把键名定下来，运行时只建树不改名。
 *  2. 源文件里混着 y（拼音首字母）这一列，前端三级联动用不上，去掉能省掉约四分之一的体积。
 *  3. 生成这一步顺带做校验（父码是否存在、层级是否只有 2/4/6 三种长度），
 *     字典本身有脏行的话要在构建期炸掉，而不是等用户在级联框里看到一个选不中的空节点。
 *
 * 用法：node scripts/gen-region-tree.mjs
 * 输出：im-ui/src/data/regionRows.js（生成文件，勿手改）
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SOURCE = resolve(ROOT, 'release/省市县三级联动数组.json')
const TARGET = resolve(ROOT, 'im-ui/src/data/regionRows.js')
// im-ui/src/data 是这一批新加的目录，从别的机器 clone 下来时它还不存在
mkdirSync(dirname(TARGET), { recursive: true })

// 该 JSON 带 BOM，不剥掉的话 JSON.parse 直接报 Unexpected token
const raw = readFileSync(SOURCE, 'utf8').replace(/^\uFEFF/, '')
const rows = JSON.parse(raw)
if (!Array.isArray(rows) || rows.length === 0) {
  throw new Error(`源文件不是一条数组：${SOURCE}`)
}

const byId = new Map()
const orphan = []
for (const row of rows) {
  const code = String(row.i ?? '').trim()
  const name = String(row.n ?? '').trim()
  const parent = String(row.p ?? '').trim()
  if (!code || !name) {
    throw new Error(`字典里有缺编码或缺名称的行：${JSON.stringify(row)}`)
  }
  if (byId.has(code)) {
    throw new Error(`编码重复：${code}（${byId.get(code).name} 与 ${name}）`)
  }
  if (![2, 4, 6, 9].includes(code.length)) {
    throw new Error(`编码长度只能是 2/4/6/9，实际 ${code}（${name}）`)
  }
  byId.set(code, { code, name, parent })
}
for (const row of byId.values()) {
  const isRoot = row.parent === '0' || row.parent === '' || row.parent === null
  if (!isRoot && !byId.has(row.parent)) {
    orphan.push(`${row.code}(${row.name}) -> ${row.parent}`)
  }
}
if (orphan.length) {
  throw new Error(`以下行的父码在字典里不存在：\n  ${orphan.join('\n  ')}`)
}

/**
 * 层级按<b>父码长度</b>判而不是按自身码长判：省直辖县级单位（济源 419001、海南各县 4690xx、
 * 新疆兵团各市 6590xx）在源数据里是六位码，却直接挂在两位的省下面——按自身码长判就把它们
 * 算成了区县，用户在级联框里永远选不到济源（河南省 → 不存在这一档）。
 *
 * 码长为 9 的那些是源数据多出来的一层：32 行，与它们的六位父行<b>完全同名</b>
 * （419001 济源市 → 419001000 济源市），留着只会让级联框出现「济源市 / 济源市」两格重复。
 * 这里直接丢掉，并在下面把「九位行只可能是同名叶子」这条前提钉死——将来换一份字典而这条
 * 不成立时要在构建期炸掉，而不是悄悄丢数据。
 */
const levelOf = (row) => (row.parent === '0' || row.parent === '' ? 1 : row.parent.length / 2 + 1)
const dropped = []
for (const row of [...byId.values()]) {
  if (levelOf(row) > 3) {
    const parent = byId.get(row.parent)
    if (!parent || parent.name !== row.name) {
      throw new Error(`第四级行 ${row.code}(${row.name}) 的父行 ${row.parent}(${parent?.name}) 不同名，不能当作重复行丢弃`)
    }
    dropped.push(row.code)
    byId.delete(row.code)
  }
}

const levels = [0, 0, 0, 0]
for (const row of byId.values()) {
  levels[levelOf(row) - 1] += 1
}
const [provinces, cities, districts] = levels
const roots = [...byId.values()].filter((r) => levelOf(r) === 1)
if (roots.some((r) => r.code.length !== 2)) {
  throw new Error('存在顶层但编码不是两位的行，层级判定不成立')
}

// 省按码升序（GB/T 2260 的顺序本身就是华北到边疆，最后两档是台湾/港澳/国外，别打乱）
const sorted = [...byId.values()].sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0))
const lines = sorted.map((r) => `  ['${r.code}','${r.parent}','${r.name}'],`)

const banner = `/**
 * 行政区划字典（省 / 市 / 区县三级），由 scripts/gen-region-tree.mjs 生成，请勿手改。
 *
 * 数据源：release/省市县三级联动数组.json（GB/T 2260 口径，源 ${rows.length} 行，
 *         其中 ${dropped.length} 行是「419001 济源市 → 419001000 济源市」这种与父行同名的重复叶子，已丢弃）
 * 结构：  [编码, 父编码, 名称] —— 父编码 '0' 即顶层，层级按<b>父码长度</b>判（见 regionTree.js 的注释）。
 * 规模：  ${provinces} 个省 / ${cities} 个市级 / ${districts} 个区县
 *         市级里包含济源市、海南各县、新疆兵团各市这些「六位码却直接挂省的」省直辖县级单位；
 *         选到它们就到底了，没有第三级，这是行政区划本身的样子，不是字典缺数据。
 * 用途：  交友资料的「所在地区」三级联动，以及定位坐标反查出三级名。
 *
 * 之所以是扁平表而不是嵌套树：嵌成三层要把每一层的键名重复一遍，文件要长一半；
 * 而建树这件事在 regionTree.js 里只做一次、且是纯内存 O(n)。
 * 要改字典就改源文件后重跑：node scripts/gen-region-tree.mjs
 */
`
writeFileSync(TARGET, `${banner}export const REGION_ROWS = [\n${lines.join('\n')}\n]\n`, 'utf8')

console.log(`已生成 ${TARGET}`)
console.log(`省 ${provinces} / 市 ${cities} / 区县 ${districts}，丢弃重复叶子 ${dropped.length} 行，入表 ${byId.size} 行`)
