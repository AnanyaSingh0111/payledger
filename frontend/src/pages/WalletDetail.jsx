import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, ShieldCheck, ShieldAlert, ArrowDownToLine, ArrowUpFromLine, Send, SearchX } from 'lucide-react'
import { api } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { CountUp, SpotlightCard, WarmTooltip } from '../components/ui/Fancy'
import { CopyChip, Loading, Pager } from '../components/ui/Bits'
import { money, dateTime } from '../lib/format'

export default function WalletDetail() {
  const { accountId } = useParams()
  const { session } = useAuth()
  const navigate = useNavigate()
  const [detail, setDetail] = useState(null)
  const [page, setPage] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.account(session.userId, accountId).then(setDetail).catch((e) => setError(e.message))
  }, [session.userId, accountId])

  const loadPage = (n) => api.statement(session.userId, accountId, n, 10).then(setPage).catch((e) => setError(e.message))
  useEffect(() => { loadPage(0) }, [accountId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (error) {
    return <div className="page"><div className="panel empty"><div className="empty-icon"><SearchX size={20} /></div>{error}<div style={{ marginTop: 12 }}><Link to="/app/wallets">Back to wallets</Link></div></div></div>
  }
  if (!detail) return <div className="page"><Loading rows={4} /></div>

  const a = detail.account
  const go = (tab) => navigate(`/app/send?tab=${tab}&from=${a.id}`)

  return (
    <div className="page">
      <Link to="/app/wallets" className="small row" style={{ marginBottom: 14 }}><ArrowLeft size={14} /> All wallets</Link>
      <div className="grid grid-2" style={{ alignItems: 'stretch' }}>
        <SpotlightCard>
          <div className="row-between">
            <div>
              <div className="eyebrow-label">{a.type} · {a.currency}</div>
              <h1 style={{ fontSize: 28, marginTop: 4 }}>{a.name}</h1>
            </div>
            <span className={`badge ${a.status === 'FROZEN' ? 'badge-red' : 'badge-green'}`}>{a.status}</span>
          </div>
          <div className="num" style={{ fontSize: 40, fontWeight: 600, letterSpacing: '-.045em', margin: '18px 0 4px' }}>
            <CountUp value={a.balance} format={(n) => money(n, a.currency)} />
          </div>
          <div className="row" style={{ flexWrap: 'wrap', marginTop: 10 }}>
            <CopyChip text={a.id} label={`Wallet id #${a.id}`} />
            <span className="small muted">Share this id so others can pay you.</span>
          </div>
          {a.status === 'FROZEN' && <p className="field-error" style={{ marginTop: 12 }}>An administrator froze this wallet: it can't send or receive money right now.</p>}
          <div className="row" style={{ marginTop: 18, flexWrap: 'wrap' }}>
            <button className="btn btn-sm btn-primary" onClick={() => go('topup')}><ArrowDownToLine size={14} /> Top up</button>
            <button className="btn btn-sm" onClick={() => go('transfer')}><Send size={14} /> Send</button>
            <button className="btn btn-sm" onClick={() => go('withdraw')}><ArrowUpFromLine size={14} /> Withdraw</button>
          </div>
        </SpotlightCard>

        <SpotlightCard color={detail.balanceMatchesLedger ? undefined : 'rgba(180,35,24,.08)'}>
          <div className="panel-title">
            {detail.balanceMatchesLedger ? <ShieldCheck size={20} color="var(--green-500)" /> : <ShieldAlert size={20} color="var(--red)" />}
            Ledger verification
          </div>
          <div className="grid grid-2">
            <div>
              <div className="small muted">Stored balance</div>
              <div className="num" style={{ fontSize: 22, fontWeight: 600 }}>{money(a.balance, a.currency)}</div>
            </div>
            <div>
              <div className="small muted">Sum of ledger entries</div>
              <div className="num" style={{ fontSize: 22, fontWeight: 600 }}>{money(detail.ledgerBalance, a.currency)}</div>
            </div>
          </div>
          <div style={{ marginTop: 16 }}>
            <WarmTooltip content="The balance column is a fast cached copy. The ledger (every DEBIT and CREDIT ever posted) is the truth. They must always agree; the nightly reconciliation checks every wallet like this.">
              {detail.balanceMatchesLedger
                ? <span className="badge badge-green" style={{ fontSize: 12, padding: '6px 11px' }}>✓ Balance matches the ledger</span>
                : <span className="badge badge-red" style={{ fontSize: 12, padding: '6px 11px' }}>✗ Mismatch detected</span>}
            </WarmTooltip>
          </div>
        </SpotlightCard>
      </div>

      <div className="panel" style={{ marginTop: 20 }}>
        <div className="panel-title">Ledger statement <span className="small muted">(append-only: rows can never be edited or deleted)</span></div>
        {!page ? <Loading /> : page.content.length === 0 ? <div className="empty small">No ledger entries yet.</div> : (
          <>
            <div className="table-wrap"><table className="table">
              <thead><tr><th>Entry</th><th>Transaction</th><th>Direction</th><th>When</th><th style={{ textAlign: 'right' }}>Amount</th></tr></thead>
              <tbody>
                {page.content.map((e) => (
                  <tr key={e.id}>
                    <td className="mono muted">#{e.id}</td>
                    <td className="mono">tx #{e.transactionId}</td>
                    <td><span className={`badge ${e.direction === 'CREDIT' ? 'badge-green' : 'badge-red'}`}>{e.direction}</span></td>
                    <td className="small muted nowrap">{dateTime(e.createdAt)}</td>
                    <td style={{ textAlign: 'right' }} className={e.direction === 'CREDIT' ? 'amount-pos' : 'amount-neg'}>
                      {e.direction === 'CREDIT' ? '+' : '−'}{money(e.amount, a.currency)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table></div>
            <Pager page={page} onChange={loadPage} />
          </>
        )}
      </div>
    </div>
  )
}
