<template>
  <div class="emoji-picker">
    <div class="emoji-picker__tabs">
      <span
        v-for="group in groups"
        :key="group.name"
        class="emoji-picker__tab"
        :class="{ 'emoji-picker__tab--active': group.name === activeGroup }"
        @click="activeGroup = group.name"
      >
        {{ group.label }}
      </span>
    </div>
    <div class="emoji-picker__body im-scroll">
      <button
        v-for="emoji in currentEmojis"
        :key="emoji"
        type="button"
        class="emoji-picker__item"
        @mouseenter="preview = emoji"
        @mouseleave="preview = ''"
        @click="emit('select', emoji)"
      >
        {{ emoji }}
      </button>
    </div>
    <div class="emoji-picker__preview im-ellipsis">{{ preview || '点击表情插入输入框' }}</div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

/**
 * 表情面板。
 *
 * 刻意做成「纯展示 + 抛事件」的哑组件：弹层的开关、位置、点击外部关闭都交给
 * 使用方的 el-popover 处理，这样聊天输入框以外的地方（例如群昵称编辑）也能直接复用。
 *
 * 表情用 Unicode 字符而不是图片雪碧图：后端存的就是文本，
 * 换图片就得额外维护一套 [微笑] 到图片的映射，而消息搜索、会话摘要全都要跟着特殊处理。
 */
const emit = defineEmits(['select'])

const groups = [
  {
    name: 'common',
    label: '常用',
    emojis: [
      '😀', '😁', '😂', '🤣', '😊', '😍', '😘', '😜', '🤔', '😐',
      '😴', '😭', '😅', '😳', '😱', '😡', '🤯', '🥳', '😎', '🤗',
      '👍', '👎', '👌', '🙏', '💪', '👏', '🤝', '✌️', '👀', '❤️',
      '💔', '🔥', '⭐', '🎉', '🎁', '💯', '✅', '❌', '❓', '❗'
    ]
  },
  {
    name: 'face',
    label: '表情',
    emojis: [
      '🙂', '🙃', '😉', '😇', '🥰', '😋', '😛', '🤪', '🤨', '🧐',
      '😌', '😔', '😪', '🤤', '😷', '🤒', '🤕', '🤢', '🥵', '🥶',
      '😵', '🤠', '🥸', '😈', '👿', '💀', '☠️', '🤡', '👻', '👽',
      '🤖', '😺', '😸', '😹', '😻', '😼', '😽', '🙀', '😿', '😾'
    ]
  },
  {
    name: 'gesture',
    label: '手势',
    emojis: [
      '👋', '🤚', '🖐️', '✋', '🖖', '👈', '👉', '👆', '🖕', '👇',
      '☝️', '✊', '🤛', '🤜', '🫰', '🤞', '🤟', '🤘', '🤙', '🖊️',
      '💅', '🤳', '👂', '👃', '🧠', '🫀', '🦷', '🦴', '👅', '👄'
    ]
  },
  {
    name: 'nature',
    label: '自然',
    emojis: [
      '🌞', '🌝', '🌚', '🌛', '🌙', '⭐', '🌟', '✨', '⚡', '☄️',
      '🌈', '☀️', '🌤️', '⛅', '🌧️', '⛈️', '❄️', '☃️', '🌊', '💧',
      '🌸', '🌹', '🌻', '🌷', '🌱', '🌲', '🍀', '🍁', '🍂', '🍃'
    ]
  },
  {
    name: 'symbol',
    label: '符号',
    emojis: [
      '💬', '💭', '🗯️', '🔔', '📢', '📣', '🎵', '🎶', '☎️', '📱',
      '💻', '⌨️', '🖥️', '📷', '🎥', '📺', '🔍', '🔒', '🔓', '🔑',
      '📌', '📎', '✂️', '📝', '📅', '📊', '📈', '🗂️', '🚀', '✈️'
    ]
  }
]

const activeGroup = ref('common')

const currentEmojis = computed(() => groups.find((g) => g.name === activeGroup.value)?.emojis || [])

/**
 * 底部预览当前悬停的表情。
 * 原生 title 属性也能显示，但它有约一秒的出现延迟，连续挑选时基本看不到。
 */
const preview = ref('')
</script>

<style scoped>
.emoji-picker {
  width: 336px;
  user-select: none;
}

.emoji-picker__tabs {
  display: flex;
  gap: 4px;
  padding: 4px 6px;
  border-bottom: 1px solid var(--im-border);
}

.emoji-picker__tab {
  padding: 3px 10px;
  font-size: 12px;
  border-radius: 4px;
  cursor: pointer;
  color: var(--im-text-secondary);
}

.emoji-picker__tab:hover {
  background: var(--im-bg);
}

.emoji-picker__tab--active {
  background: var(--im-primary-light);
  color: var(--im-primary);
}

.emoji-picker__body {
  display: grid;
  /* 固定 10 列：让面板宽度不随分组表情数量变化而抖动 */
  grid-template-columns: repeat(10, 1fr);
  gap: 2px;
  max-height: 216px;
  overflow-y: auto;
  padding: 6px;
}

.emoji-picker__item {
  height: 30px;
  padding: 0;
  border: none;
  background: transparent;
  border-radius: 4px;
  font-size: 19px;
  line-height: 30px;
  cursor: pointer;
}

.emoji-picker__item:hover {
  background: var(--im-primary-light);
}

.emoji-picker__preview {
  height: 24px;
  line-height: 24px;
  padding: 0 8px;
  font-size: 12px;
  color: var(--im-text-secondary);
  border-top: 1px solid var(--im-border);
}
</style>
