import { useEffect, useState } from 'react'
import { ScanLine, ShieldAlert, Users, ScrollText, Loader2, Snowflake, Sun, CheckCircle2, XCircle, Mailbox, ShieldCheck } from 'lucide-react'
import { api } from '../api/endpoints'
import { LedgerScan } from '../components/effects/Backgrounds'
import { CountUp, SpotlightCard, WarmTooltip } from '../components/ui/Fancy'
import { Info, Loading, Pager } from '../components/ui/Bits'
import { useToast } from '../components/ui/Toast'
import { useConfirm } from '../components/ui/Confirm'
import { useAuth } from '../auth/AuthContext'
import { money, dateTime } from '../lib/format'

const TABS = [
  { key: 'recon', label: 'Integrity', icon: <ScanLine size={16} /> },
  { key: 'fraud', label: 'Fraud flags', icon: <ShieldAlert size={16} /> },
  { key: 'people', label: 'Users & wallets', icon: <Users size={16} /> },
  { key: 'audit', label: 'Audit log', icon: <ScrollText size={16} /> },
]

export default function Admin() {
  const [tab, setTab] = useState('recon')
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Admin console</h1>
          <p>Prove the books balance, review fraud, freeze wallets and read the audit trail.</p>
        </div>
        <div className="tabs" role="tablist">
          {TABS.map((t) => (
            <button key={t.key} role="tab" aria-selected={tab === t.key} className={`tab ${tab === t.key ? 'active' : ''}`} onClick={() => setTab(t.key)}>
              {t.icon} {t.label}
            </button>
          ))}
        </div>
      </div>
      {tab === 'recon' && <Integrity />}
      {tab === 'fraud' && <Fraud />}
      {tab === 'people' && <People />}
      {tab === 'audit' && <Audit />}
    </div>
  )
}

/* ------------------------------------------------------------------ reconciliation + outbox */
function Integrity() {
  const toast = useToast()
  const [scanning, setScanning] = useState(false)
  const [report, setReport] = useState(null)
  const [outbox, setOutbox] = useState(null)

  useEffect(() => { api.admin.outboxStats().then(setOutbox).catch(() => {}) }, [])

  const run = async () => {
    setScanning(true)
    const minimum = new Promise((r) => setTimeout(r, 1600)) // let the scan animation play
    try {
      const [res] = await Promise.all([api.admin.reconcile(), minimum])
      setReport(res)
      if (res.healthy) toast.success('Books balance ✓', `${res.accountsChecked} wallets, ${res.entriesChecked} ledger entries checked`)
      else toast.error('Mismatch found', `${res.mismatches.length} wallets and ${res.unbalancedTransactionIds.length} transactions need attention`)
      api.admin.outboxStats().then(setOutbox).catch(() => {})
    } catch (e) {
      toast.error('Reconciliation failed', e.message)
    } finally {
      setScanning(false)
    }
  }

  return (
    <div className="stack">
      <div className="panel">
        <div>
          <div className="row-between" style={{ flexWrap: 'wrap' }}>
            <div>
              <div className="panel-title" style={{ marginBottom: 4 }}><ScanLine size={18} /> Reconciliation
                <Info content="Checks 3 invariants in one consistent snapshot: the whole ledger sums to 0, every transaction has 2 entries that net to 0, and every wallet balance equals the sum of its entries. Also runs nightly at 02:00." />
              </div>
              <p className="muted small" style={{ margin: 0 }}>Compares every stored balance with the ledger.</p>
            </div>
            <button className="btn btn-primary" onClick={run} disabled={scanning}>
              {scanning ? <><Loader2 size={16} className="spin" /> Scanning ledger…</> : 'Run reconciliation'}
            </button>
          </div>
          {(scanning || !report) && (
            <div className="scan-box">
              <LedgerScan active={scanning} />
              {!scanning && <span className="scan-hint">Press <b>Run reconciliation</b> to check every wallet against the ledger.</span>}
            </div>
          )}
          {report && !scanning && (
            <div className="fade-in" style={{ marginTop: 22 }}>
              <div className="row" style={{ marginBottom: 16 }}>
                {report.healthy
                  ? <span className="badge badge-green" style={{ fontSize: 14, padding: '7px 14px' }}><CheckCircle2 size={15} /> HEALTHY: the books balance</span>
                  : <span className="badge badge-red" style={{ fontSize: 14, padding: '7px 14px' }}><XCircle size={15} /> UNHEALTHY</span>}
                <span className="small muted">at {dateTime(report.runAt)}</span>
              </div>
              <div className="grid grid-4">
                <Stat label="Wallets checked" value={report.accountsChecked} />
                <Stat label="Ledger entries" value={report.entriesChecked} />
                <Stat label="Ledger net (must be 0)" value={Number(report.ledgerNet)} format={(n) => n.toFixed(2)} />
                <Stat label="Mismatched wallets" value={report.mismatches.length} danger={report.mismatches.length > 0} />
              </div>
              {report.mismatches.length > 0 && (
                <table className="table" style={{ marginTop: 14 }}>
                  <thead><tr><th>Wallet</th><th>Stored</th><th>Ledger</th><th>Difference</th></tr></thead>
                  <tbody>{report.mismatches.map((m) => (
                    <tr key={m.accountId}><td>#{m.accountId}</td><td>{money(m.storedBalance)}</td><td>{money(m.ledgerBalance)}</td><td className="amount-neg">{money(m.difference)}</td></tr>
                  ))}</tbody>
                </table>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="grid grid-3">
        <SpotlightCard><div className="stat-label"><Mailbox size={14} /> Outbox pending</div>
          <div className="stat-big"><CountUp value={outbox?.pending ?? 0} /></div></SpotlightCard>
        <SpotlightCard><div className="stat-label"><Mailbox size={14} /> Events published
          <Info content="Events are written in the same DB transaction as the money change, then a SKIP LOCKED poller publishes them every 2 seconds." /></div>
          <div className="stat-big"><CountUp value={outbox?.published ?? 0} /></div></SpotlightCard>
        <SpotlightCard><div className="stat-label"><Mailbox size={14} /> Failed (parked)</div>
          <div className="stat-big"><CountUp value={outbox?.failed ?? 0} /></div></SpotlightCard>
      </div>
    </div>
  )
}

function Stat({ label, value, format, danger }) {
  return (
    <div className="panel" style={{ padding: 16, background: 'var(--surface-2)', boxShadow: 'none' }}>
      <div className="small muted">{label}</div>
      <div style={{ fontSize: 26, fontWeight: 600, marginTop: 4 }} className={danger ? 'amount-neg' : 'num'}><CountUp value={value} format={format} /></div>
    </div>
  )
}

/* ------------------------------------------------------------------ fraud flags */
function Fraud() {
  const toast = useToast()
  const ask = useConfirm()
  const [status, setStatus] = useState('OPEN')
  const [page, setPage] = useState(null)
  const load = (n = 0) => api.admin.fraudFlags(status, n, 10).then(setPage).catch((e) => toast.error('Could not load flags', e.message))
  useEffect(() => { setPage(null); load(0) }, [status]) // eslint-disable-line react-hooks/exhaustive-deps

  const review = async (flag, outcome) => {
    const note = await ask({
      title: `${outcome === 'CLEARED' ? 'Clear' : 'Confirm'} flag #${flag.id}?`,
      body: outcome === 'CLEARED' ? 'Mark it as a false positive.' : 'Mark it as real fraud. Consider freezing the wallet too.',
      input: 'Note for the audit log (optional)',
      confirmLabel: outcome === 'CLEARED' ? 'Clear flag' : 'Confirm fraud',
      tone: outcome === 'CLEARED' ? undefined : 'danger',
    })
    if (note === null) return
    try {
      await api.admin.reviewFlag(flag.id, outcome, note || undefined)
      toast.success(`Flag #${flag.id} ${outcome.toLowerCase()}`)
      load(page?.pageNumber || 0)
    } catch (e) { toast.error('Review failed', e.message) }
  }

  return (
    <div className="panel">
      <div className="row-between" style={{ marginBottom: 12, flexWrap: 'wrap' }}>
        <div className="panel-title" style={{ marginBottom: 0 }}><ShieldAlert size={18} /> Fraud flags
          <Info content="Raised by the rapid-transfer (>10 outgoing in 60 s) and large-amount (≥ 50,000) rules. The daily-limit rule blocks instead of flagging." />
        </div>
        <div className="tabs">
          {['OPEN', 'CLEARED', 'CONFIRMED'].map((s) => <button key={s} className={`tab ${status === s ? 'active' : ''}`} onClick={() => setStatus(s)}>{s}</button>)}
        </div>
      </div>
      {!page ? <Loading /> : page.content.length === 0 ? <div className="empty"><div className="empty-icon"><ShieldCheck size={20} /></div>No {status.toLowerCase()} flags.</div> : (
        <>
          <div className="table-wrap"><table className="table">
            <thead><tr><th>#</th><th>Rule</th><th>Reason</th><th>Transaction</th><th>Wallet</th><th>Raised</th><th /></tr></thead>
            <tbody>{page.content.map((f) => (
              <tr key={f.id}>
                <td className="mono muted">{f.id}</td>
                <td><span className={`badge ${f.ruleName === 'LARGE_AMOUNT' ? 'badge-amber' : 'badge-red'}`}>{f.ruleName}</span></td>
                <td className="small">{f.reason}{f.reviewNote && <div className="tiny muted">Note: {f.reviewNote}</div>}</td>
                <td className="mono">#{f.transactionId}</td>
                <td className="mono">{f.accountId ? `#${f.accountId}` : '—'}</td>
                <td className="small muted nowrap">{dateTime(f.createdAt)}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {f.status === 'OPEN' && <>
                    <button className="btn btn-sm btn-success" onClick={() => review(f, 'CLEARED')}>Clear</button>{' '}
                    <button className="btn btn-sm btn-danger" onClick={() => review(f, 'CONFIRMED')}>Confirm</button>
                  </>}
                </td>
              </tr>
            ))}</tbody>
          </table></div>
          <Pager page={page} onChange={load} />
        </>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ users + wallet freeze */
function People() {
  const toast = useToast()
  const ask = useConfirm()
  const { session } = useAuth()
  const [page, setPage] = useState(null)
  const [wallet, setWallet] = useState({ id: '', reason: '' })
  const [busy, setBusy] = useState(false)
  const load = (n = 0) => api.admin.users(n, 10).then(setPage).catch((e) => toast.error('Could not load users', e.message))
  useEffect(() => { load(0) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const walletAction = async (freeze) => {
    if (!/^\d+$/.test(wallet.id)) { toast.error('Enter a wallet id'); return }
    setBusy(true)
    try {
      const a = freeze ? await api.admin.freeze(wallet.id, wallet.reason || undefined) : await api.admin.unfreeze(wallet.id, wallet.reason || undefined)
      toast.success(`Wallet #${a.id} is now ${a.status}`, `${a.name} · ${money(a.balance, a.currency)}`)
    } catch (e) { toast.error('Action failed', e.message) } finally { setBusy(false) }
  }

  const userAction = async (u, disable) => {
    const reason = await ask({
      title: `${disable ? 'Disable' : 'Enable'} ${u.email}?`,
      body: disable ? 'They will be unable to log in, and their current session stops working immediately.' : 'They will be able to log in again.',
      input: 'Reason for the audit log (optional)',
      confirmLabel: disable ? 'Disable user' : 'Enable user',
      tone: disable ? 'danger' : undefined,
    })
    if (reason === null) return
    try {
      await (disable ? api.admin.disableUser(u.id, reason || undefined) : api.admin.enableUser(u.id, reason || undefined))
      toast.success(`${u.email} ${disable ? 'disabled' : 'enabled'}`, disable ? 'Their existing tokens stopped working immediately.' : undefined)
      load(page?.pageNumber || 0)
    } catch (e) { toast.error('Action failed', e.message) }
  }

  return (
    <div className="grid grid-side">
      <div className="panel">
        <div className="panel-title"><Users size={18} /> Users</div>
        {!page ? <Loading /> : (
          <>
            <div className="table-wrap"><table className="table">
              <thead><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th /></tr></thead>
              <tbody>{page.content.map((u) => (
                <tr key={u.id}>
                  <td className="mono muted">{u.id}</td>
                  <td>{u.name}</td>
                  <td className="small">{u.email}</td>
                  <td><span className={`badge ${u.role === 'ADMIN' ? 'badge-ink' : ''}`}>{u.role}</span></td>
                  <td><span className={`badge ${u.status === 'ACTIVE' ? 'badge-green' : 'badge-red'}`}>{u.status}</span></td>
                  <td style={{ textAlign: 'right' }}>
                    {u.id !== session.userId && (u.status === 'ACTIVE'
                      ? <button className="btn btn-sm btn-danger" onClick={() => userAction(u, true)}>Disable</button>
                      : <button className="btn btn-sm btn-success" onClick={() => userAction(u, false)}>Enable</button>)}
                  </td>
                </tr>
              ))}</tbody>
            </table></div>
            <Pager page={page} onChange={load} />
          </>
        )}
      </div>

      <SpotlightCard>
        <div className="panel-title"><Snowflake size={18} /> Freeze a wallet
          <Info content="A frozen wallet can neither send nor receive. Freezing takes the same row lock as a transfer, so it waits for in-flight payments and every later one sees FROZEN." />
        </div>
        <div className="stack">
          <div className="field"><label htmlFor="fz-id">Wallet id</label>
            <input id="fz-id" className="input mono" inputMode="numeric" value={wallet.id} onChange={(e) => setWallet({ ...wallet, id: e.target.value })} /></div>
          <div className="field"><label htmlFor="fz-reason">Reason (goes into the audit log)</label>
            <input id="fz-reason" className="input" maxLength={255} value={wallet.reason} onChange={(e) => setWallet({ ...wallet, reason: e.target.value })} /></div>
          <div className="row">
            <button className="btn btn-danger" style={{ flex: 1 }} disabled={busy} onClick={() => walletAction(true)}><Snowflake size={15} /> Freeze</button>
            <button className="btn btn-success" style={{ flex: 1 }} disabled={busy} onClick={() => walletAction(false)}><Sun size={15} /> Unfreeze</button>
          </div>
        </div>
      </SpotlightCard>
    </div>
  )
}

/* ------------------------------------------------------------------ audit log */
function Audit() {
  const toast = useToast()
  const [filters, setFilters] = useState({ entityType: '', entityId: '', actorUserId: '' })
  const [page, setPage] = useState(null)
  const load = (n = 0, f = filters) => api.admin.auditLogs({ ...f, page: n, size: 15 }).then(setPage).catch((e) => toast.error('Could not load audit log', e.message))
  useEffect(() => { load(0) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="panel">
      <div className="panel-title"><ScrollText size={18} /> Audit log
        <WarmTooltip content="Append-only: MySQL triggers reject every UPDATE and DELETE on this table, even from raw SQL."><span className="badge badge-amber">IMMUTABLE</span></WarmTooltip>
      </div>
      <form className="filters" style={{ marginBottom: 14 }} onSubmit={(e) => { e.preventDefault(); load(0) }}>
        <div className="field"><label>Entity type</label>
          <select className="select" value={filters.entityType} onChange={(e) => setFilters({ ...filters, entityType: e.target.value })}>
            <option value="">Any</option>{['USER', 'ACCOUNT', 'TRANSACTION', 'FRAUD_FLAG', 'SYSTEM'].map((t) => <option key={t}>{t}</option>)}
          </select></div>
        <div className="field"><label>Entity id</label><input className="input mono" value={filters.entityId} onChange={(e) => setFilters({ ...filters, entityId: e.target.value })} /></div>
        <div className="field"><label>Actor user id</label><input className="input mono" value={filters.actorUserId} onChange={(e) => setFilters({ ...filters, actorUserId: e.target.value })} /></div>
        <button className="btn btn-primary">Search</button>
      </form>
      {!page ? <Loading /> : page.content.length === 0 ? <div className="empty">No entries.</div> : (
        <>
          <div className="table-wrap"><table className="table">
            <thead><tr><th>#</th><th>When</th><th>Actor</th><th>Action</th><th>Entity</th><th>Details</th></tr></thead>
            <tbody>{page.content.map((l) => (
              <tr key={l.id}>
                <td className="mono muted">{l.id}</td>
                <td className="small muted nowrap">{dateTime(l.createdAt)}</td>
                <td className="mono small">{l.actorUserId ? `user #${l.actorUserId}` : 'system'}</td>
                <td><span className={`badge ${l.action.includes('BLOCK') || l.action.includes('DISABLED') || l.action.includes('FROZEN') ? 'badge-red' : 'badge-blue'}`}>{l.action}</span></td>
                <td className="mono small">{l.entityType}{l.entityId ? ` #${l.entityId}` : ''}</td>
                <td className="small">{l.details}</td>
              </tr>
            ))}</tbody>
          </table></div>
          <Pager page={page} onChange={(n) => load(n)} />
        </>
      )}
    </div>
  )
}
