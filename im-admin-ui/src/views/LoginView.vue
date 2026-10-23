<template>
  <div class="login-page">
    <el-card class="card" shadow="always">
      <h1 class="title">管理后台</h1>
      <p class="muted subtitle">IM 平台运营：数据看板 · 用户管理 · 举报工单</p>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="账号" prop="account">
          <el-input
            v-model="form.account"
            :prefix-icon="User"
            autocomplete="username"
            placeholder="登录账号或手机号"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            :prefix-icon="Lock"
            autocomplete="current-password"
            placeholder="请输入密码"
            show-password
            type="password"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-form-item>
          <el-button :loading="loading" class="submit" type="primary" @click="onSubmit">登录</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="notice"
        :closable="false"
        class="notice"
        :title="notice"
        type="warning"
      />

      <p class="muted tip">
        只有持有 <code>system:manage</code> 权限点的账号能进（种子数据里是 admin 角色）。
        用聊天端的普通账号登录会停在本页并给出提示。这里不提供注册与找回密码入口——
        后台账号只能由管理员在数据库里加。
      </p>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 后台登录页。
 *
 * 与聊天端登录页（im-ui/views/Login.vue）相比少三块，都是有意少的：
 *  <1> 没有注册 / 短信 / 邮箱验证码 / 找回密码四个 Tab：后台账号不该能自助开；
 *  <2> 没有图形验证码：后端账号密码这条路径根本不校验 captchaKey / captchaCode，
 *      那两个字段只用于「发短信、发邮件前的闸门」，加一块图只会让人以为它有用；
 *  <3> 没有「记住我」：token 的有效期归后端 sa-token 配置管，前端存不存都一样。
 *
 * 登录成功后要跳回 redirect 指向的页面（被 1002 弹回来时带上来的），
 * 没有 redirect 才回看板。
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref()
const loading = ref(false)
const notice = ref('')

const form = reactive({ account: '', password: '' })

const rules = {
  account: [{ message: '请输入账号', required: true, trigger: 'blur' }],
  password: [{ message: '请输入密码', required: true, trigger: 'blur' }]
}

onMounted(() => {
  // 登录态过期被弹回来时，request.js 已经提示过一次原因；这里再补一句是因为
  // 直接输错权限的情形没有经过那条路，页面上需要一个静态说明
  notice.value = route.query.redirect ? '登录态已失效，请重新登录' : ''
})

async function onSubmit() {
  if (!form.account || !form.password) {
    // 空字段不进校验流程直接提示：后端会先按「账号不能为空」的 @NotBlank 打回，
    // 那句提示对操作者来说不如本页上一行「账号和密码都要填」来得直白
    notice.value = '账号和密码都要填'
    return
  }
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) {
    return
  }
  loading.value = true
  notice.value = ''
  try {
    await auth.login({ account: form.account, password: form.password })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.replace(redirect || { name: 'dashboard' })
  } catch (error) {
    // 不弹 ElMessage：失败原因有好几种（密码错、账号无权限、后端没起），
    // 前两种已由 request.js 统一提示过，这里只显示在本页是因为「没权限」这种情形
    // 用户会反复试，飘在右上角的 toast 三秒就没了，留在表单边上才看得明白
    notice.value = error?.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  align-items: center;
  display: flex;
  height: 100%;
  justify-content: center;
}

.card {
  width: 380px;
}

.title {
  font-size: 20px;
  margin: 0;
  text-align: center;
}

.subtitle {
  margin: 8px 0 20px;
  text-align: center;
}

.submit {
  width: 100%;
}

.notice {
  margin-top: 8px;
}

.tip {
  line-height: 1.7;
  margin: 16px 0 0;
}

.tip code {
  background: #f4f4f5;
  border-radius: 3px;
  padding: 0 4px;
}

:deep(.el-form-item) {
  margin-bottom: 18px;
}
</style>
