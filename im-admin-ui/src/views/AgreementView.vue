<template>
  <div class="agreement">
    <div class="toolbar">
      <h2 class="page-title">法定文本与同意留痕</h2>
      <span class="grow" />
      <el-button type="primary" @click="openDraft">
        <el-icon><Plus /></el-icon>
        <span>起草新版本</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="warning"
      title="「发布」是全端即时生效的高危动作，而且没有撤回：一份文本发出去之后就再也改不动了"
      description="要改一个字只能发一个版本号更大的新版本顶替它，同时所有只同意过旧版的人下次登录会被弹窗要求重新确认。这一屏没有删除按钮，也不是界面偷懒——已经对用户生效过的合同原件删不掉也不该删掉，最重的动作只有「被新版本顶替」。留痕那一页是举证材料：监管问「这个人同意过哪一版、什么时候同意的」，答得上来的就是那一屏。"
    />

    <el-tabs v-model="tab">
      <!-- ==================== 版本管理 ==================== -->
      <el-tab-pane label="版本管理" name="versions">
        <div class="filters">
          <el-select v-model="vQuery.code" class="code" clearable placeholder="全部文本" @change="reloadVersions">
            <el-option v-for="item in CODE_OPTIONS" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <el-select v-model="vQuery.status" class="status" clearable placeholder="全部状态" @change="reloadVersions">
            <el-option :value="0" label="草稿" />
            <el-option :value="1" label="已发布" />
            <el-option :value="2" label="已归档" />
          </el-select>
          <el-input
            v-model="vQuery.keyword"
            :maxlength="50"
            class="keyword"
            clearable
            placeholder="标题关键词"
            @clear="reloadVersions"
            @keyup.enter="reloadVersions"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button :loading="vLoading" @click="reloadVersions">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
          <span class="muted">固定按文本升序、版本号倒序；列表项不含正文，点开「查看原文」才取</span>
        </div>

        <el-card shadow="never">
          <el-table v-loading="vLoading" :data="vRows">
            <el-table-column label="文本" width="120">
              <template #default="{ row }">
                <span>{{ row.codeName }}</span>
                <span class="muted code-key">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="70">
              <template #default="{ row }">
                <span class="num">v{{ row.version }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标题" min-width="200">
              <template #default="{ row }">
                <span class="ellipsis">{{ row.title }}</span>
              </template>
            </el-table-column>
            <el-table-column label="签署要求" width="90">
              <template #default="{ row }">
                <el-tag :type="row.required === 1 ? 'danger' : 'info'" effect="plain" size="small">
                  {{ row.required === 1 ? '必签' : '可选' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)" effect="plain" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="生效" width="120">
              <template #default="{ row }">
                <span class="num">{{ row.effectiveTime ? formatListTime(row.effectiveTime) : EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="发布人" width="120">
              <template #default="{ row }">
                <span class="ellipsis">{{ row.publishByName || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="变更说明" min-width="180">
              <template #default="{ row }">
                <span class="ellipsis muted">{{ row.remark || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="190">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button link type="primary" @click="openPreview(row)">原文</el-button>
                  <template v-if="row.status === 0">
                    <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                    <el-button link type="success" @click="confirmPublish(row)">发布</el-button>
                  </template>
                  <el-button v-else-if="row.status === 1" link @click="openDraft(row.code)">起草新版</el-button>
                  <!-- 已归档的那一版仍然永久可查，只是没有下一步动作了 -->
                  <span v-else class="muted">已归档，仍可查</span>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="还没有任何协议版本，先执行 sql/migration_agreement.sql 种入三份 v1" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="vQuery.current"
              v-model:page-size="vQuery.size"
              :page-sizes="[10, 20, 50]"
              :total="vTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadVersions"
              @current-change="loadVersions"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 同意留痕 ==================== -->
      <el-tab-pane label="同意留痕" name="consents">
        <div class="filters">
          <el-select v-model="cQuery.code" class="code" clearable placeholder="全部文本" @change="reloadConsents">
            <el-option v-for="item in CODE_OPTIONS" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <el-input
            v-model="cQuery.userId"
            class="user-id"
            clearable
            placeholder="用户 ID"
            @clear="reloadConsents"
            @keyup.enter="reloadConsents"
          />
          <el-select v-model="cQuery.action" class="status" clearable placeholder="同意与拒绝" @change="reloadConsents">
            <el-option :value="1" label="仅同意" />
            <el-option :value="0" label="仅拒绝" />
          </el-select>
          <el-date-picker
            v-model="cQuery.range"
            class="range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            @change="reloadConsents"
          />
          <el-button :loading="cLoading" @click="reloadConsents">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
          <span class="muted">留痕只增不改：一次表态一行，固定按表态时刻倒序</span>
        </div>

        <el-card shadow="never">
          <el-table v-loading="cLoading" :data="cRows">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="detail">
                  <div class="detail-meta">
                    <span>用户：{{ row.nickname || EMPTY_TEXT }}（{{ row.account || EMPTY_TEXT }}，#{{ row.userId }}）</span>
                    <span>版本行：#{{ row.agreementId }}</span>
                    <span>来源场景：{{ sourceText(row.source) }}</span>
                    <span>IP：{{ row.ip || EMPTY_TEXT }}</span>
                  </div>
                  <div class="detail-ua">{{ row.userAgent || '未采集到 User-Agent' }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="表态时间" width="130">
              <template #default="{ row }">
                <span class="num">{{ formatDateTime(row.consentTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="用户" min-width="160">
              <template #default="{ row }">
                <span class="ellipsis">{{ row.nickname || row.account || `#${row.userId}` }}</span>
                <el-button link type="primary" class="chain-btn" @click="openChain(row.userId)">同意链</el-button>
              </template>
            </el-table-column>
            <el-table-column label="协议" min-width="180">
              <template #default="{ row }">
                <span>{{ row.codeName }} v{{ row.version }}</span>
                <span class="muted ellipsis">｜{{ row.title }}</span>
              </template>
            </el-table-column>
            <el-table-column label="动作" width="80">
              <template #default="{ row }">
                <el-tag :type="row.action === 1 ? 'success' : 'warning'" size="small">
                  {{ row.action === 1 ? '同意' : '拒绝' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="场景" width="110">
              <template #default="{ row }">
                <span class="muted">{{ sourceText(row.source) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="IP" width="130">
              <template #default="{ row }">
                <span class="num">{{ row.ip || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <!-- 举证的关键一问：他点的头针对的是哪一份原文。这一列就是那答案 -->
                <el-button link type="primary" @click="openPreviewById(row.agreementId)">他同意的原文</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="还没有任何同意记录" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="cQuery.current"
              v-model:page-size="cQuery.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="cTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadConsents"
              @current-change="loadConsents"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 起草 / 编辑草稿：Markdown + 实时预览，与文章管理那屏同一套两栏 -->
    <el-dialog
      v-model="editor.visible"
      :title="editor.id ? `编辑草稿：${editor.codeName} v${editor.version}` : '起草新版本'"
      width="960px"
      top="5vh"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="协议文本">
          <!--
            编辑草稿时文本锁定：草稿已经带上了 code 与版本号，
            改文本等于把这一行挪到另一份协议下面去，那应该重新起一稿而不是改这一稿。
          -->
          <el-select v-model="editor.code" :disabled="!!editor.id" class="code">
            <el-option v-for="item in CODE_OPTIONS" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <div class="note">
            {{ editor.id ? '本稿所属文本不可更改' : '版本号由服务端取「历史最大 + 1」，这里不用填' }}
          </div>
        </el-form-item>
        <el-form-item label="文档标题（必填，最多 120 字）">
          <el-input v-model="editor.title" :maxlength="120" show-word-limit placeholder="会原样显示在公示页的一级标题" />
        </el-form-item>
        <el-form-item label="一句话摘要（选填，最多 500 字）">
          <el-input
            v-model="editor.summary"
            :maxlength="500"
            :rows="2"
            show-word-limit
            resize="none"
            type="textarea"
            placeholder="弹窗与设置页那行小字用，别把整段正文抄进来"
          />
        </el-form-item>
        <el-form-item label="签署要求">
          <el-radio-group v-model="editor.required">
            <el-radio :value="1">必签（注册必须同意，拒签即无法建号）</el-radio>
            <el-radio :value="0">可选（可拒，只关掉对应增值服务入口）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Markdown 正文">
          <div class="panes">
            <div class="pane">
              <div class="pane-title">编辑</div>
              <el-input
                v-model="editor.content"
                type="textarea"
                :autosize="{ minRows: 12, maxRows: 20 }"
                placeholder="## 一、适用范围&#10;&#10;正文里的【运营主体名称】【客服邮箱】这类占位符要在发布前替换掉。"
              />
            </div>
            <div class="pane">
              <div class="pane-title">预览</div>
              <div class="markdown-body" v-html="editor.html"></div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="muted foot-note">发布后正文不可修改，请在本稿里改到位</span>
        <el-button @click="editor.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!editorReady" :loading="editor.saving" @click="saveDraft">
          {{ editor.id ? '保存草稿' : '存为草稿' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 原文预览：草稿、生效版、归档版都从这一个口子看，公示页之外再给一份后台口径 -->
    <el-dialog v-model="preview.visible" :title="preview.title" width="820px" top="6vh" destroy-on-close>
      <div v-loading="preview.loading" class="preview">
        <div v-if="preview.vo" class="preview-meta">
          <span>{{ preview.vo.codeName }} v{{ preview.vo.version }}</span>
          <span>{{ statusText(preview.vo.status) }}</span>
          <span>
            签署要求：{{ preview.vo.required === 1 ? '必签' : '可选' }}
          </span>
          <span>生效：{{ formatDateTime(preview.vo.effectiveTime) }}</span>
          <span>发布人：{{ preview.vo.publishByName || EMPTY_TEXT }}</span>
        </div>
        <div v-if="preview.vo?.summary" class="preview-summary">{{ preview.vo.summary }}</div>
        <div class="markdown-body" v-html="preview.html"></div>
      </div>
    </el-dialog>

    <!-- 个人同意链：一次把这三份文本的所有表态摊开，不分页 -->
    <el-dialog v-model="chain.visible" :title="chain.title" width="900px" top="6vh" destroy-on-close>
      <el-table v-loading="chain.loading" :data="chain.rows" max-height="480">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="num">{{ formatDateTime(row.consentTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="协议" min-width="150">
          <template #default="{ row }">
            <span>{{ row.codeName }} v{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="80">
          <template #default="{ row }">
            <el-tag :type="row.action === 1 ? 'success' : 'warning'" size="small">
              {{ row.action === 1 ? '同意' : '拒绝' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="场景" width="110">
          <template #default="{ row }">{{ sourceText(row.source) }}</template>
        </el-table-column>
        <el-table-column label="IP" width="130">
          <template #default="{ row }">
            <span class="num">{{ row.ip || EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="openPreviewById(row.agreementId)">原文</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="这个账号没有任何同意记录" :image-size="60" />
        </template>
      </el-table>
      <p class="muted chain-note">
        「从来没留过痕」与「留过 v1 的痕而现行是 v2」是两回事：前者要么走一次强制弹窗，要么这个账号根本还没登录过。
      </p>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  createAgreementDraft,
  fetchAgreementDetail,
  fetchAgreementPage,
  fetchConsentPage,
  fetchUserConsents,
  publishAgreement,
  updateAgreementDraft
} from '@/api/admin'
import { EMPTY_TEXT, formatDateTime, formatListTime } from '@/utils/format'

/**
 * 法定文本与同意留痕（本批）。
 *
 * 一屏两个权限面：左边那半是「对外说的合同」（起草 → 发布 → 被顶替），
 * 右边那半是「谁说过的话」（只增不改的留痕）。它们必须放在同一屏，
 * 因为现场问的一定是这两者的对应关系：他同意的那一版现在还在生效吗？
 *
 * 这一屏与公告、文章那两屏最大的差别是<b>动作少</b>：没有删除、没有撤回、没有改已发布正文。
 * 后端根本不开那几个端点（理由写在 AdminAgreementController 的类注释里），
 * 前端也就不去摆点了会报错的按钮。
 */

/**
 * 三份文本的字典。
 *
 * 这是本仓少见的「前端抄一份码表」：后端下发 codeName 时用的是同一份映射
 * （{@code Agreement.nameOf}），筛选下拉要的是「可选项 + 中文名」，
 * 而接口没有为此单开一个字典端点——为三个常量开一个端点不划算，
 * 加第四份协议时记得两边都改（Agreement.CODE_* 与此处）。
 */
const CODE_OPTIONS = [
  { value: 'terms', label: '用户协议 terms' },
  { value: 'privacy', label: '隐私政策 privacy' },
  { value: 'service', label: '服务条款 service' }
]

/** 表态来源：与后端 ImConstants.SOURCE_* 三个值一一对应 */
const SOURCE_TEXT = {
  register: '注册勾选',
  login: '登录建号',
  dialog: '再同意弹窗'
}

const tab = ref('versions')

const vRows = ref([])
const vTotal = ref(0)
const vLoading = ref(false)
const vQuery = reactive({ code: undefined, status: undefined, keyword: '', current: 1, size: 20 })

const cRows = ref([])
const cTotal = ref(0)
const cLoading = ref(false)
const cQuery = reactive({ code: undefined, userId: '', action: undefined, range: [], current: 1, size: 20 })

const editor = reactive({
  visible: false,
  saving: false,
  id: null,
  version: null,
  codeName: '',
  code: 'privacy',
  title: '',
  summary: '',
  content: '',
  required: 1,
  html: ''
})

const preview = reactive({ visible: false, loading: false, title: '', vo: null, html: '' })

const chain = reactive({ visible: false, loading: false, title: '', rows: [] })

const editorReady = computed(
  () => editor.code.trim() !== '' && editor.title.trim() !== '' && editor.content.trim() !== ''
)

function statusText(status) {
  if (status === 0) {
    return '草稿'
  }
  if (status === 1) {
    return '已发布'
  }
  if (status === 2) {
    return '已归档'
  }
  return EMPTY_TEXT
}

/** 草稿 info（还没对外说过）、已发布 success（正在生效）、已归档 warning（被顶替但仍可查） */
function statusTag(status) {
  if (status === 1) {
    return 'success'
  }
  if (status === 2) {
    return 'warning'
  }
  return 'info'
}

function sourceText(source) {
  return SOURCE_TEXT[source] || source || EMPTY_TEXT
}

function toHtml(markdown) {
  if (!markdown) {
    return ''
  }
  return DOMPurify.sanitize(marked.parse(markdown, { breaks: true, gfm: true }))
}

async function loadVersions() {
  vLoading.value = true
  try {
    const page = await fetchAgreementPage({
      code: vQuery.code || undefined,
      status: vQuery.status,
      keyword: vQuery.keyword || undefined,
      current: vQuery.current,
      size: vQuery.size
    })
    vRows.value = page.records || []
    vTotal.value = Number(page.total) || 0
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果，不把列表清成空白
  } finally {
    vLoading.value = false
  }
}

function reloadVersions() {
  vQuery.current = 1
  loadVersions()
}

async function loadConsents() {
  cLoading.value = true
  try {
    const range = Array.isArray(cQuery.range) ? cQuery.range : []
    const page = await fetchConsentPage({
      code: cQuery.code || undefined,
      // 用户 ID 是数字列，空串与非法输入都不送，免得后端 Long 转换报 1001
      userId: /^\d+$/.test(String(cQuery.userId || '').trim()) ? String(cQuery.userId).trim() : undefined,
      action: cQuery.action,
      startTime: range[0] || undefined,
      endTime: range[1] || undefined,
      current: cQuery.current,
      size: cQuery.size
    })
    cRows.value = page.records || []
    cTotal.value = Number(page.total) || 0
  } catch {
    // 同上
  } finally {
    cLoading.value = false
  }
}

function reloadConsents() {
  cQuery.current = 1
  loadConsents()
}

/** 切到留痕那一半才第一次拉它：一屏两个列表，都拉等于每次进这一屏打两个查询 */
watch(tab, (value) => {
  if (value === 'consents' && cRows.value.length === 0 && cTotal.value === 0) {
    loadConsents()
  }
})

/**
 * 起草新稿。
 *
 * @param code 从某一行的「起草新版」进来时带上它的 code，直接预填那一份文本
 */
function openDraft(code) {
  const preset = typeof code === 'string' ? code : ''
  editor.id = null
  editor.version = null
  editor.code = preset || 'privacy'
  editor.codeName = CODE_OPTIONS.find((item) => item.value === editor.code)?.label || ''
  editor.title = ''
  editor.summary = ''
  editor.content = ''
  editor.required = editor.code === 'service' ? 0 : 1
  editor.html = ''
  editor.visible = true
}

/**
 * 编辑草稿前现取一次详情。
 *
 * 列表行不带正文（分页时服务端把 content 置了 null），必须回查；
 * 顺带确认它还是草稿——这一屏可能开着好几个管理员，两秒前的草稿可能刚被同事发掉。
 */
async function openEdit(row) {
  let vo
  try {
    vo = await fetchAgreementDetail(row.id)
  } catch {
    return
  }
  if (vo.status !== 0) {
    ElMessage.warning(`这一版已是「${statusText(vo.status)}」，正文不可再修改`)
    loadVersions()
    return
  }
  editor.id = vo.id
  editor.version = vo.version
  editor.code = vo.code
  editor.codeName = vo.codeName
  editor.title = vo.title
  editor.summary = vo.summary || ''
  editor.content = vo.content || ''
  editor.required = vo.required === 0 ? 0 : 1
  editor.html = toHtml(vo.content)
  editor.visible = true
}

/** 正文一边打一边渲，跟文章管理一致；协议正文里的表格与列表要靠 gfm */
watch(() => editor.content, (value) => {
  editor.html = toHtml(value)
})

function draftPayload() {
  return {
    code: editor.code,
    title: editor.title.trim(),
    summary: editor.summary.trim() || null,
    content: editor.content.trim(),
    required: editor.required
  }
}

async function saveDraft() {
  if (!editorReady.value) {
    return
  }
  editor.saving = true
  try {
    if (editor.id) {
      await updateAgreementDraft(editor.id, draftPayload())
    } else {
      await createAgreementDraft(draftPayload())
    }
    editor.visible = false
    reloadVersions()
  } catch {
    // 接口失败：弹框留着，改完可以直接再点
  } finally {
    editor.saving = false
  }
}

/**
 * 发布草稿。
 *
 * 变更说明用 prompt 现填而不是在草稿表单里写死：这一句是对「v3 比 v2 改了什么」的正式回答，
 * 起草时往往还说不准，到发布那一刻才写得全。它同样会被抄进每一位重新同意者的留痕附近。
 * 已归档那版会同时被顶掉，所以确认文案里必须写清「所有人下次登录会被重新弹窗」。
 */
async function confirmPublish(row) {
  let remark
  try {
    const result = await ElMessageBox.prompt(
      `发布后「${row.codeName}」v${row.version} 立即对外生效，原生效版本自动归档；` +
        '所有只同意过旧版的人下次登录都会被强制弹窗重新确认。这一步没有撤回。',
      '填写本版变更说明',
      {
        type: 'warning',
        confirmButtonText: '确认发布',
        cancelButtonText: '再看看',
        inputValue: row.remark || '',
        // 变更说明允许留空（后端只在 remark 超长时校验），但空着将来答不上「改了什么」
        inputPlaceholder: '例如：新增第 7.3 条 AI 面试监考数据说明',
        maxlength: 255
      }
    )
    remark = result.value
  } catch {
    return
  }
  try {
    await publishAgreement(row.id, (remark || '').trim() || undefined)
    loadVersions()
  } catch {
    // 已提示
  }
}

async function openPreview(row) {
  preview.title = `${row.codeName} v${row.version} · ${row.title}`
  preview.vo = null
  preview.html = ''
  preview.loading = true
  preview.visible = true
  try {
    const vo = await fetchAgreementDetail(row.id)
    preview.vo = vo
    preview.html = toHtml(vo.content)
  } catch {
    preview.visible = false
  } finally {
    preview.loading = false
  }
}

/** 从留痕跳去看「他当时同意的那一份原文」，按版本行 ID 取 */
async function openPreviewById(agreementId) {
  if (!agreementId) {
    ElMessage.warning('这条留痕没有关联到具体版本行，取不到原文')
    return
  }
  preview.title = `版本行 #${agreementId} 的原文`
  preview.vo = null
  preview.html = ''
  preview.loading = true
  preview.visible = true
  try {
    const vo = await fetchAgreementDetail(agreementId)
    preview.vo = vo
    preview.html = toHtml(vo.content)
  } catch {
    preview.visible = false
  } finally {
    preview.loading = false
  }
}

async function openChain(userId) {
  chain.title = `用户 #${userId} 的完整同意链`
  chain.rows = []
  chain.loading = true
  chain.visible = true
  try {
    chain.rows = (await fetchUserConsents(userId)) || []
  } catch {
    chain.visible = false
  } finally {
    chain.loading = false
  }
}

onMounted(loadVersions)
</script>

<style scoped>
.hint {
  margin-bottom: 12px;
}

.filters {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}

.code {
  width: 180px;
}

.status {
  width: 140px;
}

.keyword {
  width: 200px;
}

.user-id {
  width: 140px;
}

.range {
  width: 360px;
}

.code-key {
  margin-left: 6px;
}

.chain-btn {
  margin-left: 8px;
}

.detail {
  padding: 4px 48px 12px;
}

.detail-meta {
  color: #909399;
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 16px;
}

.detail-ua {
  color: #909399;
  font-size: 12px;
  margin-top: 6px;
  word-break: break-all;
}

.note {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}

.panes {
  display: flex;
  gap: 12px;
  width: 100%;
}

.pane {
  flex: 1;
  min-width: 0;
}

.pane-title {
  color: #909399;
  font-size: 12px;
  margin-bottom: 4px;
}

.foot-note {
  float: left;
  line-height: 32px;
}

.preview {
  max-height: 68vh;
  overflow-y: auto;
}

.preview-meta {
  border-bottom: 1px dashed #dcdfe6;
  color: #909399;
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 16px;
  margin-bottom: 10px;
  padding-bottom: 8px;
}

.preview-summary {
  background: #f5f7fa;
  border-left: 3px solid #409eff;
  color: #606266;
  font-size: 13px;
  margin-bottom: 12px;
  padding: 8px 12px;
}

.chain-note {
  margin: 10px 0 0;
}

.markdown-body {
  color: #303133;
  line-height: 1.7;
  word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  font-weight: 600;
  line-height: 1.4;
  margin: 14px 0 8px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
  width: 100%;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #ebeef5;
  padding: 6px 10px;
  text-align: left;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
}

.markdown-body :deep(blockquote) {
  background: #f5f7fa;
  border-left: 3px solid #dcdfe6;
  color: #606266;
  margin: 8px 0;
  padding: 4px 12px;
}

.markdown-body :deep(pre) {
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 0.9em;
  overflow: auto;
  padding: 10px;
}

.markdown-body :deep(code) {
  background: #f5f7fa;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
  padding: 1px 5px;
}

.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
}

.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid #ebeef5;
  margin: 12px 0;
}

.markdown-body :deep(a) {
  color: #409eff;
  text-decoration: none;
}
</style>
