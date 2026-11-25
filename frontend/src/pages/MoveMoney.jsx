import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ArrowDownToLine, ArrowUpFromLine, Send, Loader2, KeyRound, RotateCcw, CheckCircle2, Wallet } from 'lucide-react'
import { api } from '../api/endpoints'
import { newIdempotencyKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { CountUp, WarmTooltip } from '../components/ui/Fancy'
import { Info, Loading } from '../components/ui/Bits'
import { useToast } from '../components/ui/Toast'
import { money, validateAmount, dateTime } from '../lib/format'

const TABS = {
  transfer: { label: 'Send', icon: <Send size={16} />, type: 'TRANSFER', verb: 'Send' },
  topup: { label: 'Top up', icon: <ArrowDownToLine size={16} />, type: 'TOP_UP', verb: 'Top up' },
  withdraw: { label: 'Withdraw', icon: <ArrowUpFromLine size={16} />, type: 'WITHDRAWAL', verb: 'Withdraw' },
}

export default function MoveMoney() {
  const { session } = useAuth()
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const tab = TABS[params.get('tab')] ? params.get('tab') : 'transfer'
  const [accounts, setAccounts] = useState(null)
  const [form, setForm] = useState({ wallet: params.get('from') || '', to: '', amount: '', description: '' })
  const [key, setKey] = useState(newIdempotencyKey)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [receipt, setReceipt] = useState(null)
  const [replayed, setReplayed] = useState(null)

  const loadAccounts = () => api.accounts(session.userId).then((list) => {
    setAccounts(list)
    setForm((f) => ({ ...f, wallet: f.wallet || (list[0] ? String(list[0].id) : '') }))
  })
  useEffect(() => { loadAccounts() }, [session.userId]) // eslint-disable-line react-hooks/exhaustive-deps

  // A new payment intent gets a new key. Retrying the SAME intent must reuse the key.
  useEffect(() => { setKey(newIdempotencyKey()); setReceipt(null); setReplayed(null); setError(null) },
    [tab, form.wallet, form.to, form.amount])

  const wallet = useMemo(() => (accounts || []).find((a) => String(a.id) === String(form.wallet)), [accounts, form.wallet])
  const cfg = TABS[tab]

  const payload = () => {
    const amount = Number(form.amount)
    const base = { type: cfg.type, amount, description: form.description.trim() || undefined }
    if (tab === 'topup') return { ...base, destinationAccountId: Number(form.wallet) }
    if (tab === 'withdraw') return { ...base, sourceAccountId: Number(form.wallet) }
    return { ...base, sourceAccountId: Number(form.wallet), destinationAccountId: Number(form.to) }
  }

  const validate = () => {
    if (!form.wallet) return 'Choose a wallet'
    if (tab === 'transfer') {
      if (!/^\d+$/.test(form.to.trim())) return "Enter the receiver's wallet id (a number)"
      if (form.to.trim() === String(form.wallet)) return "You can't send money to the same wallet"
    }
    return validateAmount(form.amount)
  }

  const submit = async (e) => {
    e?.preventDefault()
    const v = validate()
    if (v) { setError(v); return }
    setBusy(true); setError(null)
    try {
      const tx = await api.createTransaction(session.userId, payload(), key)
      setReceipt(tx)
      toast.success(`${cfg.verb} complete`, `${money(tx.amount, tx.currency)} · transaction #${tx.id}`)
      loadAccounts()
    } catch (err) {
      setError(err.status === 0 ? `${err.message} Press the button again: the same Idempotency-Key is reused, so you can't be charged twice.` : err.message)
    } finally {
      setBusy(false)
    }
  }

  const replay = async () => {
    setBusy(true)
    try {
      const again = await api.createTransaction(session.userId, payload(), key)
      setReplayed(again)
      await loadAccounts()
      toast.info('Idempotent replay', `Same key → same transaction #${again.id}. No money moved the second time.`)
    } catch (err) {
      toast.error('Replay failed', err.message)
    } finally {
      setBusy(false)
    }
  }

  if (accounts === null) return <div className="page"><Loading rows={4} /></div>
  if (accounts.length === 0) {
    return <div className="page"><div className="panel empty"><div className="empty-icon"><Wallet size={20} /></div>You need a wallet first. <Link to="/app/wallets">Open one</Link>.</div></div>
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Move money</h1>
          <p>Every request carries an Idempotency-Key, so a retry can never pay twice.</p>
        </div>
        <div className="tabs" role="tablist">
          {Object.entries(TABS).map(([k, t]) => (
            <button key={k} role="tab" aria-selected={tab === k} className={`tab ${tab === k ? 'active' : ''}`}
                    onClick={() => setParams({ tab: k, ...(form.wallet ? { from: form.wallet } : {}) })}>{t.icon} {t.label}</button>
          ))}
        </div>
      </div>

      <div className="grid grid-2" style={{ alignItems: 'start' }}>
        <form className="panel" onSubmit={submit} style={{ padding: 26 }}>
          <div className="stack">
            <div className="field">
              <label htmlFor="w">{tab === 'topup' ? 'Into wallet' : 'From wallet'}</label>
              <select id="w" className="select" value={form.wallet} onChange={(e) => setForm({ ...form, wallet: e.target.value })}>
                {accounts.map((a) => <option key={a.id} value={a.id}>#{a.id} · {a.name} · {money(a.balance, a.currency)}{a.status === 'FROZEN' ? ' · FROZEN' : ''}</option>)}
              </select>
              {wallet && tab !== 'topup' && <span className="small muted">Available: <b><CountUp value={wallet.balance} format={(n) => money(n, wallet.currency)} /></b></span>}
            </div>

            {tab === 'transfer' && (
              <div className="field">
                <label htmlFor="to" className="row">Receiver's wallet id
                  <Info content="Ask the receiver for the wallet id shown on their wallet card. It must be in the same currency as your wallet." />
                </label>
                <input id="to" className="input mono" inputMode="numeric" placeholder="e.g. 12" value={form.to}
                       onChange={(e) => setForm({ ...form, to: e.target.value })} />
              </div>
            )}

            <div className="field">
              <label htmlFor="amt">Amount {wallet ? `(${wallet.currency})` : ''}</label>
              <input id="amt" className="input input-amount" inputMode="decimal" placeholder="0.00" value={form.amount}
                     onChange={(e) => setForm({ ...form, amount: e.target.value })} />
            </div>

            <div className="field">
              <label htmlFor="desc">Note (optional)</label>
              <input id="desc" className="input" maxLength={255} placeholder={tab === 'transfer' ? 'Dinner split' : 'Optional note'}
                     value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </div>

            <div className="row small muted" style={{ flexWrap: 'wrap' }}>
              <KeyRound size={14} /> Idempotency-Key
              <WarmTooltip content="Generated in your browser for this payment. If the network fails and you press the button again, the same key is sent and the server returns the original result instead of paying twice.">
                <span className="kbd">{key.slice(0, 18)}…</span>
              </WarmTooltip>
            </div>

            {error && <div className="field-error" role="alert">{error}</div>}

            <button className="btn btn-primary btn-lg btn-block" disabled={busy || !!receipt}>
              {busy ? <Loader2 size={18} className="spin" /> : receipt ? <><CheckCircle2 size={18} /> Done</> : <>{cfg.icon} {cfg.verb}{form.amount && !validateAmount(form.amount) && wallet ? ` ${money(form.amount, wallet.currency)}` : ''}</>}
            </button>
          </div>
        </form>

        <div className="stack">
          {receipt ? (
            <div className="panel receipt fade-in">
              <div className="panel-title"><CheckCircle2 size={20} /> Receipt</div>
              <div className="num" style={{ fontSize: 34, fontWeight: 600 }}>{money(receipt.amount, receipt.currency)}</div>
              <table className="table small" style={{ marginTop: 10 }}><tbody>
                <tr><td className="muted">Transaction</td><td className="mono">#{receipt.id}</td></tr>
                <tr><td className="muted">Type</td><td>{receipt.type}</td></tr>
                {receipt.sourceAccountId && <tr><td className="muted">From wallet</td><td className="mono">#{receipt.sourceAccountId}</td></tr>}
                {receipt.destinationAccountId && <tr><td className="muted">To wallet</td><td className="mono">#{receipt.destinationAccountId}</td></tr>}
                <tr><td className="muted">When</td><td>{dateTime(receipt.createdAt)}</td></tr>
                <tr><td className="muted">Ledger</td><td>1 DEBIT + 1 CREDIT posted ✓</td></tr>
              </tbody></table>
              <div className="divider" />
              <div className="small muted" style={{ marginBottom: 10 }}>
                <b>Try the idempotency guarantee:</b> send the exact same request again with the same key.
              </div>
              <button type="button" className="btn btn-block" onClick={replay} disabled={busy}><RotateCcw size={16} /> Replay this request</button>
              {replayed && (
                <p className="small fade-in replay-note">
                  {replayed.id === receipt.id
                    ? <>The server returned <b>transaction #{replayed.id}</b> again. Your balance didn't change: the money moved exactly once.</>
                    : <>Got transaction #{replayed.id}.</>}
                </p>
              )}
              <button type="button" className="btn btn-ghost btn-block" style={{ marginTop: 6 }}
                      onClick={() => setForm({ ...form, amount: '', to: tab === 'transfer' ? form.to : '', description: '' })}>
                New {cfg.label.toLowerCase()}
              </button>
            </div>
          ) : (
            <div className="panel">
              <div className="panel-title">What happens when you press {cfg.verb}</div>
              <ol className="small muted" style={{ margin: 0, paddingLeft: 18, lineHeight: 1.9 }}>
                <li>The server checks if this key was already used (and replays it if so)</li>
                <li>Your wallet{tab === 'transfer' ? 's are' : ' is'} locked with <code>SELECT … FOR UPDATE</code></li>
                <li>Ownership, frozen-status{tab === 'transfer' ? ' and currency' : ''} checks</li>
                <li>Fraud rules: daily limit, rapid transfers, large amounts</li>
                <li>Balance{tab === 'transfer' ? 's' : ''} updated + 2 ledger entries + audit + event, all in one transaction</li>
              </ol>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
