<template>
  <el-row :gutter="20">
    <el-col :span="5" :xs="24">
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
          :filter-node-method="filterNode"
          :props="{ label: 'label', children: 'children' }"
          @node-click="handleTenantClick"
        />
      </ContentWrap>
    </el-col>

    <el-col :span="19" :xs="24">
      <ContentWrap>
        <el-form ref="queryFormRef" :inline="true" :model="queryParams" class="-mb-15px">
          <el-form-item :label="t('field.username')" prop="keyword">
            <el-input
              v-model="queryParams.keyword"
              class="!w-240px"
              clearable
              :placeholder="t('field.keyword')"
              @keyup.enter="handleQuery"
            />
          </el-form-item>
          <el-form-item :label="t('field.status')" prop="enabled">
            <el-select v-model="queryParams.enabled" class="!w-180px" clearable :placeholder="t('field.status')">
              <el-option :label="t('statusText.enabled')" :value="true" />
              <el-option :label="t('statusText.disabled')" :value="false" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button @click="handleQuery"><Icon icon="ep:search" />{{ t('action.search') }}</el-button>
            <el-button @click="resetQuery"><Icon icon="ep:refresh" />{{ t('action.reset') }}</el-button>
            <el-button v-hasPermi="['system:user:create']" type="primary" plain @click="openCreate">
              <Icon icon="ep:plus" />{{ t('action.create') }}
            </el-button>
          </el-form-item>
        </el-form>
      </ContentWrap>

      <ContentWrap>
        <el-table v-loading="loading" :data="filteredList" height="calc(100vh - 320px)">
          <el-table-column prop="id" :label="t('field.userId')" width="100" />
          <el-table-column prop="username" :label="t('field.username')" min-width="140" />
          <el-table-column prop="displayName" :label="t('field.displayName')" min-width="140" />
          <el-table-column prop="phone" :label="t('field.phone')" min-width="150" />
          <el-table-column prop="merchantName" :label="t('field.merchant')" min-width="180" />
          <el-table-column prop="storeName" :label="t('field.store')" min-width="180" />
          <el-table-column prop="role" :label="t('field.role')" min-width="150" />
          <el-table-column :label="t('field.status')" width="120">
            <template #default="{ row }">
              <el-switch
                v-hasPermi="['system:user:update']"
                v-model="row.enabled"
                :active-text="t('statusText.enabled')"
                :inactive-text="t('statusText.disabled')"
                inline-prompt
                @change="handleEnabled(row)"
              />
            </template>
          </el-table-column>
          <el-table-column :label="t('field.action')" width="260" fixed="right">
            <template #default="{ row }">
              <el-button v-hasPermi="['system:user:update']" link type="primary" @click="openEdit(row)">
                <Icon icon="ep:edit" />{{ t('action.edit') }}
              </el-button>
              <el-dropdown>
                <el-button link type="primary"><Icon icon="ep:d-arrow-right" />{{ t('action.more') }}</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-hasPermi="['system:user:password']" @click="handleResetPassword(row)">
                      <Icon icon="ep:key" />{{ t('action.resetPassword') }}
                    </el-dropdown-item>
                    <el-dropdown-item v-hasPermi="['system:user:update']" @click="openEdit(row)">
                      <Icon icon="ep:circle-check" />{{ t('action.assignRole') }}
                    </el-dropdown-item>
                    <el-dropdown-item v-hasPermi="['system:user:delete']" @click="handleDelete(row)">
                      <Icon icon="ep:delete" />{{ t('action.delete') }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
      </ContentWrap>
    </el-col>
  </el-row>

  <Dialog v-model="dialogVisible" :title="dialogTitle" width="620px">
    <el-form ref="formRef" :model="formData" label-width="110px">
      <el-form-item :label="t('field.username')" required>
        <el-input v-model="formData.username" />
      </el-form-item>
      <el-form-item :label="t('field.displayName')" required>
        <el-input v-model="formData.displayName" />
      </el-form-item>
      <el-form-item :label="t('field.phone')" required>
        <el-input v-model="formData.phone" />
      </el-form-item>
      <el-form-item v-if="!formData.id" :label="t('field.password')" required>
        <el-input v-model="formData.password" show-password type="password" />
      </el-form-item>
      <el-form-item :label="t('field.role')" required>
        <el-select v-model="formData.role" class="w-1/1" @change="handleRoleChange">
          <el-option v-for="role in roleOptions" :key="role.value" :label="role.label" :value="role.value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!isPlatformRole(formData.role)" :label="t('field.merchant')" required>
        <el-select v-model="formData.merchantId" class="w-1/1" filterable @change="handleMerchantChange">
          <el-option
            v-for="merchant in merchants"
            :key="merchant.id"
            :label="merchantDisplayName(merchant)"
            :value="merchant.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="requiresStore(formData.role)" :label="t('field.store')" required>
        <el-select v-model="formData.storeId" class="w-1/1" filterable>
          <el-option v-for="store in stores" :key="store.id" :label="store.name" :value="store.id" />
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
import { adminApi, buildMerchantTree } from '@/api/restaurant'
import { getCurrentLocaleCode } from '@/api/restaurant/client'

defineOptions({ name: 'SystemUser' })

const message = useMessage()
const { t } = useI18n()
const loading = ref(false)
const saving = ref(false)
const list = ref<Recordable[]>([])
const tenantTree = ref<Recordable[]>([])
const merchants = ref<Recordable[]>([])
const stores = ref<Recordable[]>([])
const roles = ref<Recordable[]>([])
const treeRef = ref()
const treeKeyword = ref('')
const queryFormRef = ref()
const formRef = ref()
const selectedScope = ref<Recordable>({})
const dialogVisible = ref(false)
const dialogTitle = ref('')

const queryParams = reactive({
  keyword: '',
  enabled: undefined as boolean | undefined
})

const formData = reactive<Recordable>({
  id: undefined,
  membershipId: undefined,
  username: '',
  displayName: '',
  phone: '',
  password: '',
  role: 'WAITER',
  merchantId: undefined,
  storeId: undefined
})

const platformRoles = ['PLATFORM_SUPER_ADMIN', 'PLATFORM_ADMIN']

const roleOptions = computed(() =>
  roles.value.filter((role) => !platformRoles.includes(role.code)).map((role) => ({
    label: role.name || role.code,
    value: role.code
  }))
)

const merchantDisplayName = (merchant: Recordable) => {
  const lang = getCurrentLocaleCode()
  if (lang === 'en_us') return merchant.nameEn || merchant.nameZh || merchant.name
  if (lang === 'ms_my') return merchant.nameMs || merchant.nameZh || merchant.name
  return merchant.nameZh || merchant.nameEn || merchant.name
}

const filteredList = computed(() => {
  const keyword = queryParams.keyword.trim().toLowerCase()
  return list.value.filter((item) => {
    if (isPlatformAccount(item)) return false
    const matchKeyword =
      !keyword ||
      [item.username, item.displayName, item.phone, item.merchantName, item.storeName, item.role]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword))
    const matchStatus = queryParams.enabled === undefined || item.enabled === queryParams.enabled
    return matchKeyword && matchStatus
  })
})

const isPlatformRole = (role?: string) => platformRoles.includes(role || '')
const isPlatformAccount = (item: Recordable) =>
  isPlatformRole(item.role) || !item.merchantId || ['admin', 'platform'].includes(String(item.username || '').toLowerCase())
const requiresStore = (role?: string) => !isPlatformRole(role) && !['MERCHANT_OWNER', 'MERCHANT_ADMIN'].includes(role || '')

const filterNode = (value: string, data: Recordable) => {
  if (!value) return true
  return data.label?.includes(value)
}

watch(treeKeyword, (value) => treeRef.value?.filter(value))

const handleTenantClick = (node: Recordable) => {
  selectedScope.value =
    node.type === 'store'
      ? { merchantId: node.merchantId, storeId: node.storeId }
      : node.type === 'merchant'
        ? { merchantId: node.merchantId }
        : {}
  getList()
}

const loadBaseOptions = async () => {
  const [merchantList, roleList] = await Promise.all([adminApi.merchants(), adminApi.roles()])
  merchants.value = merchantList
  roles.value = roleList
  stores.value = await adminApi.stores({ merchantId: formData.merchantId })
}

const getTree = async () => {
  try {
    tenantTree.value = await buildMerchantTree()
  } catch {
    tenantTree.value = []
  }
}

const getList = async () => {
  loading.value = true
  try {
    const data = await adminApi.users(selectedScope.value)
    list.value = data.filter((item) => !isPlatformAccount(item))
  } finally {
    loading.value = false
  }
}

const handleQuery = () => getList()

const resetQuery = () => {
  queryFormRef.value?.resetFields()
  queryParams.keyword = ''
  queryParams.enabled = undefined
  selectedScope.value = {}
  getList()
}

const resetForm = () => {
  Object.assign(formData, {
    id: undefined,
    membershipId: undefined,
    username: '',
    displayName: '',
    phone: '',
    password: 'Aa123456',
    role: 'WAITER',
    merchantId: selectedScope.value.merchantId,
    storeId: selectedScope.value.storeId
  })
}

const openCreate = async () => {
  resetForm()
  await loadBaseOptions()
  dialogTitle.value = t('action.create')
  dialogVisible.value = true
}

const openEdit = async (row: Recordable) => {
  Object.assign(formData, {
    id: row.id,
    membershipId: row.membershipId,
    username: row.username,
    displayName: row.displayName,
    phone: row.phone,
    password: '',
    role: row.role,
    merchantId: row.merchantId,
    storeId: row.storeId
  })
  await loadBaseOptions()
  dialogTitle.value = `${t('action.edit')} ${row.username}`
  dialogVisible.value = true
}

const handleRoleChange = () => {
  if (isPlatformRole(formData.role)) {
    formData.merchantId = undefined
    formData.storeId = undefined
  } else if (!requiresStore(formData.role)) {
    formData.storeId = undefined
  }
}

const handleMerchantChange = async () => {
  formData.storeId = undefined
  stores.value = await adminApi.stores({ merchantId: formData.merchantId })
}

const validateForm = () => {
  if (!formData.username || !formData.displayName || !formData.phone) return false
  if (!formData.id && !formData.password) return false
  if (!formData.role) return false
  if (!isPlatformRole(formData.role) && !formData.merchantId) return false
  if (requiresStore(formData.role) && !formData.storeId) return false
  return true
}

const submitForm = async () => {
  if (!validateForm()) {
    message.warning(t('common.required'))
    return
  }
  saving.value = true
  try {
    await adminApi.saveUser({ ...formData })
    message.success(t('common.success'))
    dialogVisible.value = false
    await Promise.all([getTree(), getList()])
  } finally {
    saving.value = false
  }
}

const handleEnabled = async (row: Recordable) => {
  await adminApi.setUserEnabled(row.id, row.enabled)
  message.success(t('common.success'))
}

const handleResetPassword = async (row: Recordable) => {
  const { value } = await message.prompt(t('field.newPassword'), t('action.resetPassword'))
  await adminApi.resetUserPassword(row.id, value || 'Aa123456', row.membershipId)
  message.success(t('common.success'))
}

const handleDelete = async (row: Recordable) => {
  await message.delConfirm()
  await adminApi.deleteUser(row.id, row.membershipId)
  message.success(t('common.success'))
  await Promise.all([getTree(), getList()])
}

onMounted(() => {
  loadBaseOptions()
  getTree()
  getList()
})
</script>
