import { defineStore } from 'pinia'
import { store } from '../index'
import { CACHE_KEY, useCache } from '@/hooks/web/useCache'
import { i18n } from '@/plugins/vueI18n'
const { wsCache } = useCache('sessionStorage')

export interface DictValueType {
  value: any
  label: string
  clorType?: string
  cssClass?: string
}
export interface DictTypeType {
  dictType: string
  dictValue: DictValueType[]
}
export interface DictState {
  dictMap: Map<string, any>
  isSetDict: boolean
}

export const useDictStore = defineStore('dict', {
  state: (): DictState => ({
    dictMap: new Map<string, any>(),
    isSetDict: false
  }),
  getters: {
    getDictMap(): Recordable {
      const dictMap = wsCache.get(CACHE_KEY.DICT_CACHE)
      if (dictMap) {
        this.dictMap = dictMap
      }
      return this.dictMap
    },
    getIsSetDict(): boolean {
      return this.isSetDict
    }
  },
  actions: {
    buildRestaurantDictMap() {
      const translate = (key: string) => i18n?.global?.t?.(key) || key
      const dictDataMap = new Map<string, any>()
      dictDataMap['common_status'] = [
        { value: 0, label: '开启', colorType: 'success' },
        { value: 1, label: '关闭', colorType: 'info' }
      ]
      dictDataMap['system_data_scope'] = [
        { value: 'ALL', label: translate('dict.dataScopeAll'), colorType: 'danger' },
        { value: 'MERCHANT', label: translate('dict.dataScopeMerchant'), colorType: 'warning' },
        { value: 'STORE', label: translate('dict.dataScopeStore'), colorType: 'success' }
      ]
      return dictDataMap
    },
    async setDictMap() {
      const dictMap = wsCache.get(CACHE_KEY.DICT_CACHE)
      if (dictMap) {
        this.dictMap = dictMap
        this.isSetDict = true
      } else {
        const dictDataMap = this.buildRestaurantDictMap()
        this.dictMap = dictDataMap
        this.isSetDict = true
        wsCache.set(CACHE_KEY.DICT_CACHE, dictDataMap, { exp: 60 }) // 60 秒 过期
      }
    },
    getDictByType(type: string) {
      if (!this.isSetDict) {
        this.setDictMap()
      }
      return this.dictMap[type]
    },
    async resetDict() {
      wsCache.delete(CACHE_KEY.DICT_CACHE)
      const dictDataMap = this.buildRestaurantDictMap()
      this.dictMap = dictDataMap
      this.isSetDict = true
      wsCache.set(CACHE_KEY.DICT_CACHE, dictDataMap, { exp: 60 }) // 60 秒 过期
    }
  }
})

export const useDictStoreWithOut = () => {
  return useDictStore(store)
}
