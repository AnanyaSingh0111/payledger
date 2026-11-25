const cache = new Map()

export function money(amount, currency = 'INR') {
  const key = currency
  if (!cache.has(key)) {
    try {
      cache.set(key, new Intl.NumberFormat('en-IN', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }))
    } catch {
      cache.set(key, new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
    }
  }
  return cache.get(key).format(Number(amount || 0))
}

export function dateTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
}

export function relative(iso) {
  if (!iso) return ''
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`
  return `${Math.floor(diff / 86400)} d ago`
}

/** Same rule as the backend's @Digits(fraction = 2) + @DecimalMin("0.01") + @DecimalMax("10000000.00"). */
export function validateAmount(raw) {
  const s = String(raw ?? '').trim()
  if (!s) return 'Enter an amount'
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return 'Use a positive number with at most 2 decimal places'
  const n = Number(s)
  if (n < 0.01) return 'Minimum is 0.01'
  if (n > 10_000_000) return 'Maximum is 10,000,000'
  return null
}

export const CURRENCIES = ['INR', 'USD', 'EUR', 'GBP', 'AED', 'SGD', 'JPY']

export const TYPE_META = {
  TOP_UP: { label: 'Top-up', badge: 'badge-green' },
  WITHDRAWAL: { label: 'Withdrawal', badge: 'badge-amber' },
  TRANSFER: { label: 'Transfer', badge: 'badge-blue' },
  REFUND: { label: 'Refund', badge: 'badge-ink' },
}
