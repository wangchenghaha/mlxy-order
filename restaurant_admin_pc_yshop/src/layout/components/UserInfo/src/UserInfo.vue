<script lang="ts" setup>
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import avatarImg from '@/assets/imgs/avatar.gif'
import { adminApi } from '@/api/restaurant'
import { useDesign } from '@/hooks/web/useDesign'
import { useTagsViewStore } from '@/store/modules/tagsView'
import { useUserStore } from '@/store/modules/user'
import LockDialog from './components/LockDialog.vue'
import LockPage from './components/LockPage.vue'
import { useLockStore } from '@/store/modules/lock'

defineOptions({ name: 'UserInfo' })

const { t } = useI18n()

const { replace } = useRouter()

const userStore = useUserStore()

const tagsViewStore = useTagsViewStore()

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('user-info')

const avatar = computed(() => userStore.user.avatar ?? avatarImg)
const userName = computed(() => userStore.user.nickname ?? 'Admin')

// 锁定屏幕
const lockStore = useLockStore()
const getIsLock = computed(() => lockStore.getLockInfo?.isLock ?? false)
const dialogVisible = ref<boolean>(false)
const lockScreen = () => {
  dialogVisible.value = true
}

const passwordDialogVisible = ref(false)
const passwordSubmitting = ref(false)
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const validateConfirmPassword = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value !== passwordForm.newPassword) {
    callback(new Error(t('profile.password.diffPwd')))
    return
  }
  callback()
}

const passwordRules = computed<FormRules>(() => ({
  oldPassword: [{ required: true, message: t('profile.password.oldPwdMsg'), trigger: 'blur' }],
  newPassword: [
    { required: true, message: t('profile.password.newPwdMsg'), trigger: 'blur' },
    { min: 6, max: 20, message: t('profile.password.pwdRules'), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: t('profile.password.cfPwdMsg'), trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}))

const openPasswordDialog = () => {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordDialogVisible.value = true
  nextTick(() => passwordFormRef.value?.clearValidate())
}

const submitPassword = async () => {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) return
  passwordSubmitting.value = true
  try {
    await adminApi.changePassword({ ...passwordForm })
    ElMessage.success(t('common.success'))
    passwordDialogVisible.value = false
  } finally {
    passwordSubmitting.value = false
  }
}

const loginOut = async () => {
  try {
    await ElMessageBox.confirm(t('common.loginOutMessage'), t('common.reminder'), {
      confirmButtonText: t('common.ok'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
    await userStore.loginOut()
    tagsViewStore.delAllViews()
    replace('/login?redirect=/index')
  } catch {}
}
</script>

<template>
  <ElDropdown class="custom-hover" :class="prefixCls" trigger="click">
    <div class="flex items-center">
      <ElAvatar :src="avatar" alt="" class="w-[calc(var(--logo-height)-25px)] rounded-[50%]" />
      <span class="pl-[5px] text-14px text-[var(--top-header-text-color)] <lg:hidden">
        {{ userName }}
      </span>
    </div>
    <template #dropdown>
      <ElDropdownMenu>
        <ElDropdownItem divided>
          <Icon icon="ep:lock" />
          <div @click="lockScreen">{{ t('lock.lockScreen') }}</div>
        </ElDropdownItem>
        <ElDropdownItem divided @click="openPasswordDialog">
          <Icon icon="ep:key" />
          <div>{{ t('profile.info.resetPwd') }}</div>
        </ElDropdownItem>
        <ElDropdownItem divided @click="loginOut">
          <Icon icon="ep:switch-button" />
          <div>{{ t('common.loginOut') }}</div>
        </ElDropdownItem>
      </ElDropdownMenu>
    </template>
  </ElDropdown>

  <LockDialog v-if="dialogVisible" v-model="dialogVisible" />

  <ElDialog v-model="passwordDialogVisible" :title="t('profile.info.resetPwd')" width="420px" append-to-body>
    <ElForm ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="100px">
      <ElFormItem :label="t('profile.password.oldPassword')" prop="oldPassword">
        <ElInput v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" />
      </ElFormItem>
      <ElFormItem :label="t('profile.password.newPassword')" prop="newPassword">
        <ElInput v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" />
      </ElFormItem>
      <ElFormItem :label="t('profile.password.confirmPassword')" prop="confirmPassword">
        <ElInput v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="passwordDialogVisible = false">{{ t('common.cancel') }}</ElButton>
      <ElButton type="primary" :loading="passwordSubmitting" @click="submitPassword">
        {{ t('common.ok') }}
      </ElButton>
    </template>
  </ElDialog>

  <teleport to="body">
    <transition name="fade-bottom" mode="out-in">
      <LockPage v-if="getIsLock" />
    </transition>
  </teleport>
</template>

<style scoped lang="scss">
.fade-bottom-enter-active,
.fade-bottom-leave-active {
  transition:
    opacity 0.25s,
    transform 0.3s;
}

.fade-bottom-enter-from {
  opacity: 0;
  transform: translateY(-10%);
}

.fade-bottom-leave-to {
  opacity: 0;
  transform: translateY(10%);
}
</style>
