import { i18n } from '@/plugins/vueI18n'
import { useLocaleStoreWithOut } from '@/store/modules/locale'
import { useAppStoreWithOut } from '@/store/modules/app'
import { setHtmlPageLang } from '@/plugins/vueI18n/helper'
import { loadLocaleMessages } from '@/plugins/vueI18n'

const setI18nLanguage = (locale: LocaleType) => {
  const localeStore = useLocaleStoreWithOut()
  const appStore = useAppStoreWithOut()

  if (i18n.mode === 'legacy') {
    i18n.global.locale = locale
  } else {
    ;(i18n.global.locale as any).value = locale
  }
  localeStore.setCurrentLocale({
    lang: locale
  })
  setHtmlPageLang(locale)
  const appTitle = i18n.global.t('app.title')
  appStore.setTitle(appTitle)
  document.title = appTitle
}

export const useLocale = () => {
  // Switching the language will change the locale of useI18n
  // And submit to configuration modification
  const changeLocale = async (locale: LocaleType) => {
    const globalI18n = i18n.global

    const langModule = await import(`../../locales/${locale}.ts`)
    const backendMessage = await loadLocaleMessages(locale)

    globalI18n.setLocaleMessage(locale, {
      ...langModule.default,
      ...backendMessage
    })

    setI18nLanguage(locale)
  }

  return {
    changeLocale
  }
}
