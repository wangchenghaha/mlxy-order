import type { App } from 'vue'
import { createI18n } from 'vue-i18n'
import { useLocaleStoreWithOut } from '@/store/modules/locale'
import { useAppStoreWithOut } from '@/store/modules/app'
import type { I18n, I18nOptions } from 'vue-i18n'
import { setHtmlPageLang } from './helper'

export let i18n: ReturnType<typeof createI18n>

const loadBackendMessages = async (locale: LocaleType) => {
  try {
    const baseUrl = `${
      import.meta.env.VITE_BASE_URL ||
      (import.meta.env.DEV ? 'http://127.0.0.1:8080' : window.location.origin)
    }${import.meta.env.VITE_API_URL || '/api'}`
    const response = await fetch(`${baseUrl}/common/i18n/list?lang=${locale}`)
    const body = await response.json()
    return body?.code === 0 ? body.data || {} : {}
  } catch {
    return {}
  }
}

const createI18nOptions = async (): Promise<I18nOptions> => {
  const localeStore = useLocaleStoreWithOut()
  const locale = localeStore.getCurrentLocale
  const localeMap = localeStore.getLocaleMap
  const defaultLocal = await import(`../../locales/${locale.lang}.ts`)
  const backendMessage = await loadBackendMessages(locale.lang)
  const message = {
    ...(defaultLocal.default ?? {}),
    ...backendMessage
  }

  setHtmlPageLang(locale.lang)

  localeStore.setCurrentLocale({
    lang: locale.lang
    // elLocale: elLocal
  })

  return {
    legacy: false,
    locale: locale.lang,
    fallbackLocale: locale.lang,
    messages: {
      [locale.lang]: message
    },
    availableLocales: localeMap.map((v) => v.lang),
    sync: true,
    silentTranslationWarn: true,
    missingWarn: false,
    silentFallbackWarn: true
  }
}

export const setupI18n = async (app: App<Element>) => {
  const options = await createI18nOptions()
  i18n = createI18n(options) as I18n
  const appStore = useAppStoreWithOut()
  const appTitle = i18n.global.t('app.title')
  appStore.setTitle(appTitle)
  document.title = appTitle
  app.use(i18n)
}

export const loadLocaleMessages = loadBackendMessages
