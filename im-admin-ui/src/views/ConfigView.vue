<template>
  <div class="config">
    <div class="toolbar">
      <h2 class="page-title">系统配置</h2>
      <span class="muted">改的是服务端运行态，不是本机偏好</span>
      <span class="grow" />
      <span v-if="items.length" class="muted">已覆盖 {{ overriddenCount }} / {{ items.length }} 项</span>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>重新读取</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="这一页上的每一个旋钮，动的都是全站"
      description="能改的只有服务端白名单点名的这些项（不含数据源口令、jwt 密钥那类启动期就定死的配置——那正是这道白名单要挡住的东西）。改一次落进 im_config 一行，本节点立即生效，其它节点靠每 30 秒一轮的同步收敛；重启后以库里那行为准，不再回到配置文件。要退回「跟随配置文件」就用「恢复默认」把那一行删掉。"
    />

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span>全服运行期旋钮</span>
          <el-tag v-if="overriddenCount" type="warning" effect="plain" size="small">
            {{ overriddenCount }} 项已偏离启动默认值
          </el-tag>
          <el-tag v-else-if="items.length" type="success" effect="plain" size="small">
            全部跟随配置文件
          </el-tag>
        </div>
      </template>

      <div v-loading="loading" class="groups">
        <section v-for="group in groups" :key="group.code" class="group">
          <h3 class="group-title">{{ group.label }}</h3>
          <div v-for="item in group.items" :key="item.key" class="row">
            <div class="label">
              <span class="name">
                {{ item.title }}
                <el-tag v-if="item.overridden" class="flag" type="warning" effect="plain" size="small">已改</el-tag>
              </span>
              <!-- 说明里带 <b> 强调：这段文案来自服务端枚举而不是任何用户输入。
                   不在这里渲染就得在代码里再维护一份纯文本版，两版迟早漂移 -->
              <span class="muted desc" v-html="item.description" />
              <span class="muted meta">
                启动默认 <b>{{ item.startupDefault }}</b>
                <template v-if="item.overridden">
                  · {{ formatListTime(item.updateTime) }} 由账号 #{{ item.updateBy }} 改
                </template>
                <template v-else>· 未改动，当前值就是它</template>
              </span>
              <span v-if="item.type === 'NUMBER'" class="muted meta">
                可填 {{ item.min }} ~ {{ item.max }}
              </span>
            </div>
            <div class="control">
              <el-switch
                v-if="item.type === 'BOOLEAN'"
                :model-value="item.value === 'true'"
                :loading="busy === item.key"
                :disabled="!items.length || busy !== ''"
                @change="(value) => saveBoolean(item, value)"
              />
              <template v-else>
                <el-input-number
                  v-model="drafts[item.key]"
                  :min="Number(item.min)"
                  :max="Number(item.max)"
                  :step="1"
                  :disabled="!items.length || busy !== ''"
                  class="number"
                  controls-position="right"
                  size="small"
                />
                <el-button
                  v-if="dirty(item)"
                  :loading="busy === item.key"
                  size="small"
                  type="primary"
                  @click="saveNumber(item)"
                >
                  保存
                </el-button>
              </template>
              <el-button
                v-if="item.overridden"
                :disabled="busy !== ''"
                class="reset"
                size="small"
                text
                type="info"
                @click="reset(item)"
              >
                恢复默认
              </el-button>
            </div>
          </div>
        </section>
      </div>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span>敏感词过滤</span>
          <el-tag v-if="filter" :type="filter.enabled ? 'success' : 'danger'" effect="dark" size="small">
            {{ filter.enabled ? '已开启' : '已关闭' }}
          </el-tag>
        </div>
      </template>

      <div class="row">
        <div class="label">
          <span class="name">命中后以 <code>*</code> 遮蔽</span>
          <span class="muted desc">
            命中词库的文本与文件名在落库和投递前被逐字符遮成 <code>*</code>（是遮蔽不是拒发）。
            对管理员自己发送的内容同样生效，平台底线不由账号身份豁免。
          </span>
        </div>
        <div class="control">
          <el-switch
            v-model="enabled"
            :loading="savingFilter"
            :disabled="!filter"
            @change="onChangeFilter"
          />
          <el-button
            v-if="filter?.overridden"
            :disabled="savingFilter"
            class="reset"
            size="small"
            text
            type="info"
            @click="resetFilter"
          >
            恢复默认
          </el-button>
        </div>
      </div>

      <div class="row">
        <div class="label">
          <span class="name">词库状态</span>
          <span class="muted desc">
            生效条数是「classpath 文件词库 ∪ 后台在线词库」合并去重后的结果，
            下面那张表里只列后台加的那部分。
          </span>
        </div>
        <span class="value">
          <el-tag v-if="filter?.ready" type="success" effect="plain" size="small">
            已装载 {{ wordCountText }}
          </el-tag>
          <el-tag v-else type="warning" effect="plain" size="small">未装载</el-tag>
        </span>
      </div>

      <div class="row">
        <div class="label">
          <span class="name">这一项现在的来源</span>
          <span class="muted desc">
            分不清「现在开着是因为我打开了」还是「配置文件里就写着开」，就没法判断
            「重启后它还会不会在」——这两种情况下答案正好相反。
          </span>
        </div>
        <span class="value muted persistence">
          <template v-if="filter?.overridden">
            后台改过并已落库，<b>重启后仍按这一次的值</b>
          </template>
          <template v-else>
            跟随配置文件 <code>im.message.sensitive-filter-enabled</code> =
            <b>{{ filter?.startupDefault }}</b>，重启后回到它
          </template>
        </span>
      </div>

      <el-alert
        v-if="filter && !filter.ready"
        class="inner-alert"
        :closable="false"
        type="warning"
        title="词库没装载成功，现在开着也不会遮蔽任何内容"
        description="开关与词库是两件事：mask() 要求 enabled 与 ready 同时为真。启动日志里的「[敏感词] 词库装载完成 / 装载失败」能给出具体原因（多半是词库文件没打进包或解析异常）。"
      />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span>在线敏感词库</span>
          <el-tag type="info" effect="plain" size="small">后台加了 {{ wordTotal }} 条</el-tag>
        </div>
      </template>

      <el-alert
        class="hint"
        :closable="false"
        type="info"
        title="这张表是文件词库之上的增量，不是替代"
        description="这里加一个词，立刻进字典树；删掉一行，只撤掉后台加的那一个词，classpath 里那一万九千余条一条都不会少。反过来说，想摘掉文件里的某个词（误伤）在本页做不到——那要改词库资源重新打包。词条至少 2 个字符，只允许汉字与小写字母数字，入库前统一折叠（全角转半角、大写转小写），所以 ＵＲ 与 ur 是同一条。"
      />

      <div class="filters">
        <el-input
          v-model="wordQuery.keyword"
          :maxlength="50"
          clearable
          class="keyword"
          placeholder="按词条模糊查找（全角也能命中）"
          @clear="reloadWords"
          @keyup.enter="reloadWords"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button :loading="wordLoading" @click="reloadWords">
          <el-icon><Refresh /></el-icon>
          <span>查询</span>
        </el-button>
        <span class="grow" />
        <el-button type="primary" @click="openAdd">
          <el-icon><Plus /></el-icon>
          <span>新增词条</span>
        </el-button>
      </div>

      <el-table v-loading="wordLoading" :data="words" class="table">
        <el-table-column label="词条" min-width="160">
          <template #default="{ row }">
            <span class="code">{{ row.word }}</span>
          </template>
        </el-table-column>
        <el-table-column label="加词原因" min-width="220">
          <template #default="{ row }">
            <span class="ellipsis">{{ row.remark || EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="添加人" width="110">
          <template #default="{ row }">
            <span class="muted">#{{ row.createBy }}</span>
          </template>
        </el-table-column>
        <el-table-column label="添加时间" width="140">
          <template #default="{ row }">
            <span class="muted num">{{ formatListTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button :disabled="wordDeleting !== ''" size="small" text type="danger" @click="removeWord(row)">
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="后台还没加过词，现在生效的只有文件词库那一份" :image-size="80" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="wordQuery.current"
          v-model:page-size="wordQuery.size"
          :page-sizes="[10, 20, 50]"
          :total="wordTotal"
          layout="total, sizes, prev, pager, next"
          @size-change="reloadWords"
          @current-change="loadWords"
        />
      </div>
    </el-card>

    <p class="muted footnote">
      三处改动的持久化语义是一样的，但生效范围要说清：<b>全服旋钮与敏感词开关落库</b>
      （<code>im_config</code>，重启后由库里那行盖回来，「恢复默认」是物理删行而不是把值写成默认值）；
      <b>在线词库落库</b>（<code>im_sensitive_word</code>，重启时由
      <code>ApplicationReadyEvent</code> 之后的同步任务重建字典树）。改动<b>本节点立即生效，
      其它节点最迟 30 秒后收敛</b>——全站没有发布订阅基础设施，靠签名轮询，这是这一批唯一被
      明确接受的延迟。操作人一律写进服务端日志（<code>[系统配置]</code> / <code>[敏感词]</code> 前缀）。
      本页不设定时器：改了才回读，不改就停在服务端的真实状态上。
    </p>

    <el-dialog v-model="addDialog.visible" title="新增敏感词" width="520px">
      <el-form label-position="top">
        <el-form-item label="词条（一个词，或用顿号 / 逗号 / 换行分隔的一批词，单次上限 500 个）">
          <el-input
            v-model="addDialog.word"
            :maxlength="20000"
            :rows="5"
            show-word-limit
            type="textarea"
            placeholder="例：刷单、代开发票"
          />
        </el-form-item>
        <el-form-item label="加词原因（可空，但它是事后唯一能问出「为什么加它」的地方）">
          <el-input
            v-model="addDialog.remark"
            :maxlength="200"
            show-word-limit
            placeholder="例：客服工单里频繁出现的引流话术"
          />
        </el-form-item>
      </el-form>
      <el-alert
        class="inner-alert"
        :closable="false"
        type="info"
        title="不合法的词会被丢掉，不会跟着入库"
        description="单字、带标点或符号、超过 64 字符的都算不合法；库里已有的（含全角与大小写变体）算重复。提交后会给「拆出多少 / 入库多少 / 重复多少 / 非法多少」四个数，只报一句「成功」看不出词表实际长了几个。"
      />
      <template #footer>
        <el-button :disabled="addDialog.saving" @click="addDialog.visible = false">取消</el-button>
        <el-button
          :disabled="!addDialog.word.trim()"
          :loading="addDialog.saving"
          type="primary"
          @click="submitAdd"
        >
          添加并重建词库
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addSensitiveWords,
  deleteSensitiveWord,
  fetchConfigItems,
  fetchSensitiveFilter,
  fetchSensitiveWordPage,
  resetConfigItem,
  setSensitiveFilter,
  updateConfigItem
} from '@/api/admin'
import { EMPTY_TEXT, formatListTime } from '@/utils/format'

/**
 * 系统配置页（四期第四批，从一卡扩成三卡）。
 *
 * 一个整页而不是三页：这三件事在服务端本来就是同一条生效链路（白名单目录 +
 * 落 im_config / im_sensitive_word + 本节点立即生效、其它节点 30 秒收敛），
 * 拆成三个菜单只会让「刚刚改的那个旋钮在哪一屏」变成一个问题。
 *
 * <b>目录不在前端维护</b>：27 项叫什么、属于哪一组、能填多少、改了有什么后果，
 * 全部由服务端从 AdminConfigKey 枚举摊平下来。前端只按 type 决定渲染开关还是数字框、
 * 按 group 分区——自己抄一份字典就是养第二份真相，而它不会因为改了 Java 枚举而跟着变。
 *
 * 唯一被从目录里摘出来、单独一卡的还是敏感词开关：它除了开关值本身另有 ready 与
 * wordCount 两个运行时事实（开着但词库没装载 = 一个都不遮），一排行放不下这套解释。
 *
 * 数字框不「一改就提交」：这些值里有「消息保留天数」「单笔转账上限」这种改错就不可逆或
 * 直接漏钱的，所以草稿停在本地，只有按下「保存」才发一次 PUT。布尔项反过来——
 * 开关没有半个值的中间态，点上立即提交与界面自洽。
 *
 * 所有写动作都以服务端响应回填，绝不做乐观更新：一个全服旋钮在界面上显示错状态，
 * 比它响应慢危害大得多。失败时（请求层已弹过提示）回读一次，让屏幕回到事实上来。
 */

/** 敏感词开关虽然在目录里，但它由第二张卡专职负责，免得同一件事出现两个控件 */
const SENSITIVE_KEY = 'message.sensitive-filter-enabled'

const items = ref([])
const drafts = reactive({})
const filter = ref(null)
const enabled = ref(false)
const loading = ref(false)
const savingFilter = ref(false)
/** 正在写入的配置键：同一时刻只可能有一个在写，用它把其余行的控件一起禁掉 */
const busy = ref('')

const words = ref([])
const wordTotal = ref(0)
const wordLoading = ref(false)
const wordDeleting = ref('')
const wordQuery = reactive({ keyword: '', current: 1, size: 20 })

const addDialog = reactive({ visible: false, word: '', remark: '', saving: false })

const overriddenCount = computed(() => items.value.filter((item) => item.overridden).length)

/** 按服务端的分组码聚合，顺序就是枚举声明顺序，前端不再自己定一套排序 */
const groups = computed(() => {
  const list = []
  items.value
    .filter((item) => item.key !== SENSITIVE_KEY)
    .forEach((item) => {
      let group = list.find((entry) => entry.code === item.group)
      if (!group) {
        group = { code: item.group, label: item.groupLabel, items: [] }
        list.push(group)
      }
      group.items.push(item)
    })
  return list
})

const wordCountText = computed(() => {
  const count = filter.value?.wordCount
  return count === null || count === undefined ? '—' : `${count} 条`
})

/** 数字框的值与运行态不一致才算「待保存」；被清空（null）时按钮不出现，省得提交一个空值 */
function dirty(item) {
  const draft = drafts[item.key]
  return draft !== null && draft !== undefined && String(draft) !== item.value
}

/** 用服务端回来的那一项替换本地那一项，并把草稿对齐——不回齐就会留下一个「待保存」的假按钮 */
function applyItem(vo) {
  const index = items.value.findIndex((item) => item.key === vo.key)
  if (index >= 0) {
    items.value[index] = vo
  } else {
    items.value.push(vo)
  }
  drafts[vo.key] = vo.type === 'NUMBER' ? Number(vo.value) : null
}

async function loadItems() {
  const list = await fetchConfigItems()
  items.value = list || []
  items.value.forEach((item) => {
    drafts[item.key] = item.type === 'NUMBER' ? Number(item.value) : null
  })
}

async function loadFilter() {
  const vo = await fetchSensitiveFilter()
  if (vo) {
    filter.value = vo
    enabled.value = !!vo.enabled
  }
}

async function load() {
  loading.value = true
  // 三条链路各自吞错：表还没建时词库那一栏会失败，但它不该把已经拿到的目录清空
  await loadItems().catch(() => {})
  await loadFilter().catch(() => {})
  await loadWords().catch(() => {})
  loading.value = false
}

async function saveBoolean(item, value) {
  busy.value = item.key
  try {
    applyItem(await updateConfigItem(item.key, String(value)))
    ElMessage.success(`「${item.title}」已改为 ${value}（本节点立即生效）`)
  } catch {
    await reloadItemsQuietly()
  } finally {
    busy.value = ''
  }
}

async function saveNumber(item) {
  busy.value = item.key
  try {
    const vo = await updateConfigItem(item.key, String(drafts[item.key]))
    applyItem(vo)
    ElMessage.success(`「${item.title}」已改为 ${vo.value}（本节点立即生效）`)
  } catch {
    await reloadItemsQuietly()
  } finally {
    busy.value = ''
  }
}

async function reset(item) {
  try {
    await ElMessageBox.confirm(
      `「${item.title}」会删掉库里那一行、把运行态写回启动默认值 ${item.startupDefault}，此后这一项重新跟随配置文件。`,
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  busy.value = item.key
  try {
    applyItem(await resetConfigItem(item.key))
    ElMessage.success(`「${item.title}」已恢复启动默认值`)
  } catch {
    await reloadItemsQuietly()
  } finally {
    busy.value = ''
  }
}

/** 写失败后的回读：错误提示请求层已经弹过，这里只负责把屏幕拉回服务端的真实状态 */
async function reloadItemsQuietly() {
  try {
    await loadItems()
  } catch {
    // 连回读也失败就保持原状，至少不显示成「已经改成功了」那种假象
  }
}

/* ==================== 敏感词开关 ==================== */

async function onChangeFilter(value) {
  savingFilter.value = true
  try {
    const vo = await setSensitiveFilter(value)
    if (vo) {
      filter.value = vo
      enabled.value = !!vo.enabled
      ElMessage.success(vo.enabled ? '已开启敏感词过滤（全服生效，已落库）' : '已关闭敏感词过滤（全服生效，已落库）')
    }
    // 这一项同时也是目录里的一行，「已覆盖 N 项」那个数字得跟着动，否则两处屏幕互相矛盾
    await reloadItemsQuietly()
  } catch {
    await loadFilterQuietly()
  } finally {
    savingFilter.value = false
  }
}

async function resetFilter() {
  try {
    await ElMessageBox.confirm(
      '会删掉库里那一行，把开关写回本次进程启动那一刻的值，此后重启跟随配置文件。',
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  busy.value = SENSITIVE_KEY
  try {
    applyItem(await resetConfigItem(SENSITIVE_KEY))
    await loadFilter().catch(() => {})
    ElMessage.success('敏感词开关已恢复启动默认值')
  } catch {
    await reloadItemsQuietly()
  } finally {
    busy.value = ''
  }
}

async function loadFilterQuietly() {
  try {
    await loadFilter()
  } catch {
    // 同上：回读也失败就停在上一次已知的事实上
  }
}

/* ==================== 在线词库 ==================== */

async function loadWords() {
  wordLoading.value = true
  try {
    const page = await fetchSensitiveWordPage({
      keyword: wordQuery.keyword || undefined,
      current: wordQuery.current,
      size: wordQuery.size
    })
    words.value = page?.records || []
    wordTotal.value = Number(page?.total || 0)
  } catch {
    // 表还没建（后端先上、SQL 后跑）时这一栏是唯一可见的报错位，保留上一次的结果
  } finally {
    wordLoading.value = false
  }
}

function reloadWords() {
  wordQuery.current = 1
  loadWords()
}

function openAdd() {
  addDialog.visible = true
  addDialog.word = ''
  addDialog.remark = ''
}

async function submitAdd() {
  addDialog.saving = true
  try {
    const result = await addSensitiveWords(addDialog.word.trim(), addDialog.remark.trim() || undefined)
    if (result) {
      ElMessage({
        type: result.added > 0 ? 'success' : 'warning',
        message: `拆出 ${result.total} 个，入库 ${result.added} 个，重复 ${result.duplicated} 个，`
          + `不合法 ${result.invalid} 个；词库现共 ${result.effectiveCount} 条生效`
      })
      addDialog.visible = false
      await loadWords().catch(() => {})
      await loadFilter().catch(() => {})
    }
  } catch {
    // 提示由请求层给；弹框不关，让人改完再试一次
  } finally {
    addDialog.saving = false
  }
}

async function removeWord(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.word}」只撤掉后台加的那一个词，文件词库里的词不受影响。`,
      '删除词条',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  wordDeleting.value = String(row.id)
  try {
    await deleteSensitiveWord(row.id)
    ElMessage.success('已删除，本节点立即生效')
    await loadWords().catch(() => {})
    await loadFilter().catch(() => {})
  } catch {
    // 请求层已弹提示
  } finally {
    wordDeleting.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.hint {
  margin-bottom: 16px;
}

.block {
  margin-bottom: 16px;
}

.card-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.groups {
  min-height: 60px;
}

.group-title {
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: #606266;
  font-size: 13px;
  font-weight: 600;
  margin: 8px 0 0;
  padding-bottom: 6px;
}

.row {
  align-items: flex-start;
  border-bottom: 1px solid var(--el-border-color-lighter);
  display: flex;
  gap: 24px;
  justify-content: space-between;
  padding: 12px 0;
}

.row:last-child {
  border-bottom: none;
}

.label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.name {
  display: flex;
  gap: 8px;
}

.flag {
  transform: translateY(-1px);
}

.desc {
  font-size: 12px;
  line-height: 1.6;
  max-width: 720px;
}

.meta {
  font-size: 12px;
}

.control {
  align-items: center;
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.number {
  width: 130px;
}

.reset {
  margin-left: 0;
}

.value {
  flex-shrink: 0;
}

.persistence {
  font-size: 12px;
  max-width: 320px;
  text-align: right;
}

.inner-alert {
  margin-top: 12px;
}

.filters {
  align-items: center;
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.filters .grow {
  flex: 1;
}

.keyword {
  width: 260px;
}

.table {
  width: 100%;
}

.footnote {
  font-size: 12px;
  line-height: 1.7;
}

.code {
  font-family: Consolas, Monaco, monospace;
}

code {
  background: var(--el-fill-color-light);
  border-radius: 3px;
  padding: 0 4px;
}
</style>
