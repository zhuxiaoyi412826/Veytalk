<template>
  <!--
    未确认公告的常驻横幅（本批修复「发布了但前端没收到」的主入口）。

    为什么要有它：通知只有 12 秒，而管理员发公告时用户很可能正在别的标签页。
    这条横幅在用户点「知道了」之前一直待在聊天界面顶部，确认状态由 store 落盘，
    所以下次进来它还在 ——「没注意看」不再是「这条公告我永远看不到」。
    没有待确认公告时整条不渲染，布局与改动前完全一致。
  -->
  <div v-if="announce.latest" class="announce-bar" :class="`announce-bar--${announce.latest.tone}`">
    <el-icon class="announce-bar__icon" :size="16"><BellFilled /></el-icon>
    <span class="announce-bar__label">公告</span>
    <span class="announce-bar__title">{{ announce.latest.title }}</span>
    <span class="announce-bar__brief">{{ announce.latest.brief }}</span>

    <div class="announce-bar__actions">
      <el-button
        v-if="announce.pendingCount > 1"
        link
        size="small"
        class="announce-bar__btn"
        @click="announce.openDialog()"
      >
        查看 {{ announce.pendingCount }} 条
      </el-button>
      <el-button link size="small" class="announce-bar__btn" @click="announce.openDialog()">
        查看详情
      </el-button>
      <el-button link size="small" class="announce-bar__btn" @click="onDismiss">知道了</el-button>
    </div>
  </div>
</template>

<script setup>
import { BellFilled } from '@element-plus/icons-vue'
import { useAnnounceStore } from '@/stores/announce'

/**
 * 只展示最新一条：横幅是「有公告」这件事的常驻提示，不是阅读器。
 * 全部待确认的公告在点开通了的列表弹窗里，那里才有版面放全文。
 */
const announce = useAnnounceStore()

/**
 * 「知道了」= 确认这一条。
 *
 * 若当前开着列表弹窗则顺手关掉：用户明明在逐条读，点完横幅的知道了却只剩弹窗里
 * 空荡荡一句「暂无公告」，那种界面比不关更像出了 bug。store 里的 ack 已经做了这件事，
 * 这里只是把「确认最新一条」说清楚。
 */
function onDismiss() {
  const latest = announce.latest
  if (latest) {
    announce.ack(latest.id)
  }
}
</script>

<style scoped>
.announce-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  padding: 7px 14px;
  font-size: 13px;
  line-height: 20px;
  color: var(--im-text);
  background: var(--im-primary-light);
  border-bottom: 1px solid var(--im-border);
}

/* 四种 tone 对应四套底色，与 ElNotification 的配色同一口径，用户不需要重新学一遍颜色 */
.announce-bar--success {
  background: #f0f9eb;
  color: #529b2e;
}

.announce-bar--warning {
  background: #fdf6ec;
  color: #b88230;
}

.announce-bar--error {
  background: #fef0f0;
  color: #c45656;
}

.announce-bar--info {
  background: var(--im-primary-light);
  color: var(--im-primary);
}

html.dark .announce-bar--success {
  background: #1d2b1c;
}

html.dark .announce-bar--warning {
  background: #2b2418;
}

html.dark .announce-bar--error {
  background: #2b1c1c;
}

html.dark .announce-bar--info {
  background: #1b2530;
}

.announce-bar__icon {
  flex: none;
}

.announce-bar__label {
  flex: none;
  padding: 0 6px;
  font-size: 12px;
  border: 1px solid currentColor;
  border-radius: 10px;
  opacity: 0.8;
}

.announce-bar__title {
  flex: none;
  max-width: 40%;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/*
 * 摘要吃掉剩余宽度：min-width: 0 是让 flex 子项允许被压缩到内容宽度以下的关键，
 * 少了它，一句长公告会把右边的按钮挤出横幅（flex 子项默认 min-width 是 auto）。
 */
.announce-bar__brief {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  opacity: 0.85;
}

.announce-bar__actions {
  display: flex;
  align-items: center;
  flex: none;
  gap: 4px;
}

.announce-bar__btn {
  color: inherit;
  padding: 0 2px;
}

.announce-bar__btn:hover {
  opacity: 0.75;
}

@media (max-width: 768px) {
  /* 窄屏把「公告」徽标和摘要让出去：横幅只有一行的高度，标题 + 按钮才是必须留下的 */
  .announce-bar {
    gap: 6px;
    padding: 6px 10px;
  }

  .announce-bar__label,
  .announce-bar__brief {
    display: none;
  }

  .announce-bar__title {
    max-width: none;
  }
}
</style>
