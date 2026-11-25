# PayLedger Web (React frontend)

The React client for PayLedger: wallets, peer-to-peer payments, refunds, ledger statements and an admin console. It talks to the Spring Boot API, which is unchanged: no CORS settings and no backend code are needed for the frontend.

## Run it

Requires Node 20+.

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. By default `/api` calls are forwarded to the **live Railway API**, so you don't need a local backend. To use a local backend instead:

```bash
# PowerShell
$env:VITE_API_TARGET="http://localhost:8080"; npm run dev
```

or copy `.env.example` to `.env` and edit `VITE_API_TARGET`.

## How it reaches the API without CORS

The browser only ever calls its own origin (`/api/...`):

| Environment | Who forwards `/api` to Spring Boot |
|---|---|
| `npm run dev` / `npm run preview` | Vite's proxy (`vite.config.js`) |
| Vercel | `vercel.json` rewrites |
| Netlify | `public/_redirects` |

Same-origin for the browser means no CORS, and the backend stays exactly as it is.

## Deploy (Vercel)

1. Import the repository in Vercel and set **Root Directory** to `frontend`.
2. Framework preset: Vite (build `npm run build`, output `dist`).
3. Deploy. `vercel.json` proxies `/api/*` to the Railway backend and serves `index.html` for client-side routes.

## Pages

| Route | What it does |
|---|---|
| `/` | Landing page: a live ledger card that posts sample transactions (debit, credit, balanced), count-up stats from the real test results, feature grid, an 8-step payment walkthrough |
| `/register`, `/login` | Split-screen auth with a sample account statement |
| `/app` | Overview: total balance, quick actions, wallet cards (coloured per currency), recent activity |
| `/app/wallets` | Open a wallet (one per currency), with an optional opening balance |
| `/app/wallets/:id` | Balance vs ledger verification + append-only ledger statement |
| `/app/send` | Top up / withdraw / transfer, with a visible Idempotency-Key and a **Replay** button that proves money moves once |
| `/app/history` | Filters (wallet, type, status, dates, amounts), sorting, paging, refunds of received transfers |
| `/app/admin` | Reconciliation (ledger-scan animation), outbox stats, fraud-flag review, freeze/unfreeze wallets, disable users, audit log |

## Design notes

- **Idempotency from the client:** one `crypto.randomUUID()` key per payment intent. Editing the form creates a new key; retrying after a network error reuses the old one, so a double click or flaky connection can't pay twice.
- **Session:** the JWT is kept in `localStorage` with its expiry. Any 401 from the API (an expired token, or a user disabled by an admin) logs out immediately. Trade-off: localStorage is readable by injected scripts (XSS); an httpOnly cookie would be safer but needs CSRF protection and backend changes.
- **Validation mirrors the backend** (amounts: 0.01–10,000,000, max 2 decimals; passwords 8–72), and server field errors are shown next to the fields.
- **Design: "ledger paper".** Warm paper background, one accent colour (ledger green), Fraunces serif headings, JetBrains Mono for every figure (tabular numbers line up like a bank statement), fine ruled lines and statement-style tables. Wallets are drawn as physical cards with banknote-style guilloche lines.
- **Effects** (live ledger posting, ledger scan, ruled paper, spotlight, count-up, tilted card, tooltip) are hand-written in plain React + CSS, inspired by [React Bits](https://reactbits.dev). No animation libraries, and all motion respects `prefers-reduced-motion`.

## Stack

React 19 · React Router 7 · Vite 8 · lucide-react icons · plain CSS (no UI framework).
