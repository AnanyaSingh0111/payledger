import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import './styles/global.css'
import { AuthProvider } from './auth/AuthContext'
import { ToastProvider } from './components/ui/Toast'
import { ConfirmProvider } from './components/ui/Confirm'
import AppShell, { RequireAuth } from './layout/AppShell'
import Landing from './pages/Landing'
import { Login, Register } from './pages/Auth'
import Dashboard from './pages/Dashboard'
import Wallets from './pages/Wallets'
import WalletDetail from './pages/WalletDetail'
import MoveMoney from './pages/MoveMoney'
import History from './pages/History'
import Admin from './pages/Admin'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <ConfirmProvider>
          <Routes>
            <Route path="/" element={<Landing />} />
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />
            <Route element={<RequireAuth />}>
              <Route path="/app" element={<AppShell />}>
                <Route index element={<Dashboard />} />
                <Route path="wallets" element={<Wallets />} />
                <Route path="wallets/:accountId" element={<WalletDetail />} />
                <Route path="send" element={<MoveMoney />} />
                <Route path="history" element={<History />} />
                <Route element={<RequireAuth admin />}>
                  <Route path="admin" element={<Admin />} />
                </Route>
              </Route>
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
          </ConfirmProvider>
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
