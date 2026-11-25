import { useState } from 'react'
import { ChevronLeft, ChevronRight, Copy, Check } from 'lucide-react'
import { WarmTooltip } from './Fancy'

/** Prev / next pager for the backend's PageResponse. */
export function Pager({ page, onChange }) {
  if (!page || page.totalPages <= 1) return null
  return (
    <div className="pager small muted">
      <span>Page {page.pageNumber + 1} of {page.totalPages} · {page.totalElements} items</span>
      <button className="btn btn-sm icon-btn" disabled={page.first} onClick={() => onChange(page.pageNumber - 1)} aria-label="Previous page"><ChevronLeft size={16} /></button>
      <button className="btn btn-sm icon-btn" disabled={page.last} onClick={() => onChange(page.pageNumber + 1)} aria-label="Next page"><ChevronRight size={16} /></button>
    </div>
  )
}

export function CopyChip({ text, label }) {
  const [done, setDone] = useState(false)
  const copy = async () => {
    try { await navigator.clipboard.writeText(String(text)); setDone(true); setTimeout(() => setDone(false), 1500) } catch { /* ignore */ }
  }
  return (
    <button type="button" className="btn btn-sm" onClick={copy}>
      {done ? <Check size={14} /> : <Copy size={14} />} {label ?? text}
    </button>
  )
}

export function Info({ children, content }) {
  return <WarmTooltip content={content}><span className="info-dot">{children ?? '?'}</span></WarmTooltip>
}

export function Loading({ rows = 3 }) {
  return (
    <div className="stack">
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" style={{ height: 44 }} />)}
    </div>
  )
}
