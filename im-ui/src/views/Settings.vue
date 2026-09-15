<template>
  <div class="settings im-scroll">
    <div class="settings__inner">
      <header class="settings__head">
        <h2 class="settings__title">设置</h2>
        <el-button link type="primary" :icon="User" @click="router.push({ name: 'profile' })">
          编辑个人信息
        </el-button>
      </header>

      <!-- ==================== 一、外观设置 ==================== -->
      <section class="settings__card">
        <div class="settings__card-title">外观设置</div>

        <div class="settings__row">
          <div class="settings__label">
            <span>主题模式</span>
            <span class="settings__desc">浅色 / 深色 / 跟随系统</span>
          </div>
          <el-radio-group v-model="themeMode">
            <el-radio-button value="light">浅色</el-radio-button>
            <el-radio-button value="dark">深色</el-radio-button>
            <el-radio-button value="system">跟随系统</el-radio-button>
          </el-radio-group>
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>主题色</span>
            <span class="settings__desc">主色调，影响按钮、链接与选中态</span>
          </div>
          <div class="settings__colors">
            <button
              v-for="c in themeColors"
              :key="c.value"
              type="button"
              class="settings__swatch"
              :class="{ 'settings__swatch--active': themeColor.toLowerCase() === c.value }"
              :style="{ background: c.value }"
              :title="c.label"
              @click="settings.update({ themeColor: c.value })"
            ></button>
            <el-color-picker v-model="themeColor" size="small" />
          </div>
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>字体大小</span>
            <span class="settings__desc">消息气泡文字的缩放</span>
          </div>
          <el-radio-group v-model="fontSize">
            <el-radio-button value="small">小</el-radio-button>
            <el-radio-button value="default">默认</el-radio-button>
            <el-radio-button value="large">大</el-radio-button>
          </el-radio-group>
        </div>

        <div class="settings__row settings__row--stack">
          <div class="settings__label">
            <span>聊天背景</span>
            <span class="settings__desc">默认底色 / 纯色 / 自定义图片</span>
          </div>
          <div class="settings__bg">
            <el-radio-group v-model="chatBgType" @change="onBgTypeChange">
              <el-radio-button value="default">默认</el-radio-button>
              <el-radio-button value="color">纯色</el-radio-button>
              <el-radio-button value="image">自定义图片</el-radio-button>
            </el-radio-group>
            <el-color-picker
              v-if="chatBgType === 'color'"
              v-model="chatBgValue"
              size="small"
            />
            <template v-if="chatBgType === 'image'">
              <el-button size="small" :icon="Picture" @click="bgInputRef?.click()">选择图片</el-button>
              <el-button v-if="chatBgValue" size="small" link type="danger" @click="settings.clearChatBackground()">
                清除
              </el-button>
            </template>
            <input ref="bgInputRef" type="file" accept="image/*" class="settings__file" @change="onBgPicked" />
          </div>
          <div class="settings__preview" :style="previewStyle">
            <span class="settings__preview-bubble settings__preview-bubble--other">对方消息</span>
            <span class="settings__preview-bubble settings__preview-bubble--self">我的消息</span>
          </div>
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>会话列表宽度</span>
            <span class="settings__desc">{{ listWidth }} px</span>
          </div>
          <el-slider v-model="listWidth" :min="220" :max="460" :step="10" class="settings__slider" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>头像大小</span>
            <span class="settings__desc">会话列表中的头像尺寸</span>
          </div>
          <el-radio-group v-model="avatarSize">
            <el-radio-button value="small">小</el-radio-button>
            <el-radio-button value="medium">中</el-radio-button>
            <el-radio-button value="large">大</el-radio-button>
          </el-radio-group>
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>消息气泡样式</span>
            <span class="settings__desc">圆角带尖角 / 简约直角</span>
          </div>
          <el-radio-group v-model="bubbleStyle">
            <el-radio-button value="rounded">圆角</el-radio-button>
            <el-radio-button value="simple">简约</el-radio-button>
          </el-radio-group>
        </div>
      </section>

      <!-- ==================== 二、消息行为设置 ==================== -->
      <section class="settings__card">
        <div class="settings__card-title">消息行为</div>

        <div class="settings__row">
          <div class="settings__label">
            <span>发送快捷键</span>
            <span class="settings__desc">另一组合键用于换行</span>
          </div>
          <el-radio-group v-model="sendKey">
            <el-radio-button value="enter">Enter 发送</el-radio-button>
            <el-radio-button value="ctrlEnter">Ctrl+Enter 发送</el-radio-button>
          </el-radio-group>
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>自动加载历史消息</span>
            <span class="settings__desc">滚动到顶部时自动加载更早的消息</span>
          </div>
          <el-switch v-model="autoLoadHistory" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>消息预览</span>
            <span class="settings__desc">会话列表展示最新一条消息摘要</span>
          </div>
          <el-switch v-model="showPreview" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>时间戳显示</span>
            <span class="settings__desc">在消息流中显示时间分隔线</span>
          </div>
          <el-switch v-model="showTimestamp" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>显示已读回执</span>
            <span class="settings__desc">单聊中显示「已送达 / 已读」标记</span>
          </div>
          <el-switch v-model="showReadReceipt" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>图片预览</span>
            <span class="settings__desc">点击图片自动放大预览</span>
          </div>
          <el-switch v-model="imagePreview" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>自动播放</span>
            <span class="settings__desc">视频消息自动预加载以便播放</span>
          </div>
          <el-switch v-model="autoPlay" />
        </div>
      </section>

      <!-- ==================== 三、高级设置 ==================== -->
      <section class="settings__card">
        <div class="settings__card-title">高级设置</div>

        <div class="settings__row">
          <div class="settings__label">
            <span>前端调试日志</span>
            <span class="settings__desc">开启后在浏览器控制台输出连接与消息日志</span>
          </div>
          <el-switch v-model="debugLog" />
        </div>

        <div class="settings__row settings__row--stack">
          <div class="settings__label">
            <span>数据备份</span>
            <span class="settings__desc">导出当前已在本地加载的聊天记录为 JSON 文件</span>
          </div>
          <div class="settings__bg">
            <el-button :icon="Download" @click="exportChatRecords">导出本地聊天记录</el-button>
          </div>
        </div>

        <!-- 桌面端专属：当前为纯 Web，无法落地，保留入口但禁用并标注 -->
        <div class="settings__row">
          <div class="settings__label">
            <span>文件下载路径 <el-tag size="small" type="info" effect="plain">需桌面版</el-tag></span>
            <span class="settings__desc">浏览器接管下载位置，Web 端无法自定义保存目录</span>
          </div>
          <el-input class="settings__disabled" placeholder="集成 Tauri / Electron 后可用" disabled />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>网络代理 <el-tag size="small" type="info" effect="plain">需桌面版</el-tag></span>
            <span class="settings__desc">Web 端无权修改系统代理设置</span>
          </div>
          <el-input class="settings__disabled" placeholder="集成桌面壳后可用" disabled />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>全局快捷键 <el-tag size="small" type="info" effect="plain">需桌面版</el-tag></span>
            <span class="settings__desc">唤起 IM 窗口需要系统级权限，Web 端仅支持应用内快捷键</span>
          </div>
          <el-input class="settings__disabled" placeholder="集成桌面壳后可用" disabled />
        </div>
      </section>

      <div class="settings__footer">
        <el-button :icon="RefreshLeft" @click="onReset">恢复默认设置</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Picture, RefreshLeft, User } from '@element-plus/icons-vue'
import { useSettingsStore, THEME_COLORS } from '@/stores/settings'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'

defineOptions({ name: 'Settings' })

const router = useRouter()
const settings = useSettingsStore()
const chat = useChatStore()
const conversations = useConversationStore()

const themeColors = THEME_COLORS

/** 背景图以 data URL 存进 localStorage，过大易撞配额，这里限 2MB */
const MAX_BG_BYTES = 2 * 1024 * 1024

/**
 * 为每个设置项生成一个双向 computed：读走 store，写走 store.update（内部会持久化 + 应用）。
 * 这样模板里能直接用 v-model，而不必为每个控件手写 :model-value + @change。
 */
function setting(key) {
  return computed({
    get: () => settings[key],
    set: (value) => settings.update({ [key]: value })
  })
}

const themeMode = setting('themeMode')
const themeColor = setting('themeColor')
const fontSize = setting('fontSize')
const chatBgType = setting('chatBgType')
const chatBgValue = setting('chatBgValue')
const listWidth = setting('listWidth')
const avatarSize = setting('avatarSize')
const bubbleStyle = setting('bubbleStyle')
const sendKey = setting('sendKey')
const autoLoadHistory = setting('autoLoadHistory')
const showPreview = setting('showPreview')
const showTimestamp = setting('showTimestamp')
const showReadReceipt = setting('showReadReceipt')
const imagePreview = setting('imagePreview')
const autoPlay = setting('autoPlay')
const debugLog = setting('debugLog')

/** 背景预览区，实时反映聊天背景设置 */
const previewStyle = computed(() => ({ ...settings.chatBackgroundStyle }))

/** 切换背景类型时清掉不匹配的旧值，避免选了纯色却残留图片 data URL */
function onBgTypeChange(value) {
  if (value === 'default') {
    settings.update({ chatBgValue: '' })
  } else if (value === 'color' && (!chatBgValue.value || chatBgValue.value.startsWith('data:'))) {
    settings.update({ chatBgValue: '#f5f5f5' })
  } else if (value === 'image' && chatBgValue.value && !chatBgValue.value.startsWith('data:')) {
    settings.update({ chatBgValue: '' })
  }
}

const bgInputRef = ref(null)

function onBgPicked(event) {
  const input = event.target
  const file = input.files && input.files[0]
  input.value = ''
  if (!file) {
    return
  }
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    return
  }
  if (file.size > MAX_BG_BYTES) {
    ElMessage.error('背景图不能超过 2MB（需存入浏览器本地，过大易超出存储配额）')
    return
  }
  const reader = new FileReader()
  reader.onload = () => settings.setChatBackgroundImage(reader.result)
  reader.readAsDataURL(file)
}

/**
 * 导出本地聊天记录。
 *
 * 只能导出「已经在本地加载过」的消息：完整历史在服务端，浏览器端并没有全量副本。
 * 导出前给出说明，避免用户误以为这是完整备份。
 */
function exportChatRecords() {
  const buckets = Object.keys(chat.messages)
  const data = buckets
    .map((cid) => {
      const conv = conversations.find(cid)
      return {
        conversationId: cid,
        name: conv?.name || cid,
        exportedAt: new Date().toISOString(),
        messages: (chat.messages[cid] || []).map((m) => ({
          messageId: m.messageId || m.clientMsgId || '',
          from: m.fromNickname || '',
          self: !!m.self,
          type: m.msgTypeDesc || '',
          content: m.recalled ? '[已撤回]' : m.content || '',
          time: m.sendTime || '',
          status: m.statusDesc || ''
        }))
      }
    })
    .filter((item) => item.messages.length > 0)

  if (!data.length) {
    ElMessage.warning('本地暂无已加载的聊天记录，先打开几个会话再导出')
    return
  }
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `im-chat-export-${Date.now()}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success('已导出本地聊天记录（仅含已加载部分）')
}

async function onReset() {
  try {
    await ElMessageBox.confirm('将把外观、消息行为等所有设置恢复为默认值，确定继续？', '恢复默认设置', {
      confirmButtonText: '恢复默认',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  settings.reset()
  ElMessage.success('已恢复默认设置')
}
</script>

<style scoped>
.settings {
  height: 100%;
  overflow-y: auto;
}

.settings__inner {
  max-width: 760px;
  margin: 0 auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 768px) {
  .settings__inner {
    padding: 12px;
    gap: 12px;
  }
}

.settings__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.settings__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.settings__card {
  padding: 8px 20px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: var(--im-radius);
}

.settings__card-title {
  padding: 14px 0 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--im-primary);
}

.settings__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 0;
  border-top: 1px solid var(--im-border);
}

.settings__row--stack {
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
}

.settings__label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  font-size: 14px;
}

.settings__desc {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.settings__colors {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.settings__swatch {
  width: 24px;
  height: 24px;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 50%;
  cursor: pointer;
  transition: transform 0.1s;
}

.settings__swatch:hover {
  transform: scale(1.12);
}

.settings__swatch--active {
  border-color: var(--im-text);
  box-shadow: 0 0 0 2px var(--im-panel) inset;
}

.settings__bg {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.settings__file {
  display: none;
}

.settings__preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: 8px;
  background: var(--im-chat-bg);
  border: 1px solid var(--im-border);
}

.settings__preview-bubble {
  max-width: 60%;
  padding: 6px 12px;
  font-size: 13px;
  border-radius: var(--im-bubble-radius);
}

.settings__preview-bubble--other {
  align-self: flex-start;
  background: var(--im-bubble-other);
  color: var(--im-text);
}

.settings__preview-bubble--self {
  align-self: flex-end;
  background: var(--im-bubble-self);
  color: #1a1a1a;
}

.settings__slider {
  width: 220px;
  flex: none;
}

.settings__disabled {
  width: 240px;
  flex: none;
}

.settings__footer {
  display: flex;
  justify-content: center;
  padding: 4px 0 24px;
}

@media (max-width: 768px) {
  .settings__row {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }

  .settings__slider,
  .settings__disabled {
    width: 100%;
  }
}
</style>
