/** PayLedger mark: a ledger book page, a "P" and the two rules of a balanced account. */
export default function Logo({ size = 32, light = false }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true">
      <rect width="64" height="64" rx="14" fill={light ? '#f4f2ec' : '#0e5a43'} />
      <path d="M22 48V16h13.5a10 10 0 0 1 0 20H22" fill="none" stroke={light ? '#0e5a43' : '#f4f2ec'} strokeWidth="5.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M36 44h10M36 50h10" stroke={light ? '#0e5a43' : '#d8c38a'} strokeWidth="3" strokeLinecap="round" />
    </svg>
  )
}

/** Logo + wordmark. */
export function Brand({ size = 30, light = false }) {
  return (
    <span className="brand-mark">
      <Logo size={size} light={light} />
      <span className="brand-word" style={light ? { color: '#f4f2ec' } : undefined}>PayLedger</span>
    </span>
  )
}
