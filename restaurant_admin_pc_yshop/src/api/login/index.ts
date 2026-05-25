import type { UserLoginVO } from './types'
import { restaurantClient } from '@/api/restaurant/client'
import { deleteUserCache } from '@/hooks/web/useCache'

export interface SmsCodeVO {
  mobile: string
  scene: number
}

export interface SmsLoginVO {
  mobile: string
  code: string
}

// 登录
export const login = async (data: UserLoginVO & Recordable) => {
  const loginResult = await restaurantClient.post('/common/auth/login', data)
  deleteUserCache()
  return {
    accessToken: loginResult.token,
    refreshToken: loginResult.token
  }
}

// 刷新访问令牌
export const refreshToken = () => {
  return Promise.reject(new Error('Refresh token is not supported by this backend'))
}

// 使用租户名，获得租户编号
export const getTenantIdByName = (name: string) => {
  return Promise.resolve(name)
}

// 使用租户域名，获得租户信息
export const getTenantByWebsite = (website: string) => {
  return Promise.resolve({ website })
}

// 登出
export const loginOut = () => {
  return Promise.resolve()
}

// 获取用户权限信息
export const getInfo = async () => {
  const me = await restaurantClient.get('/admin/me')
  const role = me.currentMembership?.role
  return {
    permissions: me.permissions || [],
    roles: role ? [role] : [],
    user: {
      id: me.user?.id,
      avatar: '',
      nickname: me.user?.displayName || role,
      deptId: me.currentMembership?.storeId || me.currentMembership?.merchantId || 0
    },
    menus: me.menus || []
  }
}

//获取登录验证码
export const sendSmsCode = (data: SmsCodeVO | Recordable) => {
  return restaurantClient.post('/common/auth/sms-code', { phone: data.mobile || data.phone })
}

// 短信验证码登录
export const smsLogin = async (data: SmsLoginVO | Recordable) => {
  const loginResult = await restaurantClient.post('/common/auth/login', {
      loginType: 'SMS',
      phone: data.mobile || data.phone,
      smsCode: data.code || data.smsCode
  })
  deleteUserCache()
  return {
    accessToken: loginResult.token,
    refreshToken: loginResult.token
  }
}

// 社交快捷登录，使用 code 授权码
export function socialLogin(type: string, code: string, state: string) {
  return restaurantClient.post('/system/auth/social-login', {
      type,
      code,
      state
  })
}

// 社交授权的跳转
export const socialAuthRedirect = (type: number, redirectUri: string) => {
  return restaurantClient.get('/system/auth/social-auth-redirect', {
    params: { type, redirectUri }
  })
}
// 获取验证图片以及 token
export const getCode = (data) => {
  return restaurantClient.get('/common/auth/captcha', {
    params: { ...data, _t: Date.now() },
    headers: {
      'Cache-Control': 'no-cache',
      Pragma: 'no-cache'
    }
  })
}

// 滑动或者点选验证
export const reqCheck = (data) => {
  return Promise.resolve(data)
}
