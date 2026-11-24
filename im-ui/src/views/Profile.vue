<template>
  <div class="profile im-scroll">
    <div class="profile__inner">
      <!-- ==================== 头像与基本身份 ==================== -->
      <section class="profile__card">
        <div class="profile__avatar-row">
          <div class="profile__avatar" title="点击更换头像" @click="pickAvatar">
            <UserAvatar :src="auth.avatarRaw" :name="auth.nickname" :size="88" />
            <div class="profile__avatar-mask">
              <el-icon v-if="avatarUploading" class="is-loading"><Loading /></el-icon>
              <el-icon v-else><Camera /></el-icon>
              <span>更换</span>
            </div>
          </div>
          <input ref="avatarInputRef" type="file" accept="image/*" class="profile__file-input" @change="onAvatarPicked" />

          <div class="profile__identity">
            <div class="profile__nickname">{{ auth.nickname || '未设置昵称' }}</div>
            <div class="profile__account">@{{ auth.username }}</div>
            <div class="profile__meta">
              <el-tag v-for="role in auth.roles" :key="role" size="small" effect="plain">{{ role }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ auth.permissions.length }} 项权限</el-tag>
            </div>
          </div>
        </div>

        <el-descriptions :column="2" border size="small" class="profile__facts">
          <el-descriptions-item label="用户 ID">{{ auth.userId }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ maskPhone(auth.phone) || '未绑定' }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ formatDateTime(userInfo.createTime) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="上次登录">{{ formatDateTime(userInfo.lastLoginTime) || '—' }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <!-- ==================== 资料编辑 ==================== -->
      <section class="profile__card">
        <div class="profile__card-title">编辑资料</div>
        <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" class="profile__form">
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model.trim="form.nickname" maxlength="32" show-word-limit placeholder="留空将回退为账号名" />
          </el-form-item>
          <el-form-item label="性别" prop="gender">
            <el-radio-group v-model="form.gender">
              <el-radio :value="0">保密</el-radio>
              <el-radio :value="1">男</el-radio>
              <el-radio :value="2">女</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="签名" prop="signature">
            <el-input
              v-model.trim="form.signature"
              type="textarea"
              :rows="2"
              maxlength="255"
              show-word-limit
              placeholder="写一句个性签名"
            />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model.trim="form.email" maxlength="64" clearable placeholder="选填" />
          </el-form-item>
          <!--
            手机号不能直接改：UpdateProfileRequest 里没有 phone 字段，
            换绑手机要走短信验证流程，放进这个表单会造成「改了但没生效」的假象。
            绑定 / 换绑都通过右侧按钮弹出的短信验证对话框完成（无需图形验证码）。
          -->
          <el-form-item label="手机号">
            <span class="profile__readonly">{{ maskPhone(auth.phone) || '未绑定' }}</span>
            <el-button class="profile__phone-btn" link type="primary" @click="openPhoneDialog">
              {{ auth.phone ? '换绑' : '绑定' }}
            </el-button>
          </el-form-item>
          <el-form-item label="账号">
            <span class="profile__readonly">{{ auth.username }}</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" :disabled="!dirty" @click="submit">保存修改</el-button>
            <el-button :disabled="!dirty" @click="resetForm">撤销</el-button>
          </el-form-item>
        </el-form>
      </section>

      <!-- ==================== 交友资料 ==================== -->
      <section class="profile__card">
        <div class="profile__head">
          <div class="profile__card-title profile__card-title--flat">交友资料</div>
          <span class="profile__score">完资料度 {{ dating.profileScore }}%</span>
        </div>
        <el-progress :percentage="dating.profileScore" :stroke-width="8" :show-text="false" class="profile__progress" />
        <div v-if="dating.missingTips.length" class="profile__tips">
          <el-tag v-for="tip in dating.missingTips" :key="tip" size="small" type="warning" effect="plain">{{ tip }}</el-tag>
        </div>

        <el-form :model="datingForm" label-width="88px" class="profile__form">
          <el-form-item label="出生日期">
            <el-date-picker
              v-model="datingForm.birthday"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="年龄按这一天现算"
              :disabled-date="disableFutureDate"
            />
          </el-form-item>
          <el-form-item label="身高">
            <el-input-number v-model="datingForm.heightCm" :min="100" :max="250" :step="1" controls-position="right" />
          </el-form-item>
          <el-form-item label="学历">
            <el-select v-model="datingForm.education" placeholder="选择学历" clearable>
              <el-option v-for="item in dict.educations" :key="item.code" :label="item.desc" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="职业">
            <el-input v-model.trim="datingForm.occupation" maxlength="32" show-word-limit placeholder="如：视觉设计师" />
          </el-form-item>
          <el-form-item label="收入区间">
            <el-select v-model="datingForm.income" placeholder="选择收入区间" clearable>
              <el-option v-for="item in dict.incomes" :key="item.code" :label="item.desc" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="婚恋状况">
            <el-select v-model="datingForm.marriage" placeholder="选择婚恋状况" clearable>
              <el-option v-for="item in dict.marriages" :key="item.code" :label="item.desc" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="交友目的">
            <el-select v-model="datingForm.datingGoal" placeholder="选择交友目的" clearable>
              <el-option v-for="item in dict.datingGoals" :key="item.code" :label="item.desc" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="所在地区">
            <div class="profile__region">
              <el-input v-model.trim="datingForm.province" maxlength="32" placeholder="省" />
              <el-input v-model.trim="datingForm.city" maxlength="32" placeholder="市（同城筛选按它）" />
              <el-input v-model.trim="datingForm.district" maxlength="32" placeholder="区" />
            </div>
          </el-form-item>
          <!--
            坐标只由这里采集、geohash 一律服务端重算：前端如果也算一份，两边迟早对不上，
            对不上的表现是“本人永远刷不到自己附近的人”。
          -->
          <el-form-item label="我的定位">
            <div class="profile__loc-row">
              <el-button :loading="locating" @click="locateMe">重新定位</el-button>
              <el-button v-if="dating.hasLocation && !clearLocation" link type="danger" @click="clearLocation = true">
                清除定位
              </el-button>
              <span class="profile__readonly">{{ locationSummary }}</span>
            </div>
            <div v-if="locatingError" class="profile__hint profile__hint--warn">{{ locatingError }}</div>
            <div v-else-if="locationForm.lng !== null" class="profile__hint">
              本次将上报新坐标：{{ locationForm.lng }}, {{ locationForm.lat }}
            </div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="datingSaving" :disabled="!datingDirty" @click="submitDating">保存交友资料</el-button>
            <el-button :disabled="!datingDirty" @click="resetDatingForm">撤销</el-button>
          </el-form-item>
        </el-form>

        <!-- ==================== 照片墙 ==================== -->
        <div class="profile__head">
          <div class="profile__card-title profile__card-title--flat">照片墙</div>
          <span class="profile__score">{{ photos.length }} / {{ PHOTO_MAX }} 张</span>
        </div>
        <div class="profile__photos">
          <div v-for="(item, index) in photos" :key="item.id" class="profile__photo">
            <!-- mediaUrl 必须在模板里调：受控地址要带登录头取成 blob，首帧为空串、取回后响应式补上 -->
            <img class="profile__photo-img" :src="mediaUrl(item.url)" alt="" />
            <div v-if="item.isMain === 1" class="profile__photo-badge profile__photo-badge--main">封面</div>
            <div v-else-if="item.auditStatus === 0" class="profile__photo-badge">审核中</div>
            <div v-else-if="item.auditStatus === 2" class="profile__photo-badge profile__photo-badge--reject">
              {{ item.rejectReason || '未通过' }}
            </div>
            <div class="profile__photo-ops">
              <el-button v-if="index > 0" link type="primary" @click="movePhoto(index, -1)">前移</el-button>
              <el-button v-if="index < photos.length - 1" link type="primary" @click="movePhoto(index, 1)">后移</el-button>
              <el-button v-if="item.isMain !== 1" link type="primary" @click="makeMain(item)">设为封面</el-button>
              <el-button link type="danger" @click="removePhoto(item)">删除</el-button>
            </div>
          </div>
          <div
            v-if="photos.length < PHOTO_MAX"
            class="profile__photo profile__photo--add"
            title="上传一张生活照"
            @click="pickPhoto"
          >
            <el-icon v-if="photoUploading" class="is-loading"><Loading /></el-icon>
            <el-icon v-else><Plus /></el-icon>
          </div>
        </div>
        <input ref="photoInputRef" type="file" accept="image/*" class="profile__file-input" @change="onPhotoPicked" />
        <div class="profile__hint">
          第一张为封面，只有审核通过的照片会出现在别人的资料卡上；它计入完资料度（最多 30 分）。
        </div>

        <!-- ==================== 兴趣标签 ==================== -->
        <div class="profile__head">
          <div class="profile__card-title profile__card-title--flat">兴趣标签</div>
          <span class="profile__score">{{ tagDraft.length }} / {{ TAG_MAX }} 个</span>
        </div>
        <div v-if="tagDraft.length" class="profile__tags profile__tags--picked">
          <div v-for="(item, index) in tagDraft" :key="item.tagId" class="profile__tag profile__tag--picked">
            <span>{{ item.name }}</span>
            <span v-if="item.disabled" class="profile__tag-off">已下架</span>
            <el-button v-if="index > 0" link type="primary" @click="moveTag(index, -1)">前移</el-button>
            <el-button v-if="index < tagDraft.length - 1" link type="primary" @click="moveTag(index, 1)">后移</el-button>
            <el-button link type="danger" @click="dropTag(index)">取消</el-button>
          </div>
        </div>
        <div v-else class="profile__hint">一个还没选：同好推荐与破冰开场白都按它匹配。</div>
        <div v-for="group in tagLibrary" :key="group.category" class="profile__tag-group">
          <div class="profile__tag-category">{{ group.category }}</div>
          <div class="profile__tags">
            <span
              v-for="item in group.tags"
              :key="item.tagId"
              class="profile__tag"
              :class="{ 'profile__tag--on': isTagPicked(item.tagId) }"
              @click="toggleTag(item)"
            >{{ item.name }}</span>
          </div>
        </div>
        <div class="profile__tag-actions">
          <el-button type="primary" :loading="tagSaving" :disabled="!tagDirty" @click="submitTags">保存兴趣标签</el-button>
          <el-button :disabled="!tagDirty" @click="resetTagDraft">撑销</el-button>
        </div>

        <div class="profile__head">
          <div class="profile__card-title profile__card-title--flat">谁能看到我</div>
        </div>
        <div class="profile__privacy">
          <div v-for="item in privacyItems" :key="item.key" class="profile__privacy-item">
            <div>
              <div class="profile__privacy-name">{{ item.label }}</div>
              <div class="profile__hint">{{ item.desc }}</div>
            </div>
            <el-switch
              v-model="privacy[item.key]"
              :active-value="1"
              :inactive-value="0"
              @change="(val) => savePrivacyFlag(item.key, val)"
            />
          </div>
          <div class="profile__privacy-item">
            <div>
              <div class="profile__privacy-name">动态可见范围</div>
              <div class="profile__hint">作用于「我的动态」；动态功能上线前不会影响任何东西</div>
            </div>
            <el-select
              v-model="privacy.momentVisibleDays"
              class="profile__privacy-select"
              @change="(val) => savePrivacyFlag('momentVisibleDays', val)"
            >
              <el-option v-for="opt in momentVisibleOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </div>
        </div>
      </section>

      <!-- ==================== 账号安全 ==================== -->
      <section class="profile__card">
        <div class="profile__card-title">账号安全</div>
        <div v-if="firstTimeSet" class="profile__hint profile__hint--warn">
          当前账号通过验证码登录自动创建，尚未设置密码，设置后即可用「账号登录」。
        </div>
        <div class="profile__actions">
          <el-button :icon="Lock" @click="openPasswordDialog">{{ firstTimeSet ? '设置密码' : '修改密码' }}</el-button>
          <el-button type="danger" plain :icon="SwitchButton" @click="onLogout">退出登录</el-button>
          <el-button type="danger" @click="onCancelAccount">注销账号</el-button>
        </div>
      </section>
    </div>

    <!-- ==================== 修改 / 首次设置密码 ==================== -->
    <el-dialog
      v-model="passwordDialog.visible"
      :title="firstTimeSet ? '设置密码' : '修改密码'"
      width="420px"
      @closed="resetPasswordForm"
    >
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="90px">
        <el-form-item v-if="!firstTimeSet" label="原密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="6-32 位" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
            placeholder="再输入一次"
            @keyup.enter="submitPassword"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="passwordForm.logoutAll">{{ firstTimeSet ? '设置后退出所有设备' : '修改后退出所有设备（包括当前）' }}</el-checkbox>
          <div class="profile__hint">
            {{ firstTimeSet
              ? '首次设置密码默认保留当前登录态，勾选后才会踢掉所有设备。'
              : '后端默认就是这个行为：不勾选才会保留当前登录态。改了密码还留着旧设备的登录，等于没改。' }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="passwordDialog.saving" @click="submitPassword">{{ firstTimeSet ? '确定设置' : '确定修改' }}</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 绑定 / 换绑手机号 ==================== -->
    <el-dialog
      v-model="phoneDialog.visible"
      :title="auth.phone ? '换绑手机号' : '绑定手机号'"
      width="420px"
      @closed="resetPhoneForm"
    >
      <el-form ref="phoneFormRef" :model="phoneForm" :rules="phoneRules" label-width="90px">
        <el-form-item label="手机号" prop="phone">
          <el-input v-model.trim="phoneForm.phone" maxlength="11" placeholder="请输入要绑定的手机号" clearable />
        </el-form-item>
        <el-form-item label="验证码" prop="smsCode">
          <div class="profile__sms-row">
            <el-input
              v-model.trim="phoneForm.smsCode"
              maxlength="6"
              placeholder="6 位短信验证码"
              @keyup.enter="submitPhone"
            />
            <el-button
              :disabled="smsCountdown > 0 || smsSending"
              :loading="smsSending"
              @click="sendBindSms"
            >
              {{ smsCountdown > 0 ? `${smsCountdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <div v-if="smsDebugCode" class="profile__hint profile__hint--warn">
        开发环境短信验证码：{{ smsDebugCode }}
      </div>
      <template #footer>
        <el-button @click="phoneDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="phoneDialog.saving" @click="submitPhone">确定绑定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Camera, Loading, Lock, Plus, SwitchButton } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { uploadAvatar, uploadFileSmart } from '@/api/file'
import { cancelAccount } from '@/api/user'
import {
  addPhoto, deletePhoto, fetchDatingDict, fetchDatingProfile, fetchPhotos, fetchTagLibrary, fetchTags,
  saveDatingPrivacy, saveDatingProfile, saveTags, setMainPhoto, sortPhotoOrder
} from '@/api/dating'
import { guardImage } from '@/utils/nsfw'
import { mediaUrl, readImageSize } from '@/utils/media'
import { sendSmsCode } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { signOut } from '@/stores'
import { formatFileSize, formatDateTime, maskPhone } from '@/utils/format'
import { openFilePicker } from '@/utils/picker'

defineOptions({ name: 'Profile' })

const router = useRouter()
const auth = useAuthStore()

/** 与 im.file.max-avatar-size 一致，头像走的是独立的更小限额 */
const MAX_AVATAR_BYTES = 2 * 1024 * 1024

const userInfo = computed(() => auth.userInfo || {})

/* ------------------------------ 资料编辑 ------------------------------ */

const formRef = ref(null)
const saving = ref(false)
const form = reactive({ nickname: '', gender: 0, signature: '', email: '' })

/** 上次成功保存的快照，用来判断「有没有改动」，没改就把保存按钮置灰 */
const snapshot = ref('')

function fillForm() {
  form.nickname = auth.userInfo?.nickname || ''
  form.gender = Number(auth.userInfo?.gender ?? 0)
  form.signature = auth.userInfo?.signature || ''
  form.email = auth.userInfo?.email || ''
  snapshot.value = JSON.stringify(form)
}

const dirty = computed(() => JSON.stringify(form) !== snapshot.value)

const rules = {
  nickname: [{ max: 32, message: '昵称最多 32 个字符', trigger: 'blur' }],
  gender: [{ type: 'number', min: 0, max: 2, message: '性别取值只能为 0/1/2', trigger: 'change' }],
  signature: [{ max: 255, message: '签名最多 255 个字符', trigger: 'blur' }],
  email: [
    { max: 64, message: '邮箱最多 64 个字符', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ]
}

function resetForm() {
  fillForm()
  formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    // 只发后端 UpdateProfileRequest 认得的字段：多传 phone 之类的会被静默忽略，
    // 但会让读代码的人误以为这里能改手机号
    await auth.updateProfile({
      nickname: form.nickname,
      gender: form.gender,
      signature: form.signature,
      email: form.email
    })
    fillForm()
    ElMessage.success('资料已更新')
  } catch {
    // 提示已由 request.js 弹出
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 交友资料 ------------------------------ */

/**
 * 交友资料与上面的「编辑资料」分两次保存：两组字段的校验规则和限流配额都不同，
 * 合成一张表单会让「只改昵称」也把生日、坐标一起提交上去，任一交友字段没过就整次被拒。
 */
const datingSaving = ref(false)
const dating = reactive({ profileScore: 0, missingTips: [], hasLocation: false, locationFresh: true, locationText: '' })
const dict = reactive({ educations: [], incomes: [], marriages: [], datingGoals: [] })

/**
 * 上次保存成功的快照。
 *
 * 后端「null 字段不修改」，所以不能把整张表单原样 PUT 回去：那样一打开页面什么也没动，
 * 就会把所有字段按当前界面上的值重写一遍，包括那些界面上根本展示不出来的列。
 */
const datingOriginal = reactive({
  birthday: '', heightCm: null, education: null, occupation: '', income: null,
  marriage: null, datingGoal: null, province: '', city: '', district: ''
})
const datingForm = reactive({
  birthday: '', heightCm: null, education: null, occupation: '', income: null,
  marriage: null, datingGoal: null, province: '', city: '', district: ''
})

/** 本次要上报的新坐标：null 表示没动定位，与「库里已有的定位」是两件事（VO 不回传经纬度，拿不到旧值） */
const locationForm = reactive({ lng: null, lat: null })
const clearLocation = ref(false)
const locating = ref(false)
const locatingError = ref('')

const privacy = reactive({ showAge: 1, showDistance: 1, showOnline: 1, discoverable: 1, momentVisibleDays: 0 })
/** 已落库的开关值，仅用于写失败时把开关拨回去 */
const privacySaved = reactive({ showAge: 1, showDistance: 1, showOnline: 1, discoverable: 1, momentVisibleDays: 0 })

const privacyItems = [
  { key: 'showAge', label: '显示年龄', desc: '关掉后资料卡上整行留空，不是显示 0 岁' },
  { key: 'showDistance', label: '显示距离', desc: '关掉后仍参与「附近的人」排序，只是不显公里数' },
  { key: 'showOnline', label: '对陌生人显示在线', desc: '只拦陌生人，好友不受这个开关影响' },
  { key: 'discoverable', label: '出现在推荐与附近', desc: '关掉即隐身：不再被陌生人刷到，你去看别人也不会留下访客记录；已有好友关系不受影响' }
]

const momentVisibleOptions = [
  { value: 0, label: '全部可见' },
  { value: 3, label: '三天可见' },
  { value: 7, label: '一周可见' },
  { value: 30, label: '一个月可见' }
]

const datingDirty = computed(() =>
  JSON.stringify(datingForm) !== JSON.stringify(datingOriginal)
  || clearLocation.value
  || locationForm.lng !== null
  || locationForm.lat !== null
)

const locationSummary = computed(() => {
  if (clearLocation.value) {
    return '保存后将清除定位'
  }
  if (!dating.hasLocation) {
    return '还没采集过定位，不影响城市筛选'
  }
  if (!dating.locationFresh) {
    return '定位已超过 7 天，建议重新采集'
  }
  return dating.locationText ? `已定位：${dating.locationText}` : '已定位'
})

/** 后端 @Past 会拒未来的出生日期，选择器里直接挡住 */
function disableFutureDate(date) {
  return date.getTime() >= Date.now()
}

function fillDating(vo) {
  if (!vo) {
    return
  }
  dating.profileScore = Number(vo.profileScore ?? 0)
  dating.missingTips = vo.missingTips || []
  dating.hasLocation = vo.hasLocation === true
  dating.locationFresh = vo.locationFresh !== false
  dating.locationText = vo.locationText || ''
  datingForm.birthday = vo.birthday || ''
  datingForm.heightCm = vo.heightCm ?? null
  // 0 就是「未填」，下拉框留空比多一个「未填」选项更像用户自己没选
  datingForm.education = vo.education ? vo.education : null
  datingForm.occupation = vo.occupation || ''
  datingForm.income = vo.income ? vo.income : null
  datingForm.marriage = vo.marriage ? vo.marriage : null
  datingForm.datingGoal = vo.datingGoal ? vo.datingGoal : null
  datingForm.province = vo.province || ''
  datingForm.city = vo.city || ''
  datingForm.district = vo.district || ''
  Object.assign(datingOriginal, datingForm)
  locationForm.lng = null
  locationForm.lat = null
  clearLocation.value = false
  locatingError.value = ''
  privacy.showAge = Number(vo.showAge ?? 1)
  privacy.showDistance = Number(vo.showDistance ?? 1)
  privacy.showOnline = Number(vo.showOnline ?? 1)
  privacy.discoverable = Number(vo.discoverable ?? 1)
  privacy.momentVisibleDays = Number(vo.momentVisibleDays ?? 0)
  Object.assign(privacySaved, privacy)
}

function resetDatingForm() {
  Object.assign(datingForm, datingOriginal)
  locationForm.lng = null
  locationForm.lat = null
  clearLocation.value = false
  locatingError.value = ''
}

/** 定位失败只在原地提示、不弹全局错误：不授权定位也能只用城市列做同城筛选 */
function locateMe() {
  locatingError.value = ''
  if (!navigator.geolocation) {
    locatingError.value = '当前浏览器不支持定位，可先只填城市'
    return
  }
  locating.value = true
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      locationForm.lng = Number(pos.coords.longitude.toFixed(6))
      locationForm.lat = Number(pos.coords.latitude.toFixed(6))
      locating.value = false
    },
    (err) => {
      locating.value = false
      locatingError.value = err && err.code === 1
        ? '定位权限被拒，可在浏览器地址栏里重新允许'
        : '定位失败，请稍后重试或直接填写城市'
    },
    { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 }
  )
}

/**
 * 只把动过的字段发出去；返回 {@code null} 表示无需提交（没改动或坐标只填了一半）。
 *
 * <p>清空分两种：日期/身高走 clears（后端只能靠它区分「不改」和「改成空」），
 * 枚举传 0、文本传空串（后端自己就会归成未填），所以 clears 只用于前者。
 */
function buildDatingPayload() {
  const payload = {}
  const clears = []
  const text = (val) => String(val ?? '').trim()
  const num = (val) => (val === null || val === undefined || val === '' ? null : Number(val))

  if (text(datingForm.birthday) !== text(datingOriginal.birthday)) {
    if (datingForm.birthday) {
      payload.birthday = datingForm.birthday
    } else {
      clears.push('birthday')
    }
  }
  if (num(datingForm.heightCm) !== num(datingOriginal.heightCm)) {
    const next = num(datingForm.heightCm)
    if (next === null) {
      clears.push('heightCm')
    } else {
      payload.heightCm = next
    }
  }
  for (const key of ['education', 'income', 'marriage', 'datingGoal']) {
    const next = num(datingForm[key]) ?? 0
    const prev = num(datingOriginal[key]) ?? 0
    if (next !== prev) {
      payload[key] = next
    }
  }
  for (const key of ['occupation', 'province', 'city', 'district']) {
    if (text(datingForm[key]) !== text(datingOriginal[key])) {
      payload[key] = text(datingForm[key])
    }
  }
  if (clearLocation.value) {
    clears.push('location')
  } else if (locationForm.lng !== null || locationForm.lat !== null) {
    if (locationForm.lng === null || locationForm.lat === null) {
      locatingError.value = '经纬度必须成对上报，请点「重新定位」重新采集'
      return null
    }
    payload.lng = locationForm.lng
    payload.lat = locationForm.lat
  }
  if (clears.length) {
    payload.clears = clears
  }
  return Object.keys(payload).length ? payload : null
}

async function submitDating() {
  const payload = buildDatingPayload()
  if (!payload) {
    if (!locatingError.value) {
      ElMessage.info('没有需要修改的字段')
    }
    return
  }
  datingSaving.value = true
  try {
    fillDating(await saveDatingProfile(payload))
    ElMessage.success('交友资料已更新')
  } catch {
    // 提示已由 request.js 弹出
  } finally {
    datingSaving.value = false
  }
}

/**
 * 隐私开关一项一改、改完即存：这一组是「临出门把隐身打开」的高频操作，
 * 排在整张表单末尾等用户点保存，就会变成“拨了开关其实没生效”。
 *
 * <p>写失败必须把开关拨回去：后端没写进去，界面留着打开状态就是骗人。
 */
async function savePrivacyFlag(key, value) {
  try {
    const vo = await saveDatingPrivacy({ [key]: value })
    privacySaved[key] = value
    if (vo) {
      dating.profileScore = Number(vo.profileScore ?? dating.profileScore)
      dating.missingTips = vo.missingTips || dating.missingTips
    }
  } catch {
    privacy[key] = privacySaved[key]
  }
}

async function loadDating() {
  try {
    const [dictData, vo, photoList, tagGroups, myTagList] = await Promise.all([
      fetchDatingDict(), fetchDatingProfile(), fetchPhotos(), fetchTagLibrary(), fetchTags()
    ])
    if (dictData) {
      dict.educations = dictData.educations || []
      dict.incomes = dictData.incomes || []
      dict.marriages = dictData.marriages || []
      dict.datingGoals = dictData.datingGoals || []
    }
    photos.value = photoList || []
    tagLibrary.value = tagGroups || []
    fillTags(myTagList)
    fillDating(vo)
  } catch {
    // 交友资料只是本页的一块，加载失败不影响上面的基本资料
  }
}

/* ------------------------------ 照片墙 ------------------------------ */

/** 与后端 UserPhoto.MAX_COUNT 一致；超上限由服务端拒，这里只是把加号收起 */
const PHOTO_MAX = 9

const photoInputRef = ref(null)
const photos = ref([])
const photoUploading = ref(false)

function pickPhoto() {
  if (photoUploading.value || photos.value.length >= PHOTO_MAX) {
    return
  }
  openFilePicker(photoInputRef.value)
}

async function onPhotoPicked(event) {
  const input = event.target
  const file = input.files && input.files[0]
  // 连选同一个文件时 value 没变不会触发 change，必须清掉
  input.value = ''
  if (!file) {
    return
  }
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    return
  }
  photoUploading.value = true
  try {
    const guard = await guardImage(file, { scene: 'dating_photo' })
    if (!guard.pass) {
      ElMessage.error(guard.message)
      return
    }
    // 宽高只能在本地读（文件服务不解析图片内容），失败不拦上传，只是别人看到时会抖动一下
    const size = await readImageSize(file)
    const vo = await uploadFileSmart(file, 'dating_photo')
    // machineChecked 用 skipped 取反：模型没真跑过时不能谎报「机器已审」，否则这张会直接对外可见
    photos.value = await addPhoto({
      fileId: vo.fileId,
      machineChecked: !(guard.result && guard.result.skipped),
      width: size ? size.width : 0,
      height: size ? size.height : 0
    })
    ElMessage.success('照片已提交')
    refreshDatingScore()
  } catch {
    // 提示已由 request.js 弹出
  } finally {
    photoUploading.value = false
  }
}

async function removePhoto(item) {
  try {
    await ElMessageBox.confirm('删除后这张就不再出现在资料卡上，确定？', '删除照片', { type: 'warning' })
  } catch {
    return
  }
  try {
    photos.value = await deletePhoto(item.id)
    ElMessage.success('照片已删除')
    refreshDatingScore()
  } catch {
    // 提示已弹出
  }
}

async function makeMain(item) {
  try {
    photos.value = await setMainPhoto(item.id)
  } catch {
    // 提示已弹出
  }
}

/**
 * 前移/后移：交换两个下标后把整个 id 数组交回去。
 *
 * 后端要的是「完整新顺序」而不是「谁插到第几位」，重复提交同一个数组不会越排越乱；
 * 失败时不本地反推顺序，重报一次完整数组比「界面和库不一致」便宜。
 */
async function movePhoto(index, offset) {
  const target = index + offset
  if (target < 0 || target >= photos.value.length) {
    return
  }
  const ids = photos.value.map((item) => item.id)
  const temp = ids[index]
  ids[index] = ids[target]
  ids[target] = temp
  try {
    photos.value = await sortPhotoOrder(ids)
  } catch {
    // 提示已弹出
  }
}

/** 照片占完资料度 30 分，增删后把分数与待补提示刷回来，不必用户再点一次「保存交友资料」 */
async function refreshDatingScore() {
  try {
    fillDating(await fetchDatingProfile())
  } catch {
    // 分数刷不动不影响照片本身已经保存
  }
}

/* ------------------------------ 兴趣标签 ------------------------------ */

/** 与后端 UserTag.MAX_SELECT 一致；超上限最终由服务端拒，这里只是拦住第 11 次的无效点击 */
const TAG_MAX = 10

const tagLibrary = ref([])
/** 已存到服务端的顺序，既当「有没有改动」的基准，也当「撑销」的回到点 */
const tagSaved = ref([])
const tagDraft = ref([])
const tagSaving = ref(false)

/**
 * 只比 id 序列：后端不关心名字与分类那些展示字段，
 * 而库内改了一个标签名不该把用户的草稿判成「已改动」。
 */
const tagDirty = computed(
  () => tagDraft.value.map((item) => item.tagId).join(',') !== tagSaved.value.map((item) => item.tagId).join(',')
)

function fillTags(list) {
  tagSaved.value = list || []
  tagDraft.value = [...tagSaved.value]
}

function isTagPicked(tagId) {
  return tagDraft.value.some((item) => item.tagId === tagId)
}

/** 点库里的 chip 就是切换：选中追加到末尾（用户自己再调顺序），再点取消 */
function toggleTag(item) {
  const index = tagDraft.value.findIndex((row) => row.tagId === item.tagId)
  if (index >= 0) {
    dropTag(index)
    return
  }
  if (tagDraft.value.length >= TAG_MAX) {
    ElMessage.warning(`最多选 ${TAG_MAX} 个，先取消一个再选`)
    return
  }
  tagDraft.value.push(item)
}

function dropTag(index) {
  tagDraft.value.splice(index, 1)
}

function moveTag(index, offset) {
  const target = index + offset
  if (target < 0 || target >= tagDraft.value.length) {
    return
  }
  const rows = [...tagDraft.value]
  const temp = rows[index]
  rows[index] = rows[target]
  rows[target] = temp
  tagDraft.value = rows
}

function resetTagDraft() {
  tagDraft.value = [...tagSaved.value]
}

async function submitTags() {
  tagSaving.value = true
  try {
    // 用后端返回的结果重绘而不是本地草稿：去重与「已下架的不给新增」这些规则在服务层
    fillTags(await saveTags(tagDraft.value.map((item) => item.tagId)))
    ElMessage.success('兴趣标签已保存')
    refreshDatingScore()
  } catch {
    // 提示已由 request.js 弹出
  } finally {
    tagSaving.value = false
  }
}

/* -------------------------------- 头像 -------------------------------- */

const avatarInputRef = ref(null)
const avatarUploading = ref(false)

function pickAvatar() {
  if (avatarUploading.value) {
    return
  }
  openFilePicker(avatarInputRef.value)
}

async function onAvatarPicked(event) {
  const input = event.target
  const file = input.files && input.files[0]
  // 连续两次选同一个文件时 value 没变则不触发 change，必须清掉
  input.value = ''
  if (!file) {
    return
  }
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    return
  }
  if (file.size > MAX_AVATAR_BYTES) {
    ElMessage.error(`头像不能超过 ${formatFileSize(MAX_AVATAR_BYTES)}，当前 ${formatFileSize(file.size)}`)
    return
  }
  // 头像会在每一个会话、每一条消息旁边渲染给所有联系人看，是全站辐射面最大的一张图，
  // 因此与聊天图片走同一道闸门（识别失败依旧降级放行，不卡用户改头像）。
  // 放在 avatarUploading 里：首次加载模型要几秒，得让头像上转起来并挡住重复选图
  avatarUploading.value = true
  try {
    const guard = await guardImage(file, { scene: 'avatar' })
    if (!guard.pass) {
      ElMessage.error(guard.message)
      return
    }
    const vo = await uploadAvatar(file)
    // /api/file/avatar 服务端已经把地址写进了当前用户资料，
    // 这里只需刷新本地缓存，不必再调一次修改资料接口
    auth.applyAvatar(vo)
    ElMessage.success('头像已更新')
  } catch {
    // 提示已弹出
  } finally {
    avatarUploading.value = false
  }
}

/* ------------------------------ 修改密码 ------------------------------ */

const passwordFormRef = ref(null)
const passwordDialog = reactive({ visible: false, saving: false })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '', logoutAll: true })

/** 验证码登录自动建号、从未设过密码的账号：首次设置无需原密码 */
const firstTimeSet = computed(() => !auth.passwordSet)

const passwordRules = computed(() => ({
  // 首次设置时不渲染也不校验原密码，与后端哨兵密码的放行逻辑一致
  oldPassword: firstTimeSet.value ? [] : [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}))

function openPasswordDialog() {
  // 首次设置默认不踢下线，避免刚设完密码就被登出；修改密码仍默认踢下线
  passwordForm.logoutAll = !firstTimeSet.value
  passwordDialog.visible = true
}

function resetPasswordForm() {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordForm.logoutAll = true
  passwordFormRef.value?.clearValidate()
}

async function submitPassword() {
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  passwordDialog.saving = true
  // 先快照当前是否为首次设置：loadCurrentUser 后 firstTimeSet 会翻转为 false，影响文案
  const wasFirstTime = firstTimeSet.value
  try {
    const stayed = await auth.changePassword({
      // 首次设置时无原密码，不传该字段（后端哨兵分支会跳过校验）
      oldPassword: wasFirstTime ? undefined : passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
      logoutAll: passwordForm.logoutAll
    })
    passwordDialog.visible = false
    if (stayed) {
      // 设完密码后 passwordSet 已变，刷新一次资料让按钮文案同步
      await auth.loadCurrentUser()
      ElMessage.success(wasFirstTime ? '密码已设置' : '密码已修改')
      return
    }
    // 勾选了「退出所有设备」时 auth.changePassword 已经清掉本地登录态，
    // 界面必须跟着回到登录页，否则会停在一个所有请求都返回未登录的空壳上
    ElMessage.success('密码已修改，请重新登录')
    router.replace({ name: 'login' })
  } catch {
    // 原密码错误等提示已弹出，保留对话框让用户重试
  } finally {
    passwordDialog.saving = false
  }
}

/* ------------------------------ 绑定手机号 ------------------------------ */

/**
 * 通用倒计时：发送短信验证码后禁用按钮，避免频繁重发。
 * 与 Login.vue 里的实现保持一致（那边是登录发码，这边是绑定发码）。
 */
function useCountdown() {
  const seconds = ref(0)
  let timer = null
  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    seconds.value = 0
  }
  function start(value) {
    stop()
    seconds.value = Math.max(1, Math.ceil(Number(value) || 60))
    timer = setInterval(() => {
      seconds.value -= 1
      if (seconds.value <= 0) {
        stop()
      }
    }, 1000)
  }
  return { seconds, start, stop }
}

const phoneFormRef = ref(null)
const phoneDialog = reactive({ visible: false, saving: false })
const phoneForm = reactive({ phone: '', smsCode: '' })
const smsSending = ref(false)
const smsDebugCode = ref('')
const sms = useCountdown()
const smsCountdown = sms.seconds

const phoneRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ]
}

function openPhoneDialog() {
  phoneForm.phone = ''
  phoneForm.smsCode = ''
  smsDebugCode.value = ''
  phoneDialog.visible = true
}

function resetPhoneForm() {
  phoneForm.phone = ''
  phoneForm.smsCode = ''
  smsDebugCode.value = ''
  sms.stop()
  phoneFormRef.value?.clearValidate()
}

/**
 * 发送绑定验证码。
 *
 * scene=bind 且用户已登录，后端跳过图形验证码闸门，因此这里只校验手机号本身，
 * 不需要像登录页那样先填图形验证码。
 */
async function sendBindSms() {
  try {
    await phoneFormRef.value.validateField('phone')
  } catch {
    return
  }
  smsSending.value = true
  try {
    const vo = await sendSmsCode({ phone: phoneForm.phone, scene: 'bind' })
    smsDebugCode.value = vo?.debugCode || ''
    sms.start(vo?.retryAfter || vo?.expiresIn || 60)
    ElMessage.success('短信验证码已发送')
  } catch {
    // 提示已由 request.js 弹出（如发送过于频繁）
  } finally {
    smsSending.value = false
  }
}

async function submitPhone() {
  const valid = await phoneFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  phoneDialog.saving = true
  try {
    await auth.bindPhone({ phone: phoneForm.phone, smsCode: phoneForm.smsCode })
    phoneDialog.visible = false
    ElMessage.success('手机号已绑定')
  } catch {
    // 验证码错误 / 号码被占用等提示已弹出，保留对话框让用户重试
  } finally {
    phoneDialog.saving = false
  }
}

/* ------------------------------ 退出登录 ------------------------------ */

async function onLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能收发消息，确定退出？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await signOut()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}

/* ------------------------------ 账号注销 ------------------------------ */

async function onCancelAccount() {
  // 第一重确认：说明后果
  try {
    await ElMessageBox.confirm(
      '注销后将永久删除您的账号及所有关联数据（好友、会话、消息、群组等），此操作不可恢复。确定继续？',
      '高危操作',
      { confirmButtonText: '我已了解，继续', cancelButtonText: '取消', type: 'error', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  // 第二重确认：输入密码
  let password
  try {
    const result = await ElMessageBox.prompt('请输入登录密码以确认注销：', '二次确认', {
      confirmButtonText: '确认注销',
      cancelButtonText: '取消',
      inputType: 'password',
      inputValidator: (val) => val ? undefined : '密码不能为空'
    })
    password = result.value
  } catch {
    return
  }
  try {
    await cancelAccount({ password })
    ElMessage.success('账号已注销')
    await signOut()
    router.replace({ name: 'login' })
  } catch (e) {
    ElMessage.error(e?.message || '注销失败，请检查密码是否正确')
  }
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(async () => {
  // 刷新页面直接落在 /profile 时 store 可能还没恢复完，这里补一次
  if (!auth.userInfo) {
    await auth.loadCurrentUser()
  }
  fillForm()
  loadDating()
})
</script>

<style scoped>
.profile {
  height: 100%;
  overflow-y: auto;
}

.profile__inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 768px) {
  .profile__inner {
    padding: 12px;
    gap: 12px;
  }
}

.profile__card {
  padding: 20px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: var(--im-radius);
}

.profile__card-title {
  margin-bottom: 16px;
  font-size: 15px;
  font-weight: 600;
}

.profile__avatar-row {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile__avatar {
  position: relative;
  width: 88px;
  height: 88px;
  flex: none;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
}

/* 悬停才显出蒙层，常驻会把头像糊掉 */
.profile__avatar-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  opacity: 0;
  transition: opacity 0.15s;
}

.profile__avatar:hover .profile__avatar-mask,
.profile__avatar-mask:has(.is-loading) {
  opacity: 1;
}

.profile__identity {
  min-width: 0;
}

.profile__nickname {
  font-size: 20px;
  font-weight: 600;
}

.profile__account {
  margin-top: 4px;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.profile__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.profile__facts {
  margin-top: 20px;
}

.profile__form {
  max-width: 480px;
}

.profile__readonly {
  color: var(--im-text-secondary);
}

.profile__phone-btn {
  margin-left: 12px;
}

.profile__sms-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.profile__sms-row :deep(.el-input) {
  flex: 1;
}

.profile__actions {
  display: flex;
  gap: 12px;
}

.profile__hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--im-text-secondary);
}

.profile__hint--warn {
  margin-bottom: 12px;
  color: #e6a23c;
}

/* 交友块：标题与完资料度同一行，不动 .profile__card-title 本身的样式，免得影响已有的两处 */
.profile__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.profile__card-title--flat {
  margin-bottom: 0;
}

.profile__score {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.profile__progress {
  margin-bottom: 10px;
}

.profile__tips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}

.profile__region {
  display: flex;
  gap: 8px;
  width: 100%;
}

.profile__loc-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

/* 三列九宫格：与后端 9 张上限正好对齐，最后一格留给「添加」按钮 */
.profile__photos {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 8px;
}

.profile__photo {
  position: relative;
  aspect-ratio: 3 / 4;
  overflow: hidden;
  border: 1px solid var(--im-border);
  border-radius: 8px;
  background: var(--im-bg);
}

.profile__photo-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile__photo--add {
  display: flex;
  align-items: center;
  justify-content: center;
  border-style: dashed;
  color: var(--im-text-secondary);
  font-size: 24px;
  cursor: pointer;
}

.profile__photo--add:hover {
  border-color: var(--im-primary);
  color: var(--im-primary);
}

.profile__photo-badge {
  position: absolute;
  top: 6px;
  left: 6px;
  max-width: calc(100% - 12px);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.profile__photo-badge--main {
  background: var(--im-primary);
}

.profile__photo-badge--reject {
  background: #f56c6c;
}

/* 操作区默认隐掉：九宫格里每格都不常驻一排按钮，否则缩略图就不是给人看的了 */
.profile__photo-ops {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  justify-content: center;
  gap: 6px;
  padding: 4px 0;
  background: rgba(0, 0, 0, 0.5);
  opacity: 0;
  transition: opacity 0.15s;
}

.profile__photo:hover .profile__photo-ops {
  opacity: 1;
}

.profile__privacy {
  display: flex;
  flex-direction: column;
}

.profile__privacy-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0;
  border-top: 1px solid var(--im-border);
}

.profile__privacy-name {
  font-size: 14px;
}

.profile__privacy-select {
  width: 140px;
  flex: none;
}

/* 同聊天页：display:none 的 input 在部分手机浏览器上 click() 无效，改为渲染但不可见 */
.profile__file-input {
  position: fixed;
  top: 0;
  left: 0;
  width: 1px;
  height: 1px;
  padding: 0;
  border: 0;
  opacity: 0;
  pointer-events: none;
  z-index: -1;
}

.profile__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.profile__tags--picked {
  margin-bottom: 12px;
}

.profile__tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border: 1px solid var(--im-border);
  border-radius: 14px;
  background: var(--im-bg);
  color: var(--im-text-secondary);
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}

.profile__tag--on {
  border-color: var(--im-primary);
  background: var(--im-panel);
  color: var(--im-primary);
}

/* 已选区的那一排带操作按钮，不是给人点的 chip */
.profile__tag--picked {
  cursor: default;
}

.profile__tag-off {
  color: #e6a23c;
  font-size: 12px;
}

.profile__tag-group {
  margin-top: 10px;
}

.profile__tag-category {
  margin-bottom: 6px;
  color: var(--im-text-secondary);
  font-size: 13px;
}

.profile__tag-actions {
  margin-top: 12px;
}
</style>
