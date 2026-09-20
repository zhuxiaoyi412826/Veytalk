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

      <!-- ==================== 三、隐私与安全 ==================== -->
      <section class="settings__card">
        <div class="settings__card-title">隐私与安全</div>

        <div class="settings__row">
          <div class="settings__label">
            <span>聊天界面水印</span>
            <span class="settings__desc">开启后在当前账号的聊天区叠加账号信息，防截图泄露（仅当前设备生效）</span>
          </div>
          <el-switch v-model="chatWatermark" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>预览时禁止下载</span>
            <span class="settings__desc">开启后文件预览弹窗不再提供“另存为”，只能在线查看（仅当前设备生效）</span>
          </div>
          <el-switch v-model="previewNoDownload" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>多端信息共享</span>
            <span class="settings__desc">开启后自己在某台设备上发的消息会实时显示在其它登录端（手机/PC/浏览器）；关闭后其它端不实时上屏，拉取历史仍可见（仅当前设备生效）</span>
          </div>
          <el-switch v-model="shareMultiDevice" />
        </div>
      </section>

      <!-- ==================== 四、本地缓存 ==================== -->
      <section class="settings__card">
        <div class="settings__card-title">本地缓存</div>
        <div class="settings__notice">
          本地缓存不是云端备份：清理浏览器数据 / 卸载客户端 / 换设备后缓存即丢失，
          可随时重新登录由服务端按需补齐历史；重要记录请用下方「导出」自行备份。
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>存储占用</span>
            <span class="settings__desc">{{ cacheUsageText }}</span>
          </div>
          <el-button link type="primary" :icon="Refresh" @click="loadCacheStats">刷新</el-button>
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>媒体缓存</span>
            <span class="settings__desc">本地缓存图片/视频/文件，减少重复网络请求；关闭后每次都从服务端重新拉取</span>
          </div>
          <el-switch v-model="mediaCacheEnabled" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>媒体缓存上限</span>
            <span class="settings__desc">超出上限时自动淘汰最久未访问的媒体（消息记录不受影响）；0 为不限制</span>
          </div>
          <el-slider
            v-model="mediaCacheMaxMb"
            :min="0"
            :max="2048"
            :step="64"
            :format-tooltip="(v) => (v === 0 ? '不限制' : v + ' MB')"
            class="settings__slider"
          />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>清除媒体缓存</span>
            <span class="settings__desc">只删本地文件/视频缓存，服务端原件不受影响，下次浏览重新拉取</span>
          </div>
          <el-button size="small" @click="onClearMedia">立即清除</el-button>
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>清除消息缓存</span>
            <span class="settings__desc">删除本地消息库与离线缓存；断网待发送队列会保留，直到发送完成</span>
          </div>
          <el-button size="small" type="danger" plain @click="onClearMessages">立即清除</el-button>
        </div>
      </section>

      <!-- ==================== 五、高级设置 ==================== -->
      <section class="settings__card">
        <div class="settings__card-title">高级设置</div>

        <div class="settings__row">
          <div class="settings__label">
            <span>前端调试日志</span>
            <span class="settings__desc">开启后在浏览器控制台输出连接与消息日志</span>
          </div>
          <el-switch v-model="debugLog" />
        </div>

        <div class="settings__row">
          <div class="settings__label">
            <span>敏感词过滤（服务端全局）</span>
            <span class="settings__desc">关闭后全服文本与文件名停止遮蔽，立即生效不需重启；重启后回到配置文件默认值</span>
          </div>
          <el-switch v-model="sensitiveFilterEnabled" :loading="sensitiveFilterSaving" @change="onSensitiveFilterChange" />
        </div>

        <div class="settings__row settings__row--stack">
          <div class="settings__label">
            <span>数据备份</span>
            <span class="settings__desc">导出当前已在本地加载的聊天记录为 JSON 文件</span>
          </div>
          <div class="settings__bg">
            <el-button :icon="Download" @click="exportChatRecords">导出本地聊天记录</el-button>
            <span class="settings__desc">导出本地库已缓存的全部消息，仅下载到本机磁盘，不会上传云端</span>
          </div>
        </div>

        <!-- 桌面端专属：当前为纯 Web，无法落地，保留入口但禁用并标注 -->
        <div class="settings__row">
          <div class="settings__label">
            <span>文件下载路径 <el-tag size="small" type="info" effect="plain">需桌面版</el-tag></span>
            <span class="settings__desc">浏览器接管下载位置，Web 端无法自定义保存目录；Electron 版另存为对话框可选路径</span>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Picture, Refresh, RefreshLeft, User } from '@element-plus/icons-vue'
import { useSettingsStore, THEME_COLORS } from '@/stores/settings'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'
import { dbStats, dbClearAllMessages, dbExportAll, localDbEnabled } from '@/utils/localdb'
import { mediaCacheStats, mediaCacheClear } from '@/utils/medacache'
import { clearMediaCache } from '@/utils/media'
import { fetchSensitiveFilter, setSensitiveFilter } from '@/api/message'

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
const chatWatermark = setting('chatWatermark')
const previewNoDownload = setting('previewNoDownload')
const shareMultiDevice = setting('shareMultiDevice')
const mediaCacheEnabled = setting('mediaCacheEnabled')
const mediaCacheMaxMb = setting('mediaCacheMaxMb')
const debugLog = setting('debugLog')

/* ------------------------------ 敏感词过滤开关（服务端全局） ------------------------------ */

const sensitiveFilterEnabled = ref(true)
const sensitiveFilterSaving = ref(false)

// 状态存在后端内存里，不是本机 localStorage：进页拉一次回填开关，
// 拉不到（未登录/后端未起）就保持默认开，不把用户带进错误的初始状态
onMounted(async () => {
  try {
    const vo = await fetchSensitiveFilter()
    if (vo) {
      sensitiveFilterEnabled.value = !!vo.enabled
    }
  } catch {
    // 保持默认
  }
})

async function onSensitiveFilterChange(value) {
  sensitiveFilterSaving.value = true
  try {
    const vo = await setSensitiveFilter(value)
    sensitiveFilterEnabled.value = vo ? !!vo.enabled : value
    ElMessage.success(value ? '已开启敏感词过滤（全服生效）' : '已关闭敏感词过滤（全服生效）')
  } catch {
    // 切换失败要把开关拨回去，否则界面状态与服务端不一致
    sensitiveFilterEnabled.value = !value
  } finally {
    sensitiveFilterSaving.value = false
  }
}

/* ------------------------------ 本地缓存管理 ------------------------------ */

const cacheStats = reactive({ messages: 0, pending: 0, backend: '', mediaCount: 0, mediaBytes: 0 })

function formatBytes(bytes) {
  const value = Number(bytes) || 0
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}

const BACKEND_TEXT = { web: '浏览器 SQLite(WASM)', electron: 'Electron 本地库', disabled: '不可用（已降级为服务端直读）' }

const cacheUsageText = computed(() => {
  if (!localDbEnabled()) {
    return '本地消息库不可用，历史记录每次从服务端拉取'
  }
  return `消息 ${cacheStats.messages} 条 · 待发送 ${cacheStats.pending} 条 · 媒体 ${cacheStats.mediaCount} 个 / ${formatBytes(cacheStats.mediaBytes)} · 引擎：${BACKEND_TEXT[cacheStats.backend] || cacheStats.backend || '—'}`
})

async function loadCacheStats() {
  const db = await dbStats()
  Object.assign(cacheStats, db)
  try {
    const media = await mediaCacheStats()
    cacheStats.mediaCount = media.count
    cacheStats.mediaBytes = media.bytes
  } catch {
    // 统计失败不影响页面其它功能
  }
}
loadCacheStats()

async function onClearMedia() {
  try {
    await ElMessageBox.confirm('将删除本地缓存的图片/视频/文件（不影响聊天记录与服务端原件），确定继续？', '清除媒体缓存', {
      confirmButtonText: '清除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await mediaCacheClear().catch(() => undefined)
  // 内存里的 objectUrl 也一并作废，界面下次渲染会从空缓存重新拉
  clearMediaCache()
  await loadCacheStats()
  ElMessage.success('媒体缓存已清除')
}

async function onClearMessages() {
  try {
    await ElMessageBox.confirm(
      '将删除本机保存的全部消息缓存（不影响服务端记录，滚动历史时重新拉取）；断网待发送的消息会保留。确定继续？',
      '清除消息缓存',
      { confirmButtonText: '清除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await dbClearAllMessages()
  // 内存分桶同步清空，否则界面还展示着已删的旧消息，下次变更又会写回库
  chat.$patch({ messages: {}, hasMore: {}, loadingHistory: {} })
  await loadCacheStats()
  ElMessage.success('消息缓存已清除')
}

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
 * 优先从本地消息库取全量（比内存分桶完整）；本地库不可用时退化到导出已加载部分。
 * 导出仅下载一个 JSON 文件到本机磁盘，不会上传云端。
 */
async function exportChatRecords() {
  const format = (m) => ({
    messageId: m.messageId || m.clientMsgId || '',
    from: m.fromNickname || '',
    self: !!m.self,
    type: m.msgTypeDesc || '',
    content: m.recalled ? '[已撤回]' : m.content || '',
    time: m.sendTime || '',
    status: m.statusDesc || ''
  })
  let data = []
  const rows = await dbExportAll()
  if (rows.length) {
    const buckets = {}
    rows.forEach(([cid, message]) => {
      ;(buckets[cid] = buckets[cid] || []).push(message)
    })
    data = Object.entries(buckets)
      .map(([cid, list]) => ({
        conversationId: cid,
        name: conversations.find(cid)?.name || cid,
        exportedAt: new Date().toISOString(),
        source: 'local-db',
        messages: list.map(format)
      }))
      .filter((item) => item.messages.length > 0)
  } else {
    data = Object.keys(chat.messages)
      .map((cid) => ({
        conversationId: cid,
        name: conversations.find(cid)?.name || cid,
        exportedAt: new Date().toISOString(),
        source: 'memory',
        messages: (chat.messages[cid] || []).map(format)
      }))
      .filter((item) => item.messages.length > 0)
  }

  if (!data.length) {
    ElMessage.warning('本地暂无已缓存的聊天记录，先打开几个会话加载历史再导出')
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
  ElMessage.success('已导出本地聊天记录到 JSON 文件（仅本机磁盘，非云端备份）')
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

.settings__notice {
  margin: 4px 0 10px;
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--im-text-secondary);
  background: var(--im-primary-light);
  border-radius: 6px;
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
