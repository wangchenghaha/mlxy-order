import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { createPinia } from 'pinia'
import { i18n } from './lang/index.js'
import './styles.css'
import App from './App.vue'

createApp(App).use(createPinia()).use(i18n).use(ElementPlus).mount('#app')
