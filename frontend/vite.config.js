import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// The browser only ever talks to this server's /api path. Vite forwards those calls to the
// Spring Boot backend, so the backend needs no CORS configuration (and no code changes).
// Default target: the live Oracle Cloud deployment. Point it at a local backend with
//   VITE_API_TARGET=http://localhost:8080
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_TARGET || 'https://80-225-247-125.sslip.io'
  const proxy = { '/api': { target, changeOrigin: true, secure: true } }

  return {
    plugins: [react()],
    server: { port: 5173, proxy },
    preview: { port: 4173, proxy },
  }
})
