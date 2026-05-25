<template>
  <div
    :class="prefixCls"
    class="h-[100%] relative <xl:bg-v-dark <sm:px-10px <xl:px-10px <md:px-10px"
  >
    <div class="relative h-full flex mx-auto">
      <div
        :class="`${prefixCls}__left flex-1 bg-gray-500 bg-opacity-20 relative p-30px <xl:hidden`"
      >
        <!-- 左上角的 logo + 系统标题 -->
        <div class="flex items-center relative text-white">
          <img alt="" class="w-48px h-48px mr-10px" src="@/assets/imgs/logo.png" />
          <span class="text-20px font-bold">{{ t('app.title') }}</span>
        </div>
        <!-- 左边的背景图 + 欢迎语 -->
        <div class="flex justify-center items-center h-[calc(100%-60px)]">
          <TransitionGroup
            appear
            enter-active-class="animate__animated animate__bounceInLeft"
            tag="div"
          >
            <img key="1" alt="" class="w-500px" src="@/assets/imgs/login_bg.png" />
            <div key="2" class="text-3xl text-white" style="text-align: center;">{{ t('login.welcome') }}</div>
            <!-- <div key="3" class="mt-5 font-normal text-white text-14px">
              {{ t('login.message') }}
            </div> -->
          </TransitionGroup>
        </div>
      </div>
      <div class="flex-1 p-30px <sm:p-10px dark:bg-v-dark relative">
        <!-- 右上角的主题、语言选择 -->
        <div class="flex justify-between items-center text-white @2xl:justify-end @xl:justify-end">
          <div class="flex items-center @2xl:hidden @xl:hidden">
            <img alt="" class="w-48px h-48px mr-10px" src="@/assets/imgs/logo.png" />
            <span class="text-20px font-bold">{{ t('app.title') }}</span>
          </div>
          <div class="flex justify-end items-center space-x-10px">
            <!-- <ThemeSwitch /> -->
            <LocaleDropdown show-name class="login-lang-switch" color="#1f2937" />
          </div>
        </div>
        <!-- 右边的登录界面 -->
        <Transition appear enter-active-class="animate__animated animate__bounceInRight">
      
          <div
            class="h-full flex items-center m-auto w-[100%] @2xl:max-w-500px @xl:max-w-500px @md:max-w-500px @lg:max-w-500px"
          >
            <!-- 账号登录 -->
            <LoginForm class="p-20px h-auto m-auto <xl:(rounded-3xl light:bg-white)" />
            <!-- 手机登录 -->
            <!-- <MobileForm class="p-20px h-auto m-auto <xl:(rounded-3xl light:bg-white)" />
             二维码登录 -->
            <!-- <QrCodeForm class="p-20px h-auto m-auto <xl:(rounded-3xl light:bg-white)" />
             注册 -->
            <!-- <RegisterForm class="p-20px h-auto m-auto <xl:(rounded-3xl light:bg-white)" /> -->
            <!-- 三方登录 -->
            <!-- <SSOLoginVue class="p-20px h-auto m-auto <xl:(rounded-3xl light:bg-white)" /> --> 
          </div>
        </Transition>
      </div>
    </div>
  </div>
</template>
<script lang="ts" name="Login" setup>
import { useDesign } from '@/hooks/web/useDesign'
import { useAppStore } from '@/store/modules/app'
//import { ThemeSwitch } from '@/layout/components/ThemeSwitch'
import { LocaleDropdown } from '@/layout/components/LocaleDropdown'

import { LoginForm } from './components'
// import { LoginForm, MobileForm, QrCodeForm, RegisterForm, SSOLoginVue } from './components'

const { t } = useI18n()
const appStore = useAppStore()
const { getPrefixCls } = useDesign()
const prefixCls = getPrefixCls('login')
appStore.setIsDark(false)
</script>

<style lang="scss" scoped>
$prefix-cls: #{$namespace}-login;

.#{$prefix-cls} {
  &__left {
    &::before {
      position: absolute;
      top: 0;
      left: 0;
      z-index: -1;
      width: 100%;
      height: 100%;
      background-image: url('@/assets/svgs/login-bg.svg');
      background-position: center;
      background-repeat: no-repeat;
      content: '';
    }
  }
}

:deep(.login-lang-switch) {
  border: 1px solid rgb(148 163 184 / 55%);
  border-radius: 6px;
  background: #ffffff;
  box-shadow: 0 8px 20px rgb(15 23 42 / 8%);
  color: #1f2937 !important;
}

:deep(.login-lang-switch:hover) {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary) !important;
}
</style>
