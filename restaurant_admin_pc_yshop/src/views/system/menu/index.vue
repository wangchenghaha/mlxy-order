<template>
  <ContentWrap>
    <el-form ref="queryFormRef" :inline="true" :model="queryParams" class="-mb-15px">
      <el-form-item :label="t('field.menuName')" prop="keyword">
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
        <el-button v-hasPermi="['system:menu:create']" type="primary" plain @click="openCreate()">
          <Icon icon="ep:plus" />{{ t('action.create') }}
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
      <el-table-column :label="t('field.menuName')" min-width="220">
        <template #default="{ row }">
          {{ getMenuName(row.name) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('field.icon')" width="100" align="center">
        <template #default="{ row }">
          <el-tooltip v-if="row.icon" :content="row.icon" placement="top">
            <Icon :icon="row.icon" :size="18" />
          </el-tooltip>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="sortNo" :label="t('field.sort')" width="80" />
      <el-table-column prop="code" :label="t('field.code')" min-width="160" />
      <el-table-column :label="t('field.parentMenu')" min-width="160">
        <template #default="{ row }">
          {{ getParentMenuName(row.parentCode) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('field.status')" width="90">
        <template #default>
          <el-tag type="success">{{ t('statusText.visible') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('field.action')" width="210" fixed="right">
        <template #default="{ row }">
          <el-button v-hasPermi="['system:menu:update']" link type="primary" @click="openEdit(row)">
            {{ t('action.edit') }}
          </el-button>
          <el-button v-hasPermi="['system:menu:create']" link type="primary" @click="openCreate(row)">
            {{ t('action.create') }}
          </el-button>
          <el-button v-hasPermi="['system:menu:delete']" link type="danger" @click="handleDelete(row)">
            {{ t('action.delete') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </ContentWrap>

  <Dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
    <el-form :model="formData" label-width="110px">
      <el-form-item :label="t('field.code')" required>
        <el-input v-model="formData.code" :disabled="Boolean(editingCode)" />
      </el-form-item>
      <el-form-item :label="t('field.menuName')" required>
        <el-input v-model="formData.name" />
      </el-form-item>
      <el-form-item :label="t('field.parentMenu')">
        <el-select v-model="formData.parentCode" class="w-1/1" clearable filterable>
          <el-option
            v-for="item in list"
            :key="item.code"
            :label="getMenuName(item.name)"
            :value="item.code"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('field.sort')">
        <el-input-number v-model="formData.sortNo" class="w-1/1" :min="0" />
      </el-form-item>
      <el-form-item :label="t('field.icon')">
        <el-input v-model="formData.icon" />
      </el-form-item>
      <el-form-item :label="t('field.path')" required>
        <el-input v-model="formData.path" />
      </el-form-item>
      <el-form-item :label="t('field.component')">
        <el-input v-model="formData.component" />
      </el-form-item>
      <el-form-item :label="t('field.componentName')">
        <el-input v-model="formData.componentName" />
      </el-form-item>
      <el-form-item :label="t('field.permission')">
        <el-input v-model="formData.permission" />
      </el-form-item>
      <el-form-item :label="t('field.status')">
        <el-switch v-model="formData.visible" :active-text="t('statusText.visible')" />
      </el-form-item>
      <el-form-item :label="t('field.keepAlive')">
        <el-switch v-model="formData.keepAlive" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" @click="submitForm">{{ t('common.save') }}</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { adminApi } from '@/api/restaurant'

defineOptions({ name: 'SystemMenu' })

const message = useMessage()
const { t } = useI18n()
const loading = ref(false)
const queryFormRef = ref()
const list = ref<Recordable[]>([])
const queryParams = reactive({ keyword: '' })
const isExpandAll = ref(true)
const refreshTable = ref(true)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const editingCode = ref('')
const formData = reactive<Recordable>({
  code: '',
  name: '',
  parentCode: '',
  sortNo: 0,
  icon: '',
  visible: true,
  path: '',
  component: '',
  componentName: '',
  keepAlive: true,
  permission: ''
})

const tree = computed(() => buildTree(list.value))

const getMenuName = (name?: string) => (name ? t(name) : '')

const getParentMenuName = (parentCode?: string) => {
  if (!parentCode) return ''
  const parent = list.value.find((item) => item.code === parentCode)
  return parent ? getMenuName(parent.name) : parentCode
}

const filteredTree = computed(() => {
  const keyword = queryParams.keyword.trim().toLowerCase()
  if (!keyword) return tree.value
  const filter = (items: Recordable[]): Recordable[] =>
    items
      .map((item) => {
        const children = filter(item.children || [])
        const matched = [item.name, getMenuName(item.name), item.code, item.parentCode, getParentMenuName(item.parentCode)]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(keyword))
        return matched || children.length ? { ...item, children } : null
      })
      .filter(Boolean) as Recordable[]
  return filter(tree.value)
})

const buildTree = (items: Recordable[]) => {
  const map = new Map<string, Recordable>()
  items.forEach((item) => map.set(item.code, { ...item, id: item.code, children: [] }))
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

const getList = async () => {
  loading.value = true
  try {
    list.value = await adminApi.menus()
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

const toggleExpandAll = () => {
  refreshTable.value = false
  isExpandAll.value = !isExpandAll.value
  nextTick(() => {
    refreshTable.value = true
  })
}

const resetForm = () => {
  editingCode.value = ''
  Object.assign(formData, {
    code: '',
    name: '',
    parentCode: '',
    sortNo: 0,
    icon: '',
    visible: true,
    path: '',
    component: '',
    componentName: '',
    keepAlive: true,
    permission: ''
  })
}

const openCreate = (parent?: Recordable) => {
  resetForm()
  formData.parentCode = parent?.code || ''
  dialogTitle.value = t('action.create')
  dialogVisible.value = true
}

const openEdit = (row: Recordable) => {
  editingCode.value = row.code
  Object.assign(formData, {
    code: row.code,
    name: row.name,
    parentCode: row.parentCode || '',
    sortNo: row.sortNo || 0,
    icon: row.icon || '',
    visible: row.visible,
    path: row.path || '',
    component: row.component || '',
    componentName: row.componentName || '',
    keepAlive: row.keepAlive !== false,
    permission: row.permission || ''
  })
  dialogTitle.value = `${t('action.edit')} ${getMenuName(row.name)}`
  dialogVisible.value = true
}

const submitForm = async () => {
  if (!formData.code || !formData.name || !formData.path) {
    message.warning(t('common.required'))
    return
  }
  const data = { ...formData, parentCode: formData.parentCode || null }
  await adminApi.saveMenu(data)
  message.success(t('common.success'))
  dialogVisible.value = false
  await getList()
}

const handleDelete = async (row: Recordable) => {
  await message.delConfirm()
  await adminApi.deleteMenu(row.code)
  message.success(t('common.success'))
  await getList()
}

onMounted(getList)
</script>
