<template>
  <el-row :gutter="20">
    <el-col v-if="showScopeTree" :span="5" :xs="24">
      <ContentWrap class="h-1/1">
        <template #header>
          <div class="font-600">{{ t('restaurant.tenantTree') }}</div>
        </template>
        <el-input v-model="treeKeyword" class="mb-12px" clearable :placeholder="t('restaurant.searchTenant')">
          <template #prefix><Icon icon="ep:search" /></template>
        </el-input>
        <el-tree
          ref="treeRef"
          :data="tenantTree"
          node-key="id"
          default-expand-all
          highlight-current
          :current-node-key="selectedTreeKey"
          :filter-node-method="filterNode"
          :props="{ label: 'label', children: 'children' }"
          @node-click="handleTenantClick"
        />
      </ContentWrap>
    </el-col>

    <el-col :span="showScopeTree ? 19 : 24" :xs="24">
      <ContentWrap>
        <el-form ref="queryFormRef" :inline="true" :model="queryParams" class="-mb-15px">
          <el-form-item :label="pageTitle" prop="keyword">
            <el-input
              v-model="queryParams.keyword"
              class="!w-240px"
              clearable
              :placeholder="t('field.keyword')"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item>
            <el-button @click="handleQuery"><Icon icon="ep:search" />{{ t('action.search') }}</el-button>
            <el-button @click="resetQuery"><Icon icon="ep:refresh" />{{ t('action.reset') }}</el-button>
            <el-button v-if="canMutate" v-hasPermi="[permissionOf('create')]" type="primary" plain @click="openCreate">
              <Icon icon="ep:plus" />{{ t('action.create') }}
            </el-button>
            <el-button type="success" plain @click="exportRows">
              <Icon icon="ep:download" />{{ t('action.export') }}
            </el-button>
          </el-form-item>
        </el-form>
      </ContentWrap>

      <ContentWrap>
        <el-table
          v-loading="loading"
          :data="filteredRows"
          :row-key="rowKey"
          :default-expand-all="section === 'merchant'"
          height="calc(100vh - 280px)"
        >
          <el-table-column
            v-for="column in columns"
            :key="column"
            :prop="column"
            :label="columnLabels[column] || column"
            min-width="140"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <el-tag
                v-if="section === 'orders' && column === 'status'"
                class="order-status-badge"
                :class="orderStatusClass(row.status)"
                disable-transitions
                effect="light"
              >
                {{ formatCell(row, column) }}
              </el-tag>
              <el-image
                v-else-if="section === 'dishes' && column === 'imageUrl' && row.imageUrl"
                class="dish-table-image"
                fit="cover"
                :preview-src-list="[assetUrl(row.imageUrl)]"
                :src="assetUrl(row.imageUrl)"
                preview-teleported
              />
              <span v-else>{{ formatCell(row, column) }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="showActionColumn" :label="t('field.action')" :width="actionColumnWidth" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="section === 'printers'"
                link
                type="success"
                :loading="testingPrinterId === row.id"
                @click="handleTestPrinter(row)"
              >
                {{ t('action.testPrint') }}
              </el-button>
              <el-button v-hasPermi="[permissionOf('update')]" link type="primary" @click="openEdit(row)">
                {{ t('action.edit') }}
              </el-button>
              <el-button v-hasPermi="[permissionOf('delete')]" link type="danger" @click="handleDelete(row)">
                {{ t('action.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="serverPaged" class="mt-12px flex justify-end">
          <el-pagination
            v-model:current-page="pageState.page"
            v-model:page-size="pageState.pageSize"
            :page-sizes="[50, 100, 200]"
            :total="pageState.total"
            layout="total, sizes, prev, pager, next"
            @size-change="handlePageSizeChange"
            @current-change="getList"
          />
        </div>
      </ContentWrap>
    </el-col>
  </el-row>

  <Dialog v-if="section === 'merchant'" v-model="dialogVisible" :title="dialogTitle" width="620px">
    <el-form :model="formData" label-width="110px">
      <el-form-item :label="t('field.type')" required>
        <el-radio-group v-model="formData.type" :disabled="Boolean(formData.id)">
          <el-radio-button label="merchant">{{ t('field.merchant') }}</el-radio-button>
          <el-radio-button label="store">{{ t('field.store') }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <template v-if="formData.type === 'merchant'">
        <el-form-item :label="t('field.name')" required>
          <el-input v-model="formData.nameZh" />
        </el-form-item>
        <el-form-item label="English" required>
          <el-input v-model="formData.nameEn" />
        </el-form-item>
        <el-form-item label="Bahasa" required>
          <el-input v-model="formData.nameMs" />
        </el-form-item>
      </template>
      <template v-else>
        <el-form-item :label="t('field.merchant')" required>
          <el-select v-model="formData.merchantId" class="w-1/1" filterable>
            <el-option
              v-for="merchant in merchantOptions"
              :key="merchant.id"
              :label="merchantDisplayName(merchant)"
              :value="merchant.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('field.code')" required>
          <el-input v-model="formData.code" />
        </el-form-item>
        <el-form-item :label="t('field.name')" required>
          <el-input v-model="formData.name" />
        </el-form-item>
      </template>
      <el-form-item :label="t('field.phone')">
        <el-input v-model="formData.phone" />
      </el-form-item>
      <el-form-item :label="t('field.address')">
        <el-input v-model="formData.address" />
      </el-form-item>
      <el-form-item :label="t('field.status')">
        <el-select v-model="formData.status" class="w-1/1">
          <el-option label="ACTIVE" value="ACTIVE" />
          <el-option label="DISABLED" value="DISABLED" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submitForm">{{ t('common.save') }}</el-button>
    </template>
  </Dialog>

  <Dialog v-if="crudSections.includes(section)" v-model="crudDialogVisible" :title="dialogTitle" width="680px">
    <el-form :model="crudFormData" label-width="120px">
      <el-form-item v-if="needsCrudScope" :label="t('field.merchant')" required>
        <el-select
          v-model="crudFormData.merchantId"
          class="w-1/1"
          filterable
          :disabled="isCrudScopeLocked"
          @change="handleCrudMerchantChange"
        >
          <el-option
            v-for="merchant in scopeMerchants"
            :key="merchant.id"
            :label="merchantDisplayName(merchant)"
            :value="merchant.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="needsCrudScope" :label="t('field.store')" required>
        <el-select
          v-model="crudFormData.storeId"
          class="w-1/1"
          filterable
          :disabled="isCrudScopeLocked"
          @change="handleCrudStoreChange"
        >
          <el-option v-for="store in formStoreOptions" :key="store.id" :label="store.name" :value="store.id" />
        </el-select>
      </el-form-item>

      <template v-if="section === 'tables'">
        <el-form-item :label="t('field.tableNo')" required>
          <el-input v-model="crudFormData.tableNo" />
        </el-form-item>
        <el-form-item :label="t('field.area')">
          <el-input v-model="crudFormData.area" />
        </el-form-item>
        <el-form-item :label="t('field.maxPeople')" required>
          <el-input-number v-model="crudFormData.maxPeople" class="w-1/1" :min="1" />
        </el-form-item>
        <el-form-item :label="t('field.status')">
          <el-select v-model="crudFormData.status" class="w-1/1">
            <el-option v-for="status in tableStatusOptions" :key="status" :label="t(`statusText.${status}`)" :value="status" />
          </el-select>
        </el-form-item>
      </template>

      <template v-if="section === 'categories'">
        <el-form-item :label="t('field.nameZh')" required>
          <el-input v-model="crudFormData.nameZh" />
        </el-form-item>
        <el-form-item :label="t('field.nameEn')" required>
          <el-input v-model="crudFormData.nameEn" />
        </el-form-item>
        <el-form-item :label="t('field.nameMs')" required>
          <el-input v-model="crudFormData.nameMs" />
        </el-form-item>
        <el-form-item :label="t('field.sort')">
          <el-input-number v-model="crudFormData.sortNo" class="w-1/1" :min="0" />
        </el-form-item>
      </template>

      <template v-if="section === 'dishes'">
        <el-form-item :label="t('field.category')" required>
          <el-select v-model="crudFormData.categoryId" class="w-1/1" filterable>
            <el-option
              v-for="category in crudCategories"
              :key="category.id"
              :label="categoryName(category)"
              :value="category.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('field.nameZh')" required>
          <el-input v-model="crudFormData.nameZh" />
        </el-form-item>
        <el-form-item :label="t('field.nameEn')" required>
          <el-input v-model="crudFormData.nameEn" />
        </el-form-item>
        <el-form-item :label="t('field.nameMs')" required>
          <el-input v-model="crudFormData.nameMs" />
        </el-form-item>
        <el-form-item :label="t('field.imageUrl')">
          <div class="dish-image-field">
            <el-upload
              accept="image/jpeg,image/png,image/webp"
              :before-upload="beforeDishImageUpload"
              :http-request="uploadDishImage"
              :show-file-list="false"
            >
              <div v-loading="dishImageUploading" class="dish-image-uploader" :class="{ 'is-filled': crudFormData.imageUrl }">
                <el-image
                  v-if="crudFormData.imageUrl"
                  class="dish-image-preview"
                  fit="cover"
                  :src="assetUrl(crudFormData.imageUrl)"
                />
                <div v-else class="dish-image-placeholder">
                  <Icon icon="ep:plus" />
                  <span>{{ t('action.uploadImage') }}</span>
                </div>
              </div>
            </el-upload>
            <el-button v-if="crudFormData.imageUrl" link type="danger" @click="removeDishImage">
              {{ t('action.removeImage') }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item :label="t('field.price')" required>
          <el-input-number v-model="crudFormData.price" class="w-1/1" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item :label="t('field.spec')">
          <el-input v-model="crudFormData.spec" />
        </el-form-item>
        <el-form-item :label="t('field.stock')">
          <el-input-number v-model="crudFormData.stock" class="w-1/1" :min="0" />
        </el-form-item>
        <el-form-item :label="t('field.status')">
          <el-switch v-model="crudFormData.enabled" :active-text="t('statusText.enabled')" :inactive-text="t('statusText.disabled')" />
        </el-form-item>
      </template>

      <template v-if="section === 'orders'">
        <el-form-item :label="t('field.tableNo')" required>
          <el-select v-model="crudFormData.tableId" class="w-1/1" filterable>
            <el-option v-for="table in crudTables" :key="table.id" :label="table.tableNo" :value="table.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('field.status')">
          <el-select v-model="crudFormData.status" class="w-1/1">
            <el-option v-for="status in orderStatusOptions" :key="status" :label="t(`statusText.${status}`)" :value="status" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('field.totalAmount')">
          <el-input-number v-model="crudFormData.totalAmount" class="w-1/1" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item :label="t('field.remark')">
          <el-input v-model="crudFormData.remark" type="textarea" />
        </el-form-item>
        <el-form-item :label="t('field.cancelReason')">
          <el-input v-model="crudFormData.cancelReason" />
        </el-form-item>
      </template>

      <template v-if="section === 'printers'">
        <el-form-item :label="t('field.name')" required>
          <el-input v-model="crudFormData.name" />
        </el-form-item>
        <el-form-item :label="t('field.type')" required>
          <el-select v-model="crudFormData.type" class="w-1/1">
            <el-option v-for="type in printerTypeOptions" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('field.ip')" required>
          <el-input v-model="crudFormData.ip" />
        </el-form-item>
        <el-form-item :label="t('field.port')" required>
          <el-input-number v-model="crudFormData.port" class="w-1/1" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item :label="t('field.status')">
          <el-switch v-model="crudFormData.enabled" :active-text="t('statusText.enabled')" :inactive-text="t('statusText.disabled')" />
        </el-form-item>
      </template>

      <template v-if="section === 'i18n'">
        <el-form-item :label="t('field.code')" required>
          <el-input v-model="crudFormData.key" />
        </el-form-item>
        <el-form-item label="中文" required>
          <el-input v-model="crudFormData.zhCn" />
        </el-form-item>
        <el-form-item label="English" required>
          <el-input v-model="crudFormData.enUs" />
        </el-form-item>
        <el-form-item label="Bahasa" required>
          <el-input v-model="crudFormData.msMy" />
        </el-form-item>
        <el-form-item :label="t('field.remark')">
          <el-input v-model="crudFormData.remark" type="textarea" />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="crudDialogVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submitCrudForm">{{ t('common.save') }}</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { adminApi } from '@/api/restaurant'
import { apiBaseUrl, getCurrentLocaleCode, issueRestaurantEventTicket, restaurantEventUrl } from '@/api/restaurant/client'

defineOptions({ name: 'RestaurantDataTable' })

const route = useRoute()
const message = useMessage()
const { t } = useI18n()
const loading = ref(false)
const saving = ref(false)
const testingPrinterId = ref<number>()
const dishImageUploading = ref(false)
const queryFormRef = ref()
const treeRef = ref()
const rows = ref<Recordable[]>([])
const tenantTree = ref<Recordable[]>([])
const treeKeyword = ref('')
const selectedTreeKey = ref('')
const selectedScope = ref<Recordable>({})
const scopeMerchants = ref<Recordable[]>([])
const scopeStores = ref<Recordable[]>([])
const queryParams = reactive({ keyword: '' })
const pageState = reactive({ page: 1, pageSize: 100, total: 0 })
const dialogVisible = ref(false)
const crudDialogVisible = ref(false)
const dialogTitle = ref('')
const merchantOptions = ref<Recordable[]>([])
const storeOptions = ref<Recordable[]>([])
const crudCategories = ref<Recordable[]>([])
const crudTables = ref<Recordable[]>([])
let eventSource: EventSource | undefined
let realtimeTimer: ReturnType<typeof setTimeout> | undefined
const formData = reactive<Recordable>({
  type: 'merchant',
  id: undefined,
  merchantId: undefined,
  nameZh: '',
  nameEn: '',
  nameMs: '',
  code: '',
  name: '',
  phone: '',
  address: '',
  status: 'ACTIVE'
})
const crudFormData = reactive<Recordable>({
  id: undefined,
  merchantId: undefined,
  storeId: undefined,
  area: '',
  tableNo: '',
  maxPeople: 4,
  status: 'EMPTY',
  nameZh: '',
  nameEn: '',
  nameMs: '',
  sortNo: 0,
  categoryId: undefined,
  descriptionZh: '',
  descriptionEn: '',
  descriptionMs: '',
  imageUrl: '',
  price: 0,
  spec: '',
  stock: 0,
  enabled: true,
  type: 'KITCHEN',
  name: '',
  ip: '',
  port: 9100,
  key: '',
  zhCn: '',
  enUs: '',
  msMy: '',
  tableId: undefined,
  totalAmount: 0,
  remark: '',
  cancelReason: ''
})

const titleMap: Record<string, string> = {
  merchant: 'restaurant.merchant',
  tables: 'restaurant.tables',
  categories: 'restaurant.categories',
  dishes: 'restaurant.dishes',
  orders: 'restaurant.orders',
  printers: 'restaurant.printers',
  i18n: 'restaurant.i18n',
  logs: 'restaurant.logs'
}

const scopedSections = ['tables', 'categories', 'dishes', 'orders', 'printers']
const crudSections = ['tables', 'categories', 'dishes', 'orders', 'printers', 'i18n']
const readOnlySections = ['logs']
const tableStatusOptions = ['EMPTY', 'DINING', 'PENDING_CHECKOUT', 'CLEANING']
const orderStatusOptions = ['DRAFT', 'PENDING_KITCHEN', 'COOKING', 'SERVED', 'PENDING_CHECKOUT', 'PAID', 'CANCELLED']
const printerTypeOptions = ['KITCHEN', 'FRONT_DESK']

const columnLabelKeys: Record<string, string> = {
  id: 'field.id',
  code: 'field.code',
  name: 'field.name',
  nameZh: 'field.nameZh',
  nameEn: 'field.nameEn',
  nameMs: 'field.nameMs',
  merchantName: 'field.merchant',
  storeName: 'field.store',
  merchantId: 'field.merchant',
  storeId: 'field.store',
  phone: 'field.phone',
  address: 'field.address',
  status: 'field.status',
  tableNo: 'field.tableNo',
  area: 'field.area',
  maxPeople: 'field.maxPeople',
  currentPeople: 'field.currentPeople',
  openedAt: 'field.openedAt',
  currentOrderId: 'field.currentOrderId',
  categoryId: 'field.category',
  price: 'field.price',
  sortNo: 'field.sort',
  descriptionZh: 'field.descriptionZh',
  descriptionEn: 'field.descriptionEn',
  descriptionMs: 'field.descriptionMs',
  imageUrl: 'field.imageUrl',
  spec: 'field.spec',
  stock: 'field.stock',
  enabled: 'statusText.enabled',
  type: 'field.type',
  ip: 'field.ip',
  port: 'field.port',
  tableId: 'field.tableNo',
  waiterId: 'field.waiter',
  waiterName: 'field.waiter',
  totalAmount: 'field.totalAmount',
  orderNo: 'field.orderNo',
  remark: 'field.remark',
  cancelReason: 'field.cancelReason',
  createdAt: 'field.createTime',
  updatedAt: 'field.updateTime',
  key: 'field.code',
  i18nKey: 'field.code',
  zhCn: 'zh_cn',
  enUs: 'en_us',
  msMy: 'ms_my',
  operatorId: 'field.operator',
  action: 'field.actionType',
  detail: 'field.detail',
  createTime: 'field.createTime'
}

const section = computed(() => {
  const parts = route.path.split('/').filter(Boolean)
  return parts[parts.length - 1] || 'merchant'
})

const needsScopeTree = computed(() => scopedSections.includes(section.value))
const needsCrudScope = computed(() => scopedSections.includes(section.value))
const isCrudScopeLocked = computed(() => needsCrudScope.value && Boolean(crudFormData.id))
const canMutate = computed(() => !readOnlySections.includes(section.value))
const showActionColumn = computed(() => canMutate.value)
const actionColumnWidth = computed(() => section.value === 'printers' ? 270 : 190)

const showScopeTree = computed(
  () => needsScopeTree.value && (scopeMerchants.value.length > 1 || scopeStores.value.length > 1)
)

const pageTitle = computed(() => t(titleMap[section.value] || 'field.name'))
const serverPaged = computed(() => ['orders', 'logs'].includes(section.value))

const permissionPrefix = computed(() => {
  if (section.value === 'merchant') return 'system:merchant'
  if (section.value === 'i18n') return 'system:i18n'
  if (section.value === 'logs') return 'system:logs'
  return `restaurant:${section.value}`
})

const permissionOf = (action: string) => `${permissionPrefix.value}:${action}`

const columnLabels = computed(() =>
  Object.fromEntries(Object.entries(columnLabelKeys).map(([key, value]) => [key, t(value)]))
)

const sectionColumns: Record<string, string[]> = {
  tables: [
    'merchantName',
    'storeName',
    'tableNo',
    'area',
    'maxPeople',
    'status',
    'currentPeople',
    'openedAt',
    'currentOrderId'
  ],
  categories: ['merchantName', 'storeName', 'nameZh', 'nameEn', 'nameMs', 'sortNo'],
  dishes: [
    'merchantName',
    'storeName',
    'categoryId',
    'imageUrl',
    'nameZh',
    'nameEn',
    'nameMs',
    'price',
    'spec',
    'stock',
    'enabled'
  ],
  orders: [
    'merchantName',
    'storeName',
    'tableNo',
    'waiterName',
    'status',
    'totalAmount',
    'remark',
    'createdAt',
    'updatedAt'
  ],
  printers: ['merchantName', 'storeName', 'name', 'type', 'ip', 'port', 'enabled'],
  i18n: ['key', 'zhCn', 'enUs', 'msMy', 'remark'],
  logs: ['createdAt', 'operatorId', 'merchantId', 'storeId', 'action', 'detail']
}

const columns = computed(() => {
  const configuredColumns = sectionColumns[section.value]
  if (configuredColumns) {
    return showScopeTree.value
      ? configuredColumns
      : configuredColumns.filter((key) => !['merchantName', 'storeName'].includes(key))
  }
  const first = rows.value[0]
  if (!first) return ['id', 'name', 'status']
  return Object.keys(first).filter((key) => !['children', 'items'].includes(key)).slice(0, 10)
})

const rowKey = (row: Recordable) => row.treeId || row.id || `${row.type}-${row.name}`

const filteredRows = computed(() => {
  const keyword = queryParams.keyword.trim().toLowerCase()
  if (!keyword) return rows.value
  const filter = (items: Recordable[]): Recordable[] =>
    items
      .map((item) => {
        const children = filter(item.children || [])
        const matched = Object.values(item)
          .filter((value) => typeof value !== 'object' && value !== null && value !== undefined)
          .some((value) => String(value).toLowerCase().includes(keyword))
        return matched || children.length ? { ...item, children } : null
      })
      .filter(Boolean) as Recordable[]
  return filter(rows.value)
})

const currentLocale = () => getCurrentLocaleCode()

const merchantDisplayName = (merchant?: Recordable) => {
  if (!merchant) return ''
  const lang = currentLocale()
  if (lang === 'en_us') return merchant.nameEn || merchant.nameZh || merchant.name
  if (lang === 'ms_my') return merchant.nameMs || merchant.nameZh || merchant.name
  return merchant.nameZh || merchant.name
}

const merchantNameById = (merchantId?: number) => {
  const merchant = scopeMerchants.value.find((item) => item.id === merchantId)
  return merchantDisplayName(merchant) || merchantId || ''
}

const storeNameById = (storeId?: number) => {
  const store = scopeStores.value.find((item) => item.id === storeId)
  return store?.name || storeId || ''
}

const formStoreOptions = computed(() =>
  scopeStores.value.filter((store) => !crudFormData.merchantId || store.merchantId === crudFormData.merchantId)
)

const categoryName = (category: Recordable) => {
  const lang = currentLocale()
  if (lang === 'en_us') return category.nameEn || category.nameZh
  if (lang === 'ms_my') return category.nameMs || category.nameZh
  return category.nameZh
}

const withScopeNames = (item: Recordable) => ({
  ...item,
  merchantName: merchantNameById(item.merchantId),
  storeName: storeNameById(item.storeId)
})

const formatCell = (row: Recordable, column: string) => {
  const value = row[column]
  if (value === null || value === undefined || value === '') return '-'
  if (column === 'enabled') return value ? t('statusText.enabled') : t('statusText.disabled')
  if (column === 'status') {
    const statusKey = `statusText.${value}`
    const translated = t(statusKey)
    return translated === statusKey ? value : translated
  }
  return value
}

const orderStatusClass = (status?: string) => {
  const statusClassMap: Record<string, string> = {
    DRAFT: 'is-draft',
    PENDING_KITCHEN: 'is-pending-kitchen',
    COOKING: 'is-cooking',
    SERVED: 'is-served',
    PENDING_CHECKOUT: 'is-pending-checkout',
    PAID: 'is-paid',
    CANCELLED: 'is-cancelled'
  }
  return statusClassMap[status || ''] || 'is-draft'
}

const assetUrl = (url?: string) => {
  if (!url) return ''
  if (/^(https?:)?\/\//.test(url) || url.startsWith('data:') || url.startsWith('blob:')) return url
  const base = apiBaseUrl().replace(/\/api\/?$/, '').replace(/\/$/, '')
  return `${base}/${url.replace(/^\/+/, '')}`
}

const beforeDishImageUpload = (file: File) => {
  const validType = ['image/jpeg', 'image/png', 'image/webp'].includes(file.type)
  if (!validType) {
    message.error(t('message.imageTypeLimit'))
    return false
  }
  const validSize = file.size <= 2 * 1024 * 1024
  if (!validSize) {
    message.error(t('message.imageSizeLimit'))
    return false
  }
  return true
}

const uploadDishImage = async (options: Recordable) => {
  dishImageUploading.value = true
  try {
    const result = await adminApi.uploadDishImage(options.file as File)
    crudFormData.imageUrl = result?.url || ''
    options.onSuccess?.(result)
  } catch (error) {
    options.onError?.(error)
  } finally {
    dishImageUploading.value = false
  }
}

const removeDishImage = () => {
  crudFormData.imageUrl = ''
}

const filterNode = (value: string, data: Recordable) => {
  if (!value) return true
  return String(data.label || '').toLowerCase().includes(value.toLowerCase())
}

watch(treeKeyword, (value) => treeRef.value?.filter(value))

const handleTenantClick = (node: Recordable) => {
  selectedTreeKey.value = node.id
  selectedScope.value =
    node.type === 'store'
      ? { merchantId: node.merchantId, storeId: node.storeId }
      : node.type === 'merchant'
        ? { merchantId: node.merchantId }
      : {}
  pageState.page = 1
  getList()
}

const defaultScope = () => {
  if (scopeStores.value.length === 1) {
    const store = scopeStores.value[0]
    return { merchantId: store.merchantId, storeId: store.id }
  }
  if (scopeMerchants.value.length === 1) {
    return { merchantId: scopeMerchants.value[0].id }
  }
  return {}
}

const buildScopeTree = () =>
  scopeMerchants.value.map((merchant) => ({
    id: `merchant-${merchant.id}`,
    label: merchantDisplayName(merchant),
    type: 'merchant',
    merchantId: merchant.id,
    children: scopeStores.value
      .filter((store) => store.merchantId === merchant.id)
      .map((store) => ({
        id: `store-${store.id}`,
        label: store.name,
        type: 'store',
        merchantId: merchant.id,
        storeId: store.id
      }))
  }))

const loadScopeOptions = async () => {
  if (!needsScopeTree.value) {
    tenantTree.value = []
    selectedScope.value = {}
    selectedTreeKey.value = ''
    if (!scopeMerchants.value.length || !scopeStores.value.length) {
      const [merchants, stores] = await Promise.all([adminApi.merchants(), adminApi.stores()])
      scopeMerchants.value = merchants
      scopeStores.value = stores
    }
    return
  }
  const me = await adminApi.me()
  const merchants = await adminApi.merchants()
  const stores = me.platform
    ? await adminApi.stores()
    : (await Promise.all(merchants.map((merchant) => adminApi.stores({ merchantId: merchant.id })))).flat()
  scopeMerchants.value = merchants
  scopeStores.value = stores
  tenantTree.value = buildScopeTree()
  const firstNode = tenantTree.value[0]
  selectedTreeKey.value = showScopeTree.value ? firstNode?.id || '' : ''
  selectedScope.value = showScopeTree.value ? { merchantId: firstNode?.merchantId } : defaultScope()
}

const getMerchantRows = async () => {
  const merchants = await adminApi.merchants()
  const stores = await adminApi.stores()
  merchantOptions.value = merchants
  storeOptions.value = stores
  return merchants.map((merchant) => ({
    treeId: `merchant-${merchant.id}`,
    id: merchant.id,
    name: merchantDisplayName(merchant),
    type: t('statusText.merchant'),
    phone: merchant.phone,
    address: merchant.address,
    status: merchant.status,
    children: stores
      .filter((store) => store.merchantId === merchant.id)
      .map((store) => ({
        treeId: `store-${store.id}`,
        id: store.id,
        merchantId: store.merchantId,
        name: store.name,
        type: t('statusText.store'),
        code: store.code,
        merchantName: store.merchantName,
        phone: store.phone,
        address: store.address,
        status: store.status
      }))
  }))
}

const getList = async () => {
  loading.value = true
  try {
    if (section.value === 'merchant') {
      rows.value = await getMerchantRows()
      return
    }
    const loader = adminApi[section.value]
    const params = needsScopeTree.value ? { ...selectedScope.value } : {}
    if (serverPaged.value) {
      params.limit = pageState.pageSize
      params.offset = (pageState.page - 1) * pageState.pageSize
    }
    const data = loader ? await loader(params) : []
    rows.value = needsScopeTree.value ? data.map(withScopeNames) : data
    if (serverPaged.value) {
      pageState.total = data.length < pageState.pageSize
        ? (pageState.page - 1) * pageState.pageSize + data.length
        : pageState.page * pageState.pageSize + 1
    } else {
      pageState.total = rows.value.length
    }
  } finally {
    loading.value = false
  }
}

const handleQuery = () => {
  pageState.page = 1
  getList()
}

const handlePageSizeChange = () => {
  pageState.page = 1
  getList()
}

const resetQuery = () => {
  queryFormRef.value?.resetFields()
  queryParams.keyword = ''
  pageState.page = 1
  const firstNode = tenantTree.value[0]
  selectedTreeKey.value = showScopeTree.value ? firstNode?.id || '' : ''
  selectedScope.value = showScopeTree.value ? { merchantId: firstNode?.merchantId } : defaultScope()
  getList()
}

const resetForm = (type: 'merchant' | 'store') => {
  Object.assign(formData, {
    type,
    id: undefined,
    merchantId: merchantOptions.value[0]?.id,
    nameZh: '',
    nameEn: '',
    nameMs: '',
    code: '',
    name: '',
    phone: '',
    address: '',
    status: 'ACTIVE'
  })
}

const resetCrudForm = () => {
  const scope = selectedScope.value.storeId ? selectedScope.value : defaultScope()
  Object.assign(crudFormData, {
    id: undefined,
    merchantId: scope.merchantId || scopeMerchants.value[0]?.id,
    storeId: scope.storeId || undefined,
    area: '',
    tableNo: '',
    maxPeople: 4,
    status: section.value === 'orders' ? 'DRAFT' : 'EMPTY',
    nameZh: '',
    nameEn: '',
    nameMs: '',
    sortNo: 0,
    categoryId: undefined,
    descriptionZh: '',
    descriptionEn: '',
    descriptionMs: '',
    imageUrl: '',
    price: 0,
    spec: '',
    stock: 0,
    enabled: true,
    type: 'KITCHEN',
    name: '',
    ip: '',
    port: 9100,
    key: '',
    zhCn: '',
    enUs: '',
    msMy: '',
    tableId: undefined,
    totalAmount: 0,
    remark: '',
    cancelReason: ''
  })
  if (!crudFormData.storeId && formStoreOptions.value.length === 1) {
    crudFormData.storeId = formStoreOptions.value[0].id
  }
}

const loadCrudOptions = async () => {
  if (!needsCrudScope.value) return
  if (!scopeMerchants.value.length || !scopeStores.value.length) {
    await loadScopeOptions()
  }
  if (section.value === 'dishes' && crudFormData.merchantId && crudFormData.storeId) {
    crudCategories.value = await adminApi.categories({
      merchantId: crudFormData.merchantId,
      storeId: crudFormData.storeId
    })
  } else {
    crudCategories.value = []
  }
  if (section.value === 'orders' && crudFormData.merchantId && crudFormData.storeId) {
    crudTables.value = await adminApi.tables({
      merchantId: crudFormData.merchantId,
      storeId: crudFormData.storeId
    })
  } else {
    crudTables.value = []
  }
}

const handleCrudMerchantChange = async () => {
  crudFormData.storeId = formStoreOptions.value[0]?.id
  crudFormData.categoryId = undefined
  crudFormData.tableId = undefined
  await loadCrudOptions()
}

const handleCrudStoreChange = async () => {
  crudFormData.categoryId = undefined
  crudFormData.tableId = undefined
  await loadCrudOptions()
}

const validateCrudForm = () => {
  if (needsCrudScope.value && (!crudFormData.merchantId || !crudFormData.storeId)) return false
  if (section.value === 'tables') return Boolean(crudFormData.tableNo && crudFormData.maxPeople)
  if (section.value === 'categories') return Boolean(crudFormData.nameZh && crudFormData.nameEn && crudFormData.nameMs)
  if (section.value === 'dishes') {
    return Boolean(crudFormData.categoryId && crudFormData.nameZh && crudFormData.nameEn && crudFormData.nameMs)
  }
  if (section.value === 'orders') return Boolean(crudFormData.tableId)
  if (section.value === 'printers') return Boolean(crudFormData.name && crudFormData.type && crudFormData.ip && crudFormData.port)
  if (section.value === 'i18n') return Boolean(crudFormData.key && crudFormData.zhCn && crudFormData.enUs && crudFormData.msMy)
  return false
}

const openCreate = async () => {
  if (crudSections.includes(section.value)) {
    resetCrudForm()
    await loadCrudOptions()
    dialogTitle.value = t('action.create')
    crudDialogVisible.value = true
    return
  }
  if (section.value !== 'merchant') {
    message.info(t('action.create'))
    return
  }
  if (!merchantOptions.value.length) await getList()
  resetForm('merchant')
  dialogTitle.value = t('action.create')
  dialogVisible.value = true
}

const openEdit = async (row: Recordable) => {
  if (crudSections.includes(section.value)) {
    resetCrudForm()
    Object.assign(crudFormData, {
      ...row,
      status: row.status || (section.value === 'orders' ? 'DRAFT' : 'EMPTY'),
      enabled: row.enabled !== false,
      price: Number(row.price || 0),
      totalAmount: Number(row.totalAmount || 0),
      port: Number(row.port || 9100)
    })
    await loadCrudOptions()
    dialogTitle.value = `${t('action.edit')} ${row.tableNo || row.nameZh || row.id}`
    crudDialogVisible.value = true
    return
  }
  if (section.value !== 'merchant') {
    message.info(`${t('action.edit')} ${row.name || row.nameZh || row.id}`)
    return
  }
  if (String(row.treeId || '').startsWith('merchant-')) {
    const merchant = merchantOptions.value.find((item) => item.id === row.id)
    resetForm('merchant')
    Object.assign(formData, merchant, { type: 'merchant' })
  } else {
    const store = storeOptions.value.find((item) => item.id === row.id)
    resetForm('store')
    Object.assign(formData, store, { type: 'store' })
  }
  dialogTitle.value = `${t('action.edit')} ${row.name || row.nameZh || row.id}`
  dialogVisible.value = true
}

const submitCrudForm = async () => {
  if (!validateCrudForm()) {
    message.warning(t('common.required'))
    return
  }
  const data = { ...crudFormData }
  saving.value = true
  try {
    if (section.value === 'tables') await adminApi.saveTable(data)
    if (section.value === 'categories') await adminApi.saveCategory(data)
    if (section.value === 'dishes') await adminApi.saveDish(data)
    if (section.value === 'orders') await adminApi.saveOrder(data)
    if (section.value === 'printers') await adminApi.savePrinter(data)
    if (section.value === 'i18n') await adminApi.saveI18n(data)
    message.success(t('common.success'))
    crudDialogVisible.value = false
    await getList()
  } finally {
    saving.value = false
  }
}

const submitForm = async () => {
  saving.value = true
  try {
    if (formData.type === 'merchant') {
      await adminApi.saveMerchant({ ...formData })
    } else {
      await adminApi.saveStore({ ...formData })
    }
    message.success(t('common.success'))
    dialogVisible.value = false
    await getList()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (row: Recordable) => {
  if (crudSections.includes(section.value)) {
    await message.delConfirm()
    if (section.value === 'tables') await adminApi.deleteTable(row.id)
    if (section.value === 'categories') await adminApi.deleteCategory(row.id)
    if (section.value === 'dishes') await adminApi.deleteDish(row.id)
    if (section.value === 'orders') await adminApi.deleteOrder(row.id)
    if (section.value === 'printers') await adminApi.deletePrinter(row.id)
    if (section.value === 'i18n') await adminApi.deleteI18n(row.id)
    message.success(t('common.success'))
    await getList()
    return
  }
  if (section.value !== 'merchant') {
    message.info(t('action.delete'))
    return
  }
  await message.delConfirm()
  if (String(row.treeId || '').startsWith('merchant-')) {
    await adminApi.deleteMerchant(row.id)
  } else {
    await adminApi.deleteStore(row.id)
  }
  message.success(t('common.success'))
  await getList()
}

const handleTestPrinter = async (row: Recordable) => {
  testingPrinterId.value = row.id
  try {
    const task = await adminApi.testPrinter(row.id)
    if (task?.status === 'FAILED') {
      message.error(task.errorMessage || t('action.testPrintFailed'))
      return
    }
    message.success(t('action.testPrintSubmitted'))
  } finally {
    testingPrinterId.value = undefined
  }
}

const exportRows = () => {
  const data = filteredRows.value
  const headers = columns.value
  const escapeCsv = (value: unknown) => `"${String(value ?? '').replaceAll('"', '""')}"`
  const csv = [headers.join(','), ...data.map((row) => headers.map((key) => escapeCsv(row[key])).join(','))].join('\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${section.value}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

const initPage = async () => {
  pageState.page = 1
  await loadScopeOptions()
  await getList()
}

const realtimeSections = ['tables', 'orders', 'printers']

const scheduleRealtimeRefresh = () => {
  if (!realtimeSections.includes(section.value) || realtimeTimer) return
  realtimeTimer = setTimeout(async () => {
    realtimeTimer = undefined
    await getList()
  }, 350)
}

const connectRealtime = async () => {
  disconnectRealtime()
  if (typeof EventSource === 'undefined') return
  await issueRestaurantEventTicket()
  eventSource = new EventSource(restaurantEventUrl())
  ;['TABLE_CHANGED', 'ORDER_CHANGED', 'PRINT_TASK_CHANGED'].forEach((eventName) => {
    eventSource?.addEventListener(eventName, scheduleRealtimeRefresh)
  })
  eventSource.onerror = () => {
    disconnectRealtime()
    window.setTimeout(connectRealtime, 3000)
  }
}

const disconnectRealtime = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = undefined
  }
  if (realtimeTimer) {
    clearTimeout(realtimeTimer)
    realtimeTimer = undefined
  }
}

watch(() => route.path, initPage)
onMounted(() => {
  initPage()
  connectRealtime()
})
onBeforeUnmount(disconnectRealtime)
</script>

<style scoped>
.order-status-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 54px;
  height: 24px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: 5px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}

.order-status-badge.is-draft {
  color: #64748b;
  background: #f1f5f9;
  border-color: #e2e8f0;
}

.order-status-badge.is-pending-kitchen {
  color: #f59e0b;
  background: #fff7e6;
  border-color: #ffe0b2;
}

.order-status-badge.is-cooking {
  color: #f59e0b;
  background: #fff7e6;
  border-color: #ffe0b2;
}

.order-status-badge.is-served {
  color: #409eff;
  background: #ecf5ff;
  border-color: #d9ecff;
}

.order-status-badge.is-pending-checkout {
  color: #f59e0b;
  background: #fff7e6;
  border-color: #ffe0b2;
}

.order-status-badge.is-paid {
  color: #52c41a;
  background: #f0ffe9;
  border-color: #d9f7be;
}

.order-status-badge.is-cancelled {
  color: #ff4d4f;
  background: #fff1f0;
  border-color: #ffccc7;
}

.dish-table-image {
  width: 54px;
  height: 54px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #f8fafc;
}

.dish-image-field {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.dish-image-uploader {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 128px;
  height: 96px;
  overflow: hidden;
  color: #64748b;
  cursor: pointer;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  transition: border-color 0.2s ease, color 0.2s ease, background 0.2s ease;
}

.dish-image-uploader:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
  background: #f5f9ff;
}

.dish-image-uploader.is-filled {
  border-style: solid;
  background: #fff;
}

.dish-image-preview {
  width: 100%;
  height: 100%;
}

.dish-image-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
</style>
