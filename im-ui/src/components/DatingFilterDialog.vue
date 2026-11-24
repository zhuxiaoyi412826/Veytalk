<template>
  <el-dialog
    v-model="visible"
    title="筛选条件"
    width="620px"
    top="7vh"
    :close-on-click-modal="false"
    append-to-body
    @open="onOpen"
  >
    <div v-loading="loading" class="dtf">
      <!-- 没保存过也回一组出厂默认值，但后端此时根本不拿它们筛人：不说清这一条，用户会以为已经生效了 -->
      <el-alert
        v-if="!saved"
        class="dtf__notice"
        type="info"
        :closable="false"
        show-icon
        title="下面这些是出厂默认值，你还没保存过"
        description="保存之前，卡池与附近的人都按「无筛选」出人在跑；年龄 18~45 这两格只是预先填好的表单。"
      />

      <div class="dtf__group">
        <div class="dtf__label">对方性别</div>
        <el-radio-group v-model="form.targetGender">
          <el-radio-button v-for="opt in GENDER_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </el-radio-button>
        </el-radio-group>
      </div>

      <div class="dtf__group">
        <div class="dtf__label">
          年龄区间
          <span class="dtf__hint">下限不低于 18 岁：未成年人不进交友池</span>
        </div>
        <div class="dtf__pair">
          <el-input-number v-model="form.ageMin" :min="18" :max="80" :step="1" controls-position="right" />
          <span class="dtf__dash">至</span>
          <el-input-number v-model="form.ageMax" :min="18" :max="80" :step="1" controls-position="right" />
          <span class="dtf__unit">岁</span>
        </div>
      </div>

      <div class="dtf__group">
        <div class="dtf__label">
          身高区间
          <span class="dtf__hint">填 0 表示这一头不限</span>
        </div>
        <div class="dtf__pair">
          <el-input-number v-model="form.heightMin" :min="0" :max="250" :step="1" controls-position="right" />
          <span class="dtf__dash">至</span>
          <el-input-number v-model="form.heightMax" :min="0" :max="250" :step="1" controls-position="right" />
          <span class="dtf__unit">cm</span>
        </div>
        <!-- 说清「没填身高的人会被排除」：否则卡池突然变少，用户只会以为附近没人 -->
        <div class="dtf__warn">一旦填了身高区间，<b>没填身高的候选人会被一并排除</b>（学历、婚恋状况同理）。</div>
      </div>

      <div class="dtf__row">
        <div class="dtf__group dtf__group--half">
          <div class="dtf__label">最低学历</div>
          <el-select v-model="form.targetEducation" class="dtf__fill">
            <el-option label="不限" :value="0" />
            <el-option v-for="item in dict.educations" :key="item.code" :label="item.desc" :value="item.code" />
          </el-select>
        </div>
        <div class="dtf__group dtf__group--half">
          <div class="dtf__label">婚恋状况</div>
          <el-select v-model="form.targetMarriage" class="dtf__fill">
            <el-option label="不限" :value="0" />
            <el-option v-for="item in dict.marriages" :key="item.code" :label="item.desc" :value="item.code" />
          </el-select>
        </div>
      </div>

      <div class="dtf__row">
        <div class="dtf__group dtf__group--half">
          <div class="dtf__label">
            只看城市
            <span class="dtf__hint">包含匹配，「杭州」也能命中「杭州市」</span>
          </div>
          <el-input
            v-model="form.targetCity"
            maxlength="32"
            clearable
            placeholder="留空为不限"
            class="dtf__fill"
          />
        </div>
        <div class="dtf__group dtf__group--half">
          <div class="dtf__label">
            距离上限
            <span class="dtf__hint">只作用于「附近的人」</span>
          </div>
          <el-select v-model="form.distanceKm" class="dtf__fill">
            <el-option v-for="opt in DISTANCE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </div>
      </div>

      <!-- 排序档来自字典而不是这里写死两项：口径背后是 im-common 的 DatingSort 枚举，
           以后多一档（比如「新鲜优先」）只改后端，前端不会“能存但不能选” -->
      <div v-if="dict.sorts.length" class="dtf__group">
        <div class="dtf__label">默认排序口径</div>
        <el-radio-group v-model="form.sortBy">
          <el-radio-button v-for="opt in dict.sorts" :key="opt.code" :value="opt.code">
            {{ opt.desc }}
          </el-radio-button>
        </el-radio-group>
        <div class="dtf__hintline">这里选的是以后的默认口径；卡池那一栏可以临时换一次看法，不会写回来。「附近的人」永远按远近排。</div>
      </div>

      <div class="dtf__switches">
        <div class="dtf__switch">
          <div class="dtf__switch-text">
            <span>在线优先</span>
            <small>把当前在线的人排到前面，其余顺序不变（不影响谁能出现）</small>
          </div>
          <el-switch :model-value="form.onlineFirst === 1" @update:model-value="toggleOnlineFirst" />
        </div>
        <div class="dtf__switch">
          <div class="dtf__switch-text">
            <span>排除已跳过的人</span>
            <small>关掉之后，只滑过「跳过」的人会重新回到卡池；喜欢过的人仍不会回来</small>
          </div>
          <el-switch :model-value="form.excludePassed === 1" @update:model-value="toggleExcludePassed" />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="dtf__footer">
        <el-button link @click="resetForm">恢复默认</el-button>
        <div class="dtf__footer-right">
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDatingDict, fetchPreference, savePreference } from '@/api/dating'

/**
 * 交友筛选偏好弹层。
 *
 * 字典复用 fetchDatingDict 而不是在这里写死一份：学历与婚恋状况的 code 同时存在于数据库注释、
 * Java 枚举和前端常量里，抄第三份的那一刻就开始漂移（「学历 4」后端是本科、前端显示成大专）。
 * 字典只在模块级缓存一次，弹层反复打开不该反复请求。
 *
 * 提交是**整组替换**，所以 form 里的每一项都必须有值（不限用 0 / 空串表达），
 * 不能靠「不传这个字段」来表示不限——那样会把上一次的筛选永远留在身上。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'applied'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const GENDER_OPTIONS = [
  { value: 0, label: '不限' },
  { value: 1, label: '男生' },
  { value: 2, label: '女生' }
]

/** 与后端 distanceKm 的 [0, 20] 一致；再远就不是「附近」，那一档归城市筛选 */
const DISTANCE_OPTIONS = [
  { value: 0, label: '不限' },
  { value: 1, label: '1 公里内' },
  { value: 3, label: '3 公里内' },
  { value: 5, label: '5 公里内' },
  { value: 10, label: '10 公里内' },
  { value: 20, label: '20 公里内' }
]

/** 出厂默认，「恢复默认」按它填；与后端 MatchPreference 的那几个常量一一对应 */
const DEFAULT_FORM = {
  targetGender: 0,
  ageMin: 18,
  ageMax: 45,
  distanceKm: 0,
  targetCity: '',
  heightMin: 0,
  heightMax: 0,
  targetEducation: 0,
  targetMarriage: 0,
  onlineFirst: 0,
  sortBy: 'recommend',
  excludePassed: 1
}

const dict = reactive({ educations: [], marriages: [], sorts: [] })
/** 字典只拉一次：这一张表是 Java 枚举算出来的定长数据，反复打开弹层没必要反复请求 */
let dictLoaded = false

const form = reactive({ ...DEFAULT_FORM })
const loading = ref(false)
const saving = ref(false)
const saved = ref(true)

async function onOpen() {
  loading.value = true
  try {
    await loadDict()
    const res = await fetchPreference()
    applyVo(res)
  } catch {
    // 请求层已弹提示；表单保持上一次的值，不至于空白一片
  } finally {
    loading.value = false
  }
}

async function loadDict() {
  if (dictLoaded) {
    return
  }
  const data = await fetchDatingDict()
  dict.educations = (data && data.educations) || []
  dict.marriages = (data && data.marriages) || []
  dict.sorts = (data && data.sorts) || []
  dictLoaded = true
}

function applyVo(vo) {
  if (!vo) {
    return
  }
  saved.value = vo.saved !== false
  Object.keys(DEFAULT_FORM).forEach((key) => {
    // 只有后端明确给了值才覆盖：少一个字段就按默认，而不是渲出一个 undefined 的控件
    form[key] = vo[key] === null || vo[key] === undefined ? DEFAULT_FORM[key] : vo[key]
  })
}

function resetForm() {
  Object.assign(form, DEFAULT_FORM)
  // 出厂默认在「还没保存过」与「刚点了恢复默认」两种情况下含义不同，这里统一成前者：
  // 用户没点保存就什么都还没生效，提示语不该说谎
  saved.value = false
}

function toggleOnlineFirst(val) {
  form.onlineFirst = val ? 1 : 0
}

function toggleExcludePassed(val) {
  form.excludePassed = val ? 1 : 0
}

/**
 * 本地只拦「区间反了」这一种：它是两个控件之间的事，后端也确实是这么报错的，
 * 提前一步就少一次来回。其余越界一律交给后端判，这里<em>不夹紧也不改值</em>——
 * 静默改过的筛选条件，用户下次打开弹层看到的会是自己没填过的数字。
 */
function checkRange() {
  if (form.ageMin > form.ageMax) {
    ElMessage.warning('年龄上限不能小于下限')
    return false
  }
  if (form.heightMin > 0 && form.heightMax > 0 && form.heightMin > form.heightMax) {
    ElMessage.warning('身高上限不能小于下限')
    return false
  }
  return true
}

async function onSave() {
  if (saving.value || !checkRange()) {
    return
  }
  saving.value = true
  try {
    // 整组替换：把 form 全量 PUT 回去，字段名与后端 PreferenceRequest 完全同名
    const vo = await savePreference({ ...form, targetCity: (form.targetCity || '').trim() })
    applyVo(vo)
    ElMessage.success('筛选条件已保存')
    emit('applied', vo)
    visible.value = false
  } catch (err) {
    // 后端的 message 里带的是具体哪一条不合格（「城市名至少 2 个字」），原样转达比自己兜一层有用
    ElMessage.error((err && err.message) || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.dtf {
  max-height: 66vh;
  overflow-y: auto;
}

.dtf__notice {
  margin-bottom: 12px;
}

.dtf__group {
  margin-bottom: 14px;
  min-width: 0;
}

.dtf__group--half {
  flex: 1;
  min-width: 0;
  margin-bottom: 0;
}

.dtf__row {
  display: flex;
  gap: 16px;
  margin-bottom: 14px;
}

.dtf__label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.dtf__hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--im-text-secondary, #909399);
}

.dtf__hintline {
  margin-top: 4px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.dtf__pair {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dtf__dash,
.dtf__unit {
  font-size: 13px;
  color: var(--im-text-secondary, #909399);
}

.dtf__warn {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #d4380d;
}

.dtf__fill {
  width: 100%;
}

.dtf__switches {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 4px;
  border-top: 1px solid var(--im-border-light, #f0f0f0);
}

.dtf__switch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.dtf__switch-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  font-size: 13px;
  color: var(--im-text, #303133);
}

.dtf__switch-text small {
  font-size: 12px;
  line-height: 1.5;
  color: var(--im-text-secondary, #909399);
}

.dtf__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dtf__footer-right {
  display: flex;
  gap: 8px;
}
</style>
