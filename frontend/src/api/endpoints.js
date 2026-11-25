import { request } from './client'

/** One function per backend endpoint. Paths match the Spring controllers exactly. */
export const api = {
  // auth & users
  register: (name, email, password) => request('/users', { method: 'POST', body: { name, email, password }, auth: false }),
  login: (email, password) => request('/auth/login', { method: 'POST', body: { email, password }, auth: false }),
  me: (userId) => request(`/users/${userId}`),

  // wallets
  accounts: (userId) => request(`/users/${userId}/accounts`),
  account: (userId, accountId) => request(`/users/${userId}/accounts/${accountId}`),
  openAccount: (userId, payload) => request(`/users/${userId}/accounts`, { method: 'POST', body: payload }),
  statement: (userId, accountId, page = 0, size = 10) =>
    request(`/users/${userId}/accounts/${accountId}/statement`, { query: { page, size } }),

  // money movement (Idempotency-Key required)
  createTransaction: (userId, payload, idempotencyKey) =>
    request(`/users/${userId}/transactions`, { method: 'POST', body: payload, idempotencyKey }),
  refund: (userId, transactionId, idempotencyKey) =>
    request(`/users/${userId}/transactions/${transactionId}/refund`, { method: 'POST', idempotencyKey }),

  // history
  transactions: (userId, filters = {}) => request(`/users/${userId}/transactions`, { query: filters }),
  transaction: (userId, id) => request(`/users/${userId}/transactions/${id}`),

  // admin
  admin: {
    reconcile: () => request('/admin/reconciliation/run', { method: 'POST' }),
    fraudFlags: (status = 'OPEN', page = 0, size = 10) => request('/admin/fraud-flags', { query: { status, page, size } }),
    reviewFlag: (id, outcome, note) => request(`/admin/fraud-flags/${id}/review`, { method: 'POST', body: { outcome, note } }),
    freeze: (accountId, reason) => request(`/admin/accounts/${accountId}/freeze`, { method: 'POST', body: { reason } }),
    unfreeze: (accountId, reason) => request(`/admin/accounts/${accountId}/unfreeze`, { method: 'POST', body: { reason } }),
    disableUser: (userId, reason) => request(`/admin/users/${userId}/disable`, { method: 'POST', body: { reason } }),
    enableUser: (userId, reason) => request(`/admin/users/${userId}/enable`, { method: 'POST', body: { reason } }),
    auditLogs: (filters = {}) => request('/admin/audit-logs', { query: filters }),
    outboxStats: () => request('/admin/outbox/stats'),
    users: (page = 0, size = 10) => request('/users', { query: { page, size } }),
  },
}
