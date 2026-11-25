import { useEffect, useState } from 'react'
import { Filter, RotateCcw, Undo2, Loader2, ReceiptText } from 'lucide-react'
import { api } from '../api/endpoints'
import { newIdempotencyKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Info, Loading, Pager } from '../components/ui/Bits'
import { useToast } from '../components/ui/Toast'
import { useConfirm } from '../components/ui/Confirm'
import { money, dateTime, TYPE_META } from '../lib/format'

const EMPTY = { accountId: '', type: '', status: '', from: '', to: '', minAmount: '', maxAmount: '' }

export default function History() {
  const { session } = useAuth()
  const toast = useToast()
  const ask = useConfirm()
  const [accounts, setAccounts] = useState([])
  const [filters, setFilters] = useState(EMPTY)
  const [applied, setApplied] = useState(EMPTY)
  const [sort, setSort] = useState({ sortBy: 'createdAt', direction: 'desc' })
  const [page, setPage] = useState(null)
  const [error, setError] = useState(null)
  const [refunding, setRefunding] = useState(null)

  useEffect(() => { api.accounts(session.userId).then(setAccounts).catch(() => {}) }, [session.userId])

  const load = (n = 0) => {
    setError(null)
    const q = {
      ...applied,
      // date inputs give yyyy-mm-dd; the API expects ISO date-times ('to' is exclusive)
      from: applied.from ? `${applied.from}T00:00:00` : '',
      to: applied.to ? `${nextDay(applied.to)}T00:00:00` : '',
      page: n, size: 12, ...sort,
    }
    api.transactions(session.userId, q).then(setPage).catch((e) => setError(e.message))
  }
  useEffect(() => { load(0) }, [applied, sort]) // eslint-disable-line react-hooks/exhaustive-deps

  const mine = new Set(accounts.map((a) => a.id))

  const refund = async (t) => {
    const ok = await ask({
      title: `Refund ${money(t.amount, t.currency)}?`,
      body: `The full amount goes back to wallet #${t.sourceAccountId}. A transfer can only be refunded once.`,
      confirmLabel: 'Refund',
    })
    if (!ok) return
    setRefunding(t.id)
    try {
      const r = await api.refund(session.userId, t.id, newIdempotencyKey())
      toast.success('Refund sent', `Refund #${r.id} for transaction #${t.id}`)
      load(page?.pageNumber || 0)
    } catch (e) {
      toast.error('Refund failed', e.message)
    } finally {
      setRefunding(null)
    }
  }

  const set = (k) => (e) => setFilters({ ...filters, [k]: e.target.value })

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>History</h1>
          <p>Everything you sent or received, newest first.</p>
        </div>
      </div>

      <form className="panel" style={{ marginBottom: 18 }} onSubmit={(e) => { e.preventDefault(); setApplied(filters) }}>
        <div className="panel-title"><Filter size={16} /> Filters
          <Info content="Built on the backend with a JPA Specification: only the filters you fill in become WHERE conditions." />
        </div>
        <div className="filters">
          <div className="field"><label>Wallet</label>
            <select className="select" value={filters.accountId} onChange={set('accountId')}>
              <option value="">All wallets</option>
              {accounts.map((a) => <option key={a.id} value={a.id}>#{a.id} · {a.currency}</option>)}
            </select></div>
          <div className="field"><label>Type</label>
            <select className="select" value={filters.type} onChange={set('type')}>
              <option value="">Any</option>{Object.entries(TYPE_META).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
            </select></div>
          <div className="field"><label>Status</label>
            <select className="select" value={filters.status} onChange={set('status')}>
              <option value="">Any</option><option>COMPLETED</option><option>REFUNDED</option>
            </select></div>
          <div className="field"><label>From</label><input className="input" type="date" value={filters.from} onChange={set('from')} /></div>
          <div className="field"><label>To</label><input className="input" type="date" value={filters.to} onChange={set('to')} /></div>
          <div className="field"><label>Min amount</label><input className="input" inputMode="decimal" value={filters.minAmount} onChange={set('minAmount')} /></div>
          <div className="field"><label>Max amount</label><input className="input" inputMode="decimal" value={filters.maxAmount} onChange={set('maxAmount')} /></div>
          <div className="row">
            <button className="btn btn-primary">Apply</button>
            <button type="button" className="btn icon-btn" aria-label="Reset filters" onClick={() => { setFilters(EMPTY); setApplied(EMPTY) }}><RotateCcw size={16} /></button>
          </div>
        </div>
      </form>

      <div className="panel">
        {error && <div className="field-error">{error}</div>}
        {!page ? <Loading rows={5} /> : page.content.length === 0 ? <div className="empty"><div className="empty-icon"><ReceiptText size={20} /></div>No transactions match these filters.</div> : (
          <>
            <div className="table-wrap"><table className="table">
              <thead><tr>
                <th className="sortable" onClick={() => setSort((s) => ({ sortBy: 'id', direction: s.direction === 'desc' ? 'asc' : 'desc' }))} style={{ cursor: 'pointer' }}>#</th>
                <th>Type</th><th>From → To</th><th>Note</th>
                <th style={{ cursor: 'pointer' }} onClick={() => setSort((s) => ({ sortBy: 'createdAt', direction: s.direction === 'desc' ? 'asc' : 'desc' }))}>When {sort.sortBy === 'createdAt' ? (sort.direction === 'desc' ? '↓' : '↑') : ''}</th>
                <th style={{ textAlign: 'right', cursor: 'pointer' }} onClick={() => setSort((s) => ({ sortBy: 'amount', direction: s.direction === 'desc' ? 'asc' : 'desc' }))}>Amount {sort.sortBy === 'amount' ? (sort.direction === 'desc' ? '↓' : '↑') : ''}</th>
                <th />
              </tr></thead>
              <tbody>
                {page.content.map((t) => {
                  const incoming = mine.has(t.destinationAccountId) && !mine.has(t.sourceAccountId)
                  const outgoing = mine.has(t.sourceAccountId) && !mine.has(t.destinationAccountId)
                  const canRefund = t.type === 'TRANSFER' && t.status === 'COMPLETED' && incoming
                  return (
                    <tr key={t.id}>
                      <td className="mono muted">{t.id}</td>
                      <td>
                        <span className={`badge ${TYPE_META[t.type]?.badge}`}>{TYPE_META[t.type]?.label}</span>
                        {t.status === 'REFUNDED' && <span className="badge badge-ink" style={{ marginLeft: 6 }}>REFUNDED</span>}
                      </td>
                      <td className="mono small">{t.sourceAccountId ? `#${t.sourceAccountId}` : <span className="faint">EXT</span>} → {t.destinationAccountId ? `#${t.destinationAccountId}` : <span className="faint">EXT</span>}</td>
                      <td className="small">{t.refundOfTransactionId ? `Refund of #${t.refundOfTransactionId}` : (t.description || <span className="faint">—</span>)}</td>
                      <td className="small muted nowrap">{dateTime(t.createdAt)}</td>
                      <td style={{ textAlign: 'right' }} className={incoming ? 'amount-pos' : outgoing ? 'amount-neg' : 'amount'}>
                        {incoming ? '+' : outgoing ? '−' : ''}{money(t.amount, t.currency)}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        {canRefund && (
                          <button className="btn btn-sm" onClick={() => refund(t)} disabled={refunding === t.id}
                                  title="Only the receiver can refund a transfer, in full, once (a UNIQUE constraint enforces it).">
                            {refunding === t.id ? <Loader2 size={14} className="spin" /> : <Undo2 size={14} />} Refund
                          </button>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table></div>
            <Pager page={page} onChange={load} />
          </>
        )}
      </div>
      <p className="tiny faint center" style={{ marginTop: 12 }}>EXT = outside the platform (top-ups come from it, withdrawals go to it).</p>
    </div>
  )
}

function nextDay(yyyyMmDd) {
  const d = new Date(`${yyyyMmDd}T00:00:00`)
  d.setDate(d.getDate() + 1)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
