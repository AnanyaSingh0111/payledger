import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { loadSession, saveSession, SESSION_EXPIRED } from '../api/client'
import { api } from '../api/endpoints'

const AuthCtx = createContext(null)

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => loadSession())

  const logout = useCallback(() => { saveSession(null); setSession(null) }, [])

  const login = useCallback(async (email, password) => {
    const res = await api.login(email, password)
    const s = {
      token: res.token,
      userId: res.userId,
      role: res.role,
      expiresAt: Date.now() + res.expiresInSeconds * 1000 - 30_000,
    }
    saveSession(s)
    try {
      const me = await api.me(res.userId)
      s.name = me.name
      s.email = me.email
      saveSession(s)
    } catch { /* profile is optional */ }
    setSession({ ...s })
    return s
  }, [])

  // The backend rejects tokens of disabled users and expired tokens with 401: log out right away.
  useEffect(() => {
    const onExpired = () => logout()
    window.addEventListener(SESSION_EXPIRED, onExpired)
    const timer = session ? setTimeout(logout, Math.max(0, session.expiresAt - Date.now())) : null
    return () => { window.removeEventListener(SESSION_EXPIRED, onExpired); if (timer) clearTimeout(timer) }
  }, [session, logout])

  const value = useMemo(() => ({ session, login, logout, isAdmin: session?.role === 'ADMIN' }), [session, login, logout])
  return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>
}

export const useAuth = () => useContext(AuthCtx)
