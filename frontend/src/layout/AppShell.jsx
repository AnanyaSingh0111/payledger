import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LayoutDashboard, Wallet, ArrowLeftRight, ReceiptText, ShieldCheck, LogOut } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { WarmTooltip } from '../components/ui/Fancy'
import { Brand } from '../components/ui/Logo'
import './shell.css'

export function RequireAuth({ admin = false }) {
  const { session, isAdmin } = useAuth()
  const location = useLocation()
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (admin && !isAdmin) return <Navigate to="/app" replace />
  return <Outlet />
}

const NAV = [
  { to: '/app', end: true, label: 'Overview', icon: LayoutDashboard },
  { to: '/app/wallets', label: 'Wallets', icon: Wallet },
  { to: '/app/send', label: 'Move money', short: 'Pay', icon: ArrowLeftRight },
  { to: '/app/history', label: 'History', icon: ReceiptText },
]

export default function AppShell() {
  const { session, isAdmin, logout } = useAuth()
  const navigate = useNavigate()
  const items = isAdmin ? [...NAV, { to: '/app/admin', label: 'Admin console', short: 'Admin', icon: ShieldCheck, admin: true }] : NAV
  const initials = (session?.name || session?.email || 'U').split(/[\s@]/).filter(Boolean).slice(0, 2).map((s) => s[0]).join('').toUpperCase()
  const signOut = () => { logout(); navigate('/') }

  return (
    <div className="shell">
      <aside className="sidebar">
        <NavLink to="/app" className="side-brand" aria-label="PayLedger overview"><Brand size={28} /></NavLink>
        <nav className="side-nav" aria-label="Main navigation">
          <div className="side-label">Banking</div>
          {NAV.map(({ to, end, label, icon: Icon }) => (
            <NavLink key={to} to={to} end={end} className="side-link"><Icon size={18} /> {label}</NavLink>
          ))}
          {isAdmin && <>
            <div className="side-label" style={{ marginTop: 18 }}>Operations</div>
            <NavLink to="/app/admin" className="side-link"><ShieldCheck size={18} /> Admin console</NavLink>
          </>}
        </nav>
        <div className="side-user">
          <WarmTooltip align="end" content={<>User id #{session?.userId}<br />Session valid until {new Date(session?.expiresAt).toLocaleTimeString()}</>}>
            <div className="avatar">{initials}</div>
          </WarmTooltip>
          <div className="side-user-text">
            <b>{session?.name || 'Signed in'}</b>
            <span>{isAdmin ? 'Administrator' : (session?.email || `User #${session?.userId}`)}</span>
          </div>
          <button className="btn btn-ghost icon-btn" onClick={signOut} aria-label="Log out" title="Log out"><LogOut size={17} /></button>
        </div>
      </aside>

      <header className="mobile-top">
        <NavLink to="/app" aria-label="PayLedger overview"><Brand size={26} /></NavLink>
        <button className="btn btn-ghost btn-sm" onClick={signOut}><LogOut size={15} /> Log out</button>
      </header>

      <main className="shell-main">
        <Outlet />
      </main>

      <nav className="tabbar" aria-label="Main navigation">
        {items.map(({ to, end, label, short, icon: Icon }) => (
          <NavLink key={to} to={to} end={end} className="tabbar-link"><Icon size={20} /><span>{short || label}</span></NavLink>
        ))}
      </nav>
    </div>
  )
}
