<template>
  <!--
    待确认公告的列表弹窗：横幅「查看详情」、通知的点击、「查看 N 条」都汇到这里。

    全文与确认动作放在同一个界面上是刻意的：旧版是「点通知 → ElMessageBox 看全文」，
    看完只是看了，落库的已读早在通知弹出时就写完了。这一版反过来——
    <b>看不是确认，点「知道了」才是</b>，所以关掉弹窗（右上角叉、点遮罩）不会抹掉任何一条，
    它们明天还在横幅上等着。
  -->
  <el-dialog
    :model-value="announce.dialogVisible"
    title="系统公告"
    width="560px"
    append-to-body
    @update:model-value="onVisibleChange"
  >
    <div class="an im-scroll">
      <article v-for="item in announce.items" :key="item.id" class="an__item">
        <header class="an__head">
          <h3 class="an__title">{{ item.title }}</h3>
          <span class="an__time">{{ timeText(item) }}</span>
        </header>
        <p class="an__content">{{ item.content || '（正文为空）' }}</p>
        <footer class="an__foot">
          <el-button size="small" type="primary" plain @click="announce.ack(item.id)">知道了</el-button>
        </footer>
      </article>
      <p v-if="announce.items.length === 0" class="an__empty">没有待确认的公告。</p>
    </div>

    <template #footer>
      <el-button @click="announce.closeDialog()">关闭</el-button>
      <el-button type="primary" @click="announce.ackAll()">全部知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { useAnnounceStore } from '@/stores/announce'
import { formatDateTime } from '@/utils/format'

/**
 * 逐条列出 {@code stores/announce} 里的待确认公告。
 *
 * 一次补拉最多 5 条，加上本会话里 WS 推来的，正常也就个位数，
 * 所以直接用一条滚动的纵向列表，不做分页也不做折叠——
 * 公告是让人尽快读完的东西，多点两下都是摩擦。
 */
const announce = useAnnounceStore()

function onVisibleChange(visible) {
  if (!visible) {
    announce.closeDialog()
  }
}

/**
 * 发布时刻。
 *
 * 时间取不到时整块不显示，而不是显示「Invalid Date」或一个空括号：
 * 一条没有日期的公告仍然是完整的公告，而尾巴上一对空括号只会让人以为是渲染坏了。
 */
function timeText(item) {
  return item.publishTime ? formatDateTime(item.publishTime) : ''
}
</script>

<style scoped>
.an {
  max-height: 55vh;
  overflow-y: auto;
}

.an__item + .an__item {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--im-border);
}

.an__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.an__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--im-text);
}

.an__time {
  flex: none;
  font-size: 12px;
  color: var(--im-text-secondary);
}

/*
 * 正文里的换行是管理员打上去的：默认的空白折叠会把一份分段写的公告糊成一段。
 * pre-wrap 保住分段，word-break 保住一长串 URL 不会撑破弹窗。
 */
.an__content {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 22px;
  color: var(--im-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.an__foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.an__empty {
  margin: 0;
  padding: 20px 0;
  font-size: 13px;
  color: var(--im-text-secondary);
  text-align: center;
}
</style>
