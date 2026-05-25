<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  CreditCard,
  DataAnalysis,
  Delete,
  Goods,
  Grid,
  HomeFilled,
  Key,
  Money,
  OfficeBuilding,
  Minus,
  Plus,
  Printer,
  Refresh,
  Search,
  ShoppingCart,
  Switch,
  SwitchButton,
  Tickets,
  UserFilled,
  View,
} from '@element-plus/icons-vue'
import {
  cancelReservation,
  changePassword,
  checkout,
  eventUrl,
  fetchDashboard,
  fetchCaptcha,
  issueEventTicket,
  listCategories,
  listDishes,
  listOrders,
  listTableBills,
  login,
  logout,
  modifyAssistOrder,
  paymentMethods,
  printBill,
  registerCheckout,
  reprint,
  reserveTable,
  returnDish,
  sendSmsCode,
  setUnauthorizedHandler,
  cleanComplete,
  submitAssistOrder,
  transferTable,
} from './api.js'
import { loadLang } from './lang/index.js'
import { langs } from './utils/locale.js'
import { formatReceipt } from './utils/printer.js'

const { t } = useI18n()
const loginMode = ref('PASSWORD')
const loginForm = ref({ username: 'cashier', password: 'Cashier@123', captchaCode: '', phone: '+60000000003', smsCode: '' })
const captcha = ref({ captchaId: '', question: '' })
const smsDebugCode = ref('')
const authed = ref(Boolean(localStorage.getItem('cashier_token')))
const activeMenu = ref('checkout')
const orders = ref([])
const dashboard = ref({ todayRevenue: 0, todayOrders: 0, openOrders: 0, pendingAmount: 0 })
const tableBills = ref([])
const methods = ref([])
const selected = ref(null)
const selectedTableBill = ref(null)
const status = ref('PENDING_CHECKOUT')
const tableNo = ref('')
const tableKeyword = ref('')
const payment = ref({ method: 'CASH', referenceNo: '' })
const transferVisible = ref(false)
const transferForm = ref({ from: null, toTableId: '' })
const reservationVisible = ref(false)
const reservationForm = ref({ name: '', phone: '', arrivalTime: '' })
const assistDrawerVisible = ref(false)
const assistCategories = ref([])
const assistDishes = ref([])
const assistMenuStoreId = ref(null)
const assistCategoryId = ref('ALL')
const assistCart = ref({})
const assistForm = ref({ people: 1, remark: '' })
const assistSubmitting = ref(false)
const registerCategories = ref([])
const registerDishes = ref([])
const registerCategoryId = ref('ALL')
const registerKeyword = ref('')
const registerCart = ref({})
const registerPayment = ref({ method: 'CASH', referenceNo: '' })
const registerSubmitting = ref(false)
const registerMenuLoading = ref(false)
const storeOrders = ref([])
const orderQuery = ref({ status: '', tableNo: '' })
const orderDetailVisible = ref(false)
const orderDetail = ref(null)
const passwordVisible = ref(false)
const passwordSubmitting = ref(false)
const passwordForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const lang = ref(localStorage.getItem('lang') || 'ms_my')
let eventSource = null
let realtimeReloadTimer = null
let redirectingToLogin = false
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'
const apiOrigin = apiBaseUrl.replace(/\/api\/?$/, '').replace(/\/$/, '')

const activeTitle = computed(() => {
  if (activeMenu.value === 'register') return t('cashier_menu_register')
  if (activeMenu.value === 'tables') return t('cashier_menu_tables')
  if (activeMenu.value === 'orders') return t('cashier_menu_orders')
  return t('cashier_menu_checkout')
})
const pageTitle = computed(() => {
  if (activeMenu.value === 'register') return t('cashier_register_title')
  if (activeMenu.value === 'tables') return t('cashier_table_overview')
  if (activeMenu.value === 'orders') return t('cashier_orders_title')
  return t('checkout_title')
})
const pageDesc = computed(() => {
  if (activeMenu.value === 'register') return t('cashier_register_desc')
  if (activeMenu.value === 'tables') return t('cashier_table_desc')
  if (activeMenu.value === 'orders') return t('cashier_orders_desc')
  return t('login_hero_subtitle')
})
const cashierName = computed(() => localStorage.getItem('cashier_display_name') || 'Cashier')
const receiptPreview = computed(() => selected.value ? formatReceipt(selected.value, payment.value.method, payment.value.referenceNo) : '')
const selectedItemCount = computed(() => selected.value?.items?.reduce((sum, item) => sum + item.quantity, 0) || 0)
const occupiedTableCount = computed(() => tableBills.value.filter((bill) => bill.table.status !== 'EMPTY').length)
const currentTableOrder = computed(() => selectedTableBill.value?.order || null)
const emptyTargetTables = computed(() => tableBills.value.filter((bill) => bill.table.status === 'EMPTY'))
const assistTable = computed(() => selectedTableBill.value?.table || null)
const assistFilteredDishes = computed(() => {
  if (assistCategoryId.value === 'ALL') return assistDishes.value
  return assistDishes.value.filter((dish) => String(dish.categoryId) === String(assistCategoryId.value))
})
const assistCartLines = computed(() => Object.values(assistCart.value))
const assistTotal = computed(() => assistCartLines.value.reduce((sum, line) => sum + line.quantity * Number(line.price || 0), 0))
const assistCanOpen = computed(() => ['EMPTY', 'DINING'].includes(selectedTableBill.value?.table?.status))
const registerCartLines = computed(() => Object.values(registerCart.value))
const registerItemCount = computed(() => registerCartLines.value.reduce((sum, line) => sum + line.quantity, 0))
const registerTotal = computed(() => registerCartLines.value.reduce((sum, line) => sum + line.quantity * Number(line.dish.price || 0), 0))
const orderDetailItemCount = computed(() => orderDetail.value?.items?.reduce((sum, item) => sum + item.quantity, 0) || 0)
const filteredRegisterDishes = computed(() => {
  const keyword = registerKeyword.value.trim().toLowerCase()
  return registerDishes.value.filter((dish) => {
    const matchCategory = registerCategoryId.value === 'ALL' || String(dish.categoryId) === String(registerCategoryId.value)
    const names = [dish.nameZh, dish.nameEn, dish.nameMs].join(' ').toLowerCase()
    return matchCategory && (!keyword || names.includes(keyword))
  })
})
const filteredTableBills = computed(() => {
  const keyword = tableKeyword.value.trim().toLowerCase()
  if (!keyword) return tableBills.value
  return tableBills.value.filter((bill) => {
    const table = bill.table
    return [table.tableNo, table.area, table.status].some((value) => String(value || '').toLowerCase().includes(keyword))
  })
})
const paymentMethodLabel = (method) => t(`payment_method_${method.toLowerCase()}`)
const orderStatusLabel = (value) => t(`order_status_${String(value).toLowerCase()}`)
const canCheckoutOrder = (order) => order && !['PAID', 'CANCELLED'].includes(order.status)
const canPrintBillOrder = (order) => canCheckoutOrder(order)
const canReprintReceiptOrder = (order) => order?.status === 'PAID'
const tableStatusLabel = (value) => t(`table_status_${String(value).toLowerCase()}`)
const tableTagType = (value) => {
  if (value === 'EMPTY') return 'success'
  if (value === 'RESERVED') return 'warning'
  if (value === 'PENDING_CHECKOUT') return 'warning'
  if (value === 'CLEANING') return 'info'
  return 'primary'
}
const dishName = (item) => {
  if (lang.value === 'zh_cn') return item.dishNameZh || item.dishNameEn || item.dishNameMs || item.nameZh || item.nameEn || item.nameMs || ''
  if (lang.value === 'ms_my') return item.dishNameMs || item.dishNameEn || item.dishNameZh || item.nameMs || item.nameEn || item.nameZh || ''
  return item.dishNameEn || item.dishNameZh || item.dishNameMs || item.nameEn || item.nameZh || item.nameMs || ''
}
const productName = (item) => {
  if (lang.value === 'zh_cn') return item.nameZh || item.nameEn || item.nameMs
  if (lang.value === 'ms_my') return item.nameMs || item.nameEn || item.nameZh
  return item.nameEn || item.nameZh || item.nameMs
}
const money = (value) => `RM ${Number(value || 0).toFixed(2)}`
const assetUrl = (url) => {
  if (!url) return ''
  if (/^(https?:)?\/\//.test(url) || url.startsWith('data:') || url.startsWith('blob:')) return url
  return `${apiOrigin}/${String(url).replace(/^\/+/, '')}`
}

async function doLogin() {
  try {
    const body = loginMode.value === 'SMS'
      ? { loginType: 'SMS', phone: loginForm.value.phone, smsCode: loginForm.value.smsCode }
      : {
          loginType: 'PASSWORD',
          username: loginForm.value.username,
          password: loginForm.value.password,
          captchaId: captcha.value.captchaId,
          captchaCode: loginForm.value.captchaCode,
        }
    const result = await login(body)
    if (result?.displayName) localStorage.setItem('cashier_display_name', result.displayName)
    authed.value = true
    await boot()
  } catch (error) {
    if (loginMode.value === 'PASSWORD') await loadCaptcha()
    ElMessage.error(error.message)
  }
}

async function loadCaptcha() {
  captcha.value = await fetchCaptcha()
  loginForm.value.captchaCode = ''
}

async function doSendSmsCode() {
  const result = await sendSmsCode(loginForm.value.phone)
  smsDebugCode.value = result.debugCode
  ElMessage.success(`${t('login_sms_sent')}${result.debugCode ? `，${t('login_sms_debug')}：${result.debugCode}` : ''}`)
}

async function boot() {
  try { await loadLang(lang.value) } catch {}
  methods.value = await paymentMethods().catch(() => ['CASH', 'BANK_CARD', 'DUITNOW_QR', 'TOUCH_N_GO', 'ALIPAY_CROSS_BORDER'])
  await reloadAll()
  connectRealtime()
}

async function reload() {
  if (activeMenu.value === 'tables') {
    await reloadTables()
  } else if (activeMenu.value === 'register') {
    await loadRegisterMenu()
  } else if (activeMenu.value === 'orders') {
    await reloadStoreOrders()
  } else {
    await Promise.all([reloadOrders(), reloadDashboard()])
  }
}

async function reloadAll() {
  await Promise.all([reloadDashboard(), reloadOrders(), reloadTables(), reloadStoreOrders()])
}

async function reloadDashboard() {
  dashboard.value = await fetchDashboard()
}

async function reloadOrders() {
  const previousId = selected.value?.id
  orders.value = await listOrders(status.value, tableNo.value)
  selected.value = orders.value.find((order) => order.id === previousId) || orders.value[0] || null
}

async function reloadTables() {
  const previousId = selectedTableBill.value?.table?.id
  tableBills.value = await listTableBills()
  selectedTableBill.value = tableBills.value.find((bill) => bill.table.id === previousId)
    || tableBills.value.find((bill) => bill.order)
    || tableBills.value[0]
    || null
  if (selectedTableBill.value?.order) selected.value = selectedTableBill.value.order
  syncReservationForm()
}

async function resetTableSearch() {
  tableKeyword.value = ''
  await reloadTables()
}

async function reloadStoreOrders() {
  storeOrders.value = await listOrders(orderQuery.value.status || undefined, orderQuery.value.tableNo || undefined, 200, 0)
  if (orderDetail.value) {
    orderDetail.value = storeOrders.value.find((order) => order.id === orderDetail.value.id) || orderDetail.value
  }
}

async function resetStoreOrderQuery() {
  orderQuery.value = { status: '', tableNo: '' }
  await reloadStoreOrders()
}

function openOrderDetail(order) {
  orderDetail.value = order
  orderDetailVisible.value = true
}

async function printOrderSlip(order = orderDetail.value) {
  if (!order) return
  if (order.status === 'PAID') {
    await reprint(order.id)
  } else {
    await printBill(order.id)
  }
  ElMessage.success(t('cashier_print_submitted'))
}

async function changeMenu(key) {
  activeMenu.value = key
  await reload()
}

async function loadRegisterMenu() {
  if (registerMenuLoading.value) return
  registerMenuLoading.value = true
  try {
    const [categories, dishes] = await Promise.all([listCategories(), listDishes()])
    registerCategories.value = Array.isArray(categories) ? categories : []
    registerDishes.value = Array.isArray(dishes) ? dishes : []
    const categoryStillExists = registerCategoryId.value === 'ALL'
      || registerCategories.value.some((category) => String(category.id) === String(registerCategoryId.value))
    if (!categoryStillExists) registerCategoryId.value = 'ALL'
  } finally {
    registerMenuLoading.value = false
  }
}

async function pay(order = selected.value) {
  if (!order) return
  await checkout(order.id, payment.value.method, payment.value.referenceNo)
  ElMessage.success(t('cashier_paid_success'))
  payment.value.referenceNo = ''
  status.value = 'PAID'
  await reloadAll()
  await reloadOrders()
}

async function printBillOrder(order = selected.value) {
  if (!order) return
  await printBill(order.id)
  ElMessage.success(t('cashier_print_submitted'))
}

async function printAgain(order = selected.value) {
  if (!order) return
  await reprint(order.id)
  ElMessage.success(t('cashier_print_submitted'))
}

function selectTableBill(bill) {
  selectedTableBill.value = bill
  if (bill.order) selected.value = bill.order
  syncReservationForm()
}

function syncReservationForm() {
  const table = selectedTableBill.value?.table
  reservationForm.value = {
    name: table?.reservationName || '',
    phone: table?.reservationPhone || '',
    arrivalTime: table?.reservationArrivalTime || '',
  }
}

function openReservationDialog() {
  const bill = selectedTableBill.value
  if (!bill || !['EMPTY', 'RESERVED'].includes(bill.table.status)) return
  syncReservationForm()
  reservationVisible.value = true
}

function openTransfer(bill) {
  if (!bill?.order) return
  transferForm.value = { from: bill, toTableId: '' }
  transferVisible.value = true
}

async function submitTransfer() {
  if (!transferForm.value.from || !transferForm.value.toTableId) return
  await transferTable(transferForm.value.from.table.id, transferForm.value.toTableId)
  ElMessage.success(t('cashier_transfer_success'))
  transferVisible.value = false
  await reloadAll()
}

async function markCleaningDone(bill) {
  await cleanComplete(bill.table.id)
  ElMessage.success(t('common_success'))
  await reloadTables()
}

async function reserveSelectedTable() {
  const bill = selectedTableBill.value
  if (!bill || !['EMPTY', 'RESERVED'].includes(bill.table.status)) return
  await reserveTable(bill.table.id, reservationForm.value.name, reservationForm.value.phone, reservationForm.value.arrivalTime)
  ElMessage.success(t('cashier_reservation_success'))
  reservationVisible.value = false
  await reloadTables()
}

async function cancelSelectedReservation() {
  const bill = selectedTableBill.value
  if (!bill || bill.table.status !== 'RESERVED') return
  await cancelReservation(bill.table.id)
  ElMessage.success(t('cashier_cancel_reservation_success'))
  await reloadTables()
}

async function openAssistDrawer() {
  const bill = selectedTableBill.value
  const table = bill?.table
  if (!table || !assistCanOpen.value) return
  assistForm.value = {
    people: Math.min(Math.max(table.currentPeople || bill.order?.people || 1, 1), table.maxPeople || 1),
    remark: bill.order?.remark || '',
  }
  assistCart.value = {}
  assistCategoryId.value = 'ALL'
  assistDrawerVisible.value = true
  await loadAssistMenu()
}

async function loadAssistMenu() {
  const storeId = selectedTableBill.value?.table?.storeId
  if (assistMenuStoreId.value === storeId && assistDishes.value.length && assistCategories.value.length) return
  const [categories, dishes] = await Promise.all([listCategories(storeId), listDishes(storeId)])
  assistCategories.value = categories
  assistDishes.value = dishes
  assistMenuStoreId.value = storeId
}

function addAssistDish(dish) {
  const id = dish.id
  const current = assistCart.value[id] || {
    dishId: id,
    name: productName(dish),
    imageUrl: dish.imageUrl,
    price: Number(dish.price || 0),
    quantity: 0,
    remark: '',
  }
  assistCart.value = {
    ...assistCart.value,
    [id]: { ...current, name: productName(dish), imageUrl: dish.imageUrl, quantity: current.quantity + 1 },
  }
}

function removeAssistDish(dishId) {
  const current = assistCart.value[dishId]
  if (!current) return
  const next = { ...assistCart.value }
  if (current.quantity <= 1) {
    delete next[dishId]
  } else {
    next[dishId] = { ...current, quantity: current.quantity - 1 }
  }
  assistCart.value = next
}

function addRegisterDish(dish) {
  if (dish.stock <= 0) return
  const id = dish.id
  const current = registerCart.value[id] || { dish, quantity: 0 }
  registerCart.value = {
    ...registerCart.value,
    [id]: { dish, quantity: current.quantity + 1 },
  }
}

function decreaseRegisterDish(dishId) {
  const current = registerCart.value[dishId]
  if (!current) return
  const next = { ...registerCart.value }
  if (current.quantity <= 1) {
    delete next[dishId]
  } else {
    next[dishId] = { ...current, quantity: current.quantity - 1 }
  }
  registerCart.value = next
}

function setRegisterQuantity(dish, quantity) {
  const nextQuantity = Math.max(1, Number(quantity || 1))
  registerCart.value = {
    ...registerCart.value,
    [dish.id]: { dish, quantity: nextQuantity },
  }
}

function removeRegisterDish(dishId) {
  const next = { ...registerCart.value }
  delete next[dishId]
  registerCart.value = next
}

function clearRegisterCart() {
  registerCart.value = {}
}

async function submitRegisterCheckout() {
  if (!registerCartLines.value.length || registerSubmitting.value) return
  registerSubmitting.value = true
  try {
    await registerCheckout({
      method: registerPayment.value.method,
      referenceNo: registerPayment.value.referenceNo,
      items: registerCartLines.value.map((line) => ({
        dishId: line.dish.id,
        quantity: line.quantity,
        remark: '',
      })),
    })
    ElMessage.success(t('cashier_register_checkout_success'))
    clearRegisterCart()
    registerPayment.value.referenceNo = ''
    await reloadOrders()
  } finally {
    registerSubmitting.value = false
  }
}

async function submitAssistOrderForm() {
  const bill = selectedTableBill.value
  const table = assistTable.value
  if (!table || assistSubmitting.value || !assistCartLines.value.length) return
  assistSubmitting.value = true
  try {
    const newItems = assistCartLines.value.map((line) => ({
      dishId: line.dishId,
      quantity: line.quantity,
      remark: line.remark || '',
    }))
    const body = {
      tableId: table.id,
      people: assistForm.value.people,
      remark: assistForm.value.remark,
      items: table.status === 'DINING' && bill?.order
        ? mergeOrderItems(bill.order.items, newItems)
        : newItems,
    }
    if (table.status === 'DINING' && bill?.order) {
      await modifyAssistOrder(bill.order.id, body)
    } else {
      await submitAssistOrder(body)
    }
    ElMessage.success(t('cashier_order_success'))
    assistDrawerVisible.value = false
    await reloadAll()
  } finally {
    assistSubmitting.value = false
  }
}

function mergeOrderItems(existingItems = [], addedItems = []) {
  const merged = new Map()
  existingItems.forEach((item) => {
    merged.set(item.dishId, {
      dishId: item.dishId,
      quantity: item.quantity,
      remark: item.remark || '',
    })
  })
  addedItems.forEach((item) => {
    const current = merged.get(item.dishId)
    merged.set(item.dishId, {
      dishId: item.dishId,
      quantity: (current?.quantity || 0) + item.quantity,
      remark: item.remark || current?.remark || '',
    })
  })
  return Array.from(merged.values())
}

async function returnOrderDish(order, item) {
  if (!order || !item) return
  await returnDish(order.id, item.id, 1)
  ElMessage.success(t('cashier_return_success'))
  await reloadAll()
}

async function changeLang(value) {
  lang.value = value
  await loadLang(value)
}

function openPasswordDialog() {
  passwordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  passwordVisible.value = true
}

async function submitPasswordChange() {
  const oldPassword = passwordForm.value.oldPassword.trim()
  const newPassword = passwordForm.value.newPassword.trim()
  const confirmPassword = passwordForm.value.confirmPassword.trim()
  if (!oldPassword || !newPassword || !confirmPassword) {
    ElMessage.warning(t('password_required'))
    return
  }
  if (newPassword.length < 6 || newPassword.length > 20) {
    ElMessage.warning(t('password_length_tip'))
    return
  }
  if (newPassword !== confirmPassword) {
    ElMessage.warning(t('password_mismatch'))
    return
  }
  passwordSubmitting.value = true
  try {
    await changePassword({ oldPassword, newPassword, confirmPassword })
    ElMessage.success(t('password_changed'))
    passwordVisible.value = false
  } finally {
    passwordSubmitting.value = false
  }
}

function doLogout() {
  disconnectRealtime()
  logout()
  redirectingToLogin = false
  authed.value = false
  orders.value = []
  dashboard.value = { todayRevenue: 0, todayOrders: 0, openOrders: 0, pendingAmount: 0 }
  storeOrders.value = []
  tableBills.value = []
  methods.value = []
  selected.value = null
  selectedTableBill.value = null
  payment.value = { method: 'CASH', referenceNo: '' }
  registerCart.value = {}
  registerPayment.value = { method: 'CASH', referenceNo: '' }
  registerCategories.value = []
  registerDishes.value = []
}

async function handleUnauthorized() {
  if (redirectingToLogin || !authed.value) return
  redirectingToLogin = true
  doLogout()
  ElMessage.warning('登录已失效，请重新登录')
  try { await loadCaptcha() } catch {}
}

async function connectRealtime() {
  disconnectRealtime()
  const token = localStorage.getItem('cashier_token')
  if (!token || typeof EventSource === 'undefined') return
  await issueEventTicket()
  eventSource = new EventSource(eventUrl())
  ;['TABLE_CHANGED', 'ORDER_CHANGED', 'PRINT_TASK_CHANGED'].forEach((name) => {
    eventSource.addEventListener(name, scheduleRealtimeReload)
  })
  eventSource.onerror = () => {
    disconnectRealtime()
    if (authed.value) window.setTimeout(connectRealtime, 3000)
  }
}

function disconnectRealtime() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (realtimeReloadTimer) {
    clearTimeout(realtimeReloadTimer)
    realtimeReloadTimer = null
  }
}

function scheduleRealtimeReload() {
  if (realtimeReloadTimer) return
  realtimeReloadTimer = setTimeout(async () => {
    realtimeReloadTimer = null
    if (authed.value) await reloadAll()
  }, 350)
}

onMounted(async () => {
  setUnauthorizedHandler(handleUnauthorized)
  try { await loadLang(lang.value) } catch {}
  if (!authed.value) {
    try { await loadCaptcha() } catch {}
  }
  if (authed.value) boot()
})

onBeforeUnmount(disconnectRealtime)
</script>

<template>
  <main v-if="!authed" class="login-page">
    <div class="login-lang">
      <el-segmented :model-value="lang" :options="langs" @change="changeLang" />
    </div>
    <section class="login-panel">
      <div class="login-brand">
        <el-icon><OfficeBuilding /></el-icon>
        <div>
          <strong>{{ t('login_hero_brand') }}</strong>
          <span>{{ t('login_hero_badge') }}</span>
        </div>
      </div>
      <h2>{{ t('cashier_login_title') }}</h2>
      <el-segmented
        v-model="loginMode"
        class="login-mode"
        :options="[
          { label: t('login_method_password'), value: 'PASSWORD' },
          { label: t('login_method_sms'), value: 'SMS' },
        ]"
      />
      <template v-if="loginMode === 'PASSWORD'">
        <el-input v-model="loginForm.username" size="large" :placeholder="t('login_account')" />
        <el-input v-model="loginForm.password" size="large" :placeholder="t('login_password')" show-password />
        <div class="captcha-row">
          <el-input v-model="loginForm.captchaCode" size="large" :placeholder="t('login_captcha_placeholder')" @keyup.enter="doLogin" />
          <button type="button" class="captcha-box" @click="loadCaptcha">{{ captcha.question }}</button>
        </div>
      </template>
      <template v-else>
        <el-input v-model="loginForm.phone" size="large" :placeholder="t('login_phone')" />
        <div class="captcha-row">
          <el-input v-model="loginForm.smsCode" size="large" :placeholder="t('login_sms_code')" @keyup.enter="doLogin" />
          <el-button class="sms-button" @click="doSendSmsCode">{{ t('login_send_sms') }}</el-button>
        </div>
        <p v-if="smsDebugCode" class="debug-code">{{ t('login_sms_debug') }}：{{ smsDebugCode }}</p>
      </template>
      <el-button type="primary" size="large" class="login-submit" @click="doLogin">{{ t('common_login') }}</el-button>
      <p>{{ t('login_hero_stack') }}</p>
    </section>
  </main>

  <main v-else class="ruoyi-layout">
    <aside class="ruoyi-sidebar">
      <div class="sidebar-logo">
        <el-icon><OfficeBuilding /></el-icon>
        <span>{{ t('cashier_brand_line1') }} {{ t('cashier_brand_line2') }}</span>
      </div>
      <el-menu :default-active="activeMenu" class="sidebar-menu" @select="changeMenu">
        <el-menu-item index="checkout">
          <el-icon><CreditCard /></el-icon>
          <span>{{ t('cashier_menu_checkout') }}</span>
        </el-menu-item>
        <el-menu-item index="register">
          <el-icon><Goods /></el-icon>
          <span>{{ t('cashier_menu_register') }}</span>
        </el-menu-item>
        <el-menu-item index="tables">
          <el-icon><Grid /></el-icon>
          <span>{{ t('cashier_menu_tables') }}</span>
        </el-menu-item>
        <el-menu-item index="orders">
          <el-icon><Tickets /></el-icon>
          <span>{{ t('cashier_menu_orders') }}</span>
        </el-menu-item>
      </el-menu>
    </aside>
    <section class="ruoyi-body">
      <header class="navbar">
        <div class="breadcrumb">
          <el-icon><HomeFilled /></el-icon>
          <span>{{ t('cashier_brand_line1') }}</span>
          <span>/</span>
          <strong>{{ activeTitle }}</strong>
        </div>
        <div class="navbar-actions">
          <el-segmented :model-value="lang" :options="langs" @change="changeLang" />
          <el-button :icon="Refresh" @click="reload">{{ t('common_refresh') }}</el-button>
          <el-dropdown trigger="click">
            <div class="cashier-user cashier-user-dropdown">
              <el-icon><UserFilled /></el-icon>
              <span>{{ cashierName }}</span>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openPasswordDialog">
                  <el-icon><Key /></el-icon>
                  <span>{{ t('common_change_password') }}</span>
                </el-dropdown-item>
                <el-dropdown-item divided @click="doLogout">
                  <el-icon><SwitchButton /></el-icon>
                  <span>{{ t('common_logout') }}</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="app-main">
        <div class="page-head">
          <div>
            <h1>{{ pageTitle }}</h1>
            <p>{{ pageDesc }}</p>
          </div>
          <el-button type="primary" :icon="Refresh" @click="reload">{{ t('common_refresh') }}</el-button>
        </div>

        <template v-if="activeMenu === 'checkout'">
          <div class="metric-grid">
            <el-card class="metric-card" shadow="never">
              <el-icon><Money /></el-icon>
              <span>{{ t('cashier_today_revenue') }}</span>
              <strong>{{ money(dashboard.todayRevenue) }}</strong>
            </el-card>
            <el-card class="metric-card" shadow="never">
              <el-icon><Tickets /></el-icon>
              <span>{{ t('cashier_today_orders') }}</span>
              <strong>{{ dashboard.todayOrders || 0 }}</strong>
            </el-card>
            <el-card class="metric-card" shadow="never">
              <el-icon><DataAnalysis /></el-icon>
              <span>{{ t('cashier_pending_order_count') }}</span>
              <strong>{{ dashboard.openOrders || 0 }}</strong>
            </el-card>
            <el-card class="metric-card" shadow="never">
              <el-icon><Money /></el-icon>
              <span>{{ t('cashier_pending_amount') }}</span>
              <strong>{{ money(dashboard.pendingAmount) }}</strong>
            </el-card>
          </div>

          <el-card class="query-panel" shadow="never">
            <el-form inline>
              <el-form-item>
                <el-select v-model="status" class="status-select" @change="reloadOrders">
                  <el-option :label="t('order_status_pending_checkout')" value="PENDING_CHECKOUT" />
                  <el-option :label="t('order_status_paid')" value="PAID" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-input v-model="tableNo" class="table-search" :placeholder="t('table_no')" :prefix-icon="Search" @keyup.enter="reloadOrders" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :icon="Search" @click="reloadOrders">{{ t('common_search') }}</el-button>
                <el-button :icon="Refresh" @click="reloadOrders">{{ t('common_reset') }}</el-button>
              </el-form-item>
            </el-form>
          </el-card>

          <div class="cashier-grid">
            <el-card class="data-panel" shadow="never">
              <template #header>
                <div class="panel-header">{{ t('cashier_open_bills') }}</div>
              </template>
              <div class="orders">
                <article v-for="order in orders" :key="order.id" class="order-card" :class="{ active: selected?.id === order.id }" @click="selected = order">
                  <div class="order-card-top">
                    <strong>{{ order.tableNo }}</strong>
                    <el-tag size="small" :type="order.status === 'PAID' ? 'success' : 'warning'">{{ orderStatusLabel(order.status) }}</el-tag>
                  </div>
                  <div class="order-meta">{{ t('order_label') }} {{ order.id }}</div>
                  <div class="order-total">RM {{ order.totalAmount }}</div>
                </article>
                <el-empty v-if="!orders.length" />
              </div>
            </el-card>

            <el-card v-if="selected" class="data-panel summary-panel" shadow="never">
              <template #header>
                <div class="panel-header">{{ selected.tableNo }} · RM {{ selected.totalAmount }}</div>
              </template>
              <div class="summary-stat">
                <span>{{ t('order_label') }} {{ selected.id }}</span>
                <el-tag>{{ selectedItemCount }}</el-tag>
              </div>
              <div v-for="item in selected.items" :key="item.id" class="line">
                <div class="dish-line-info">
                  <div class="dish-line-thumb">
                    <img v-if="item.imageUrl" :src="assetUrl(item.imageUrl)" alt="" @error="$event.target.style.display = 'none'" />
                    <span>{{ dishName(item).slice(0, 1) }}</span>
                  </div>
                  <span>{{ dishName(item) }} × {{ item.quantity }}</span>
                </div>
                <strong>RM {{ (item.quantity * item.unitPrice).toFixed(2) }}</strong>
              </div>
              <el-divider />
              <template v-if="canCheckoutOrder(selected)">
                <el-select v-model="payment.method" class="full-width">
                  <el-option v-for="method in methods" :key="method" :label="paymentMethodLabel(method)" :value="method" />
                </el-select>
                <el-input v-model="payment.referenceNo" class="reference-input" :placeholder="t('payment_reference_no')" />
                <el-button :icon="Printer" class="full-width secondary-action" @click="printBillOrder()">{{ t('cashier_print_bill') }}</el-button>
                <el-button type="primary" :icon="CreditCard" class="full-width action-button" @click="pay()">{{ t('checkout_title') }}</el-button>
              </template>
              <el-button
                v-if="canReprintReceiptOrder(selected)"
                :icon="Printer"
                class="full-width secondary-action"
                @click="printAgain()"
              >
                {{ t('cashier_reprint_receipt') }}
              </el-button>
              <pre>{{ receiptPreview }}</pre>
            </el-card>
          </div>
        </template>

        <template v-else-if="activeMenu === 'register'">
          <div class="register-workbench">
            <section class="register-cart-panel data-panel">
              <div class="register-cart-head">
                <strong>{{ t('cashier_register_selected') }} <b>{{ registerItemCount }}</b> {{ t('field_quantity') }}</strong>
                <el-button text :icon="Delete" @click="clearRegisterCart">{{ t('cashier_register_clear') }}</el-button>
              </div>
              <div class="register-cart-list">
                <article v-for="line in registerCartLines" :key="line.dish.id" class="register-cart-item">
                  <div class="register-thumb">
                    <img v-if="line.dish.imageUrl" :src="assetUrl(line.dish.imageUrl)" alt="" @error="$event.target.style.display = 'none'" />
                    <span>{{ productName(line.dish).slice(0, 1) }}</span>
                  </div>
                  <div class="register-cart-main">
                    <div class="register-cart-title">
                      <strong>{{ productName(line.dish) }}</strong>
                      <el-button link type="primary" @click="removeRegisterDish(line.dish.id)">{{ t('common_delete') }}</el-button>
                    </div>
                    <span>{{ line.dish.spec || '-' }}</span>
                    <div class="register-cart-bottom">
                      <b>RM {{ Number(line.dish.price || 0).toFixed(2) }}</b>
                      <el-input-number
                        :model-value="line.quantity"
                        :min="1"
                        size="small"
                        @change="(value) => setRegisterQuantity(line.dish, value)"
                      />
                    </div>
                  </div>
                </article>
                <el-empty v-if="!registerCartLines.length" :description="t('cashier_register_empty_cart')" />
              </div>
              <div class="register-cart-footer">
                <div class="register-total">
                  <span>{{ t('cashier_register_total_count') }}：{{ registerItemCount }}</span>
                  <span>{{ t('cashier_register_total_amount') }}：<b>RM {{ registerTotal.toFixed(2) }}</b></span>
                </div>
                <el-select v-model="registerPayment.method" class="full-width">
                  <el-option v-for="method in methods" :key="method" :label="paymentMethodLabel(method)" :value="method" />
                </el-select>
                <el-input v-model="registerPayment.referenceNo" class="reference-input" :placeholder="t('payment_reference_no')" />
                <el-button
                  type="primary"
                  size="large"
                  class="full-width register-checkout"
                  :loading="registerSubmitting"
                  :disabled="!registerCartLines.length"
                  @click="submitRegisterCheckout"
                >
                  {{ t('cashier_register_checkout_now') }}
                </el-button>
              </div>
            </section>

            <section class="register-products-panel data-panel" v-loading="registerMenuLoading">
              <div class="register-products-body">
                <div class="register-products-main">
                  <div class="register-search">
                    <el-input v-model="registerKeyword" size="large" :placeholder="t('cashier_register_search_placeholder')" />
                    <el-button size="large" type="primary" :icon="Search">{{ t('common_search') }}</el-button>
                  </div>
                  <div class="register-products-grid">
                    <article
                      v-for="dish in filteredRegisterDishes"
                      :key="dish.id"
                      class="register-product-card"
                      :class="{ disabled: dish.stock <= 0 }"
                      @click="addRegisterDish(dish)"
                    >
                      <div class="register-product-image">
                        <img v-if="dish.imageUrl" :src="assetUrl(dish.imageUrl)" alt="" @error="$event.target.style.display = 'none'" />
                        <span>{{ productName(dish).slice(0, 1) }}</span>
                        <em v-if="dish.stock <= 0">{{ t('cashier_register_out_of_stock') }}</em>
                      </div>
                      <strong>{{ productName(dish) }}</strong>
                      <b>RM {{ Number(dish.price || 0).toFixed(2) }}</b>
                    </article>
                    <el-empty v-if="!filteredRegisterDishes.length" />
                  </div>
                </div>
                <aside class="register-category-list">
                  <button :class="{ active: registerCategoryId === 'ALL' }" @click="registerCategoryId = 'ALL'">
                    {{ t('cashier_register_all_products') }}
                  </button>
                  <button
                    v-for="category in registerCategories"
                    :key="category.id"
                    :class="{ active: String(registerCategoryId) === String(category.id) }"
                    @click="registerCategoryId = String(category.id)"
                  >
                    {{ productName(category) }}
                  </button>
                </aside>
              </div>
            </section>
          </div>
        </template>

        <template v-else-if="activeMenu === 'tables'">
          <div class="metric-grid table-metric-grid">
            <el-card class="metric-card" shadow="never">
              <el-icon><Grid /></el-icon>
              <span>{{ t('cashier_total_tables') }}</span>
              <strong>{{ tableBills.length }}</strong>
            </el-card>
            <el-card class="metric-card" shadow="never">
              <el-icon><Tickets /></el-icon>
              <span>{{ t('cashier_occupied_tables') }}</span>
              <strong>{{ occupiedTableCount }}</strong>
            </el-card>
            <el-card class="metric-card" shadow="never">
              <el-icon><Money /></el-icon>
              <span>{{ t('cashier_current_bill') }}</span>
              <strong>RM {{ currentTableOrder?.totalAmount || 0 }}</strong>
            </el-card>
          </div>

          <div class="table-workbench">
            <el-card class="data-panel" shadow="never">
              <template #header>
                <div class="panel-header table-board-header">
                  <span>{{ t('cashier_table_overview') }}</span>
                  <div class="table-board-tools">
                    <el-input
                      v-model="tableKeyword"
                      class="table-search"
                      :placeholder="t('table_no')"
                      :prefix-icon="Search"
                      clearable
                      @keyup.enter="reloadTables"
                    />
                    <el-button type="primary" :icon="Search" @click="reloadTables">{{ t('common_search') }}</el-button>
                    <el-button :icon="Refresh" @click="resetTableSearch">{{ t('common_reset') }}</el-button>
                  </div>
                </div>
              </template>
              <div class="table-grid">
                <article
                  v-for="bill in filteredTableBills"
                  :key="bill.table.id"
                  class="table-card"
                  :class="{ active: selectedTableBill?.table?.id === bill.table.id }"
                  @click="selectTableBill(bill)"
                >
                  <div class="table-card-top">
                    <strong>{{ bill.table.tableNo }}</strong>
                    <el-tag size="small" :type="tableTagType(bill.table.status)">{{ tableStatusLabel(bill.table.status) }}</el-tag>
                  </div>
                  <div class="table-meta">{{ bill.table.area }} · {{ bill.table.currentPeople }}/{{ bill.table.maxPeople }}</div>
                  <div class="table-total">RM {{ bill.order?.totalAmount || 0 }}</div>
                </article>
                <el-empty v-if="!filteredTableBills.length" />
              </div>
            </el-card>

            <el-card v-if="selectedTableBill" class="data-panel summary-panel" shadow="never">
              <template #header>
                <div class="panel-header">{{ selectedTableBill.table.tableNo }} · {{ tableStatusLabel(selectedTableBill.table.status) }}</div>
              </template>
              <template v-if="selectedTableBill.order">
                <div class="summary-stat">
                  <span>{{ t('field_order_id') }} {{ selectedTableBill.order.id }}</span>
                  <el-tag :type="selectedTableBill.order.status === 'PAID' ? 'success' : 'warning'">{{ orderStatusLabel(selectedTableBill.order.status) }}</el-tag>
                </div>
                <el-table class="dish-table" :data="selectedTableBill.order.items" border>
                  <el-table-column :label="t('cashier_table_items')" min-width="140">
                    <template #default="{ row }">
                      <div class="dish-cell">
                        <div class="dish-line-thumb">
                          <img v-if="row.imageUrl" :src="assetUrl(row.imageUrl)" alt="" @error="$event.target.style.display = 'none'" />
                          <span>{{ dishName(row).slice(0, 1) }}</span>
                        </div>
                        <strong>{{ dishName(row) }}</strong>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('field_quantity')" prop="quantity" width="78" align="center" />
                  <el-table-column :label="t('field_total_amount')" width="110" align="right">
                    <template #default="{ row }">RM {{ (row.quantity * row.unitPrice).toFixed(2) }}</template>
                  </el-table-column>
                  <el-table-column :label="t('common_operate')" width="124" align="center">
                    <template #default="{ row }">
                      <el-button
                        v-if="selectedTableBill.table.status === 'DINING'"
                        link
                        type="danger"
                        @click="returnOrderDish(selectedTableBill.order, row)"
                      >
                        {{ t('cashier_return_dish') }}
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <el-divider />
                <template v-if="canCheckoutOrder(selectedTableBill.order)">
                  <el-select v-model="payment.method" class="full-width">
                    <el-option v-for="method in methods" :key="method" :label="paymentMethodLabel(method)" :value="method" />
                  </el-select>
                  <el-input v-model="payment.referenceNo" class="reference-input" :placeholder="t('payment_reference_no')" />
                </template>
                <div class="table-detail-actions">
                  <el-button
                    v-if="selectedTableBill.table.status === 'DINING'"
                    type="success"
                    :icon="ShoppingCart"
                    @click="openAssistDrawer"
                  >
                    {{ t('cashier_assist_order') }}
                  </el-button>
                  <el-button v-if="canCheckoutOrder(selectedTableBill.order)" :icon="Switch" @click="openTransfer(selectedTableBill)">
                    {{ t('cashier_transfer_table') }}
                  </el-button>
                  <el-button v-if="canPrintBillOrder(selectedTableBill.order)" :icon="Printer" @click="printBillOrder(selectedTableBill.order)">
                    {{ t('cashier_print_bill') }}
                  </el-button>
                  <el-button v-if="canCheckoutOrder(selectedTableBill.order)" type="primary" :icon="CreditCard" @click="pay(selectedTableBill.order)">
                    {{ t('checkout_title') }}
                  </el-button>
                  <el-button v-if="canReprintReceiptOrder(selectedTableBill.order)" :icon="Printer" @click="printAgain(selectedTableBill.order)">
                    {{ t('cashier_reprint_receipt') }}
                  </el-button>
                </div>
              </template>
              <template v-else>
                <div v-if="['EMPTY', 'RESERVED'].includes(selectedTableBill.table.status)" class="reserve-panel">
                  <template v-if="selectedTableBill.table.status === 'RESERVED'">
                    <div class="reserve-title">{{ t('cashier_reservation_detail') }}</div>
                    <div class="reservation-info">
                      <span>{{ t('cashier_reservation_name') }}</span>
                      <strong>{{ selectedTableBill.table.reservationName || '-' }}</strong>
                      <span>{{ t('cashier_reservation_phone') }}</span>
                      <strong>{{ selectedTableBill.table.reservationPhone || '-' }}</strong>
                      <span>{{ t('cashier_reservation_arrival_time') }}</span>
                      <strong>{{ selectedTableBill.table.reservationArrivalTime || '-' }}</strong>
                    </div>
                    <div class="reserve-actions">
                      <el-button type="primary" plain @click="openReservationDialog">
                        {{ t('cashier_reserve_table') }}
                      </el-button>
                      <el-button type="danger" plain @click="cancelSelectedReservation">
                        {{ t('cashier_cancel_reservation') }}
                      </el-button>
                    </div>
                  </template>
                  <template v-else>
                    <div class="reserve-title">{{ selectedTableBill.table.tableNo }}</div>
                    <div class="reserve-actions">
                      <el-button type="primary" @click="openReservationDialog">
                        {{ t('cashier_reserve_table') }}
                      </el-button>
                      <el-button type="success" :icon="ShoppingCart" @click="openAssistDrawer">
                        {{ t('cashier_assist_order') }}
                      </el-button>
                    </div>
                  </template>
                </div>
                <el-empty v-else :description="t('cashier_no_order')" />
                <el-button
                  v-if="selectedTableBill.table.status === 'CLEANING'"
                  class="full-width secondary-action"
                  type="success"
                  @click="markCleaningDone(selectedTableBill)"
                >
                  {{ t('cashier_clean_complete') }}
                </el-button>
              </template>
            </el-card>
          </div>
        </template>

        <template v-else>
          <el-card class="data-panel store-orders-card" shadow="never">
            <template #header>
              <div class="panel-header orders-board-header">
                <span>{{ t('cashier_orders_title') }}</span>
                <div class="orders-board-tools">
                  <el-select v-model="orderQuery.status" class="order-status-select" :placeholder="t('field_status')" clearable>
                    <el-option :label="t('cashier_order_status_all')" value="" />
                    <el-option :label="t('order_status_draft')" value="DRAFT" />
                    <el-option :label="t('order_status_pending_kitchen')" value="PENDING_KITCHEN" />
                    <el-option :label="t('order_status_cooking')" value="COOKING" />
                    <el-option :label="t('order_status_served')" value="SERVED" />
                    <el-option :label="t('order_status_pending_checkout')" value="PENDING_CHECKOUT" />
                    <el-option :label="t('order_status_paid')" value="PAID" />
                    <el-option :label="t('order_status_cancelled')" value="CANCELLED" />
                  </el-select>
                  <el-input
                    v-model="orderQuery.tableNo"
                    class="table-search"
                    :placeholder="t('table_no')"
                    :prefix-icon="Search"
                    clearable
                    @keyup.enter="reloadStoreOrders"
                  />
                  <el-button type="primary" :icon="Search" @click="reloadStoreOrders">{{ t('common_search') }}</el-button>
                  <el-button :icon="Refresh" @click="resetStoreOrderQuery">{{ t('common_reset') }}</el-button>
                </div>
              </div>
            </template>

            <el-table :data="storeOrders" border stripe class="store-orders-table">
              <el-table-column prop="id" :label="t('field_order_id')" width="140" />
              <el-table-column prop="tableNo" :label="t('field_table_no')" min-width="110" />
              <el-table-column prop="waiterName" :label="t('field_waiter_name')" min-width="130" />
              <el-table-column :label="t('field_status')" min-width="140">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'PAID' ? 'success' : row.status === 'CANCELLED' ? 'danger' : 'warning'">
                    {{ orderStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('cashier_people_count')" width="110" align="center">
                <template #default="{ row }">{{ row.people || 0 }}</template>
              </el-table-column>
              <el-table-column :label="t('field_total_amount')" width="130" align="right">
                <template #default="{ row }">RM {{ Number(row.totalAmount || 0).toFixed(2) }}</template>
              </el-table-column>
              <el-table-column prop="remark" :label="t('field_remark')" min-width="150" show-overflow-tooltip />
              <el-table-column prop="createdAt" :label="t('field_created_at')" min-width="170" />
              <el-table-column prop="updatedAt" :label="t('field_updated_at')" min-width="170" />
              <el-table-column :label="t('common_operate')" width="110" fixed="right" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" :icon="View" @click="openOrderDetail(row)">
                    {{ t('field_detail') }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!storeOrders.length" />
          </el-card>
        </template>
      </main>
    </section>

    <el-dialog v-model="transferVisible" :title="t('cashier_transfer_table')" width="420px">
      <el-form label-width="96px">
        <el-form-item :label="t('cashier_from_table')">
          <el-input :model-value="transferForm.from?.table?.tableNo || ''" disabled />
        </el-form-item>
        <el-form-item :label="t('cashier_to_table')">
          <el-select v-model="transferForm.toTableId" class="full-width" :placeholder="t('cashier_select_target_table')">
            <el-option v-for="bill in emptyTargetTables" :key="bill.table.id" :label="`${bill.table.area} · ${bill.table.tableNo}`" :value="bill.table.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">{{ t('common_cancel') }}</el-button>
        <el-button type="primary" @click="submitTransfer">{{ t('common_confirm') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reservationVisible" :title="t('cashier_reserve_table')" width="440px">
      <el-form label-position="top">
        <el-form-item :label="t('cashier_reservation_name')">
          <el-input v-model="reservationForm.name" />
        </el-form-item>
        <el-form-item :label="t('cashier_reservation_phone')">
          <el-input v-model="reservationForm.phone" />
        </el-form-item>
        <el-form-item :label="t('cashier_reservation_arrival_time')">
          <el-input v-model="reservationForm.arrivalTime" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reservationVisible = false">{{ t('common_cancel') }}</el-button>
        <el-button type="primary" @click="reserveSelectedTable">{{ t('common_confirm') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="assistDrawerVisible" :title="t('cashier_order_drawer_title')" size="520px" append-to-body>
      <div class="assist-drawer">
        <div class="assist-table-head">
          <div>
            <strong>{{ assistTable?.tableNo }}</strong>
            <span>{{ assistTable?.area }} · {{ t('cashier_people_count') }} {{ assistForm.people }}/{{ assistTable?.maxPeople }}</span>
          </div>
          <el-input-number v-model="assistForm.people" :min="1" :max="assistTable?.maxPeople || 20" />
        </div>
        <el-input v-model="assistForm.remark" type="textarea" :rows="2" :placeholder="t('order_note')" />
        <el-tabs v-model="assistCategoryId" class="assist-tabs">
          <el-tab-pane :label="t('dish_category_all')" name="ALL" />
          <el-tab-pane
            v-for="category in assistCategories"
            :key="category.id"
            :label="productName(category)"
            :name="String(category.id)"
          />
        </el-tabs>
        <div class="assist-dish-list">
          <article v-for="dish in assistFilteredDishes" :key="dish.id" class="assist-dish">
            <div class="assist-dish-main">
              <div class="assist-dish-thumb">
                <img v-if="dish.imageUrl" :src="assetUrl(dish.imageUrl)" alt="" @error="$event.target.style.display = 'none'" />
                <span>{{ productName(dish).slice(0, 1) }}</span>
              </div>
              <div>
                <strong>{{ productName(dish) }}</strong>
                <span>RM {{ Number(dish.price || 0).toFixed(2) }}</span>
              </div>
            </div>
            <div class="assist-qty">
              <el-button circle size="small" :icon="Minus" :disabled="!assistCart[dish.id]" @click="removeAssistDish(dish.id)" />
              <b>{{ assistCart[dish.id]?.quantity || 0 }}</b>
              <el-button circle size="small" type="primary" :icon="Plus" @click="addAssistDish(dish)" />
            </div>
          </article>
          <el-empty v-if="!assistFilteredDishes.length" :description="t('cashier_select_dishes')" />
        </div>
        <div class="assist-cart">
          <div class="assist-cart-title">
            <strong>{{ t('cashier_cart') }}</strong>
            <span>RM {{ assistTotal.toFixed(2) }}</span>
          </div>
          <template v-if="assistCartLines.length">
            <div v-for="line in assistCartLines" :key="line.dishId" class="assist-cart-line">
              <div class="dish-line-info">
                <div class="dish-line-thumb">
                  <img v-if="line.imageUrl" :src="assetUrl(line.imageUrl)" alt="" @error="$event.target.style.display = 'none'" />
                  <span>{{ line.name.slice(0, 1) }}</span>
                </div>
                <span>{{ line.name }} × {{ line.quantity }}</span>
              </div>
              <strong>RM {{ (line.quantity * line.price).toFixed(2) }}</strong>
            </div>
          </template>
          <el-empty v-else :description="t('cashier_no_selected_dishes')" />
        </div>
      </div>
      <template #footer>
        <el-button @click="assistDrawerVisible = false">{{ t('common_cancel') }}</el-button>
        <el-button type="primary" :loading="assistSubmitting" :disabled="!assistCartLines.length" @click="submitAssistOrderForm">
          {{ t('cashier_submit_order') }}
        </el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="orderDetailVisible" :title="t('cashier_order_detail_title')" size="560px" append-to-body>
      <div v-if="orderDetail" class="order-detail-drawer">
        <div class="order-detail-title">
          <strong>{{ orderDetail.tableNo }}</strong>
          <el-tag :type="orderDetail.status === 'PAID' ? 'success' : orderDetail.status === 'CANCELLED' ? 'danger' : 'warning'">
            {{ orderStatusLabel(orderDetail.status) }}
          </el-tag>
        </div>
        <div class="order-detail-summary">
          <span>{{ t('field_order_id') }}</span>
          <strong>{{ orderDetail.id }}</strong>
          <span>{{ t('cashier_people_count') }}</span>
          <strong>{{ orderDetail.people || 0 }}</strong>
          <span>{{ t('field_waiter_name') }}</span>
          <strong>{{ orderDetail.waiterName || '-' }}</strong>
          <span>{{ t('field_total_amount') }}</span>
          <strong>RM {{ Number(orderDetail.totalAmount || 0).toFixed(2) }}</strong>
          <span>{{ t('field_created_at') }}</span>
          <strong>{{ orderDetail.createdAt || '-' }}</strong>
          <span>{{ t('field_updated_at') }}</span>
          <strong>{{ orderDetail.updatedAt || '-' }}</strong>
          <span>{{ t('field_remark') }}</span>
          <strong>{{ orderDetail.remark || '-' }}</strong>
        </div>
        <div class="summary-stat">
          <span>{{ t('cashier_table_items') }}</span>
          <el-tag>{{ orderDetailItemCount }}</el-tag>
        </div>
        <el-table class="dish-table order-detail-items" :data="orderDetail.items" border>
          <el-table-column :label="t('cashier_table_items')" min-width="160">
            <template #default="{ row }">
              <div class="dish-cell">
                <div class="dish-line-thumb">
                  <img v-if="row.imageUrl" :src="assetUrl(row.imageUrl)" alt="" @error="$event.target.style.display = 'none'" />
                  <span>{{ dishName(row).slice(0, 1) }}</span>
                </div>
                <div>
                  <strong>{{ dishName(row) }}</strong>
                  <small v-if="row.remark">{{ row.remark }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('field_quantity')" prop="quantity" width="86" align="center" />
          <el-table-column :label="t('field_unit_price')" width="110" align="right">
            <template #default="{ row }">RM {{ Number(row.unitPrice || 0).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column :label="t('field_total_amount')" width="120" align="right">
            <template #default="{ row }">RM {{ (row.quantity * row.unitPrice).toFixed(2) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="orderDetailVisible = false">{{ t('common_cancel') }}</el-button>
        <el-button type="primary" :icon="Printer" :disabled="!orderDetail" @click="printOrderSlip()">
          {{ t('cashier_order_print_slip') }}
        </el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="passwordVisible" :title="t('common_change_password')" width="420px" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="t('password_old')">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item :label="t('password_new')">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item :label="t('password_confirm')">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">{{ t('common_cancel') }}</el-button>
        <el-button type="primary" :loading="passwordSubmitting" @click="submitPasswordChange">
          {{ t('common_confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>
