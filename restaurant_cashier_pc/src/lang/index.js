import { createI18n } from 'vue-i18n'
import { api, unwrap } from '../api.js'

export const i18n = createI18n({
  legacy: false,
  locale: localStorage.getItem('lang') || 'ms_my',
  messages: { ms_my: {}, en_us: {}, zh_cn: {} },
})

export async function loadLang(lang) {
  localStorage.setItem('lang', lang)
  const cached = localStorage.getItem(`i18n_${lang}`)
  if (cached) i18n.global.setLocaleMessage(lang, JSON.parse(cached))
  const data = unwrap(await api.get('/common/i18n/list', { params: { lang } }))
  localStorage.setItem(`i18n_${lang}`, JSON.stringify(data))
  i18n.global.setLocaleMessage(lang, data)
  i18n.global.locale.value = lang
}
