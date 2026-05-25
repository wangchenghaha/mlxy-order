<template>
  <div>
    <ContentWrap>
      <div class="dashboard-head">
        <div>
          <div class="dashboard-title">{{ t('restaurant.platformOverview') }}</div>
          <div class="dashboard-subtitle">{{ t('restaurant.dashboardScopeTip') }}</div>
        </div>
        <div class="dashboard-actions">
          <el-select
            v-if="showScopeSwitch"
            v-model="selectedKey"
            class="scope-select"
            filterable
            :placeholder="t('restaurant.scopeSwitch')"
            @change="handleScopeSelect"
          >
            <el-option
              v-for="item in scopeSelectOptions"
              :key="item.id"
              :label="item.label"
              :value="item.id"
            >
              <div class="scope-option">
                <Icon :icon="item.icon" :size="15" />
                <span>{{ item.label }}</span>
                <el-tag size="small" effect="plain">{{ scopeTypeText(item.type) }}</el-tag>
              </div>
            </el-option>
          </el-select>
          <el-button :loading="loading" @click="refreshAll">
            <Icon icon="ep:refresh" />{{ t('action.reset') }}
          </el-button>
        </div>
      </div>
    </ContentWrap>

    <el-row :gutter="16">
      <el-col v-for="item in globalMetrics" :key="item.label" :xs="24" :sm="12" :lg="6">
        <ContentWrap class="metric-card">
          <div class="metric-title">{{ item.label }}</div>
          <div class="metric-value">{{ item.value }}</div>
          <div class="metric-sub">{{ item.sub }}</div>
        </ContentWrap>
      </el-col>
    </el-row>

    <ContentWrap v-if="showScopeBrowser" class="mt-16px">
      <Transition name="fade" mode="out-in">
        <div :key="selectedKey" class="scope-detail">
          <div class="scope-header">
            <div>
              <div class="font-600">{{ selectedLabel }}</div>
              <div class="scope-hint">{{ t('restaurant.selectedScope') }}</div>
            </div>
            <el-tag :type="selectedTagType">{{ selectedTypeLabel }}</el-tag>
          </div>
          <el-skeleton v-if="scopeLoading" :rows="6" animated />
          <template v-else>
            <el-row :gutter="12">
              <el-col v-for="item in selectedMetrics" :key="item.label" :xs="24" :sm="12" :lg="6">
                <div class="mini-metric">
                  <div class="metric-title">{{ item.label }}</div>
                  <div class="mini-value">{{ item.value }}</div>
                </div>
              </el-col>
            </el-row>
            <el-table :data="selectedRank" class="mt-14px" height="248">
              <el-table-column type="index" width="70" :label="t('restaurant.rank')" />
              <el-table-column prop="name" :label="t('restaurant.dish')" />
              <el-table-column prop="quantity" :label="t('restaurant.sales')" width="120" />
            </el-table>
          </template>
        </div>
      </Transition>
    </ContentWrap>
  </div>
</template>

<script setup lang="ts">
import { adminApi } from '@/api/restaurant'
import { getCurrentLocaleCode } from '@/api/restaurant/client'

defineOptions({ name: 'Index' })

const { t } = useI18n()
const loading = ref(false)
const scopeLoading = ref(false)
const treeRef = ref()
const me = ref<Recordable>({})
const merchants = ref<Recordable[]>([])
const stores = ref<Recordable[]>([])
const selectedKey = ref('all')
const dashboardCache = reactive<Record<string, Recordable>>({})
const globalSummary = ref<Recordable>({
  paidOrders: 0,
  todayOrders: 0,
  revenue: 0,
  totalOrders: 0,
  totalRevenue: 0,
  todayRevenue: 0,
  openOrders: 0,
  pendingAmount: 0,
  tableUsage: 0,
  dishRank: []
})
const selectedSummary = ref<Recordable>({ ...globalSummary.value })

const money = (value: unknown) => `RM ${Number(value || 0).toFixed(2)}`

const normalizeRank = (dishRank: unknown) => {
  if (Array.isArray(dishRank)) return dishRank
  return Object.entries((dishRank || {}) as Record<string, number>)
    .map(([name, quantity]) => ({ name, quantity }))
    .sort((a, b) => Number(b.quantity) - Number(a.quantity))
}

const normalizeSummary = (data: Recordable = {}) => ({
  paidOrders: Number(data.paidOrders || 0),
  todayOrders: Number(data.todayOrders || 0),
  revenue: Number(data.revenue || 0),
  totalOrders: Number(data.totalOrders ?? data.paidOrders ?? 0),
  totalRevenue: Number(data.totalRevenue ?? data.revenue ?? 0),
  todayRevenue: Number(data.todayRevenue || 0),
  openOrders: Number(data.openOrders || 0),
  pendingAmount: Number(data.pendingAmount || 0),
  tableUsage: Number(data.tableUsage || data.occupiedTables || 0),
  dishRank: normalizeRank(data.dishRank)
})

const merchantName = (merchant: Recordable) => {
  const lang = getCurrentLocaleCode()
  if (lang === 'en_us') return merchant.nameEn || merchant.nameZh
  if (lang === 'ms_my') return merchant.nameMs || merchant.nameZh
  return merchant.nameZh || merchant.name
}

const scopeOf = (node: Recordable) => {
  if (node.type === 'store') return { merchantId: node.merchantId, storeId: node.storeId }
  if (node.type === 'merchant') return { merchantId: node.merchantId }
  return {}
}

const keyOf = (node: Recordable) => {
  if (node.type === 'store') return `store-${node.storeId}`
  if (node.type === 'merchant') return `merchant-${node.merchantId}`
  return 'all'
}

const selectedNode = computed(
  () => scopeTreeFlat.value.find((item) => item.id === selectedKey.value) || scopeTreeFlat.value[0]
)

const selectedLabel = computed(() => selectedNode.value?.label || t('restaurant.allAccessibleData'))

const selectedTypeLabel = computed(() => scopeTypeText(selectedNode.value?.type || 'all'))

const selectedTagType = computed(() => {
  if (selectedNode.value?.type === 'store') return 'success'
  if (selectedNode.value?.type === 'merchant') return 'warning'
  return undefined
})

const showScopeBrowser = computed(() => merchants.value.length > 1 || stores.value.length > 1)
const showScopeSwitch = computed(() => scopeSelectOptions.value.length > 1)

const globalMetrics = computed(() => metricItems(globalSummary.value, t('restaurant.allAccessibleData')))

const selectedMetrics = computed(() => metricItems(selectedSummary.value, selectedLabel.value))

const selectedRank = computed(() => normalizeSummary(selectedSummary.value).dishRank)

const metricItems = (summary: Recordable, sub: string) => [
  { label: t('restaurant.todayRevenue'), value: money(summary.todayRevenue), sub: t('restaurant.realtime') },
  { label: t('restaurant.todayOrders'), value: summary.todayOrders || 0, sub: t('restaurant.realtime') },
  { label: t('restaurant.totalRevenue'), value: money(summary.totalRevenue ?? summary.revenue), sub },
  { label: t('restaurant.totalOrders'), value: summary.totalOrders ?? summary.paidOrders ?? 0, sub },
  { label: t('restaurant.pendingAmount'), value: money(summary.pendingAmount), sub: t('restaurant.realtime') },
  { label: t('restaurant.openOrders'), value: summary.openOrders || 0, sub: t('restaurant.realtime') },
  { label: t('restaurant.occupiedTables'), value: summary.tableUsage || 0, sub: t('restaurant.realtime') }
]

const scopeTypeText = (type: string) => {
  if (type === 'merchant') return t('field.merchant')
  if (type === 'store') return t('field.store')
  return t('restaurant.allAccessibleData')
}

const scopeTree = computed(() => [
  {
    id: 'all',
    label: t('restaurant.allAccessibleData'),
    type: 'all',
    icon: 'ep:data-analysis',
    children: merchants.value.map((merchant) => ({
      id: `merchant-${merchant.id}`,
      label: merchantName(merchant),
      type: 'merchant',
      icon: 'ep:shop',
      merchantId: merchant.id,
      children: stores.value
        .filter((store) => store.merchantId === merchant.id)
        .map((store) => ({
          id: `store-${store.id}`,
          label: store.name,
          type: 'store',
          icon: 'ep:office-building',
          merchantId: merchant.id,
          storeId: store.id
        }))
    }))
  }
])

const scopeTreeFlat = computed(() => {
  const rows: Recordable[] = []
  const walk = (nodes: Recordable[]) => {
    nodes.forEach((node) => {
      rows.push(node)
      walk(node.children || [])
    })
  }
  walk(scopeTree.value)
  return rows
})

const scopeSelectOptions = computed(() =>
  scopeTreeFlat.value.map((node) => ({
    id: node.id,
    label: node.label,
    type: node.type,
    icon: node.icon
  }))
)

const fetchDashboard = async (node: Recordable) => {
  const key = keyOf(node)
  if (!dashboardCache[key]) {
    dashboardCache[key] = normalizeSummary(await adminApi.dashboard(scopeOf(node)))
  }
  return dashboardCache[key]
}

const setSelectedNode = async (node: Recordable, forceLoading = false) => {
  selectedKey.value = keyOf(node)
  const key = keyOf(node)
  const cached = dashboardCache[key]
  if (cached && !forceLoading) {
    selectedSummary.value = cached
    await nextTick()
    treeRef.value?.setCurrentKey?.(selectedKey.value)
    return
  }
  scopeLoading.value = true
  try {
    selectedSummary.value = await fetchDashboard(node)
  } finally {
    scopeLoading.value = false
    await nextTick()
    treeRef.value?.setCurrentKey?.(selectedKey.value)
  }
}

const handleScopeClick = async (node: Recordable) => {
  await setSelectedNode(node)
}

const handleScopeSelect = async (key: string) => {
  const node = scopeTreeFlat.value.find((item) => item.id === key)
  if (node) await setSelectedNode(node)
}

const loadStores = async () => {
  if (me.value.platform) return adminApi.stores()
  const grouped = await Promise.all(merchants.value.map((merchant) => adminApi.stores({ merchantId: merchant.id })))
  return grouped.flat()
}

const loadGlobalSummary = async () => {
  globalSummary.value = normalizeSummary(await adminApi.dashboard())
  dashboardCache.all = globalSummary.value
}

const refreshAll = async () => {
  Object.keys(dashboardCache).forEach((key) => delete dashboardCache[key])
  await loadData()
}

const loadData = async () => {
  loading.value = true
  try {
    me.value = await adminApi.me()
    merchants.value = await adminApi.merchants()
    stores.value = await loadStores()
    await loadGlobalSummary()
    const initialNode = showScopeBrowser.value
      ? scopeTreeFlat.value.find((node) => node.id === 'all')
      : scopeTreeFlat.value.find((node) => node.type === 'store') || scopeTreeFlat.value.find((node) => node.type === 'merchant')
    if (initialNode) {
      await setSelectedNode(initialNode)
    } else {
      selectedKey.value = 'all'
      selectedSummary.value = globalSummary.value
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.dashboard-head,
.scope-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dashboard-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.scope-select {
  width: 280px;
}

.scope-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.scope-option span {
  flex: 1;
}

.dashboard-title {
  font-size: 20px;
  font-weight: 700;
}

.dashboard-subtitle,
.scope-hint {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.metric-card {
  min-height: 130px;
}

.metric-title {
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.metric-value {
  margin-top: 14px;
  color: var(--el-text-color-primary);
  font-size: 30px;
  font-weight: 700;
}

.metric-sub {
  margin-top: 10px;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.scope-detail {
  min-height: 360px;
}

.scope-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.mini-metric {
  min-height: 92px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 14px;
  background: var(--el-fill-color-lighter);
}

.mini-value {
  margin-top: 10px;
  font-size: 22px;
  font-weight: 700;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

@media (max-width: 768px) {
  .dashboard-head,
  .dashboard-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .scope-select {
    width: 100%;
  }
}
</style>
