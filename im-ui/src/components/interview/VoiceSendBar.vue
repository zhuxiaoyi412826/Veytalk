<template>
  <el-tooltip :disabled="!disabled" :content="disabledHint || '语音功能暂不可用'" placement="top">
    <HoldTalkButton
      :disabled="disabled"
      :disabled-hint="disabledHint"
      :busy="transcribing"
      busy-label="识别中…"
      @result="onRecord"
    />
  </el-tooltip>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import HoldTalkButton from '@/components/HoldTalkButton.vue'
import { transcribeAudio } from '@/api/ai'

/**
 * 面试页的「按住说话」：录一段音、转成文字，把文字交给父组件。
 *
 * 录音交互（按下开麦 / 上滑取消 / 太短提示 / 无麦克风权限提示）都在通用的
 * HoldTalkButton 里，与聊天页共用同一套手感；这个组件只负责「拿到音频之后
 * 去转写」这一件事——面试要的是文字进草稿，聊天要的是语音消息，两者的差别
 * 正好落在这一层。
 *
 * 转写失败/没听清都是 ElMessage 明确提示，不静默吞。
 */
defineOptions({ name: 'VoiceSendBar' })

const props = defineProps({
  disabled: { type: Boolean, default: false },
  disabledHint: { type: String, default: '' }
})
const emit = defineEmits(['text'])

const transcribing = ref(false)

async function onRecord({ blob }) {
  transcribing.value = true
  try {
    const text = await transcribeAudio(blob)
    if (text.trim()) {
      emit('text', text.trim())
    } else {
      ElMessage.warning('没听清，请再说一次')
    }
  } catch (error) {
    ElMessage.error(error?.message || '语音转写失败，请重试')
  } finally {
    transcribing.value = false
  }
}
</script>
