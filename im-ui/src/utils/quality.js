/**
 * 直播/回放清晰度选项的**唯一口径**（观众端选择器、设置页默认值、开播面板都从这里取）。
 *
 * 为什么单独成文件：这段判定要能被 node 直接 import 跑测（im-ui 是 "type": "module"），
 * 挂在 Live.vue 里就只能靠真人开播才能验，而「哪一档可点」这件事错一次就是给观众一排死按钮。
 *
 * 命名统一用 p 记法（480p/720p/1080p/1440p），与开播面板的分辨率下拉一致——
 * 观众看到的四个选项和主播开播时选的四个必须同名，否则「切到 1K」这种话没人对得上。
 */

/** 固定的四个档位，顺序与开播面板相同（低到高，「自动」由调用方放在最前） */
export const QUALITY_CHOICES = [
  { height: 480, label: '480p' },
  { height: 720, label: '720p' },
  { height: 1080, label: '1080p' },
  { height: 1440, label: '1440p' }
]

/** 播放器形态 → 选择器的可用性；加进来才发现「能不能切」根本不是前端说了算 */
export const QUALITY_MODE = {
  /** hls.js + MSE：主清单里有几路就能切几路 */
  MSE: 'mse',
  /** 连麦合成流：主播端 MediaRecorder 只产一路 WebM，物理上无档可切 */
  WEBM: 'webm',
  /** Safari/iOS 原生 HLS：能播但拿不到 levels 也设不了 currentLevel */
  NATIVE: 'native'
}

/**
 * 档位高度与标准档位的匹配容差。
 * 推流端是 `scale=-2:H`，产物高度就是 H 本身，理论上不需要容差；留 8px 是因为 hls.js 的
 * level.height 也可能来自主清单的 RESOLUTION 属性，个别编码器会把 1440 写成 1438/1442。
 */
const TOLERANCE = 8

/** 高度 → 展示标签；非常规高度如实写出像素值，不套一个不存在的档位名 */
export function qualityLabel(height) {
  const hit = QUALITY_CHOICES.find((c) => c.height === Number(height))
  return hit ? hit.label : `${height}p`
}

/**
 * 把 hls.js 的 levels 去重成「每个高度一项」。
 * 同高度出现两路（主清单里重复列同一档，或 ABR 变体）时留码率大的那一路，
 * 否则 el-select 里会出现两个同名选项、value 撞车。
 *
 * @param {Array<{height:number,bitrate:number}>} levels hls.js 的 inst.levels（下标即 currentLevel 的序号）
 * @returns {Array<{index:number,height:number,bitrate:number}>} 从高到低
 */
export function dedupeTiers(levels) {
  const byHeight = new Map()
  ;(levels || []).forEach((lv, index) => {
    const height = Number(lv.height) || 0
    if (!height) return
    const bitrate = Number(lv.bitrate) || 0
    const prev = byHeight.get(height)
    if (!prev || bitrate > prev.bitrate) byHeight.set(height, { index, height, bitrate })
  })
  return [...byHeight.values()].sort((a, b) => b.height - a.height)
}

/**
 * 生成选择器的四个固定选项：能不能点、点对应哪个 level 序号、不能点时为什么。
 *
 * ★ 这里的取舍：选项**恒定四个**（和开播界面一样多），但本场没有的档位是 `enabled:false` +
 * 一句原因，而不是干脆不渲染。少渲染会让观众以为「这个直播间只能自动」，而真实原因是
 * 主播只推到 720p / 这是连麦合成流 / 这个浏览器不给切——把原因摊开比藏掉按钮有用。
 * 反过来也绝不给出「点了没反应」的假可用项：那比灰掉更糟。
 *
 * @param {Array<{index:number,height:number}>} tiers dedupeTiers 的结果
 * @param {string} mode QUALITY_MODE 之一
 */
export function buildQualityChoices(tiers, mode) {
  return QUALITY_CHOICES.map((c) => {
    const hit = tiers.find((t) => Math.abs(t.height - c.height) <= TOLERANCE)
    if (mode === QUALITY_MODE.MSE && hit) {
      return { value: String(c.height), label: c.label, enabled: true, index: hit.index, note: '' }
    }
    let note
    if (mode === QUALITY_MODE.WEBM) note = '单档'
    else if (mode === QUALITY_MODE.NATIVE) note = '不支持'
    else if (!tiers.length) note = '未标注'
    else note = '本场无'
    return { value: String(c.height), label: c.label, enabled: false, index: -1, note }
  })
}

/**
 * 把「用户想要的值」落到实际可点的选项上。
 * 偏好是跨直播间存的全局设置（settings.liveQuality），进到一个只推到 720p 的场次时
 * 1440p 必然落不了地——这时**回落自动**，绝不能把 currentLevel 锁到一个不存在的序号，
 * 那会让 hls.js 无流可拉直接黑屏。
 *
 * @returns {{choice: object|null}} 命中的可用选项，null 表示走自动
 */
export function resolveQualityChoice(choices, want) {
  if (want === 'auto' || want === null || want === undefined) return { choice: null }
  const hit = (choices || []).find((c) => c.value === String(want) && c.enabled)
  return { choice: hit || null }
}

/**
 * 选哪种播放方式。★ 顺序不能反：**先试 hls.js（MSE），只有它不支持才退回原生 HLS**。
 *
 * 反过来的写法（先判 `canPlayType('application/vnd.apple.mpegurl')`）会把能切档的浏览器
 * 白推进没有档位 API 的原生分支：不少国产浏览器 / Android WebView 对这个类型一律回 `maybe`
 * （它们并没有 Apple 那套 HLS 栈，只是照例不拒绝），而 macOS Safari 其实支持 MSE，
 * 走 hls.js 反而能锁档。真正只剩原生的是 iOS：那里 `Hls.isSupported()` 为 false。
 *
 * @param {{mse: boolean, native: boolean}} caps 两个能力探测结果
 * @returns {string} QUALITY_MODE.MSE / QUALITY_MODE.NATIVE / ''（都不支持）
 */
export function pickPlayerMode(caps) {
  const c = caps || {}
  if (c.mse) return QUALITY_MODE.MSE
  if (c.native) return QUALITY_MODE.NATIVE
  return ''
}

/** 选择器旁的一句话说明：本场到底出了哪几档（灰选项需要一句总括） */
export function qualitySummary(tiers, mode) {
  if (mode === QUALITY_MODE.WEBM) return '连麦合成画面，只有原画一档'
  if (mode === QUALITY_MODE.NATIVE) return '本浏览器无 MSE，走原生播放不能切档'
  if (!tiers.length) return '未拿到档位信息，走自动'
  const heights = tiers.map((t) => qualityLabel(t.height)).join('/')
  return mode === QUALITY_MODE.MSE && tiers.length > 1 ? `本场 ${heights}` : `本场仅 ${heights}`
}
