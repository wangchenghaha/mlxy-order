<template>
  <ContentWrap>
    <el-form ref="queryFormRef" :inline="true" :model="queryParams" class="-mb-15px">
      <el-form-item :label="t('field.roleName')" prop="keyword">
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
        <el-button v-hasPermi="['system:role:create']" type="primary" plain @click="openCreate">
          <Icon icon="ep:plus" />{{ t('action.create') }}
        </el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <ContentWrap>
    <el-table v-loading="loading" :data="filteredList" height="calc(100vh - 280px)">
      <el-table-column prop="code" :label="t('field.roleCode')" min-width="190" />
      <el-table-column prop="name" :label="t('field.roleName')" min-width="160" />
      <el-table-column :label="t('field.scope')" min-width="140">
        <template #default="{ row }">
          <DictTag :type="DICT_TYPE.SYSTEM_DATA_SCOPE" :value="row.dataScope || 'STORE'" />
        </template>
      </el-table-column>
      <el-table-column prop="description" :label="t('field.description')" min-width="260" />
      <el-table-column :label="t('field.status')" width="90">
        <template #default>
          <el-tag type="success">{{ t('statusText.enabled') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('field.action')" width="300" fixed="right">
        <template #default="{ row }">
          <el-button v-hasPermi="['system:role:update']" link type="primary" @click="openEdit(row)">
            {{ t('action.edit') }}
          </el-button>
          <el-button v-hasPermi="['system:role:update']" link type="primary" @click="openMenuPermission(row)">
            {{ t('action.menuPermission') }}
          </el-button>
          <el-button v-hasPermi="['system:role:delete']" link type="danger" @click="handleDelete(row)">
            {{ t('action.delete') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </ContentWrap>

  <Dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
    <el-form :model="formData" label-width="110px">
      <el-form-item :label="t('field.roleCode')" required>
        <el-input v-model="formData.code" :disabled="Boolean(editingCode)" />
      </el-form-item>
      <el-form-item :label="t('field.roleName')" required>
        <el-input v-model="formData.name" />
      </el-form-item>
      <el-form-item :label="t('field.scope')" required>
        <el-select v-model="formData.dataScope" class="w-1/1">
          <el-option
            v-for="item in dataScopeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('field.description')">
        <el-input v-model="formData.description" type="textarea" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" @click="submitForm">{{ t('common.save') }}</el-button>
    </template>
  </Dialog>

  <Dialog v-model="permissionVisible" :title="permissionTitle" width="720px">
    <el-form label-width="110px">
      <el-form-item :label="t('action.menuPermission')">
        <el-tree
          ref="menuTreeRef"
          :data="permissionMenus"
          node-key="code"
          show-checkbox
          default-expand-all
          :props="{ label: 'name', children: 'children' }"
        />
      </el-form-item>
      <el-form-item :label="t('action.buttonPermission')">
        <div class="permission-groups">
          <div v-for="group in operationPermissionGroups" :key="group.code" class="permission-group">
            <div class="permission-group__title">{{ group.name }}</div>
            <el-checkbox-group v-model="checkedPermissions">
              <el-checkbox
                v-for="permission in group.permissions"
                :key="permission.value"
                :label="permission.value"
              >
                {{ permission.label }}
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="permissionVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" @click="savePermission">{{ t('common.save') }}</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { adminApi } from '@/api/restaurant'
import { DictTag } from '@/components/DictTag'
import { DICT_TYPE, getStrDictOptions } from '@/utils/dict'

defineOptions({ name: 'SystemRole' })

const message = useMessage()
const { t } = useI18n()
const loading = ref(false)
const queryFormRef = ref()
const list = ref<Recordable[]>([])
const queryParams = reactive({ keyword: '' })
const dialogVisible = ref(false)
const dialogTitle = ref('')
const editingCode = ref('')
const formData = reactive<Recordable>({
  code: '',
  name: '',
  scope: '',
  description: '',
  dataScope: 'STORE',
  status: 'ACTIVE'
})
const permissionVisible = ref(false)
const permissionTitle = ref('')
const permissionRole = ref<Recordable>({})
const permissionMenus = ref<Recordable[]>([])
const rawMenus = ref<Recordable[]>([])
const checkedPermissions = ref<string[]>([])
const menuTreeRef = ref()

const operationSuffixes = [
  { suffix: 'view', label: () => t('action.view') },
  { suffix: 'create', label: () => t('action.create') },
  { suffix: 'update', label: () => t('action.edit') },
  { suffix: 'delete', label: () => t('action.delete') },
  { suffix: 'password', label: () => t('action.resetPassword') }
]

const dataScopeOptions = computed(() => getStrDictOptions(DICT_TYPE.SYSTEM_DATA_SCOPE))

const filteredList = computed(() => {
  const keyword = queryParams.keyword.trim().toLowerCase()
  if (!keyword) return list.value
  return list.value.filter((item) =>
    [item.code, item.name, item.scope, item.description]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword))
  )
})

const getList = async () => {
  loading.value = true
  try {
    list.value = await adminApi.roles()
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

const resetForm = () => {
  editingCode.value = ''
  Object.assign(formData, {
    code: '',
    name: '',
    scope: '',
    description: '',
    dataScope: 'STORE',
    status: 'ACTIVE'
  })
}

const openCreate = () => {
  resetForm()
  dialogTitle.value = t('action.create')
  dialogVisible.value = true
}

const openEdit = (row: Recordable) => {
  editingCode.value = row.code
  Object.assign(formData, row)
  dialogTitle.value = `${t('action.edit')} ${row.name}`
  dialogVisible.value = true
}

const submitForm = async () => {
  if (!formData.code || !formData.name || !formData.dataScope) {
    message.warning(t('common.required'))
    return
  }
  await adminApi.saveRole({
    ...formData,
    scope: getStrDictOptions(DICT_TYPE.SYSTEM_DATA_SCOPE).find((item) => item.value === formData.dataScope)?.label || formData.dataScope,
    status: formData.status || 'ACTIVE'
  })
  message.success(t('common.success'))
  dialogVisible.value = false
  await getList()
}

const handleDelete = async (row: Recordable) => {
  await message.delConfirm()
  await adminApi.deleteRole(row.code)
  message.success(t('common.success'))
  await getList()
}

const buildTree = (items: Recordable[]) => {
  const map = new Map<string, Recordable>()
  items.forEach((item) =>
    map.set(item.code, {
      ...item,
      name: t(item.name || item.title || item.code),
      children: []
    })
  )
  const roots: Recordable[] = []
  map.forEach((item) => {
    if (item.parentCode && map.has(item.parentCode)) {
      map.get(item.parentCode)?.children.push(item)
    } else {
      roots.push(item)
    }
  })
  return roots
}

const operationPermissionGroups = computed(() =>
  rawMenus.value
    .filter((menu) => menu.permission)
    .map((menu) => {
      const base = String(menu.permission).split(':').slice(0, -1).join(':')
      return {
        code: menu.code,
        name: t(menu.name),
        permissions: operationSuffixes.map((item) => ({
          value: `${base}:${item.suffix}`,
          label: item.label()
        }))
      }
    })
)

const openMenuPermission = async (row: Recordable) => {
  permissionRole.value = row
  permissionTitle.value = `${t('action.menuPermission')} ${row.name}`
  rawMenus.value = await adminApi.menus()
  permissionMenus.value = buildTree(rawMenus.value)
  checkedPermissions.value = [...(row.permissions || [])]
  permissionVisible.value = true
  nextTick(() => {
    menuTreeRef.value?.setCheckedKeys(row.menuCodes || [])
  })
}

const savePermission = async () => {
  const checked = menuTreeRef.value?.getCheckedKeys?.() || []
  const halfChecked = menuTreeRef.value?.getHalfCheckedKeys?.() || []
  const menuCodes = [...new Set([...checked, ...halfChecked])]
  await adminApi.assignRoleMenus(permissionRole.value.code, {
    menuCodes,
    permissions: checkedPermissions.value
  })
  message.success(t('common.success'))
  permissionVisible.value = false
  await getList()
}

onMounted(getList)
</script>

<style scoped>
.permission-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 260px;
  overflow-y: auto;
}

.permission-group {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding-bottom: 8px;
}

.permission-group__title {
  color: var(--el-text-color-primary);
  font-weight: 600;
  margin-bottom: 6px;
}
</style>
