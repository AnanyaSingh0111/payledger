/**
 * Background treatments. Deliberately quiet: ruled ledger paper instead of glowing blobs.
 */

/** Faint horizontal rules plus a red margin line, like an accounting book page. */
export function RuledPaper({ margin = true, className = '' }) {
  return (
    <div className={`fx-layer ruled ${className}`} aria-hidden="true">
      {margin && <span className="ruled-margin" />}
    </div>
  )
}

/**
 * Reconciliation animation: ledger rows are "ticked" one by one while a scan line moves down.
 * `active` speeds it up while the real check is running.
 */
export function LedgerScan({ active = false, rows = 7 }) {
  return (
    <div className={`fx-layer ledger-scan ${active ? 'active' : ''}`} aria-hidden="true">
      <div className="ls-rows">
        {Array.from({ length: rows }, (_, i) => (
          <div key={i} className="ls-row" style={{ '--i': i }}>
            <span className="ls-bar" style={{ width: `${30 + ((i * 37) % 45)}%` }} />
            <span className="ls-tick">✓</span>
          </div>
        ))}
      </div>
      <span className="ls-line" />
    </div>
  )
}
