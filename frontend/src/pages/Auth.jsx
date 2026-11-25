import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Loader2, ArrowRight, ShieldCheck, Check } from 'lucide-react'
import { RuledPaper } from '../components/effects/Backgrounds'
import { useAuth } from '../auth/AuthContext'
import { api } from '../api/endpoints'
import { useToast } from '../components/ui/Toast'
import { Brand } from '../components/ui/Logo'
import './auth.css'

function AuthFrame({ title, subtitle, children, footer }) {
  return (
    <div className="auth">
      <div className="auth-form-side">
        <Link to="/" className="auth-home" aria-label="PayLedger home"><Brand size={28} /></Link>
        <div className="auth-card fade-in">
          <h1>{title}</h1>
          <p className="muted" style={{ margin: '8px 0 28px' }}>{subtitle}</p>
          {children}
          <p className="small muted" style={{ marginTop: 22 }}>{footer}</p>
        </div>
        <p className="auth-note"><ShieldCheck size={14} /> Passwords are hashed with BCrypt. Sessions are signed JWTs valid for one hour.</p>
      </div>

      <aside className="auth-art" aria-hidden="true">
        <RuledPaper margin={false} />
        <div className="auth-art-inner">
          <div className="eyebrow-label" style={{ color: '#8fb3a2' }}>Statement · INR wallet #7</div>
          <table className="auth-stmt">
            <tbody>
              <tr><td>Opening balance</td><td className="num">75,000.00</td></tr>
              <tr><td>Transfer to #12 <span>Dinner split</span></td><td className="num neg">−1,250.50</td></tr>
              <tr><td>Top-up <span>Salary</span></td><td className="num pos">+20,000.00</td></tr>
              <tr><td>Refund from #12</td><td className="num pos">+1,250.50</td></tr>
            </tbody>
            <tfoot><tr><td>Closing balance</td><td className="num">95,000.00</td></tr></tfoot>
          </table>
          <div className="auth-check"><Check size={15} strokeWidth={3} /> Balance matches the ledger</div>
          <blockquote>Every balance is the sum of append-only debits and credits, so it can always be proven.</blockquote>
        </div>
      </aside>
    </div>
  )
}

function Field({ label, error, ...props }) {
  return (
    <div className="field">
      <label htmlFor={props.id}>{label}</label>
      <input className={`input ${error ? 'invalid' : ''}`} {...props} />
      {error && <span className="field-error">{error}</span>}
    </div>
  )
}

export function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [form, setForm] = useState({ email: '', password: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      await login(form.email, form.password)
      navigate(location.state?.from || '/app', { replace: true })
    } catch (err) {
      setError(err.status === 403 ? 'This account has been disabled by an administrator.' : err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthFrame title="Welcome back" subtitle="Log in to your wallets."
               footer={<>New here? <Link to="/register">Create an account</Link></>}>
      <form className="stack" onSubmit={submit}>
        <Field id="email" label="Email" type="email" autoComplete="email" required value={form.email}
               onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <Field id="password" label="Password" type="password" autoComplete="current-password" required value={form.password}
               onChange={(e) => setForm({ ...form, password: e.target.value })} />
        {error && <div className="field-error" role="alert">{error}</div>}
        <button className="btn btn-primary btn-lg btn-block" disabled={busy}>
          {busy ? <Loader2 size={18} className="spin" /> : <>Log in <ArrowRight size={18} /></>}
        </button>
      </form>
    </AuthFrame>
  )
}

export function Register() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [form, setForm] = useState({ name: '', email: '', password: '' })
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)

  const submit = async (e) => {
    e.preventDefault()
    const local = {}
    if (form.password.length < 8) local.password = 'At least 8 characters'
    if (form.password.length > 72) local.password = 'At most 72 characters'
    setErrors(local)
    if (Object.keys(local).length) return
    setBusy(true); setError(null)
    try {
      await api.register(form.name, form.email, form.password)
      await login(form.email, form.password)
      toast.success('Welcome to PayLedger', 'Open your first wallet to get started.')
      navigate('/app/wallets', { replace: true })
    } catch (err) {
      setErrors(err.fieldErrors || {})
      setError(err.fieldErrors ? null : err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthFrame title="Create your account" subtitle="One minute. No card needed. Demo money only."
               footer={<>Already have an account? <Link to="/login">Log in</Link></>}>
      <form className="stack" onSubmit={submit}>
        <Field id="name" label="Full name" required maxLength={100} value={form.name} error={errors.name}
               onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <Field id="email" label="Email" type="email" autoComplete="email" required value={form.email} error={errors.email}
               onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <Field id="password" label="Password (8–72 characters)" type="password" autoComplete="new-password" required
               value={form.password} error={errors.password}
               onChange={(e) => setForm({ ...form, password: e.target.value })} />
        {error && <div className="field-error" role="alert">{error}</div>}
        <button className="btn btn-primary btn-lg btn-block" disabled={busy}>
          {busy ? <Loader2 size={18} className="spin" /> : <>Create account <ArrowRight size={18} /></>}
        </button>
      </form>
    </AuthFrame>
  )
}
