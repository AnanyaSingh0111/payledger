import { useEffect, useState } from 'react'
import { Loader2, Plus, Wallet } from 'lucide-react'
import { api } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { SpotlightCard } from '../components/ui/Fancy'
import { Info, Loading } from '../components/ui/Bits'
import WalletCard from '../components/ui/WalletCard'
import { useToast } from '../components/ui/Toast'
import { CURRENCIES, validateAmount } from '../lib/format'

export default function Wallets() {
  const { session } = useAuth()
  const toast = useToast()
  const [accounts, setAccounts] = useState(null)
  const [form, setForm] = useState({ currency: 'INR', name: '', type: 'WALLET', initialBalance: '' })
  const [errors, setErrors] = useState({})
  const [busy, setBusy] = useState(false)

  const load = () => api.accounts(session.userId).then(setAccounts).catch((e) => toast.error('Could not load wallets', e.message))
  useEffect(() => { load() }, [session.userId]) // eslint-disable-line react-hooks/exhaustive-deps

  const owned = new Set((accounts || []).map((a) => a.currency))
  const available = CURRENCIES.filter((c) => !owned.has(c))

  useEffect(() => {
    if (accounts && owned.has(form.currency) && available.length) setForm((f) => ({ ...f, currency: available[0] }))
  }, [accounts]) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async (e) => {
    e.preventDefault()
    const local = {}
    if (form.initialBalance !== '' && form.initialBalance !== '0') {
      const err = validateAmount(form.initialBalance)
      if (err) local.initialBalance = err
    }
    setErrors(local)
    if (Object.keys(local).length) return
    setBusy(true)
    try {
      const created = await api.openAccount(session.userId, {
        currency: form.currency,
        name: form.name.trim() || undefined,
        type: form.type,
        initialBalance: form.initialBalance === '' ? undefined : Number(form.initialBalance),
      })
      toast.success(`${created.currency} wallet opened`, `Wallet #${created.id}. Share this id to receive payments.`)
      setForm({ currency: 'INR', name: '', type: 'WALLET', initialBalance: '' })
      load()
    } catch (err) {
      if (err.fieldErrors) setErrors(err.fieldErrors)
      toast.error(err.status === 409 ? 'Already exists' : 'Could not open wallet', err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Wallets</h1>
          <p>One wallet per currency. Click a card to see its ledger statement.</p>
        </div>
      </div>
      <div className="grid grid-side">
        <div>
          {accounts === null ? <Loading rows={2} /> : (
            <div className="grid grid-2">
              {accounts.map((a) => <WalletCard key={a.id} account={a} />)}
              {accounts.length === 0 && <div className="panel empty" style={{ gridColumn: '1 / -1' }}><div className="empty-icon"><Wallet size={20} /></div>No wallets yet. Open your first one with the form.</div>}
            </div>
          )}
        </div>

        <SpotlightCard>
          <div className="panel-title"><Plus size={18} /> Open a wallet
            <Info content="The backend enforces UNIQUE(user_id, currency): a second wallet in the same currency is refused with 409, even if two requests race." />
          </div>
          {available.length === 0 ? <p className="muted small">You have a wallet in every supported currency.</p> : (
            <form className="stack" onSubmit={submit}>
              <div className="field">
                <label htmlFor="cur">Currency</label>
                <select id="cur" className="select" value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
                  {available.map((c) => <option key={c}>{c}</option>)}
                </select>
              </div>
              <div className="field">
                <label htmlFor="nm">Name (optional)</label>
                <input id="nm" className="input" maxLength={100} placeholder={`${form.currency} Wallet`} value={form.name}
                       onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor="tp">Type</label>
                <select id="tp" className="select" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}>
                  <option>WALLET</option><option>BANK</option><option>CASH</option>
                </select>
              </div>
              <div className="field">
                <label htmlFor="ib" className="row">Opening balance (optional)
                  <Info content="Recorded as a real TOP_UP transaction with 2 ledger entries, never written straight into the balance." />
                </label>
                <input id="ib" className={`input ${errors.initialBalance ? 'invalid' : ''}`} inputMode="decimal" placeholder="0.00"
                       value={form.initialBalance} onChange={(e) => setForm({ ...form, initialBalance: e.target.value })} />
                {errors.initialBalance && <span className="field-error">{errors.initialBalance}</span>}
              </div>
              <button className="btn btn-primary btn-block" disabled={busy}>
                {busy ? <Loader2 size={16} className="spin" /> : 'Open wallet'}
              </button>
            </form>
          )}
        </SpotlightCard>
      </div>
    </div>
  )
}
