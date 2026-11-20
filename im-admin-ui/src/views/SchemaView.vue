<template>
  <div class="schema">
    <div class="toolbar">
      <h2 class="page-title">SQL 版本管理</h2>
      <span class="grow" />
      <span class="muted dir-hint">脚本目录：{{ overview.dir || '-' }}</span>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>重新读取</span>
      </el-button>
    </div>

    <el-alert
      v-if="!overview.tableReady"
      class="hint"
      type="warning"
      :closable="false"
      show-icon
      title="台账表还没建"
      description="请先由库管理员在存量库执行一次引导脚本 sql/migration_schema_version.sql（建 im_schema_migration 两张表并登记 1.0.0 基线），再回来刷新本页面。这是全项目最后一个需要手动执行的 DDL。"
    />
    <el-alert
      v-else-if="!overview.dirFound"
      class="hint"
      type="warning"
      :closable="false"
      show-icon
      title="找不到 sql/versions 脚本目录"
      description="后端在 jar 外部读脚本目录；部署环境请在 application.yml 用 im.admin.schema-dir 指定绝对路径（或把仓库的 sql/versions 随部署带上）。台账展示不受影响，但应用/回滚会被拒绝。"
    />
    <el-alert
      v-else
      class="hint"
      type="info"
      :closable="false"
      title="规矩：改库 = 往 sql/versions 里成对提交 V<版本>__名.sql 与 U<版本>__名.sql，然后在这里点「应用」"
      :description="`按版本号升序逐个应用；回滚走 LIFO（只许回最近一次已应用的 ${overview.latestApplied || '——'}）。一键回滚只还原结构，DROP/DELETE 掉的数据靠备份恢复——动手前先 mysqldump。`"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="overview.items || []" size="small">
        <el-table-column label="版本" width="90" prop="version" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="statusTag(row)" size="small" effect="light">{{ row.statusText }}</el-tag>
            <el-tag v-if="row.checksumMatched === false" class="drift" type="danger" size="small" effect="plain">
              脚本已改动
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="向前脚本" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 实体文件（.sql 结尾）才可点：历史基线那行是合集描述文字，没文件可查 -->
            <el-link v-if="isScriptFile(row.scriptName)" type="primary" :underline="false" @click="viewScript(row, false)">
              <code>{{ row.scriptName }}</code>
            </el-link>
            <span v-else class="muted">{{ row.scriptName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="回滚脚本" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link v-if="isScriptFile(row.undoName)" type="primary" :underline="false" @click="viewScript(row, true)">
              <code>{{ row.undoName }}</code>
            </el-link>
            <span v-else class="muted">不可一键回滚</span>
          </template>
        </el-table-column>
        <el-table-column label="执行人" width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.operatorName">{{ row.operatorName }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="应用时间" width="150">
          <template #default="{ row }">{{ row.appliedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="回滚时间" width="150">
          <template #default="{ row }">{{ row.rolledBackAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.canApply" size="small" type="primary" :loading="acting" @click="confirmApply(row)">
              应用
            </el-button>
            <el-button v-if="row.canRollback" size="small" type="danger" plain :loading="acting" @click="confirmRollback(row)">
              回滚
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="log-card">
      <template #header>
        <span class="log-title">变更过程（最近 {{ logs.length }} 条操作流水）</span>
      </template>
      <el-empty v-if="!logs.length" description="还没有任何应用/回滚记录" :image-size="60" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="(l, i) in logs"
          :key="i"
          :timestamp="l.createTime"
          :type="l.result === 'SUCCESS' ? (l.action === 'ROLLBACK' ? 'warning' : 'success') : 'danger'"
          placement="top"
        >
          <div class="log-line">
            <b>{{ actionText(l.action) }}</b>
            <span>版本 {{ l.version }}</span>
            <span>操作人：{{ l.operatorName || '系统' }}</span>
            <span>{{ l.statementCount }} 条语句 / {{ l.durationMs }}ms</span>
            <el-tag :type="l.result === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="plain">
              {{ l.result === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </div>
          <div v-if="l.message" class="log-msg">{{ l.message }}</div>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <!-- 脚本原文弹窗：只读展示，内容来自后端按版本号扫目录解析的文件，不是拼路径直读 -->
    <el-dialog v-model="scriptDlg.visible" :title="scriptDlg.title" width="min(1280px, 94vw)" top="4vh" class="script-dlg">
      <el-input v-loading="scriptDlg.loading" type="textarea" :model-value="scriptDlg.content" readonly resize="none" class="script-pre" />
      <template #footer>
        <el-button :disabled="!scriptDlg.content" @click="copyScript">一键复制</el-button>
        <el-button :disabled="!scriptDlg.content" type="primary" plain @click="exportScript">导出 SQL 文件</el-button>
        <el-button @click="scriptDlg.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  applySchemaVersion,
  fetchSchemaLogs,
  fetchSchemaOverview,
  fetchSchemaScript,
  rollbackSchemaVersion
} from '@/api/admin'

const loading = ref(false)
const acting = ref(false)
const overview = reactive({ dir: '', dirFound: false, tableReady: false, latestApplied: '', items: [] })
const logs = ref([])
const scriptDlg = reactive({ visible: false, loading: false, title: '', name: '', content: '' })

// 只有真实脚本文件才能点开看：历史基线的 scriptName 是描述文字，不带 .sql
function isScriptFile(name) {
  return typeof name === 'string' && name.endsWith('.sql')
}

async function viewScript(row, undo) {
  const name = undo ? row.undoName : row.scriptName
  scriptDlg.title = `${undo ? '回滚' : '向前'}脚本：${name}（版本 ${row.version}）`
  scriptDlg.name = name
  scriptDlg.content = ''
  scriptDlg.visible = true
  scriptDlg.loading = true
  try {
    const data = await fetchSchemaScript(row.version, undo)
    scriptDlg.content = data.content
  } finally {
    scriptDlg.loading = false
  }
}

async function copyScript() {
  try {
    await navigator.clipboard.writeText(scriptDlg.content)
    ElMessage.success('脚本内容已复制到剪贴板')
  } catch {
    // 非 HTTPS 且非 localhost 的上下文里 clipboard API 不可用，退回隐藏 textarea + execCommand
    const ta = document.createElement('textarea')
    ta.value = scriptDlg.content
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    ok ? ElMessage.success('脚本内容已复制到剪贴板') : ElMessage.error('复制失败，请全选文本后手动复制')
  }
}

function exportScript() {
  // 脚本都是文本级体量，Blob 直下不经接口；文件名沿用仓库里的真实脚本名，下载回来就是可入库的 V/U 对
  const blob = new Blob([scriptDlg.content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = scriptDlg.name
  a.click()
  URL.revokeObjectURL(url)
}

function statusTag(row) {
  if (row.statusText === '历史基线') return 'primary'
  if (row.canApply || row.statusText === '待应用') return 'info'
  return { 已应用: 'success', 已回滚: 'warning', 应用失败: 'danger' }[row.statusText] || 'info'
}

function actionText(action) {
  return { APPLY: '应用', ROLLBACK: '回滚', MARK: '登记基线' }[action] || action
}

async function loadOverview() {
  loading.value = true
  try {
    const data = await fetchSchemaOverview()
    Object.assign(overview, data)
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  try {
    logs.value = (await fetchSchemaLogs(100)) || []
  } catch {
    logs.value = []
  }
}

function reload() {
  loadOverview()
  loadLogs()
}

async function confirmApply(row) {
  try {
    await ElMessageBox.confirm(
      `将逐条执行 ${row.scriptName}（版本 ${row.version}）。脚本直接改动库结构，` +
        `确认已对当前库做过 mysqldump 备份？半途失败时前几条已落库（DDL 不可回卷），台账会标「应用失败」。`,
      `应用版本 ${row.version}`,
      { type: 'warning', confirmButtonText: '已备份，执行', cancelButtonText: '取消', confirmButtonClass: 'el-button--primary' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await applySchemaVersion(row.version)
    ElMessage.success(`版本 ${row.version} 已应用`)
    reload()
  } finally {
    acting.value = false
  }
}

async function confirmRollback(row) {
  try {
    // 输版本号本身作二次确认：这一页的按钮直接改库，只靠「手快点到」不该能触发回滚
    await ElMessageBox.prompt(
      `将执行 ${row.undoName}（版本 ${row.version} 的回滚脚本）。结构可还原、数据不可恢复，` +
        `请先确认已有备份。输入版本号以确认：`,
      `回滚版本 ${row.version}`,
      {
        type: 'warning',
        confirmButtonText: '已备份，回滚',
        cancelButtonText: '取消',
        inputPattern: new RegExp(`^${row.version.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
        inputErrorMessage: `请逐字输入 ${row.version}`
      }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await rollbackSchemaVersion(row.version)
    ElMessage.success(`版本 ${row.version} 已回滚`)
    reload()
  } finally {
    acting.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.schema {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dir-hint {
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drift {
  margin-left: 6px;
}
.log-card {
  margin-top: 4px;
}
.log-title {
  font-weight: 600;
}
.log-line {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.log-msg {
  margin-top: 4px;
  color: var(--el-color-danger);
  font-size: 12px;
  word-break: break-all;
}
code {
  background: var(--el-fill-color-light);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
}
.script-pre :deep(.el-textarea__inner) {
  /* 高度跟着视口走而不是写死 rows：矮屏不溢出、高屏多读几行；前一行 100vh 兜老内核 */
  height: calc(100vh - 240px);
  height: calc(100dvh - 240px);
  min-height: 360px;
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
}
</style>
