<template>
  <div class="im-watermark" :style="layerStyle" aria-hidden="true" />
</template>

<script setup>
/**
 * 平铺文字水印覆盖层。
 *
 * 用一张内联 SVG 作 background-image 平铺：SVG 里画一段旋转过半透明的文字，
 * 平铺成密集的斜向水印。相比逐个渲染 DOM 文字，一层背景图开销最小、天然不可选中复制；
 * pointer-events:none 保证不挡下面的正常点击与滚动。
 *
 * 覆盖层绝对定位铺满父容器，因此父容器必须是 position: relative，
 * 且覆盖层要放在滚动容器「外面」作为其兄弟节点，这样水印固定在可视区而随内容滚走。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 水印文字，一般传账号信息 */
  text: { type: String, required: true },
  /** 字号（px） */
  fontSize: { type: Number, default: 14 },
  /** 填充色，含透明度 */
  color: { type: String, default: 'rgba(110, 110, 110, 0.16)' },
  /** 旋转角度，负值为逆时针 */
  rotate: { type: Number, default: -22 },
  /** 相邻水印的间距（px），同时决定瓦片高度 */
  gap: { type: Number, default: 96 }
})

function escapeXml(value) {
  return String(value).replace(/[<>&'"]/g, (c) => ({
    '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;'
  }[c]))
}

const tile = computed(() => {
  const len = Math.max(4, (props.text || '').length)
  // 瓦片宽度按文字长度估算：一段文字完整落进一格并留出左右间距，平铺后不会首尾相接
  const width = Math.ceil(len * props.fontSize * 0.95) + props.gap
  const height = props.gap
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' width='${width}' height='${height}'>` +
    `<text x='50%' y='50%' fill='${props.color}' font-size='${props.fontSize}' ` +
    `font-family='PingFang SC, Microsoft YaHei, sans-serif' text-anchor='middle' ` +
    `dominant-baseline='middle' transform='rotate(${props.rotate} ${width / 2} ${height / 2})'>` +
    escapeXml(props.text) +
    `</text></svg>`
  return { width, height, svg }
})

const layerStyle = computed(() => ({
  backgroundImage: `url("data:image/svg+xml,${encodeURIComponent(tile.value.svg)}")`,
  backgroundSize: `${tile.value.width}px ${tile.value.height}px`
}))
</script>

<style scoped>
.im-watermark {
  position: absolute;
  inset: 0;
  z-index: 5;
  pointer-events: none;
  user-select: none;
  background-repeat: repeat;
}
</style>
