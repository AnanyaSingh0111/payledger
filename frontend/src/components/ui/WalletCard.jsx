import { Link } from 'react-router-dom'
import { Snowflake } from 'lucide-react'
import { CountUp, TiltedCard } from './Fancy'
import { money } from '../../lib/format'

/** A wallet drawn as a physical card, coloured by currency. Used on the overview and the wallets page. */
export default function WalletCard({ account }) {
  const frozen = account.status === 'FROZEN'
  return (
    <TiltedCard>
      <Link to={`/app/wallets/${account.id}`} className={`wallet-card cur-${account.currency} ${frozen ? 'frozen' : ''}`}>
        <div className="wc-top">
          <div className="chip" />
          {frozen
            ? <span className="wc-status row" style={{ gap: 4 }}><Snowflake size={11} /> FROZEN</span>
            : <span className="wc-cur">{account.currency}</span>}
        </div>
        <div>
          <div className="wc-label">Available balance</div>
          <div className="bal"><CountUp value={account.balance} format={(n) => money(n, account.currency)} /></div>
        </div>
        <div className="wc-foot">
          <span className="wc-name">{account.name}</span>
          <span className="wc-id">ID #{account.id}</span>
        </div>
      </Link>
    </TiltedCard>
  )
}
