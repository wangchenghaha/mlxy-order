import axios from 'axios'

let unauthorizedHandler = null

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  timeout: 8000,
})

export const eventUrl = () => {
  const baseURL = api.defaults.baseURL || 'http://localhost:8080/api'
  const ticket = sessionStorage.getItem('cashier_event_ticket')
  return `${baseURL}/common/events?ticket=${encodeURIComponent(ticket || '')}`
}

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('cashier_token')
  const lang = localStorage.getItem('lang') || 'ms_my'
  config.headers.lang = lang
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => {
    if (response.data?.code === 401) {
      unauthorizedHandler?.()
    }
    return response
  },
  (error) => {
    if (error?.response?.status === 401 || error?.response?.data?.code === 401) {
      unauthorizedHandler?.()
    }
    return Promise.reject(error)
  },
)

export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler
}

export function unwrap(response) {
  if (response.data?.code === 0) return response.data.data
  throw new Error(response.data?.message || 'API error')
}

export async function fetchCaptcha() {
  return unwrap(await api.get('/common/auth/captcha'))
}

export async function sendSmsCode(phone) {
  return unwrap(await api.post('/common/auth/sms-code', { phone }))
}

export async function issueEventTicket() {
  const ticket = unwrap(await api.post('/common/events/ticket'))
  sessionStorage.setItem('cashier_event_ticket', ticket.ticket)
  return ticket
}

export async function login(body) {
  const data = unwrap(await api.post('/common/auth/login', body))
  localStorage.setItem('cashier_token', data.token)
  return data
}

export async function changePassword(body) {
  return unwrap(await api.put('/common/auth/password', body))
}

export function logout() {
  localStorage.removeItem('cashier_token')
  localStorage.removeItem('cashier_display_name')
  sessionStorage.removeItem('cashier_event_ticket')
}

export async function listOrders(status, tableNo, limit = 100, offset = 0) {
  return unwrap(await api.get('/cashier/orders', { params: { status, tableNo, limit, offset } }))
}

export async function fetchDashboard() {
  return unwrap(await api.get('/cashier/dashboard'))
}

export async function listTableBills() {
  return unwrap(await api.get('/cashier/tables'))
}

export async function listCategories(storeId) {
  return unwrap(await api.get('/cashier/categories', { params: { storeId } }))
}

export async function listDishes(storeId) {
  return unwrap(await api.get('/cashier/dishes', { params: { storeId } }))
}

export async function paymentMethods() {
  return unwrap(await api.get('/cashier/payment-methods'))
}

export async function checkout(orderId, method, referenceNo) {
  return unwrap(await api.post(`/cashier/orders/${orderId}/checkout`, { method, referenceNo }))
}

export async function transferTable(fromTableId, toTableId) {
  return unwrap(await api.post('/cashier/tables/transfer', { fromTableId, toTableId }))
}

export async function cleanComplete(tableId) {
  return unwrap(await api.post(`/cashier/tables/${tableId}/clean-complete`))
}

export async function reserveTable(tableId, name, phone, arrivalTime) {
  return unwrap(await api.post(`/cashier/tables/${tableId}/reserve`, { name, phone, arrivalTime }))
}

export async function cancelReservation(tableId) {
  return unwrap(await api.post(`/cashier/tables/${tableId}/reservation/cancel`))
}

export async function submitAssistOrder(body) {
  return unwrap(await api.post('/cashier/orders', body))
}

export async function modifyAssistOrder(orderId, body) {
  return unwrap(await api.put(`/cashier/orders/${orderId}`, body))
}

export async function registerCheckout(body) {
  return unwrap(await api.post('/cashier/register/checkout', body))
}

export async function returnDish(orderId, itemId, quantity = 1) {
  return unwrap(await api.post(`/cashier/orders/${orderId}/items/${itemId}/return`, { quantity }))
}

export async function printBill(orderId) {
  return unwrap(await api.post(`/cashier/orders/${orderId}/bill-print`))
}

export async function reprint(orderId) {
  return unwrap(await api.post(`/cashier/orders/${orderId}/reprint`))
}
