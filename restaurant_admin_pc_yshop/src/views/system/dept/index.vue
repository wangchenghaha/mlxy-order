<template>
  <ContentWrap>
    <el-form ref="queryFormRef" :inline="true" :model="queryParams" class="-mb-15px">
      <el-form-item :label="t('field.deptName')" prop="keyword">
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
        <el-button v-hasPermi="['system:dept:create']" type="primary" plain @click="openCreateMerchant">
          <Icon icon="ep:plus" />{{ t('action.create') }}{{ t('field.merchant') }}
        </el-button>
        <el-button type="danger" plain @click="toggleExpandAll">
          <Icon icon="ep:sort" />{{ t('action.expandCollapse') }}
        </el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <ContentWrap>
    <el-table
      v-if="refreshTable"
      v-loading="loading"
      :data="filteredTree"
      :default-expand-all="isExpandAll"
      row-key="id"
      height="calc(100vh - 280px)"
    >
      <el-table-column prop="name" :label="t('field.deptName')" min-width="240" />
      <el-table-column prop="typeName" :label="t('field.type')" min-width="120" />
      <el-table-column prop="parentId" :label="t('field.parentId')" min-width="160" />
      <el-table-column prop="userCount" :label="t('field.userCount')" width="100" />
      <el-table-column prop="status" :label="t('field.status')" width="110" />
      <el-table-column :label="t('field.action')" width="260" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.id !== 'platform'" v-hasPermi="['system:dept:update']" link type="primary" @click="openEdit(row)">
            {{ t('action.edit') }}
          </el-button>
          <el-button
            v-if="row.id === 'platform' || row.id?.startsWith('merchant-')"
            v-hasPermi="['system:dept:create']"
            link
            type="primary"
            @click="openCreateStore(row)"
          >
            {{ t('action.create') }}{{ t('field.store') }}
          </el-button>
          <el-button v-if="row.id !== 'platform'" v-hasPermi="['system:dept:delete']" link type="danger" @click="handleDelete(row)">
            {{ t('action.delete') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </ContentWrap>

  <Dialog v-model="dialogVisible" :title="dialogTitle" width="620px">
    <el-form :model="formData" label-width="110px">
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
            <el-option v-for="merchant in merchants" :key="merchant.id" :label="merchant.nameZh" :value="merchant.id" />
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
</template>

<script setup lang="ts">
import { adminApi } from '@/api/restaurant'

defineOptions({ name: 'SystemDept' })

const message = useMessage()
const { t } = useI18n()
const loading = ref(false)
const saving = ref(false)
const queryFormRef = ref()
const list = ref<Recordable[]>([])
const merchants = ref<Recordable[]>([])
const stores = ref<Recordable[]>([])
const queryParams = reactive({ keyword: '' })
const isExpandAll = ref(true)
const refreshTable = ref(true)
const dialogVisible = ref(false)
const dialogTitle = ref('')
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

const flatRows = computed(() => {
  const rows: Recordable[] = []
  const walk = (items: Recordable[]) => {
    items.forEach((item) => {
      rows.push(item)
      walk(item.children || [])
    })
  }
  walk(list.value)
  return rows
})

const filteredTree = computed(() => {
  const keyword = queryParams.keyword.trim().toLowerCase()
  if (!keyword) return list.value
  const filter = (items: Recordable[]): Recordable[] =>
    items
      .map((item) => {
        const children = filter(item.children || [])
        const matched = [item.name, item.typeName, item.parentId, item.status]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(keyword))
        return matched || children.length ? { ...item, children } : null
      })
      .filter(Boolean) as Recordable[]
  return filter(list.value)
})

const parseId = (id: string) => Number(String(id).split('-')[1])

const getList = async () => {
  loading.value = true
  try {
    list.value = await adminApi.departments()
    merchants.value = await adminApi.merchants()
    stores.value = await adminApi.stores()
  } finally {
    loading.value = false
  }
}

const handleQuery = () => getList()

const resetQuery = () => {
  queryFormRef.value?.resetFields()
  queryParams.keyword = ''
  getList()
}

const resetForm = (type: 'merchant' | 'store') => {
  Object.assign(formData, {
    type,
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
}

const openCreateMerchant = () => {
  resetForm('merchant')
  dialogTitle.value = `${t('action.create')}${t('field.merchant')}`
  dialogVisible.value = true
}

const openCreateStore = (row: Recordable) => {
  resetForm('store')
  formData.merchantId = row.id?.startsWith('merchant-') ? parseId(row.id) : merchants.value[0]?.id
  dialogTitle.value = `${t('action.create')}${t('field.store')}`
  dialogVisible.value = true
}

const openEdit = (row: Recordable) => {
  if (row.id.startsWith('merchant-')) {
    const merchant = merchants.value.find((item) => item.id === parseId(row.id))
    resetForm('merchant')
    Object.assign(formData, merchant, { type: 'merchant' })
  } else {
    const storeId = parseId(row.id)
    const rowStore = stores.value.find((item) => item.id === storeId)
    resetForm('store')
    Object.assign(formData, {
      type: 'store',
      id: storeId,
      merchantId: rowStore?.merchantId || parseId(row.parentId),
      code: rowStore?.code || '',
      name: rowStore?.name || row.name,
      phone: rowStore?.phone || '',
      address: rowStore?.address || '',
      status: rowStore?.status || row.status || 'ACTIVE'
    })
  }
  dialogTitle.value = `${t('action.edit')} ${row.name}`
  dialogVisible.value = true
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
  await message.delConfirm()
  if (row.id.startsWith('merchant-')) {
    await adminApi.deleteMerchant(parseId(row.id))
  } else {
    await adminApi.deleteStore(parseId(row.id))
  }
  message.success(t('common.success'))
  await getList()
}

const toggleExpandAll = () => {
  refreshTable.value = false
  isExpandAll.value = !isExpandAll.value
  nextTick(() => {
    refreshTable.value = true
  })
}

onMounted(getList)
</script>
