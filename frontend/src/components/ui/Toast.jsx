import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import { CheckCircle2, AlertTriangle, Info, X } from 'lucide-react'

const ToastCtx = createContext(null)

const tone = {
  success: <CheckCircle2 size={18} color="var(--green-500)" />,
  error: <AlertTriangle size={18} color="var(--red)" />,
  info: <Info size={18} color="var(--blue)" />,
}

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const dismiss = useCallback((id) => setToasts((t) => t.filter((x) => x.id !== id)), [])
  const push = useCallback((type, title, body) => {
    const id = crypto.randomUUID()
    setToasts((t) => [...t.slice(-3), { id, type, title, body }])
    setTimeout(() => dismiss(id), type === 'error' ? 7000 : 4500)
  }, [dismiss])
  const api = useMemo(() => ({
    success: (title, body) => push('success', title, body),
    error: (title, body) => push('error', title, body),
    info: (title, body) => push('info', title, body),
  }), [push])

  return (
    <ToastCtx.Provider value={api}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`panel toast fade-in ${t.type}`}>
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <span style={{ marginTop: 2 }}>{tone[t.type]}</span>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 650 }}>{t.title}</div>
                {t.body && <div className="small muted" style={{ marginTop: 2 }}>{t.body}</div>}
              </div>
              <button className="btn btn-ghost icon-btn" onClick={() => dismiss(t.id)} aria-label="Dismiss"><X size={16} /></button>
            </div>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}

export const useToast = () => useContext(ToastCtx)
