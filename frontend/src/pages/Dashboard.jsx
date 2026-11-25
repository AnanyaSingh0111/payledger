import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Plus, ArrowDownToLine, ArrowUpFromLine, ArrowLeftRight, Wallet, ArrowRight } from 'lucide-react'
import { api } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { CountUp } from '../components/ui/Fancy'
import { Info, Loading } from '../components/ui/Bits'
import WalletCard from '../components/ui/WalletCard'
import { money, dateTime, relative, TYPE_META } from '../lib/format'

function greeting() {
  const h = new Date().getHours()
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

export default function Dashboard() {
  const { session } = useAuth()
  const navigate = useNavigate()
  const [accounts, setAccounts] = useState(null)
  const [recent, setRecent] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.accounts(session.userId).then(setAccounts).catch((e) => setError(e.message))
    api.transactions(session.userId, { size: 6 }).then((p) => setRecent(p.content)).catch(() => setRecent([]))
  }, [session.userId])

  const mine = new Set((accounts || []).map((a) => a.id))
  const totals = (accounts || []).reduce((acc, a) => ({ ...acc, [a.currency]: (acc[a.currency] || 0) + Number(a.balance) }), {})
  const totalEntries = Object.entries(totals)

  const quick = [
    { label: 'Send', icon: <ArrowLeftRight size={17} />, tab: 'transfer', primary: true },
    { label: 'Top up', icon: <ArrowDownToLine size={17} />, tab: 'topup' },
    { label: 'Withdraw', icon: <ArrowUpFromLine size={17} />, tab: 'withdraw' },
  ]

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <div className="eyebrow-label">{new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })}</div>
          <h1 style={{ marginTop: 6 }}>{greeting()}{session.name ? `, ${session.name.split(' ')[0]}` : ''}.</h1>
        </div>
        <Link className="btn" to="/app/wallets"><Plus size={16} /> Open wallet</Link>
      </div>
      {error && <div className="panel field-error" style={{ marginBottom: 16 }}>{error}</div>}

      <section className="panel overview">
        <div>
          <div className="stat-label">Total balance
            <Info content="The sum of your wallets, per currency. Each wallet balance is checked against its ledger entries." />
          </div>
          <div className="overview-total">
            {accounts === null ? <div className="skeleton" style={{ height: 44, width: 240 }} />
              : totalEntries.length === 0 ? <span className="faint">{money(0)}</span>
                : totalEntries.map(([cur, v]) => <div key={cur} className="num"><CountUp value={v} format={(n) => money(n, cur)} /></div>)}
          </div>
          <div className="small muted">{accounts ? `${accounts.length} wallet${accounts.length === 1 ? '' : 's'} · every balance backed by the ledger` : ' '}</div>
        </div>
        <div className="overview-actions">
          {quick.map((q) => (
            <button key={q.tab} className={`btn ${q.primary ? 'btn-primary' : ''}`} onClick={() => navigate(`/app/send?tab=${q.tab}`)}>
              {q.icon} {q.label}
            </button>
          ))}
        </div>
      </section>

      <div className="section-row">
        <h2>Your wallets</h2>
        <Link className="small row" style={{ gap: 4 }} to="/app/wallets">Manage <ArrowRight size={14} /></Link>
      </div>
      {accounts === null ? <Loading rows={2} /> : (
        <div className="grid grid-3">
          {accounts.map((a) => <WalletCard key={a.id} account={a} />)}
          {accounts.length === 0 && (
            <div className="panel empty" style={{ gridColumn: '1 / -1' }}>
              <div className="empty-icon"><Wallet size={20} /></div>
              You don't have a wallet yet. <Link to="/app/wallets">Open your first one</Link>.
            </div>
          )}
        </div>
      )}

      <div className="section-row">
        <h2>Recent activity</h2>
        <Link className="small row" style={{ gap: 4 }} to="/app/history">View all <ArrowRight size={14} /></Link>
      </div>
      <div className="panel" style={{ padding: '6px 18px' }}>
        {recent === null ? <div style={{ padding: 12 }}><Loading /></div> : recent.length === 0 ? <div className="empty small">No transactions yet.</div> : (
          <div className="table-wrap"><table className="table"><tbody>
            {recent.map((t) => {
              const incoming = mine.has(t.destinationAccountId) && !mine.has(t.sourceAccountId)
              const outgoing = mine.has(t.sourceAccountId) && !mine.has(t.destinationAccountId)
              return (
                <tr key={t.id}>
                  <td style={{ width: 120 }}><span className={`badge ${TYPE_META[t.type]?.badge}`}>{TYPE_META[t.type]?.label}</span></td>
                  <td>{t.description || <span className="muted">Transaction #{t.id}</span>}
                    {t.status === 'REFUNDED' && <span className="badge" style={{ marginLeft: 8 }}>Refunded</span>}</td>
                  <td className="muted small" title={dateTime(t.createdAt)}>{relative(t.createdAt)}</td>
                  <td style={{ textAlign: 'right' }} className={incoming ? 'amount-pos' : outgoing ? 'amount-neg' : 'amount'}>
                    {incoming ? '+' : outgoing ? '−' : ''}{money(t.amount, t.currency)}
                  </td>
                </tr>
              )
            })}
          </tbody></table></div>
        )}
      </div>
    </div>
  )
}
