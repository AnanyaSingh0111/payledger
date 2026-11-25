/**
 * Thin fetch wrapper for the PayLedger API.
 * - Relative "/api/..." URLs: the dev server / hosting proxy forwards them, so no CORS is needed.
 * - Attaches the JWT and, for money movement, the Idempotency-Key header.
 * - Turns the backend's error JSON {status, message, errors} into an ApiError.
 */

const SESSION_KEY = 'payledger.session'

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Request failed (${status})`)
    this.status = status
    this.fieldErrors = body?.errors || null
    this.body = body
  }
}

export function loadSession() {
  try {
    const s = JSON.parse(localStorage.getItem(SESSION_KEY) || 'null')
    if (!s || !s.token || Date.now() > s.expiresAt) return null
    return s
  } catch {
    return null
  }
}

export function saveSession(s) {
  if (s) localStorage.setItem(SESSION_KEY, JSON.stringify(s))
  else localStorage.removeItem(SESSION_KEY)
}

/** Fired when the API says the token is no longer valid; AuthContext listens and logs out. */
export const SESSION_EXPIRED = 'payledger:session-expired'

export async function request(path, { method = 'GET', body, query, idempotencyKey, auth = true } = {}) {
  const url = new URL(`/api${path}`, window.location.origin)
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v)
    }
  }
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  const session = loadSession()
  if (auth && session) headers.Authorization = `Bearer ${session.token}`

  let res
  try {
    res = await fetch(url.pathname + url.search, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  } catch (e) {
    // network failure: the request may or may not have reached the server
    throw new ApiError(0, { message: 'Network error: the server could not be reached. It is safe to retry.' })
  }

  const text = await res.text()
  let data = null
  if (text) {
    try { data = JSON.parse(text) } catch { data = { message: text } }
  }
  if (!res.ok) {
    if (res.status === 401 && auth && session) window.dispatchEvent(new Event(SESSION_EXPIRED))
    throw new ApiError(res.status, data)
  }
  return data
}

/** A fresh key per payment intent. Retries of the SAME payment must reuse it. */
export const newIdempotencyKey = () => `web-${crypto.randomUUID()}`
