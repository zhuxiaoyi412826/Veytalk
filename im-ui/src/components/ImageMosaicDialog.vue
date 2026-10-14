<template>
  <el-dialog
    :model-value="visible"
    title="图片马赛克"
    width="auto"
    top="5vh"
    append-to-body
    destroy-on-close
    class="mosaic-dialog"
    @update:model-value="emit('update:visible', $event)"
    @opened="initEditor"
  >
    <div class="mosaic__toolbar">
      <span class="mosaic__label">块体</span>
      <el-radio-group v-model="level" size="small" @change="clearStrokes">
        <el-radio-button :value="40">细</el-radio-button>
        <el-radio-button :value="22">中</el-radio-button>
        <el-radio-button :value="12">粗</el-radio-button>
      </el-radio-group>
      <el-button text size="small" :icon="RefreshLeft" :disabled="!strokes.length" @click="undo">撤销</el-button>
      <el-button text size="small" :icon="Delete" :disabled="!strokes.length" @click="clearStrokes">清空</el-button>
      <span class="mosaic__hint">在图上按住拖动，经过的区域会被打码；保存后替换待发送的原图</span>
    </div>
    <div class="mosaic__stage">
      <canvas
        ref="canvasRef"
        class="mosaic__canvas"
        @pointerdown="onDown"
        @pointermove="onMove"
        @pointerup="onUp"
        @pointercancel="onUp"
      />
    </div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :disabled="!base || !strokes.length" @click="save">保存并替换</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, RefreshLeft } from '@element-plus/icons-vue'

/**
 * 待发送图片的马赛克编辑器。
 *
 * 纯前端 canvas 像素化处理，不经过任何后端或第三方服务：打码的动机通常是
 * 「这部分内容不能让服务器/对方看到」，若流程里还有一段回传原图的请求就本末倒置了。
 * 编辑只发生在入托盘之后、上传之前，保存产物直接替换托盘里的 File。
 *
 * 状态用「笔刷经过的格子坐标」表达而不是位图快照：4000×3000 的原图一份 ImageData
 * 就近 48MB，存 10 步撤销足以卡死标签页；格子坐标集合每条笔迹只有几十字节，
 * 撤销 = 弹出一组坐标重画遮罩，成本可以忽略。
 */
defineOptions({ name: 'ImageMosaicDialog' })

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 被编辑的托盘文件 */
  file: { type: Object, default: null }
})

const emit = defineEmits(['update:visible', 'apply'])

const canvasRef = ref(null)
/** 块体档位：值 = 短边按几等分，越小块越大 */
const level = ref(22)

let base = null
let objectUrl = ''
/** 每笔一组格子坐标 [[gx,gy],...]，撤销按组弹出 */
const strokes = ref([])
let painting = false
let currentStroke = null
let cell = 16
let gridCols = 0
let gridRows = 0
let frame = 0

function initEditor() {
  if (!props.file || !canvasRef.value) {
    return
  }
  disposeBase()
  objectUrl = URL.createObjectURL(props.file)
  const img = new Image()
  img.onload = () => {
    base = img
    const canvas = canvasRef.value
    if (!canvas) {
      return
    }
    canvas.width = img.naturalWidth
    canvas.height = img.naturalHeight
    cell = Math.max(6, Math.round(Math.min(img.naturalWidth, img.naturalHeight) / level.value))
    gridCols = Math.ceil(img.naturalWidth / cell)
    gridRows = Math.ceil(img.naturalHeight / cell)
    strokes.value = []
    render()
  }
  img.onerror = () => {
    ElMessage.error('图片解码失败，无法编辑')
    emit('update:visible', false)
  }
  img.src = objectUrl
}

function disposeBase() {
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl)
    objectUrl = ''
  }
  base = null
}

/** 画布合成：原图 -> 局部像素化层（像素图按最近邻放大后用遮罩裁剪） */
function render() {
  const canvas = canvasRef.value
  if (!base || !canvas) {
    return
  }
  const width = canvas.width
  const height = canvas.height
  const ctx = canvas.getContext('2d')
  ctx.imageSmoothingEnabled = true
  ctx.clearRect(0, 0, width, height)
  ctx.drawImage(base, 0, 0)
  if (!strokes.value.length) {
    return
  }
  const smallW = Math.ceil(width / cell)
  const smallH = Math.ceil(height / cell)
  const mask = document.createElement('canvas')
  mask.width = smallW
  mask.height = smallH
  const mctx = mask.getContext('2d')
  mctx.fillStyle = '#fff'
  for (const stroke of strokes.value) {
    for (const [gx, gy] of stroke) {
      mctx.fillRect(gx, gy, 1, 1)
    }
  }
  const pixel = document.createElement('canvas')
  pixel.width = smallW
  pixel.height = smallH
  const pctx = pixel.getContext('2d')
  pctx.drawImage(base, 0, 0, smallW, smallH)
  const copy = document.createElement('canvas')
  copy.width = width
  copy.height = height
  const cctx = copy.getContext('2d')
  cctx.imageSmoothingEnabled = false
  cctx.drawImage(pixel, 0, 0, width, height)
  cctx.globalCompositeOperation = 'destination-in'
  cctx.drawImage(mask, 0, 0, width, height)
  ctx.drawImage(copy, 0, 0)
}

/** 拖动时按帧节流重绘，指针事件比渲染快得多 */
function scheduleRender() {
  if (!frame) {
    frame = requestAnimationFrame(() => {
      frame = 0
      render()
    })
  }
}

/** 以指针点为中心涂 3×3 个格子，连笔拖动靠它保证覆盖连续 */
function paintAt(event) {
  const canvas = canvasRef.value
  if (!canvas || !base) {
    return
  }
  const rect = canvas.getBoundingClientRect()
  const x = (event.clientX - rect.left) * (canvas.width / rect.width)
  const y = (event.clientY - rect.top) * (canvas.height / rect.height)
  const bx = Math.floor(x / cell)
  const by = Math.floor(y / cell)
  let touched = false
  for (let gx = bx - 1; gx <= bx + 1; gx += 1) {
    for (let gy = by - 1; gy <= by + 1; gy += 1) {
      if (gx < 0 || gy < 0 || gx >= gridCols || gy >= gridRows) {
        continue
      }
      currentStroke.push([gx, gy])
      touched = true
    }
  }
  if (touched) {
    scheduleRender()
  }
}

function onDown(event) {
  if (!base) {
    return
  }
  painting = true
  currentStroke = []
  strokes.value.push(currentStroke)
  canvasRef.value.setPointerCapture(event.pointerId)
  paintAt(event)
}

function onMove(event) {
  if (painting) {
    paintAt(event)
  }
}

function onUp() {
  painting = false
  currentStroke = null
}

function undo() {
  strokes.value.pop()
  render()
}

function clearStrokes() {
  strokes.value = []
  if (base && canvasRef.value) {
    cell = Math.max(6, Math.round(Math.min(base.naturalWidth, base.naturalHeight) / level.value))
    gridCols = Math.ceil(base.naturalWidth / cell)
    gridRows = Math.ceil(base.naturalHeight / cell)
  }
  render()
}

/** 目标格式：主流位图类型原样保持（文件名与内容扩展名一致），其余归 PNG */
function outputTypeOf(type) {
  return ['image/png', 'image/jpeg', 'image/webp'].includes(type) ? type : 'image/png'
}

function save() {
  const canvas = canvasRef.value
  if (!base || !canvas) {
    return
  }
  const type = outputTypeOf(props.file?.type)
  canvas.toBlob((blob) => {
    if (!blob) {
      ElMessage.error('图片导出失败，请换一张试试')
      return
    }
    const original = props.file?.name || 'image'
    const dot = original.lastIndexOf('.')
    const stem = dot > 0 ? original.slice(0, dot) : original
    // 转成 PNG 时改掉扩展名，别让「内容 png、名字 .jpg」的错位文件进秒传与类型嗅探
    const name = type === props.file?.type ? original : `${stem}_马赛克.png`
    emit('apply', new File([blob], name, { type, lastModified: Date.now() }))
    emit('update:visible', false)
  }, type, 0.92)
}

onBeforeUnmount(disposeBase)
</script>

<style scoped>
.mosaic__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.mosaic__label {
  font-size: 13px;
  color: var(--im-text-secondary);
}

.mosaic__hint {
  flex: 1;
  min-width: 160px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.mosaic__stage {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #000;
  border-radius: 6px;
  overflow: hidden;
}

.mosaic__canvas {
  display: block;
  max-width: 78vw;
  max-height: 66vh;
  touch-action: none;
  cursor: crosshair;
}
</style>
