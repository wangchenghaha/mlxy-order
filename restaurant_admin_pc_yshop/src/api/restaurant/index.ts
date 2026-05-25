import { getCurrentLocaleCode, restaurantClient } from './client'

const scopeParams = (scope: Recordable = {}) => ({
  merchantId: scope.merchantId || undefined,
  storeId: scope.storeId || undefined,
  status: scope.status || undefined,
  tableNo: scope.tableNo || undefined,
  limit: scope.limit || undefined,
  offset: scope.offset || undefined
})

export const adminApi = {
  me: () => restaurantClient.get('/admin/me'),
  changePassword: (data: Recordable) => restaurantClient.put('/common/auth/password', data),
  dashboard: (scope?: Recordable) =>
    restaurantClient.get('/admin/dashboard', { params: scopeParams(scope) }),
  merchants: () => restaurantClient.get('/admin/merchants'),
  stores: (scope?: Recordable) =>
    restaurantClient.get('/admin/stores', { params: { merchantId: scope?.merchantId || undefined } }),
  roles: () => restaurantClient.get('/admin/roles'),
  saveRole: (data: Recordable) => restaurantClient.post('/admin/roles', data),
  deleteRole: (code: string) => restaurantClient.delete(`/admin/roles/${code}`),
  assignRoleMenus: (code: string, data: Recordable) =>
    restaurantClient.put(`/admin/roles/${code}/menus`, data),
  assignRoleDataScope: (code: string, data: Recordable) =>
    restaurantClient.put(`/admin/roles/${code}/data-scope`, data),
  menus: () => restaurantClient.get('/admin/menus'),
  saveMenu: (data: Recordable) => restaurantClient.post('/admin/menus', data),
  deleteMenu: (code: string) => restaurantClient.delete(`/admin/menus/${code}`),
  departments: () => restaurantClient.get('/admin/departments'),
  users: (scope?: Recordable) => restaurantClient.get('/admin/users', { params: scopeParams(scope) }),
  saveUser: (data: Recordable) =>
    data.id ? restaurantClient.put(`/admin/users/${data.id}`, data) : restaurantClient.post('/admin/users', data),
  deleteUser: (id: number, membershipId?: number) =>
    restaurantClient.delete(`/admin/users/${id}`, { params: { membershipId } }),
  resetUserPassword: (id: number, password: string, membershipId?: number) =>
    restaurantClient.put(`/admin/users/${id}/password`, { password, membershipId }),
  setUserEnabled: (id: number, enabled: boolean) =>
    restaurantClient.put(`/admin/users/${id}/enabled`, { enabled }),
  saveMerchant: (data: Recordable) => restaurantClient.post('/admin/merchants', data),
  deleteMerchant: (id: number) => restaurantClient.delete(`/admin/merchants/${id}`),
  saveStore: (data: Recordable) => restaurantClient.post('/admin/stores', data),
  deleteStore: (id: number) => restaurantClient.delete(`/admin/stores/${id}`),
  tables: (scope?: Recordable) => restaurantClient.get('/admin/tables', { params: scopeParams(scope) }),
  saveTable: (data: Recordable) => restaurantClient.post('/admin/tables', data),
  deleteTable: (id: number) => restaurantClient.delete(`/admin/tables/${id}`),
  categories: (scope?: Recordable) =>
    restaurantClient.get('/admin/categories', { params: scopeParams(scope) }),
  saveCategory: (data: Recordable) => restaurantClient.post('/admin/categories', data),
  deleteCategory: (id: number) => restaurantClient.delete(`/admin/categories/${id}`),
  dishes: (scope?: Recordable) => restaurantClient.get('/admin/dishes', { params: scopeParams(scope) }),
  uploadDishImage: (file: File) => {
    const data = new FormData()
    data.append('file', file)
    return restaurantClient.post('/common/upload/dish-image', data, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  saveDish: (data: Recordable) => restaurantClient.post('/admin/dishes', data),
  deleteDish: (id: number) => restaurantClient.delete(`/admin/dishes/${id}`),
  orders: (scope?: Recordable) => restaurantClient.get('/admin/orders', { params: scopeParams(scope) }),
  saveOrder: (data: Recordable) => restaurantClient.post('/admin/orders', data),
  deleteOrder: (id: number) => restaurantClient.delete(`/admin/orders/${id}`),
  printers: (scope?: Recordable) => restaurantClient.get('/admin/printers', { params: scopeParams(scope) }),
  savePrinter: (data: Recordable) => restaurantClient.post('/admin/printers', data),
  deletePrinter: (id: number) => restaurantClient.delete(`/admin/printers/${id}`),
  testPrinter: (id: number) => restaurantClient.post(`/admin/printers/${id}/test`),
  i18n: () => restaurantClient.get('/admin/i18n'),
  saveI18n: (data: Recordable) => restaurantClient.post('/admin/i18n', data),
  deleteI18n: (id: number) => restaurantClient.delete(`/admin/i18n/${id}`),
  logs: (scope?: Recordable) => restaurantClient.get('/admin/logs', { params: scopeParams(scope) })
}

const merchantDisplayName = (merchant: Recordable) => {
  const lang = getCurrentLocaleCode()
  if (lang === 'en_us') return merchant.nameEn || merchant.nameZh || merchant.name
  if (lang === 'ms_my') return merchant.nameMs || merchant.nameZh || merchant.name
  return merchant.nameZh || merchant.nameEn || merchant.name
}

export const buildMerchantTree = async () => {
  const merchants = await adminApi.merchants()
  const stores = await adminApi.stores()
  return merchants.map((merchant) => ({
    id: `merchant-${merchant.id}`,
    label: merchantDisplayName(merchant),
    type: 'merchant',
    merchantId: merchant.id,
    children: stores
      .filter((store) => store.merchantId === merchant.id)
      .map((store) => ({
        id: `store-${store.id}`,
        label: store.name,
        type: 'store',
        merchantId: merchant.id,
        storeId: store.id
      }))
  }))
}
