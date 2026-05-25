<template>
  <el-form
    v-show="getShow"
    ref="formLogin"
    :model="loginData.loginForm"
    :rules="loginRules"
    class="login-form"
    label-position="top"
    size="large"
  >
    <el-form-item>
      <LoginFormTitle class="w-[100%]" />
    </el-form-item>

    <el-radio-group v-model="loginData.loginType" class="mb-20px w-[100%]">
      <el-radio-button label="PASSWORD">{{ t('loginExt.passwordLogin') }}</el-radio-button>
      <el-radio-button label="SMS">{{ t('loginExt.smsLogin') }}</el-radio-button>
    </el-radio-group>

    <template v-if="loginData.loginType === 'PASSWORD'">
      <el-form-item prop="username">
        <el-input
          v-model="loginData.loginForm.username"
          :placeholder="t('loginExt.accountPlaceholder')"
          :prefix-icon="iconAvatar"
        />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="loginData.loginForm.password"
          :placeholder="t('loginExt.passwordPlaceholder')"
          :prefix-icon="iconLock"
          show-password
          type="password"
          @keyup.enter="handleLogin"
        />
      </el-form-item>
      <el-form-item prop="captchaCode">
        <div class="captcha-row">
          <el-input
            v-model="loginData.loginForm.captchaCode"
            :placeholder="t('loginExt.captchaPlaceholder')"
            @keyup.enter="handleLogin"
          />
          <el-button class="captcha-btn" @click="loadCaptcha">{{ captcha.question }}</el-button>
        </div>
      </el-form-item>
    </template>

    <template v-else>
      <el-form-item prop="phone">
        <el-input
          v-model="loginData.loginForm.phone"
          :placeholder="t('loginExt.phonePlaceholder')"
          :prefix-icon="iconAvatar"
        />
      </el-form-item>
      <el-form-item prop="smsCode">
        <div class="captcha-row">
          <el-input
            v-model="loginData.loginForm.smsCode"
            :placeholder="t('loginExt.smsPlaceholder')"
            @keyup.enter="handleLogin"
          />
          <el-button :loading="smsLoading" @click="sendSms">{{ t('action.sendCode') }}</el-button>
        </div>
      </el-form-item>
      <el-alert
        v-if="smsDebugCode"
        class="mb-16px"
        type="info"
        :closable="false"
        :title="`${t('loginExt.smsDebug')}：${smsDebugCode}`"
      />
    </template>

    <el-form-item>
      <el-checkbox v-model="loginData.loginForm.rememberMe">{{ t('login.remember') }}</el-checkbox>
    </el-form-item>

    <el-form-item>
      <XButton
        :loading="loginLoading"
        :title="t('login.login')"
        class="w-[100%]"
        type="primary"
        @click="handleLogin"
      />
    </el-form-item>
  </el-form>
</template>

<script lang="ts" setup>
import { ElLoading, ElMessage } from 'element-plus'
import LoginFormTitle from './LoginFormTitle.vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { useIcon } from '@/hooks/web/useIcon'
import * as authUtil from '@/utils/auth'
import { usePermissionStore } from '@/store/modules/permission'
import * as LoginApi from '@/api/login'
import { LoginStateEnum, useFormValid, useLoginState } from './useLogin'

const { t } = useI18n()
const iconAvatar = useIcon({ icon: 'ep:avatar' })
const iconLock = useIcon({ icon: 'ep:lock' })
const formLogin = ref()
const { validForm } = useFormValid(formLogin)
const { getLoginState } = useLoginState()
const { currentRoute, push } = useRouter()
const permissionStore = usePermissionStore()
const redirect = ref<string>('')
const loginLoading = ref(false)
const smsLoading = ref(false)
const smsDebugCode = ref('')
const captcha = ref({ captchaId: '', question: t('action.refreshCaptcha') })

const getShow = computed(() => unref(getLoginState) === LoginStateEnum.LOGIN)

const loginRules = computed(() => {
  if (loginData.loginType === 'SMS') {
    return {
      phone: [required],
      smsCode: [required]
    }
  }
  return {
    username: [required],
    password: [required],
    captchaCode: [required]
  }
})

const loginData = reactive({
  loginType: 'PASSWORD',
  loginForm: {
    username: 'platform',
    password: 'Platform@123',
    captchaId: '',
    captchaCode: '',
    phone: '+60100000000',
    smsCode: '',
    rememberMe: false
  }
})

const loadCaptcha = async () => {
  captcha.value = await LoginApi.getCode({})
  loginData.loginForm.captchaId = captcha.value.captchaId
  loginData.loginForm.captchaCode = ''
}

const sendSms = async () => {
  if (!loginData.loginForm.phone) {
    ElMessage.warning(t('loginExt.inputPhoneFirst'))
    return
  }
  smsLoading.value = true
  try {
    const result = await LoginApi.sendSmsCode({ mobile: loginData.loginForm.phone, scene: 1 })
    smsDebugCode.value = result.debugCode || ''
    ElMessage.success(t('loginExt.smsSent'))
  } finally {
    smsLoading.value = false
  }
}

const getCookie = () => {
  const loginForm = authUtil.getLoginForm()
  if (loginForm) {
    loginData.loginForm.username = loginForm.username || loginData.loginForm.username
    loginData.loginForm.password = loginForm.password || loginData.loginForm.password
    loginData.loginForm.rememberMe = Boolean(loginForm.rememberMe)
  }
}

const handleLogin = async () => {
  loginLoading.value = true
  try {
    const valid = await validForm()
    if (!valid) return

    const token =
      loginData.loginType === 'SMS'
        ? await LoginApi.smsLogin({
            mobile: loginData.loginForm.phone,
            code: loginData.loginForm.smsCode
          })
        : await LoginApi.login({
            loginType: 'PASSWORD',
            username: loginData.loginForm.username,
            password: loginData.loginForm.password,
            captchaId: loginData.loginForm.captchaId,
            captchaCode: loginData.loginForm.captchaCode
          })

    authUtil.setToken(token)
    if (loginData.loginForm.rememberMe) {
      authUtil.setLoginForm({
        tenantName: '',
        username: loginData.loginForm.username,
        password: loginData.loginForm.password,
        rememberMe: loginData.loginForm.rememberMe
      })
    } else {
      authUtil.removeLoginForm()
    }

    const loading = ElLoading.service({
      lock: true,
      text: t('loginExt.loading'),
      background: 'rgba(0, 0, 0, 0.7)'
    })
    setTimeout(() => loading.close(), 400)
    push({ path: redirect.value || permissionStore.addRouters[0]?.path || '/' })
  } catch {
    await loadCaptcha()
  } finally {
    loginLoading.value = false
  }
}

watch(
  () => currentRoute.value,
  (route: RouteLocationNormalizedLoaded) => {
    redirect.value = route?.query?.redirect as string
  },
  { immediate: true }
)

onMounted(() => {
  getCookie()
  loadCaptcha()
})
</script>

<style lang="scss" scoped>
.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 136px;
  gap: 10px;
  width: 100%;
}

.captcha-btn {
  width: 136px;
  font-weight: 700;
}
</style>
