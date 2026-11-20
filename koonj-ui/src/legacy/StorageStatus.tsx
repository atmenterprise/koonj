import type { Navigate } from './Home'
import type { Endpoint } from './sampleData'
import { Badge, Button, Icon, Metric, bytes, count, percent } from '../shared/ui/primitives'
import { isActive, type Workspace } from './workspace'

const tiers = [
  { name: 'Local only', volume: 12.4e12, files: 24_612, color: 'local-tier' },
  { name: 'Synced · Local + S3', volume: 9.6e12, files: 79_048, color: 'sync-tier' },
  { name: 'Optimized · S3 only', volume: 6.6e12, files: 24_760, color: 'cold-tier' },
]
const archiveClasses = [
  { name: 'Standard', volume: 10.4e12, files: 67_824 },
  { name: 'Glacier', volume: 5.8e12, files: 35_984 },
]
const volume = tiers.reduce((total, tier) => total + tier.volume, 0)
const catalogedFiles = tiers.reduce((total, tier) => total + tier.files, 0)
const archivedVolume = archiveClasses.reduce((total, tier) => total + tier.volume, 0)
const archivedFiles = archiveClasses.reduce((total, tier) => total + tier.files, 0)
const optimized = tiers[2]
const monthlyRate = 0.02
const monthlySavings = optimized.volume / 1e9 * monthlyRate
const currency = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP', maximumFractionDigits: 0 })

function StorageEndpoints({ endpoints, workspace, navigate }: {
  endpoints: Endpoint[]; workspace: Workspace; navigate: Navigate
}) {
  return <section className="panel endpoints">
    <div className="panel-heading"><div><h2>Registered storage</h2><p>Source servers and cloud targets</p></div><Badge>{count(endpoints.length)} endpoints</Badge></div>
    {endpoints.length ? <div className="endpoint-grid">{endpoints.map(endpoint => {
      const scanning = workspace.jobs.some(job => job.operation === 'SCAN' && job.target === endpoint.path && isActive(job))
      const status = endpoint.enabled === false ? 'Disabled' : scanning ? 'Scanning' : endpoint.health
      const tone = status === 'Online' ? 'green' : status === 'Scanning' || status === 'Syncing' ? 'blue' : 'neutral'
      return <article className="endpoint" key={endpoint.id}>
        <div className="endpoint-heading"><span className={`icon-tile ${endpoint.origin === 'S3' ? 'blue' : 'neutral'}`}><Icon name={endpoint.origin === 'S3' ? 'cloud' : 'server'} /></span>
          <Badge tone={tone} dot>{status}</Badge>
        </div>
        {endpoint.enabled !== false
          ? <button type="button" className="text-button endpoint-name" onClick={() => navigate('/explorer?' + new URLSearchParams({ endpoint: endpoint.id }))}>{endpoint.name}</button>
          : <strong className="endpoint-name">{endpoint.name}</strong>}
        <div className="endpoint-detail">{endpoint.origin} · {endpoint.detail}</div>
        {endpoint.enabled !== false && <Button icon="scan" disabled={scanning} onClick={() => workspace.scan(endpoint)}>{scanning ? 'Scanning…' : 'Scan now'}</Button>}
      </article>
    })}</div> : <p className="empty compact">No storage endpoints are registered.</p>}
  </section>
}

export default function StorageStatus({ workspace, endpoints, navigate }: {
  workspace: Workspace; endpoints: Endpoint[]; navigate: Navigate
}) {
  const transfers = workspace.jobs.filter(job => isActive(job) && (job.operation === 'ARCHIVE' || job.operation === 'RECALL'))
  const throughput = transfers.reduce((sum, job) => sum + job.speed, 0)
  const legend = tiers.map(tier => `${tier.name}: ${percent(tier.volume, volume).toFixed(1)}%`).join(', ')
  return <>
    <div className="page-heading"><div><h1>Storage status</h1><p>See how much storage you use, what’s saved in S3, and where you’re saving space.</p></div><Badge>Sample totals</Badge></div>

    <div className="metrics storage-metrics">
      <Metric icon="drive" tone="blue" title="Total cataloged volume" value={bytes(volume)}>{count(catalogedFiles)} cataloged files across all tiers</Metric>
      <Metric icon="cloud" tone="blue" title="S3 archived footprint" value={bytes(archivedVolume)}>Combined Standard and Glacier storage</Metric>
      <Metric icon="files" tone="green" title="Files saved to S3" value={count(archivedFiles)}>Files with a copy in S3</Metric>
      <Metric icon="layers" tone="purple" title="Files optimized" value={count(optimized.files)}>Files kept in S3 with local space freed</Metric>
      <Metric icon="check" tone="green" title="Local space reclaimed" value={bytes(14.8e12)}>Total space freed over time</Metric>
      <Metric icon="bolt" tone="yellow" title="Estimated monthly savings" value={currency.format(monthlySavings)}>Based on current S3-only volume</Metric>
    </div>

    <section className="panel distribution">
      <div className="panel-heading"><div><h2>Storage tier distribution</h2><p>How your files are split between local storage and S3</p></div><div className="distribution-total"><strong>{bytes(volume)}</strong><span>Total volume</span></div></div>
      <div className="stacked-bar" role="img" aria-label={legend}>
        {tiers.map(tier => <div key={tier.color} className={tier.color} style={{ flex: tier.volume / volume }}><span>{percent(tier.volume, volume).toFixed(1)}%</span></div>)}
      </div>
      <div className="tier-legend">{tiers.map(tier => <div key={tier.name}><i className={tier.color} /><span>{tier.name}</span><strong>{bytes(tier.volume)}</strong><span>{count(tier.files)} files</span></div>)}</div>
    </section>

    <div className="storage-detail-grid">
      <section className="panel storage-detail">
        <div className="panel-heading"><div><h2>S3 storage classes</h2><p>Archived file counts and volume</p></div><span className="icon-tile blue"><Icon name="cloud" /></span></div>
        <ul className="storage-breakdown">{archiveClasses.map(tier => <li key={tier.name}><strong>{tier.name}</strong><span>{count(tier.files)} files</span><strong>{bytes(tier.volume)}</strong></li>)}</ul>
      </section>
      <section className="panel storage-detail">
        <div className="panel-heading"><div><h2>Savings estimate</h2><p>Monthly and annual storage savings</p></div><span className="icon-tile green"><Icon name="check" /></span></div>
        <div className="storage-savings"><div><strong>{currency.format(monthlySavings)}</strong><span>per month</span></div><div><strong>{currency.format(monthlySavings * 12)}</strong><span>per year</span></div></div>
        <p className="storage-assumption">Illustrative estimate using £0.02 net savings per GB of S3-only storage per month.</p>
      </section>
      <section className="panel storage-detail">
        <div className="panel-heading"><div><h2>Transfer activity</h2><p>Current archive and recall work</p></div><span className="icon-tile yellow"><Icon name="activity" /></span></div>
        <div className="storage-savings"><div><strong>{count(transfers.length)}</strong><span>active transfers</span></div><div><strong>{count(throughput)} MB/s</strong><span>demo throughput</span></div></div>
      </section>
    </div>
    <StorageEndpoints endpoints={endpoints} workspace={workspace} navigate={navigate} />
  </>
}
