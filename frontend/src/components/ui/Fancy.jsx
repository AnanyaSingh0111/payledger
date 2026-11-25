import { useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from '../effects/motion'
import '../effects/effects.css'

/* ------------------------------------------------------------------ CountUp */
/** Animates from the previous value (or 0) to `value` when it scrolls into view. */
export function CountUp({ value, duration = 1200, format = (n) => Math.round(n).toLocaleString('en-IN'), className = '' }) {
  const [shown, setShown] = useState(0)
  const ref = useRef(null)
  const from = useRef(0)

  useEffect(() => {
    const el = ref.current
    if (!el || value == null || Number.isNaN(Number(value))) return undefined
    const target = Number(value)
    if (prefersReducedMotion()) { setShown(target); from.current = target; return undefined }
    let raf = 0
    let started = false
    const run = () => {
      started = true
      const start = performance.now()
      const begin = from.current
      const tick = (now) => {
        const p = Math.min(1, (now - start) / duration)
        const eased = p === 1 ? 1 : 1 - Math.pow(2, -10 * p) // easeOutExpo
        setShown(begin + (target - begin) * eased)
        if (p < 1) raf = requestAnimationFrame(tick)
        else from.current = target
      }
      raf = requestAnimationFrame(tick)
    }
    const io = new IntersectionObserver(([entry]) => { if (entry.isIntersecting && !started) run() }, { threshold: 0.2 })
    io.observe(el)
    return () => { io.disconnect(); cancelAnimationFrame(raf) }
  }, [value, duration])

  return <span ref={ref} className={`countup ${className}`}>{format(shown)}</span>
}

/* ------------------------------------------------------------------ SpotlightCard */
/** A panel with a faint light that follows the pointer. */
export function SpotlightCard({ children, className = '', color, style, as: Tag = 'div', ...rest }) {
  const onMove = (e) => {
    const r = e.currentTarget.getBoundingClientRect()
    e.currentTarget.style.setProperty('--x', `${e.clientX - r.left}px`)
    e.currentTarget.style.setProperty('--y', `${e.clientY - r.top}px`)
  }
  return (
    <Tag className={`panel spotlight ${className}`} onMouseMove={onMove} style={{ ...(color ? { '--spot': color } : {}), ...style }} {...rest}>
      {children}
    </Tag>
  )
}

/* ------------------------------------------------------------------ TiltedCard */
/** Tilts its child toward the pointer, with a soft glare. Kept subtle (max 7°). */
export function TiltedCard({ children, max = 7, className = '' }) {
  const [active, setActive] = useState(false)
  const inner = useRef(null)
  const onMove = (e) => {
    if (prefersReducedMotion()) return
    const r = e.currentTarget.getBoundingClientRect()
    const px = (e.clientX - r.left) / r.width
    const py = (e.clientY - r.top) / r.height
    inner.current.style.transform = `rotateX(${(0.5 - py) * max}deg) rotateY(${(px - 0.5) * max}deg) translateY(-2px)`
    inner.current.style.setProperty('--gx', `${px * 100}%`)
    inner.current.style.setProperty('--gy', `${py * 100}%`)
  }
  const reset = () => { setActive(false); if (inner.current) inner.current.style.transform = '' }
  return (
    <div className={`tilt ${active ? 'active' : ''} ${className}`} onMouseEnter={() => setActive(true)} onMouseMove={onMove} onMouseLeave={reset}>
      <div className="tilt-inner" ref={inner}>
        {children}
        <div className="tilt-glare" />
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ WarmTooltip */
export function WarmTooltip({ content, children, placement = 'top', align = 'center' }) {
  return (
    <span className={`wt ${placement === 'bottom' ? 'bottom' : ''} ${align === 'end' ? 'end' : ''}`} tabIndex={0}>
      {children}
      <span className="wt-bubble" role="tooltip">{content}</span>
    </span>
  )
}
