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
            <!--
              三个自由输入框换成一份离线行政区划字典的三级联动：手填会出现「深圳 / 深圳市 / 广东省深圳市」
              三种写法，而同城筛选是按 city 做包含匹配（LIKE '%深圳市%'），它只能一个方向兼容：存「深圳市」能
              被「深圳」筛到，存「深圳」却筛不到。写法统一了两个方向才不会莫名其妙少人。存进库的仍是三个名字（见 regionTree.js 头注释）。
            -->
            <div class="profile__region">
              <el-cascader
                v-model="regionValue"
                class="profile__region-picker"
                :options="regionOpts"
                :props="regionProps"
                :placeholder="regionPlaceholder"
                filterable
                clearable
                @change="onRegionChange"
              />
              <div v-if="regionHint" class="profile__hint">{{ regionHint }}</div>
            </div>
          </el-form-item>
          <!--
            坐标只由这里采集、geohash 一律服务端重算：前端如果也算一份，两边迟早对不上，
            对不上的表现是“本人永远刷不到自己附近的人”。
            ★ 定位一成功就单独落库，不等「保存交友资料」：附近的人与资料卡距离读的是库里那一行，
              要用户记得再点一次保存才生效，就是「明明定位了却还是不行」那个 bug 本身。
          -->
          <el-form-item label="我的定位">
            <div class="profile__loc-row">
              <el-button :loading="locating" @click="locateMe">重新定位</el-button>
              <el-button v-if="dating.hasLocation" link type="danger" @click="clearMyLocation">清除定位</el-button>
              <span class="profile__readonly">{{ locationSummary }}</span>
            </div>
            <div v-if="locatingError" class="profile__hint profile__hint--warn">{{ locatingError }}</div>
            <div v-else-if="locating" class="profile__hint">正在取位置；浏览器弹窗里点「允许」后会自动继续</div>
            <div v-else-if="!dating.hasLocation" class="profile__hint">
              定位一次即可：坐标当场入库，附近的人与资料卡距离立刻生效，不用再点「保存交友资料」
            </div>
            <div v-else-if="privacy.showDistance !== 1" class="profile__hint profile__hint--warn">
              「显示距离」这个开关是关的，别人的资料卡上看不到公里数；打开后仍需双方在 {{ LOCATION_VALID_DAYS }} 天内都定位过
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

      <!-- ==================== 登录设备 ==================== -->
      <section class="profile__card">
        <div class="profile__card-title">登录设备</div>
        <div class="profile__hint">
          一个账号可以在浏览器 / 桌面端 / 手机 / 小程序 各占一位同时在线；同一类设备超过后台配的台数时，
          最早那台会被顶下线。撤销只是踢掉那台设备，不禁止它下次用密码登录。
        </div>
        <el-table
          v-loading="device.loading"
          :data="device.rows"
          size="small"
          class="profile__table"
          empty-text="还没有设备登记记录（重新登录一次就有了）"
        >
          <el-table-column label="设备" min-width="180">
            <template #default="{ row }">
              <div class="profile__cell-main">{{ row.deviceName || row.deviceTypeDesc }}</div>
              <div class="profile__cell-sub">{{ row.deviceTypeDesc }} · 共登录 {{ row.loginCount || 0 }} 次</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag v-if="row.current" size="small" type="primary" effect="plain">本机</el-tag>
              <el-tag v-else-if="row.status !== 1" size="small" type="info" effect="plain">已撤销</el-tag>
              <el-tag v-else-if="row.online" size="small" type="success" effect="plain">在线</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">离线</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近登录" min-width="150">
            <template #default="{ row }">{{ formatDateTime(row.lastLoginTime) || '—' }}</template>
          </el-table-column>
          <el-table-column label="IP" min-width="120">
            <template #default="{ row }">{{ row.ip || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="76">
            <template #default="{ row }">
              <!-- 本机的撤销按钮必须禁用：点了等于自己把自己踢下线，而那不是「管理设备」是误操作 -->
              <el-button link type="danger" :disabled="row.current || row.status !== 1" @click="onRevokeDevice(row)">
                撤销
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="device.rows.some((it) => !it.identifiable)" class="profile__hint profile__hint--warn">
          列表里有只认到设备类型、认不到具体一台的记录（旧版客户端上送的）：撤销它会把同类型的所有登录一起下线。
        </div>
        <div class="profile__actions">
          <el-button :loading="device.busy" @click="onLogoutOthers">下线其他设备</el-button>
          <el-button text type="primary" @click="toggleRecords">
            {{ device.showRecords ? '收起登录记录' : '查看登录记录' }}
          </el-button>
        </div>
        <el-table
          v-if="device.showRecords"
          v-loading="record.loading"
          :data="record.rows"
          size="small"
          class="profile__table"
          empty-text="暂无记录"
        >
          <el-table-column label="时间" min-width="150">
            <template #default="{ row }">{{ formatDateTime(row.createTime) || '—' }}</template>
          </el-table-column>
          <el-table-column label="事件" width="110">
            <template #default="{ row }">{{ row.eventDesc || '—' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="150">
            <template #default="{ row }">{{ row.deviceName || row.deviceTypeDesc || '—' }}</template>
          </el-table-column>
          <el-table-column label="IP" min-width="120">
            <template #default="{ row }">{{ row.ip || '—' }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="180">
            <template #default="{ row }">{{ row.detail || '—' }}</template>
          </el-table-column>
        </el-table>
        <div v-if="device.showRecords" class="profile__hint">
          记录只到 IP，不显示城市：服务端没接任何归属地库，凭 IP 写「来自某市」是编出来的。
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
        <!-- 首绑不涉及“换了别人的号”，这个选项只在换绑时出现 -->
        <el-form-item v-if="auth.phone">
          <el-checkbox v-model="phoneForm.logoutAll">换绑后退出所有设备（包括当前）</el-checkbox>
          <div class="profile__hint">
            不勾选也会把其他设备下线，只留着本机这一台——换绑就在手机上做，做完不必再登一次。
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
import { fetchDevices, fetchLoginRecords, logoutOtherDevices, revokeDevice } from '@/api/device'
import {
  addPhoto, deletePhoto, fetchDatingDict, fetchDatingProfile, fetchPhotos, fetchTagLibrary, fetchTags,
  saveDatingPrivacy, saveDatingProfile, saveTags, setMainPhoto, sortPhotoOrder
} from '@/api/dating'
import { guardImage } from '@/utils/nsfw'
import { mediaUrl, readImageSize } from '@/utils/media'
import { regionOptions, regionCodes, regionNames } from '@/data/regionTree'
import { resolveRegionByCoords, provinceByCoords } from '@/utils/regionLookup'
import { requestLocation } from '@/utils/location'
import { getCache, setCache } from '@/utils/localCache'
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
  // 先记下旧邮箱：后端在换绑邮箱时会顺带下线其他设备，提示必须说出来，
  // 否则用户会以为手机那边还登着（而实际上已经被踢了）
  const oldEmail = (auth.userInfo?.email || '').trim().toLowerCase()
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
    ElMessage.success(
      oldEmail && oldEmail !== (form.email || '').trim().toLowerCase()
        ? '资料已更新，其他设备已下线'
        : '资料已更新'
    )
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

/** 本次要上报的新坐标：正常情况下它只在「自动落库失败」时留一会儿（定位一成功就单独存一次） */
const locationForm = reactive({ lng: null, lat: null })
const locating = ref(false)
const locatingError = ref('')

/* ---------------- 所在地区：省市县三级联动 ---------------- */

/** 与后端 UserProfile.LOCATION_VALID_DAYS 同一口径，只用来把提示写清楚 */
const LOCATION_VALID_DAYS = 7

const regionOpts = regionOptions()
// checkStrictly：东莞、儋州这类下面没有区县的市，以及不想填到区的用户，都要能只选到市那一档
// checkOnClickNode：点一级就选中一级（上方立刻显示「广东省」，再点市就显示「广东省 / 深圳市」）。
// 不开这个的话，非叶子节点只能点它前面那个小圆圈才选中，点文字只展开下一级，
// 用户的感受就是「必须三级全选完才一次显示」。
const regionProps = { checkStrictly: true, expandTrigger: 'hover', checkOnClickNode: true }
/** 级联框的值是编码数组，而库里存的是三个名字（为什么这么切分见 data/regionTree.js 头注释） */
const regionValue = ref([])
const regionHint = ref('')
const regionLegacy = ref('')

const regionPlaceholder = computed(() =>
  regionLegacy.value ? `原填「${regionLegacy.value}」，重新选可对齐字典` : '选择省 / 市 / 区县'
)

/**
 * 把库里已有的手填文本换算成级联框的选中值。
 *
 * <p>查不到这套名字时<b>保留原文并解释一句</b>，不清空：历史数据里大量是「深圳」这种少个「市」
 * 字的写法，替用户把它抹掉等于把一个他填过的地址弄丢，而同城筛选本来就还能按包含匹配到它。
 */
function syncRegionValue() {
  const raw = [datingForm.province, datingForm.city, datingForm.district].filter(Boolean).join(' ')
  const codes = regionCodes(datingForm.province, datingForm.city, datingForm.district)
  regionValue.value = codes || []
  regionLegacy.value = codes || !raw ? '' : raw
  regionHint.value = ''
}

function onRegionChange(value) {
  const names = regionNames(Array.isArray(value) ? value : [])
  datingForm.province = names[0] || ''
  datingForm.city = names[1] || ''
  datingForm.district = names[2] || ''
  regionLegacy.value = ''
  regionHint.value = ''
}

/**
 * 把一套编码写进级联框（regionValue）与表单三个名字。
 *
 * <p>★ 两边必须一起写：级联框显示的是 regionValue，而 {@code onRegionChange} 只写名字。
 * 只调 onRegionChange 就是「定位后框里空的，保存之后才出现三级」那个 bug：名字已经进了表单，
 * 但要等保存后回读调一次 syncRegionValue() 才把编码算回来给级联框。
 *
 * <p>两条不覆盖原则：① 空的不动（定位没判出来 ≠ 用户原来填的是错的）；
 * ② 同一个省里档数变少也不动（定位只判到省，不能把用户填好的市与区县抹掉）。
 *
 * @returns {boolean} 是否真写进去了（false = 按上面两条保住了原值）
 */
function applyRegionCodes(codes, note) {
  const next = (Array.isArray(codes) ? codes : []).filter(Boolean)
  // 级联框取消选中时 v-model 会变 null（不是空数组），不能直接读 .length
  const prev = Array.isArray(regionValue.value) ? regionValue.value : []
  const keep = !next.length || (next.length < prev.length && next[0] === prev[0])
  if (!keep) {
    regionValue.value = next
    onRegionChange(next)
  }
  // onRegionChange 会把 hint 清空，所以这句必须放在它后面
  regionHint.value = note || ''
  return !keep
}

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
  || locationForm.lng !== null
  || locationForm.lat !== null
)

const locationSummary = computed(() => {
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
  syncRegionValue()
  locationForm.lng = null
  locationForm.lat = null
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
  syncRegionValue()
  locatingError.value = ''
}

/**
 * 坐标落库后只刷定位相关的几个字段。
 *
 * <p>不能直接走 {@code fillDating(vo)}：那会把用户正在改、还没保存的生日/城市一起按库里的旧值
 * 覆盖回去，界面凭空吞掉几次输入。
 */
function applyLocationFields(vo) {
  if (!vo) {
    return
  }
  dating.hasLocation = vo.hasLocation === true
  dating.locationFresh = vo.locationFresh !== false
  dating.locationText = vo.locationText || ''
  dating.profileScore = Number(vo.profileScore ?? 0)
  dating.missingTips = vo.missingTips || []
}

/** 坐标单独落库：成功了就清掉待上报值，剩下的交友字段仍等用户自己点保存 */
async function persistLocation() {
  if (locationForm.lng === null || locationForm.lat === null) {
    return
  }
  try {
    applyLocationFields(await saveDatingProfile({ lng: locationForm.lng, lat: locationForm.lat }))
    locationForm.lng = null
    locationForm.lat = null
    ElMessage.success('定位已保存，附近的人与资料卡距离即刻生效')
  } catch {
    // 提示已由 request.js 弹出；坐标留着，点「保存交友资料」时还会再带一次
  }
}

async function clearMyLocation() {
  try {
    applyLocationFields(await saveDatingProfile({ clears: ['location'] }))
    locationForm.lng = null
    locationForm.lat = null
    ElMessage.success('定位已清除，附近的人将不再按距离找你')
  } catch {
    // 提示已由 request.js 弹出
  }
}

/**
 * 坐标 → 三级并回填到级联框。
 *
 * <p>两步走：先用随包的省级外包框<b>零请求</b>填出省（同步、立刻可见），再等
 * {@code resolveRegionByCoords} 把市与区县补上。判市判区可能要连公网取边界文件（内网与
 * 打包成 EXE/APK 时取不到，那时走随包中心点表兜底），所以它不参与 locating 那把锁：
 * 坐标已经落库，行政区划只是顺手预填，再慢也不能把按钮占着。
 *
 * <p>★ 这里填的只是界面与表单，三个名字仍要点「保存交友资料」才写库（坐标是另一条路，
 * 定位成功即落库）。不跟着坐标一起自动写库的理由：出差在外点一下定位，不应该静默把
 * 家里的城市改掉——那会直接改变同城匹配的结果。
 */
async function fillRegionByCoords(lng, lat) {
  const quick = provinceByCoords(lng, lat)
  applyRegionCodes(quick ? [quick.code] : [], '正在按坐标判断所在地区…')
  const got = await resolveRegionByCoords(lng, lat)
  if (!got.codes.length) {
    // 判不出来只说原因，不动用户原来填的值（applyRegionCodes 对空数组就是这个语义）
    applyRegionCodes([], got.note || '这个坐标对不上行政区划字典，请手动选择所在地区')
    return
  }
  const written = applyRegionCodes(got.codes, got.note)
  if (!written) {
    regionHint.value = `定位只判断到「${got.text}」，已保留原来更细的地区`
  } else if (!got.note) {
    regionHint.value = `已按定位填为 ${got.text}，确认无误后点「保存交友资料」写库`
  }
}

/** 定位失败只在原地提示、不弹全局错误：不授权定位也能只用城市列做同城筛选 */
async function locateMe() {
  locatingError.value = ''
  locating.value = true
  const got = await requestLocation()
  if (!got.ok) {
    locating.value = false
    locatingError.value = got.message
    return
  }
  locationForm.lng = got.lng
  locationForm.lat = got.lat
  locating.value = false
  // 先起地区反查再落库坐标：反查第一步是零请求的省级判定，能立刻把「广东省」显示出来，
  // 市与区县等公网边界回来再补——点完定位框里马上有东西，而不是等一趟保存请求回来还是空的
  const filling = fillRegionByCoords(got.lng, got.lat)
  await persistLocation()
  // 反查失败不影响已经落库的坐标，也不能抛到事件处理器外面变成 unhandled rejection
  await filling.catch(() => {})
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
  if (locationForm.lng !== null || locationForm.lat !== null) {
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

/**
 * 原地拦重复上传。
 *
 * <p>后端的判重依据是 fileId（同一张图上传会秒传命中同一行 im_file），报 2026 是一条全局错误
 * 弹框；而“同一张图再传一次”根本不需要往返一趟服务端才能发现。两道拦法：
 * 一是本地记住“文件指纹 → fileId”，命中已在墙上的那张时连上传都不发；
 * 二是上传完先拿 fileId 对一遍照片列表，命中就不再去调 addPhoto。
 */
const PHOTO_FP_TTL = 90 * 24 * 60 * 60 * 1000

/** 指纹用 name+size+lastModified：三者全同几乎只会是同一个文件，比再算一遍 MD5 便宜得多 */
function fingerprintOf(file) {
  return `${file.name}:${file.size}:${file.lastModified}`
}

/** 按用户隔离：同一台机器换一个账号，不能拿上一个号的相册指纹来判重 */
function photoFpKey() {
  return `photo.fp.${auth.userInfo?.id || 0}`
}

function photoFpMap() {
  const map = getCache(photoFpKey())
  return map && typeof map === 'object' ? map : {}
}

/** 长整型 ID 一律按字符串比：序列化配置一变就是一扇漏判的门 */
function sameId(left, right) {
  return left != null && right != null && String(left) === String(right)
}

function wallHasFile(fileId) {
  return photos.value.some((item) => sameId(item.fileId, fileId))
}

function rememberPhotoFile(file, fileId) {
  if (!fileId) {
    return
  }
  const map = photoFpMap()
  map[fingerprintOf(file)] = String(fileId)
  setCache(photoFpKey(), map, PHOTO_FP_TTL)
}

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
  const knownFileId = photoFpMap()[fingerprintOf(file)]
  if (knownFileId && wallHasFile(knownFileId)) {
    ElMessage.warning('这张照片已经在照片墙里了，先删掉那张再传')
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
    // ★ 同一张图秒传回来的是同一个 fileId，在这里就地拦下，不必让后端回 2026 再弹一个全局错误
    if (wallHasFile(vo.fileId)) {
      rememberPhotoFile(file, vo.fileId)
      ElMessage.warning('这张照片已经在照片墙里了，先删掉那张再传')
      return
    }
    // machineChecked 用 skipped 取反：模型没真跑过时不能谎报「机器已审」，否则这张会直接对外可见
    photos.value = await addPhoto({
      fileId: vo.fileId,
      machineChecked: !(guard.result && guard.result.skipped),
      width: size ? size.width : 0,
      height: size ? size.height : 0
    })
    rememberPhotoFile(file, vo.fileId)
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
const phoneForm = reactive({ phone: '', smsCode: '', logoutAll: false })
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
  phoneForm.logoutAll = false
  smsDebugCode.value = ''
  phoneDialog.visible = true
}

function resetPhoneForm() {
  phoneForm.phone = ''
  phoneForm.smsCode = ''
  phoneForm.logoutAll = false
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
    // 换绑时后端默认保留本机、只踢其他设备；勾上 logoutAll 则连本机一起下线（需重登）
    const rebind = !!auth.phone
    await auth.bindPhone({ phone: phoneForm.phone, smsCode: phoneForm.smsCode, logoutAll: phoneForm.logoutAll })
    phoneDialog.visible = false
    if (rebind && phoneForm.logoutAll) {
      ElMessage.success('手机号已换绑，所有设备需重新登录')
      await signOut()
      router.replace({ name: 'login' })
      return
    }
    ElMessage.success(rebind ? '手机号已换绑，其他设备已下线' : '手机号已绑定')
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

/* ------------------------------ 登录设备 ------------------------------ */

const device = reactive({ rows: [], loading: false, busy: false, showRecords: false })
const record = reactive({ rows: [], loading: false, loaded: false })

async function loadDevices() {
  device.loading = true
  try {
    device.rows = (await fetchDevices()) || []
  } catch (e) {
    // 拦截器已经弹了错误条，这里只兼顾「列表拿不到不要把页面弄挂」
    device.rows = []
  } finally {
    device.loading = false
  }
}

async function loadRecords() {
  record.loading = true
  try {
    record.rows = (await fetchLoginRecords(30)) || []
    record.loaded = true
  } catch (e) {
    record.rows = []
  } finally {
    record.loading = false
  }
}

/** 展开时才拉：登录记录是一张只增表，没人开页就想看它 */
function toggleRecords() {
  device.showRecords = !device.showRecords
  if (device.showRecords && !record.loaded) {
    loadRecords()
  }
}

async function onRevokeDevice(row) {
  const name = row.deviceName || row.deviceTypeDesc || '该设备'
  try {
    await ElMessageBox.confirm(
      row.identifiable
        ? `确定撤销「${name}」？它会立刻下线，下次需重新登录。`
        : `「${name}」只认到设备类型认不到具体一台，撤销会把 ${row.deviceTypeDesc} 类型的所有登录一起下线。确定继续？`,
      '撤销设备',
      { type: 'warning', confirmButtonText: '确定撤销', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await revokeDevice(row.id)
    ElMessage.success('该设备已下线')
    await loadDevices()
    // 撤销会写一条流水，已经展开的记录表得跟着重拉，否则用户以为没生效
    if (device.showRecords) {
      loadRecords()
    }
  } catch (e) {
    // 错误提示由拦截器统一弹（含 2033「设备不存在或已下线」）
  }
}

async function onLogoutOthers() {
  try {
    await ElMessageBox.confirm(
      '除当前这台以外的登录全部下线，包括手机与小程序。确定继续？',
      '下线其他设备',
      { type: 'warning', confirmButtonText: '确定下线', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  device.busy = true
  try {
    const res = await logoutOtherDevices()
    ElMessage.success(res && res.count ? `已下线 ${res.count} 台其他设备` : '没有其他设备在线')
    await loadDevices()
    if (device.showRecords) {
      loadRecords()
    }
  } catch (e) {
    // 同上，拦截器已提示
  } finally {
    device.busy = false
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
  loadDevices()
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

/* 登录设备与登录记录两张表：靠上边留白与上方提示行分开，不改 .profile__actions 的既有间距 */
.profile__table {
  margin-top: 10px;
  width: 100%;
}

.profile__cell-main {
  font-size: 13px;
  color: var(--im-text);
}

.profile__cell-sub {
  font-size: 12px;
  color: var(--im-text-secondary);
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
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.profile__region-picker {
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
