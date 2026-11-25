import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'

const ConfirmCtx = createContext(null)

/**
 * Styled replacement for window.confirm / window.prompt.
 * const ask = useConfirm();  const answer = await ask({ title, body, input: 'Note (optional)', confirmLabel, tone })
 * Resolves to null when cancelled, otherwise to the typed text ('' if empty) or true when there is no input.
 */
export function ConfirmProvider({ children }) {
  const [req, setReq] = useState(null)
  const [text, setText] = useState('')
  const resolver = useRef(null)
  const inputRef = useRef(null)

  const ask = useCallback((opts) => new Promise((resolve) => {
    resolver.current = resolve
    setText('')
    setReq(opts)
  }), [])

  const close = (value) => { resolver.current?.(value); resolver.current = null; setReq(null) }

  useEffect(() => {
    if (!req) return undefined
    const onKey = (e) => { if (e.key === 'Escape') close(null) }
    window.addEventListener('keydown', onKey)
    setTimeout(() => inputRef.current?.focus(), 30)
    return () => window.removeEventListener('keydown', onKey)
  }, [req]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <ConfirmCtx.Provider value={ask}>
      {children}
      {req && (
        <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) close(null) }}>
          <form className="panel modal fade-in" role="dialog" aria-modal="true" aria-label={req.title}
                onSubmit={(e) => { e.preventDefault(); close(req.input ? text.trim() : true) }}>
            <h2 style={{ fontSize: 20, marginBottom: 6 }}>{req.title}</h2>
            {req.body && <p className="muted" style={{ marginTop: 0 }}>{req.body}</p>}
            {req.input && (
              <div className="field" style={{ marginBottom: 6 }}>
                <label htmlFor="confirm-input">{req.input}</label>
                <input id="confirm-input" ref={inputRef} className="input" maxLength={255} value={text} onChange={(e) => setText(e.target.value)} />
              </div>
            )}
            <div className="row" style={{ justifyContent: 'flex-end', marginTop: 14 }}>
              <button type="button" className="btn" onClick={() => close(null)}>Cancel</button>
              <button type="submit" className={`btn ${req.tone === 'danger' ? 'btn-danger' : 'btn-primary'}`} ref={req.input ? undefined : inputRef}>
                {req.confirmLabel || 'Confirm'}
              </button>
            </div>
          </form>
        </div>
      )}
    </ConfirmCtx.Provider>
  )
}

export const useConfirm = () => useContext(ConfirmCtx)
