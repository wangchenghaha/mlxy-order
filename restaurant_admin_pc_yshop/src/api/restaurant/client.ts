import axios from 'axios'
import { ElMessage } from 'element-plus'
import router, { resetRouter } from '@/router'
import { getAccessToken, removeToken } from '@/utils/auth'
import { restaurantMessages } from '@/locales/restaurant'
import { CACHE_KEY, deleteUserCache, useCache } from '@/hooks/web/useCache'

const { wsCache } = useCache()
let redirectingToLogin = false

const normalizeLocaleCode = (value?: string): LocaleType => {
  if (value === 'zh_cn' || value === 'en_us' || value === 'ms_my') return value
  if (value === 'zh-CN') return 'zh_cn'
  if (value === 'en') return 'en_us'
  return 'ms_my'
}

export const getCurrentLocaleCode = (): LocaleType => {
  return normalizeLocaleCode(wsCache.get(CACHE_KEY.LANG) || 'ms_my')
}

const localText = (key: keyof (typeof restaurantMessages)['ms_my']['loginExt']) => {
  const lang = getCurrentLocaleCode()
  return restaurantMessages[lang]?.loginExt[key] || restaurantMessages.ms_my.loginExt[key]
}

const redirectToLogin = () => {
  if (redirectingToLogin) return
  redirectingToLogin = true
  resetRouter()
  deleteUserCache()
  removeToken()
  sessionStorage.removeItem('restaurant_event_ticket')
  const current = router.currentRoute.value
  const redirect = current?.path && current.path !== '/login' ? current.fullPath : undefined
  router
    .replace({ path: '/login', query: redirect ? { redirect } : undefined })
    .finally(() => {
      window.setTimeout(() => {
        redirectingToLogin = false
      }, 500)
    })
}

export const apiBaseUrl = () => {
  const baseUrl =
    import.meta.env.VITE_BASE_URL ||
    (import.meta.env.DEV ? 'http://127.0.0.1:8080' : window.location.origin)
  const apiUrl = import.meta.env.VITE_API_URL || '/api'
  return `${baseUrl}${apiUrl}`
}

export const restaurantEventUrl = () => {
  const ticket = sessionStorage.getItem('restaurant_event_ticket')
  return `${apiBaseUrl()}/common/events?ticket=${encodeURIComponent(ticket || '')}`
}

export const issueRestaurantEventTicket = async () => {
  const ticket = await restaurantClient.post('/common/events/ticket')
  sessionStorage.setItem('restaurant_event_ticket', ticket.ticket)
  return ticket
}

export const restaurantClient = axios.create({
  baseURL: apiBaseUrl(),
  timeout: 15000
})

restaurantClient.interceptors.request.use((config) => {
  const token = getAccessToken()
  config.headers.lang = getCurrentLocaleCode()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

restaurantClient.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body?.code === 0) return body.data
    if (body?.code === 401) {
      redirectToLogin()
      return Promise.reject(new Error(body?.message || localText('requestFailed')))
    }
    const message = body?.message || body?.msg || localText('requestFailed')
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  },
  (error) => {
    if (error?.response?.status === 401 || error?.response?.data?.code === 401) {
      redirectToLogin()
      return Promise.reject(error)
    }
    ElMessage.error(error?.message || localText('networkFailed'))
    return Promise.reject(error)
  }
)
