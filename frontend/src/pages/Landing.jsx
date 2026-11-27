import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ArrowRight, ArrowUpRight, Code2 as Github, Check, KeyRound, Lock } from 'lucide-react'
import { RuledPaper } from '../components/effects/Backgrounds'
import { CountUp, SpotlightCard, WarmTooltip } from '../components/ui/Fancy'
import { prefersReducedMotion } from '../components/effects/motion'
import { useAuth } from '../auth/AuthContext'
import { Brand } from '../components/ui/Logo'
import { money } from '../lib/format'
import './landing.css'

const API_DOCS = 'https://80-225-247-125.sslip.io/swagger-ui/index.html'
const REPO = 'https://github.com/AnanyaSingh0111/payledger'

const STATS = [
  { value: 1000, label: 'concurrent opposing transfers, exact balances after', tip: '1,000 transfers A→B and B→A on 32 threads. Every balance is exact afterwards.' },
  { value: 0, label: 'deadlocks, because locks are always taken in id order', tip: 'Every path locks wallets lowest id first, so a wait cycle can never form.' },
  { value: 40, label: 'automated tests against real MySQL', tip: 'Unit, integration, concurrency and HTTP tests, run on every push by GitHub Actions.' },
  { value: 1, label: 'payment for 20 identical retries', tip: '20 requests with the same Idempotency-Key move money exactly once.' },
]

const FEATURES = [
  { n: '01', title: 'Double-entry ledger', text: 'Every transaction posts a debit and a credit that sum to zero. Ledger rows are append-only, and MySQL triggers reject edits.' },
  { n: '02', title: 'Ordered row locks', text: 'SELECT … FOR UPDATE on both wallets, lowest id first. No overdrafts under load, and no deadlocks.' },
  { n: '03', title: 'Idempotency keys', text: 'Each payment carries a key. A retry returns the original receipt, so a double click never pays twice.' },
  { n: '04', title: 'Fraud rules', text: 'A daily limit blocks payments outright. Rapid bursts and large amounts are flagged for an admin to review.' },
  { n: '05', title: 'Transactional outbox', text: 'Events are committed with the money and then published by a SKIP LOCKED poller, with retries and backoff.' },
  { n: '06', title: 'Nightly reconciliation', text: 'Proves the books balance: the ledger nets to zero and every wallet matches its entries. Runs at 02:00 or on demand.' },
  { n: '07', title: 'Immutable audit trail', text: 'Registrations, payments, refunds, freezes and reviews are recorded, and can never be edited or deleted.' },
  { n: '08', title: 'Isolation-level aware', text: 'Money moves at READ COMMITTED: a REPEATABLE READ snapshot let 10 of 10 parallel payments past a limit of 6. Fixed, with a test.' },
]

const STEPS = [
  ['Replay check', 'Was this Idempotency-Key already used? Return the original result.'],
  ['Validate', 'Shape of the request: type, amount, wallets.'],
  ['Lock', 'Both wallets, SELECT … FOR UPDATE, smallest id first.'],
  ['Re-check key', 'A parallel twin may have finished while we waited.'],
  ['Business rules', 'Ownership, frozen wallets, matching currency, enough balance.'],
  ['Fraud', 'Block over the daily limit; flag bursts and large amounts.'],
  ['Post', 'Update balances and write one DEBIT and one CREDIT entry.'],
  ['Record', 'Transaction, audit row and outbox event, all in the same commit.'],
]

const SAMPLES = [
  { id: 1042, type: 'TRANSFER', from: { id: 7, name: 'Aarav · INR' }, to: { id: 12, name: 'Meera · INR' }, amount: 1250.5, key: '9f3c2a71-6b0e' },
  { id: 1043, type: 'TOP_UP', from: { id: 1, name: 'Settlement (external)' }, to: { id: 7, name: 'Aarav · INR' }, amount: 20000, key: 'c41d88e0-2f9a' },
  { id: 1044, type: 'REFUND', from: { id: 12, name: 'Meera · INR' }, to: { id: 7, name: 'Aarav · INR' }, amount: 1250.5, key: '07be5d13-a8c4' },
]

/** Hero visual: a statement card that posts sample transactions entry by entry. */
function LiveLedger() {
  const [i, setI] = useState(0)
  const [step, setStep] = useState(prefersReducedMotion() ? 4 : 0)

  useEffect(() => {
    if (prefersReducedMotion()) return undefined
    const timers = [1, 2, 3, 4].map((s) => setTimeout(() => setStep(s), 500 + s * 650))
    const next = setTimeout(() => { setStep(0); setI((n) => (n + 1) % SAMPLES.length) }, 6200)
    return () => { timers.forEach(clearTimeout); clearTimeout(next) }
  }, [i])

  const t = SAMPLES[i]
  const amt = money(t.amount, 'INR')
  return (
    <div className="ledger-card" aria-label="Example ledger posting">
      <div className="lc-head">
        <div>
          <div className="eyebrow-label">Transaction</div>
          <div className="lc-title"><span className="num">#{t.id}</span> <span className="badge badge-blue">{t.type.replace('_', ' ')}</span></div>
        </div>
        <div className="lc-amount num">{amt}</div>
      </div>

      <div className="lc-meta">
        <span className={step >= 1 ? 'on' : ''}><KeyRound size={13} /> Idempotency-Key <code>{t.key}…</code></span>
        <span className={step >= 1 ? 'on' : ''}><Lock size={13} /> Lock #{Math.min(t.from.id, t.to.id)} → #{Math.max(t.from.id, t.to.id)}</span>
      </div>

      <table className="lc-table">
        <thead><tr><th>Account</th><th className="r">Debit</th><th className="r">Credit</th></tr></thead>
        <tbody>
          <tr className={step >= 2 ? 'in' : ''}>
            <td><span className="num faint">#{t.from.id}</span> {t.from.name}</td>
            <td className="r num">{amt}</td><td className="r num faint">—</td>
          </tr>
          <tr className={step >= 3 ? 'in' : ''}>
            <td><span className="num faint">#{t.to.id}</span> {t.to.name}</td>
            <td className="r num faint">—</td><td className="r num">{amt}</td>
          </tr>
        </tbody>
        <tfoot>
          <tr>
            <td>Net</td>
            <td colSpan={2} className="r">
              <span className={`lc-balanced ${step >= 4 ? 'on' : ''}`}><Check size={14} strokeWidth={3} /> Balanced · <span className="num">0.00</span></span>
            </td>
          </tr>
        </tfoot>
      </table>
      <div className="lc-foot">
        <span>1 transaction · 2 ledger entries · 1 audit row · 1 event</span>
        <span className="lc-dots">{SAMPLES.map((s, n) => <i key={s.id} className={n === i ? 'on' : ''} />)}</span>
      </div>
    </div>
  )
}

export default function Landing() {
  const { session } = useAuth()
  const navigate = useNavigate()
  const go = () => navigate(session ? '/app' : '/register')

  return (
    <div className="landing">
      <header className="land-nav">
        <Link to="/" className="land-brand" aria-label="PayLedger home"><Brand /></Link>
        <nav className="land-links">
          <a href="#features">Features</a>
          <a href="#how">How a payment works</a>
          <a href={API_DOCS} target="_blank" rel="noreferrer">API</a>
        </nav>
        <div className="row">
          <a className="btn btn-ghost btn-sm hide-sm" href={REPO} target="_blank" rel="noreferrer"><Github size={16} /> GitHub</a>
          {session
            ? <Link className="btn btn-primary btn-sm" to="/app">Dashboard</Link>
            : <><Link className="btn btn-ghost btn-sm" to="/login">Log in</Link><Link className="btn btn-primary btn-sm hide-xs" to="/register">Open an account</Link></>}
        </div>
      </header>

      <section className="hero">
        <RuledPaper />
        <div className="hero-grid">
          <div className="hero-copy fade-in">
            <div className="eyebrow-label hero-eyebrow">Double-entry · Idempotent · Audited</div>
            <h1 className="hero-title">Every rupee,<br /><em>accounted for.</em></h1>
            <p className="hero-sub">
              PayLedger is a wallet and payments platform built on an append-only, double-entry ledger.
              It stays correct when a thousand payments arrive at once, and it can prove it.
            </p>
            <div className="row hero-cta">
              <button className="btn btn-primary btn-lg" onClick={go}>
                {session ? 'Go to dashboard' : 'Open a free account'} <ArrowRight size={18} />
              </button>
              <a className="btn btn-lg" href={API_DOCS} target="_blank" rel="noreferrer">API docs <ArrowUpRight size={16} /></a>
            </div>
            <dl className="hero-stack">
              <div><dt>Frontend</dt><dd>React 19 · Vite</dd></div>
              <div><dt>Backend</dt><dd>Spring Boot 4 · Java 21</dd></div>
              <div><dt>Data</dt><dd>MySQL 8 · Flyway</dd></div>
            </dl>
          </div>
          <div className="hero-visual fade-in"><LiveLedger /></div>
        </div>
      </section>

      <section className="stats-strip" aria-label="Test results">
        {STATS.map((s) => (
          <div key={s.label} className="stat-cell">
            <WarmTooltip content={s.tip}>
              <div className="stat-value num"><CountUp value={s.value} duration={1600} /></div>
            </WarmTooltip>
            <div className="stat-text">{s.label}</div>
          </div>
        ))}
      </section>

      <section className="land-section" id="features">
        <div className="section-head">
          <div className="eyebrow-label">What's under the hood</div>
          <h2>Built the way real payment systems are built.</h2>
          <p className="muted">Every item below is a backend feature with a test that proves it.</p>
        </div>
        <div className="feature-grid">
          {FEATURES.map((f) => (
            <SpotlightCard key={f.n} className="feature">
              <span className="feature-n num">{f.n}</span>
              <h3>{f.title}</h3>
              <p>{f.text}</p>
            </SpotlightCard>
          ))}
        </div>
      </section>

      <section className="how" id="how">
        <div className="how-inner">
          <div className="how-copy">
            <div className="eyebrow-label" style={{ color: '#8fb3a2' }}>Anatomy of a payment</div>
            <h2>Eight steps.<br />One database transaction.</h2>
            <p>Everything commits together or nothing does. A crash halfway leaves no half-moved money, no orphan event and no missing audit row.</p>
            <pre className="how-code"><span className="c-k">POST</span> /api/users/7/transactions{'\n'}<span className="c-h">Idempotency-Key:</span> 9f3c2a71-6b0e-4d2a…{'\n'}{'\n'}{'{'} <span className="c-s">"type"</span>: <span className="c-v">"TRANSFER"</span>, <span className="c-s">"amount"</span>: <span className="c-n">1250.50</span>,{'\n'}  <span className="c-s">"sourceAccountId"</span>: <span className="c-n">7</span>, <span className="c-s">"destinationAccountId"</span>: <span className="c-n">12</span> {'}'}</pre>
          </div>
          <ol className="steps">
            {STEPS.map(([title, text], n) => (
              <li key={title}>
                <span className="step-n num">{String(n + 1).padStart(2, '0')}</span>
                <div><b>{title}</b><span>{text}</span></div>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="land-section cta-band">
        <h2>See the ledger balance for yourself.</h2>
        <p className="muted">Open a wallet, send money, replay the same request, and watch it move exactly once.</p>
        <button className="btn btn-primary btn-lg" onClick={go}>Try it now <ArrowRight size={18} /></button>
      </section>

      <footer className="land-footer">
        <Brand size={24} />
        <span className="faint">Demo project. Top-ups and withdrawals are simulated; no real money moves.</span>
        <a href={REPO} target="_blank" rel="noreferrer">Source on GitHub</a>
      </footer>
    </div>
  )
}
