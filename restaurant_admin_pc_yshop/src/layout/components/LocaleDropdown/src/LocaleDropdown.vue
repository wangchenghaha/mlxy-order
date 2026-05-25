<script lang="ts" setup>
import { useLocaleStore } from '@/store/modules/locale'
import { useLocale } from '@/hooks/web/useLocale'
import { propTypes } from '@/utils/propTypes'
import { useDesign } from '@/hooks/web/useDesign'
import { deleteUserCache } from '@/hooks/web/useCache'

defineOptions({ name: 'LocaleDropdown' })

const { getPrefixCls } = useDesign()

const prefixCls = getPrefixCls('locale-dropdown')

const props = defineProps({
  color: propTypes.string.def(''),
  showName: propTypes.bool.def(false)
})

const localeStore = useLocaleStore()

const langMap = computed(() => localeStore.getLocaleMap)

const currentLang = computed(() => localeStore.getCurrentLocale)

const currentLangName = computed(
  () => unref(langMap).find((item) => item.lang === unref(currentLang).lang)?.name || unref(currentLang).lang
)

const setLang = async (lang: LocaleType) => {
  if (lang === unref(currentLang).lang) return
  localeStore.setCurrentLocale({
    lang
  })
  const { changeLocale } = useLocale()
  await changeLocale(lang)
  deleteUserCache()
  window.location.reload()
}
</script>

<template>
  <ElDropdown :class="prefixCls" trigger="click" @command="setLang">
    <span :class="[$attrs.class, `${prefixCls}__trigger`, { 'is-text': props.showName }]">
      <Icon :color="color" :size="18" class="!p-0" icon="ion:language-sharp" />
      <span v-if="props.showName" class="ml-6px">{{ currentLangName }}</span>
    </span>
    <template #dropdown>
      <ElDropdownMenu>
        <ElDropdownItem v-for="item in langMap" :key="item.lang" :command="item.lang">
          {{ item.name }}
        </ElDropdownItem>
      </ElDropdownMenu>
    </template>
  </ElDropdown>
</template>

<style lang="scss" scoped>
$prefix-cls: #{$namespace}-locale-dropdown;

.#{$prefix-cls} {
  &__trigger {
    display: inline-flex;
    align-items: center;
    cursor: pointer;
    line-height: 1;
  }

  &__trigger.is-text {
    border: 1px solid rgb(255 255 255 / 45%);
    border-radius: 4px;
    padding: 8px 10px;
    font-size: 14px;
    background: rgb(255 255 255 / 12%);
  }
}
</style>
