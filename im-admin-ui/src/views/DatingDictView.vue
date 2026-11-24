<template>
  <div class="dict">
    <el-tabs v-model="activeTab" class="dict__tabs">
      <!-- ==================== 兴趣标签 ==================== -->
      <el-tab-pane label="兴趣标签" name="tags">
        <div class="toolbar">
          <span class="muted">标签库是全站的：选人时看到的、匹配加权算的都在这里。停用不删用户已有的绑定，随时能再启用。</span>
          <span class="grow" />
          <el-input v-model="tagCategory" :maxlength="16" clearable class="cat" placeholder="按分类过滤" @clear="loadTags" @keyup.enter="loadTags" />
          <el-button type="primary" @click="openTagCreate">
            <el-icon><Plus /></el-icon>
            <span>新增标签</span>
          </el-button>
          <el-button :loading="tagLoading" @click="loadTags">
            <el-icon><Refresh /></el-icon>
            <span>重新读取</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="tagLoading" :data="tags">
            <el-table-column align="center" label="排序" prop="sort" width="80" />
            <el-table-column label="标签名" min-width="150">
              <template #default="{ row }">
                <span>{{ row.name }}</span>
                <span v-if="row.icon" class="muted"> {{ row.icon }}</span>
              </template>
            </el-table-column>
            <el-table-column label="分类" min-width="110">
              <template #default="{ row }">
                <el-tag effect="plain" size="small">{{ row.category || '未分类' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column align="right" label="在用人数" width="110">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.useCount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-switch :model-value="row.status === 1" @change="(v) => toggleTag(row, v)" />
                <span class="muted switch-text">{{ row.status === 1 ? '启用' : '停用' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="140">
              <template #default="{ row }">
                <el-button text type="primary" @click="openTagEdit(row)">编辑</el-button>
                <el-button text type="danger" @click="confirmDeleteTag(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有标签" :image-size="80" />
            </template>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 破冰文案 ==================== -->
      <el-tab-pane label="破冰文案" name="icebreakers">
        <div class="toolbar">
          <span class="muted">三类文案共用一屏，靠类型区分：打招呼模板、话题卡、标签开场白（必须绑一个标签）。</span>
          <span class="grow" />
          <el-select v-model="ibQuery.type" class="pick" clearable placeholder="全部类型" @change="reloadIcebreakers">
            <el-option v-for="(text, code) in TYPE_TEXT" :key="code" :value="Number(code)" :label="text" />
          </el-select>
          <el-select v-model="ibQuery.tagId" class="pick" clearable filterable placeholder="全部标签" @change="reloadIcebreakers">
            <el-option v-for="tag in tags" :key="tag.id" :value="tag.id" :label="tag.name" />
          </el-select>
          <el-input v-model="ibQuery.keyword" :maxlength="50" clearable class="keyword" placeholder="文案关键词" @clear="reloadIcebreakers" @keyup.enter="reloadIcebreakers" />
          <el-button type="primary" @click="openIbCreate">
            <el-icon><Plus /></el-icon>
            <span>新增文案</span>
          </el-button>
          <el-button :loading="ibLoading" @click="loadIcebreakers">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="ibLoading" :data="icebreakers">
            <el-table-column align="center" label="排序" prop="sort" width="80" />
            <el-table-column label="类型" width="130">
              <template #default="{ row }">
                <el-tag :type="TYPE_TAG[row.type] || 'info'" effect="plain" size="small">{{ typeText(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="绑定标签" width="130">
              <template #default="{ row }">
                <span v-if="row.tagName">{{ row.tagName }}</span>
                <span v-else-if="row.tagId" class="muted">标签已删除</span>
                <span v-else class="muted">{{ EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="文案" min-width="280">
              <template #default="{ row }">
                <span class="content">{{ row.content }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="发送次数" width="110">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.useCount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-switch :model-value="row.status === 1" @change="(v) => toggleIcebreaker(row, v)" />
                <span class="muted switch-text">{{ row.status === 1 ? '启用' : '停用' }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="140">
              <template #default="{ row }">
                <el-button text type="primary" @click="openIbEdit(row)">编辑</el-button>
                <el-button text type="danger" @click="confirmDeleteIcebreaker(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的文案" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="ibQuery.current"
              v-model:page-size="ibQuery.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="ibTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadIcebreakers"
              @current-change="loadIcebreakers"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 标签弹框：sort 留空则后端排到本分类最后 -->
    <el-dialog v-model="tagDialog.visible" :title="tagDialog.id ? '编辑标签' : '新增标签'" width="440px">
      <el-form label-width="72px">
        <el-form-item label="标签名">
          <el-input v-model="tagDialog.name" :maxlength="32" placeholder="不超过 32 字，重名会被拒绝" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="tagDialog.category" :maxlength="16" placeholder="如 outdoor / food，不超过 16 字" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="tagDialog.icon" :maxlength="32" placeholder="选填：一个 emoji 或图标标识" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="tagDialog.sort" :min="0" :max="9999" />
          <span class="muted hint-inline">升序展示，留空排在本分类最后</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="tagSaving" @click="submitTag">保存</el-button>
      </template>
    </el-dialog>

    <!-- 破冰文案弹框：只有「标签开场白」需要绑标签，绑不到就不给提交（否则用户端永远取不出这一条） -->
    <el-dialog v-model="ibDialog.visible" :title="ibDialog.id ? '编辑文案' : '新增文案'" width="520px">
      <el-form label-width="72px">
        <el-form-item label="类型">
          <el-radio-group v-model="ibDialog.type">
            <el-radio v-for="(text, code) in TYPE_TEXT" :key="code" :value="Number(code)">{{ text }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="ibDialog.type === TAG_OPENER" label="标签">
          <el-select v-model="ibDialog.tagId" filterable placeholder="这条开场白适用于哪个标签" class="full">
            <el-option v-for="tag in tags" :key="tag.id" :value="tag.id" :label="tag.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="文案">
          <el-input v-model="ibDialog.content" type="textarea" :rows="3" :maxlength="255" show-word-limit placeholder="用户点一下就发出去的那句话" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="ibDialog.sort" :min="0" :max="9999" />
          <span class="muted hint-inline">同类型内升序</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ibDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="ibSaving" @click="submitIcebreaker">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createAdminIcebreaker,
  createAdminTag,
  deleteAdminIcebreaker,
  deleteAdminTag,
  fetchAdminIcebreakerPage,
  fetchAdminTagList,
  updateAdminIcebreaker,
  updateAdminTag
} from '@/api/admin'
import { EMPTY_TEXT, formatListTime, formatNumber } from '@/utils/format'

/**
 * 后台「交友字典」屏：兴趣标签库 + 破冰文案库（权限点 134 system:tag:manage）。
 *
 * 两张表在一屏而不是拆两屏：前台选完标签立刻就要出开场白，运营改文案时必然对着同一个标签
 * 想效果，拆两个菜单就是把一次编辑拆成两次跳转。后端也是一个 Controller 一套权限。
 *
 * 标签<b>不做分页</b>：它是下拉数据源，破冰页的「绑定标签」与筛选都要一次拿全量才能填满，
 * 分页反而逼前端为同一个字典再写一套「搜索时按需加载」。
 *
 * 文案里的「标签已删除」是一种真实存在态：删标签会连带删掉绑它的开场白，但把文案的类型改成
 * 打招呼模板时不会顺手清掉旧的 tagId（对另外两类是无害冗余），于是可能留下一个指向已删标签的
 * 值——这里显示成一句人话，而不是一个裸 ID 或空白。
 */
const TYPE_TEXT = { 1: '打招呼模板', 2: '话题卡', 3: '标签开场白' }
const TYPE_TAG = { 1: 'primary', 2: 'warning', 3: 'success' }
/** IcebreakerType.TAG_OPENER：只有这一档必须绑标签（码值以 im-common 的枚举与库里的种子为准） */
const TAG_OPENER = 3

function typeText(type) {
  return TYPE_TEXT[type] || '未知'
}

const activeTab = ref('tags')

onMounted(() => {
  loadTags()
  loadIcebreakers()
})

/* ==================== 标签 ==================== */

const tags = ref([])
const tagLoading = ref(false)
const tagCategory = ref('')

async function loadTags() {
  tagLoading.value = true
  try {
    tags.value = (await fetchAdminTagList(tagCategory.value || undefined)) || []
  } catch {
    // 提示已由 request.js 弹过；保留上一次结果，否则一次网络抖动就把下拉清空
  } finally {
    tagLoading.value = false
  }
}

const tagDialog = reactive({ visible: false, id: null, name: '', category: '', icon: '', sort: undefined })
const tagSaving = ref(false)

function openTagCreate() {
  tagDialog.id = null
  tagDialog.name = ''
  tagDialog.category = ''
  tagDialog.icon = ''
  tagDialog.sort = undefined
  tagDialog.visible = true
}

function openTagEdit(row) {
  tagDialog.id = row.id
  tagDialog.name = row.name
  tagDialog.category = row.category || ''
  tagDialog.icon = row.icon || ''
  tagDialog.sort = row.sort
  tagDialog.visible = true
}

async function submitTag() {
  if (!tagDialog.name.trim()) {
    ElMessage.warning('标签名不能为空')
    return
  }
  tagSaving.value = true
  try {
    const payload = {
      name: tagDialog.name.trim(),
      category: tagDialog.category.trim() || undefined,
      icon: tagDialog.icon.trim() || undefined,
      sort: tagDialog.sort
    }
    if (tagDialog.id) {
      await updateAdminTag(tagDialog.id, payload)
      ElMessage.success('标签已保存')
    } else {
      await createAdminTag(payload)
      ElMessage.success('标签已新增')
    }
    tagDialog.visible = false
    loadTags()
  } catch {
    // 同名之类的拒绝由接口层弹提示
  } finally {
    tagSaving.value = false
  }
}

async function toggleTag(row, enabled) {
  try {
    const updated = await updateAdminTag(row.id, { status: enabled ? 1 : 0 })
    Object.assign(row, updated)
    ElMessage.success(enabled ? '标签已启用' : '标签已停用')
  } catch {
    // 失败不本地改状态：:model-value 单向绑定，不更新就自动回弹
  }
}

function confirmDeleteTag(row) {
  ElMessageBox.confirm(
    `删除「${row.name}」会连带删掉绑在这个标签上的开场白文案，用户资料里的这条选择也不再展示（记录还在）。只想暂时藏起来请用「停用」。`,
    '删除标签',
    {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'error',
      inputPlaceholder: '请输入标签名确认',
      // 手敲标签名：列表里几十个标签，点错行比故意删更常见
      inputValidator: (value) => ((value || '').trim() === row.name ? true : `请精确输入：${row.name}`)
    }
  )
    .then(async () => {
      await deleteAdminTag(row.id)
      ElMessage.success('标签已删除')
      loadTags()
      loadIcebreakers()
    })
    .catch(() => {})
}

/* ==================== 破冰文案 ==================== */

const icebreakers = ref([])
const ibTotal = ref(0)
const ibLoading = ref(false)
const ibQuery = reactive({ type: undefined, tagId: undefined, keyword: '', current: 1, size: 20 })

async function loadIcebreakers() {
  ibLoading.value = true
  try {
    const page = await fetchAdminIcebreakerPage({
      type: ibQuery.type,
      tagId: ibQuery.tagId,
      keyword: ibQuery.keyword || undefined,
      current: ibQuery.current,
      size: ibQuery.size
    })
    icebreakers.value = page.records || []
    ibTotal.value = Number(page.total) || 0
  } catch {
    // 同上
  } finally {
    ibLoading.value = false
  }
}

function reloadIcebreakers() {
  ibQuery.current = 1
  loadIcebreakers()
}

const ibDialog = reactive({ visible: false, id: null, type: 1, tagId: undefined, content: '', sort: undefined })
const ibSaving = ref(false)

function openIbCreate() {
  ibDialog.id = null
  ibDialog.type = 1
  ibDialog.tagId = undefined
  ibDialog.content = ''
  ibDialog.sort = undefined
  ibDialog.visible = true
}

function openIbEdit(row) {
  ibDialog.id = row.id
  ibDialog.type = row.type
  ibDialog.tagId = row.tagId || undefined
  ibDialog.content = row.content || ''
  ibDialog.sort = row.sort
  ibDialog.visible = true
}

async function submitIcebreaker() {
  if (!ibDialog.content.trim()) {
    ElMessage.warning('文案不能为空')
    return
  }
  if (ibDialog.type === TAG_OPENER && !ibDialog.tagId) {
    ElMessage.warning('标签开场白必须绑定一个兴趣标签')
    return
  }
  ibSaving.value = true
  try {
    const payload = {
      type: ibDialog.type,
      tagId: ibDialog.type === TAG_OPENER ? ibDialog.tagId : undefined,
      content: ibDialog.content.trim(),
      sort: ibDialog.sort
    }
    if (ibDialog.id) {
      await updateAdminIcebreaker(ibDialog.id, payload)
      ElMessage.success('文案已保存')
    } else {
      await createAdminIcebreaker(payload)
      ElMessage.success('文案已新增')
    }
    ibDialog.visible = false
    loadIcebreakers()
  } catch {
    // 同上
  } finally {
    ibSaving.value = false
  }
}

async function toggleIcebreaker(row, enabled) {
  try {
    const updated = await updateAdminIcebreaker(row.id, { status: enabled ? 1 : 0 })
    Object.assign(row, updated)
    ElMessage.success(enabled ? '文案已启用' : '文案已停用')
  } catch {
    // 同上，开关自动回弹
  }
}

async function confirmDeleteIcebreaker(row) {
  try {
    await ElMessageBox.confirm(
      `删除这条「${typeText(row.type)}」只会让候选少一条，不影响任何已发出去的消息。`,
      '删除文案',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteAdminIcebreaker(row.id)
    ElMessage.success('文案已删除')
    loadIcebreakers()
  } catch {
    // 同上
  }
}
</script>

<style scoped>
.cat {
  width: 150px;
}

.pick {
  width: 150px;
}

.keyword {
  width: 200px;
}

.full {
  width: 100%;
}

.dict__tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.switch-text {
  margin-left: 8px;
}

.hint-inline {
  margin-left: 8px;
}

.content {
  white-space: pre-line;
  word-break: break-all;
}
</style>
