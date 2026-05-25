import { defineStore } from 'pinia'
import { store } from '../index'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { CACHE_KEY, useCache } from '@/hooks/web/useCache'
import { LocaleDropdownType } from '@/types/localeDropdown'

const { wsCache } = useCache()

const elLocaleMap = {
  zh_cn: zhCn,
  en_us: en,
  ms_my: en
}

const normalizeLocale = (lang?: string): LocaleType => {
  if (lang === 'zh_cn' || lang === 'en_us' || lang === 'ms_my') return lang
  if (lang === 'zh-CN') return 'zh_cn'
  if (lang === 'en') return 'en_us'
  return 'ms_my'
}

interface LocaleState {
  currentLocale: LocaleDropdownType
  localeMap: LocaleDropdownType[]
}

export const useLocaleStore = defineStore('locales', {
  state: (): LocaleState => {
    const lang = normalizeLocale(wsCache.get(CACHE_KEY.LANG))
    return {
      currentLocale: {
        lang,
        elLocale: elLocaleMap[lang]
      },
      // 多语言
      localeMap: [
        {
          lang: 'ms_my',
          name: 'Bahasa Malaysia'
        },
        {
          lang: 'zh_cn',
          name: '中文'
        },
        {
          lang: 'en_us',
          name: 'English'
        }
      ]
    }
  },
  getters: {
    getCurrentLocale(): LocaleDropdownType {
      return this.currentLocale
    },
    getLocaleMap(): LocaleDropdownType[] {
      return this.localeMap
    }
  },
  actions: {
    setCurrentLocale(localeMap: LocaleDropdownType) {
      // this.locale = Object.assign(this.locale, localeMap)
      const lang = normalizeLocale(localeMap?.lang)
      this.currentLocale.lang = lang
      this.currentLocale.elLocale = elLocaleMap[lang]
      wsCache.set(CACHE_KEY.LANG, lang)
    }
  }
})

export const useLocaleStoreWithOut = () => {
  return useLocaleStore(store)
}
