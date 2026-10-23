<template>
  <el-dialog
    :model-value="report.visible"
    title="举报"
    width="460px"
    append-to-body
    :close-on-click-modal="!report.submitting"
    @update:model-value="onVisibleChange"
  >
    <div v-if="report.target" class="rp">
      <div class="rp__target">
        <span class="rp__type">{{ targetTypeName }}</span>
        <span class="rp__title im-ellipsis" :title="report.target.title">{{ report.target.title || '（无标题）' }}</span>
      </div>

      <p class="rp__label">选择理由</p>
      <div v-loading="!report.loaded" class="rp__reasons">
        <el-radio-group v-model="reasonCode" class="rp__group">
          <el-radio v-for="item in report.reasons" :key="item.code" :value="item.code">
            {{ item.name }}
            <span v-if="item.textRequired" class="rp__must">*</span>
          </el-radio>
        </el-radio-group>
        <p v-if="report.loaded && !report.reasons.length" class="rp__empty">
          字典没取到，可能是后端未启动；稍后再试。
        </p>
      </div>

      <p class="rp__label">
        补充描述
        <span v-if="textRequired" class="rp__must">*</span>
        <span class="rp__hint">{{ textRequired ? '这个理由必须说清依据' : '可选，但写清楚能快很多' }}</span>
      </p>
      <el-input
        v-model="reasonText"
        :maxlength="report.reasonTextMax"
        :rows="3"
        show-word-limit
        type="textarea"
        placeholder="时间、地点、原话，以及它为什么违规"
      />

      <p class="rp__note">
        提交即抓取这条内容的快照作为证据，之后内容被删、直播关播都不影响处理。
        结论会以系统通知发回给你，一天内可举报的条数有上限。
      </p>
    </div>

    <template #footer>
      <el-button :disabled="report.submitting" @click="close">取消</el-button>
      <el-button :loading="report.submitting" type="danger" @click="onSubmit">提交举报</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useReportStore } from '@/stores/report'

/**
 * 举报弹窗（全站只挂一份，见 stores/report 的类注释）。
 *
 * 理由、必填标记、描述字数上限<b>全部来自服务端的 /api/report/config</b>，这里不写死一份码表：
 * 后端加了个理由而前端没跟，用户就会看到一个没有该选项的举报框；
 * 而 textRequired 这一位尤其重要——前端不知道某个理由必须填描述，用户提交才被打回，
 * 那会觉得按钮在故意刁难。
 *
 * 理由用竖排 radio 而不是 select：八个选项一次全看见，比点开下拉再找更适合「挑一个最像的」
 * 这种操作；代价是弹窗高度固定不下来，所以给列表留了内部滚动。
 */
const report = useReportStore()

const reasonCode = ref('')
const reasonText = ref('')

const targetTypeName = computed(() => {
  const item = report.targetTypes.find((t) => t.code === report.target?.targetType)
  return item ? item.name : '内容'
})

const selectedReason = computed(() => report.reasons.find((r) => r.code === reasonCode.value))
const textRequired = computed(() => !!selectedReason.value?.textRequired)

// 换对象重开时把上一次的填写清掉：留着上次填的描述会让人觉得这次也提交对了
watch(
  () => report.target,
  (target) => {
    if (target) {
      reasonCode.value = ''
      reasonText.value = ''
    }
  }
)

function close() {
  report.closeReport()
}

function onVisibleChange(value) {
  if (!value) {
    // 提交进行中时 el-dialog 的关闭事件不该被采纳， :close-on-click-modal 已经挡了点击，
    // 这里再兜一层：ESC 键关闭同样会在请求飞行中把弹窗收掉
    if (!report.submitting) {
      close()
    }
  }
}

async function onSubmit() {
  if (!reasonCode.value) {
    ElMessage.warning('先选一个举报理由')
    return
  }
  const text = reasonText.value.trim()
  if (textRequired.value && !text) {
    ElMessage.warning('这个理由需要填写补充描述')
    return
  }
  try {
    await report.submit({ reasonCode: reasonCode.value, reasonText: text })
    ElMessage.success('举报已提交，等待处理')
    close()
  } catch {
    // 重复举报（14003）、超出当日配额（14004）、功能已关闭（14009）这些原因
    // 由 request.js 统一弹提示；弹窗保持打开，用户可以改描述再试一次
  }
}
</script>

<style scoped>
.rp__target {
  align-items: center;
  background: var(--im-bg-hover, #f5f7fa);
  border-radius: 6px;
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 10px;
}

.rp__type {
  color: #909399;
  flex: none;
  font-size: 12px;
}

.rp__title {
  font-weight: 600;
  min-width: 0;
}

.rp__label {
  font-size: 13px;
  margin: 12px 0 6px;
}

.rp__hint {
  color: #909399;
  font-size: 12px;
  margin-left: 6px;
}

.rp__must {
  color: var(--el-color-danger);
}

.rp__reasons {
  max-height: 190px;
  overflow-y: auto;
}

.rp__group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rp__empty {
  color: #909399;
  font-size: 12px;
}

.rp__note {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  margin: 14px 0 0;
}
</style>
