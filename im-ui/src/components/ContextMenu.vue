<template>
  <Teleport to="body">
    <ul
      v-if="visible"
      ref="menuRef"
      class="im-context-menu"
      :style="positionStyle"
      @contextmenu.prevent
      @click.stop
    >
      <li
        v-for="item in visibleItems"
        :key="item.key"
        class="im-context-menu__item"
        :class="{
          'im-context-menu__item--danger': item.danger,
          'im-context-menu__item--disabled': item.disabled
        }"
        @click="onSelect(item)"
      >
        {{ item.label }}
      </li>
    </ul>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 通用右键菜单。
 *
 * 会话列表、好友列表、消息气泡三处都要用，行为完全一样（跟随鼠标、贴边翻转、
 * 点击别处关闭、Esc 关闭），所以抽成一个组件而不是各写一份。
 *
 * 用 Teleport 挂到 body：菜单是 position:fixed，如果留在列表项内部，
 * 父级的 overflow:hidden 与 transform 都会把它裁掉或改变定位基准。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 鼠标事件的 clientX / clientY */
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  /** [{ key, label, danger?, disabled?, show? }]，show 为 false 的项不渲染 */
  items: { type: Array, default: () => [] }
})

const emit = defineEmits(['select', 'close', 'update:visible'])

const menuRef = ref(null)
/** 贴边修正后的实际坐标 */
const posX = ref(0)
const posY = ref(0)
/**
 * 测量完成前不显示。
 * 先按原始坐标渲染一帧再挪回来的话，靠近屏幕右下角时会看到菜单跳一下。
 */
const measured = ref(false)

const visibleItems = computed(() => props.items.filter((item) => item && item.show !== false))

const positionStyle = computed(() => ({
  left: posX.value + 'px',
  top: posY.value + 'px',
  visibility: measured.value ? 'visible' : 'hidden'
}))

function onSelect(item) {
  close()
  if (item.disabled) {
    return
  }
  emit('select', item.key, item)
}

function close() {
  // 同时抛 update:visible 与 close：前者让调用方能用 v-model:visible，
  // 后者给需要在关闭时做额外清理（例如清掉当前选中行）的场景留个钩子
  emit('update:visible', false)
  emit('close')
}

/** 把菜单约束在视口内：放不下就往左/往上翻，而不是让它被截掉一截 */
function clamp() {
  const element = menuRef.value
  if (!element) {
    return
  }
  const width = element.offsetWidth
  const height = element.offsetHeight
  // 留 4px 边距，紧贴视口边缘时阴影会被裁掉
  posX.value = Math.max(4, Math.min(props.x, window.innerWidth - width - 4))
  posY.value = Math.max(4, Math.min(props.y, window.innerHeight - height - 4))
  measured.value = true
}

function onDocumentMouseDown(event) {
  // 菜单内部的点击由 @click.stop 与 onSelect 处理，这里只管「点在别处」
  if (menuRef.value && menuRef.value.contains(event.target)) {
    return
  }
  close()
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    close()
  }
}

/** 滚动或缩放后菜单会停在旧位置，与触发它的行脱节，直接关掉最省事 */
function onViewportChange() {
  close()
}

function bind() {
  // 用捕获阶段监听 mousedown：右键本身也是一次 mousedown，
  // 若在冒泡阶段监听，打开菜单的这一次点击会立刻把它关掉
  document.addEventListener('mousedown', onDocumentMouseDown, true)
  document.addEventListener('contextmenu', onDocumentMouseDown, true)
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onViewportChange)
  window.addEventListener('scroll', onViewportChange, true)
}

function unbind() {
  document.removeEventListener('mousedown', onDocumentMouseDown, true)
  document.removeEventListener('contextmenu', onDocumentMouseDown, true)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onViewportChange)
  window.removeEventListener('scroll', onViewportChange, true)
}

watch(
  () => props.visible,
  async (value) => {
    if (!value) {
      measured.value = false
      unbind()
      return
    }
    posX.value = props.x
    posY.value = props.y
    await nextTick()
    clamp()
    bind()
  }
)

watch(
  () => [props.x, props.y],
  async () => {
    if (!props.visible) {
      return
    }
    measured.value = false
    await nextTick()
    clamp()
  }
)

onBeforeUnmount(unbind)
</script>

<style scoped>
.im-context-menu__item--disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}

.im-context-menu__item--disabled:hover {
  background: transparent;
  color: #c0c4cc;
}
</style>
