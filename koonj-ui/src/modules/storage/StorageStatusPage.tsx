import { useCatalogLocations, useCatalogSnapshot, useTransferJobs } from '../../api/hooks/queries'
import { isActiveJob, isObjectStorage } from '../../api/mappers'
import type { FileMetadataDTO } from '../../api/types'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import type { Navigate } from '../../shared/ui/feedback'
import { Icon, Metric, bytes, count, percent, storageName } from '../../shared/ui/primitives'
import { StorageConnectionsPage } from './StorageConnectionsPage'

const currency = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP', maximumFractionDigits: 0 })

export default function StorageStatusPage({ navigate }: { navigate: Navigate }) {
  const locations = useCatalogLocations()
  const catalog = useCatalogSnapshot()
  const jobs = useTransferJobs()
  const queries = [locations, catalog, jobs]
  const failed = queries.find(query => query.isError)
  if (failed?.error) return <ApiPageState loadingLabel="Loading storage status" error={failed.error}
    retry={() => { for (const query of queries) if (query.isError) void query.refetch() }} retrying={failed.isFetching} />
  if (queries.some(query => query.isPending)) return <ApiPageState loadingLabel="Loading storage status" />

  const tiers = [
    { name: 'Local only', volume: 0, files: 0, color: 'local-tier' },
    { name: 'Synced · Local + S3', volume: 0, files: 0, color: 'sync-tier' },
    { name: 'Optimized · S3 only', volume: 0, files: 0, color: 'cold-tier' },
  ]
  const cloudIds = new Set(locations.data?.filter(item => isObjectStorage(item.providerType)).map(item => item.id))
  const key = (id: string, path: string) => JSON.stringify([id, path])
  const knownArchiveBindings = new Set<string>()
  const archived = new Map<string, FileMetadataDTO>()
  for (const file of catalog.data ?? []) {
    if (cloudIds.has(file.sourceConnectionId)) continue
    const isArchived = !!file.targetConnectionId && !!file.s3Key && ['ARCHIVED', 'PURGED_LOCAL'].includes(file.storageState)
    const tier = file.storageState === 'PURGED_LOCAL' ? tiers[2] : isArchived ? tiers[1] : tiers[0]
    tier.volume += file.sizeBytes
    tier.files++
    if (isArchived && file.targetConnectionId && file.s3Key) {
      const object = key(file.targetConnectionId, file.s3Key)
      knownArchiveBindings.add(object)
      archived.set(object, file)
    }
  }
  for (const file of catalog.data ?? []) {
    if (!cloudIds.has(file.sourceConnectionId)) continue
    const object = key(file.sourceConnectionId, file.s3Key ?? file.relativePath)
    archived.set(object, file)
    if (!knownArchiveBindings.has(object)) {
      tiers[2].volume += file.sizeBytes
      tiers[2].files++
    }
  }
  const classes = new Map<string, { name: string; files: number; volume: number }>()
  for (const file of archived.values()) {
    const storageClass = file.s3StorageClass ?? 'STANDARD'
    const group = classes.get(storageClass) ?? { name: storageName[storageClass] ?? storageClass, files: 0, volume: 0 }
    group.files++
    group.volume += file.sizeBytes
    classes.set(storageClass, group)
  }
  const archiveClasses = [...classes.values()]
  const volume = tiers.reduce((total, tier) => total + tier.volume, 0)
  const catalogedFiles = tiers.reduce((total, tier) => total + tier.files, 0)
  const archivedFiles = archived.size
  const archivedVolume = archiveClasses.reduce((total, tier) => total + tier.volume, 0)
  const purged = (catalog.data ?? []).filter(file => file.storageState === 'PURGED_LOCAL' && !cloudIds.has(file.sourceConnectionId))
  const optimized = { files: purged.length, volume: purged.reduce((total, file) => total + file.sizeBytes, 0) }
  const monthlySavings = optimized.volume / 1e9 * 0.02
  const transfers = (jobs.data ?? []).filter(job => job.operation !== 'SCAN' && isActiveJob(job))
  const throughput = transfers.reduce((sum, job) => sum + job.transferredBytes / Math.max(1, (Date.now() - Date.parse(job.createdAt)) / 1000), 0)
  const legend = tiers.map(tier => `${tier.name}: ${percent(tier.volume, volume).toFixed(1)}%`).join(', ')
  return <>
    <div className="page-heading"><div><h1>Storage status</h1><p>See how much storage you use, what’s saved in S3, and where you’re saving space.</p></div></div>

    <div className="metrics storage-metrics">
      <Metric icon="drive" tone="blue" title="Total cataloged volume" value={bytes(volume)}>{count(catalogedFiles)} cataloged {catalogedFiles === 1 ? 'file' : 'files'} across all tiers</Metric>
      <Metric icon="cloud" tone="blue" title="S3 archived footprint" value={bytes(archivedVolume)}>Combined Standard and Glacier storage</Metric>
      <Metric icon="files" tone="green" title="Files saved to S3" value={count(archivedFiles)}>Files with a copy in S3</Metric>
      <Metric icon="layers" tone="purple" title="Files optimized" value={count(optimized.files)}>Files kept in S3 with local space freed</Metric>
      <Metric icon="check" tone="green" title="Local space reclaimed" value={bytes(optimized.volume)}>Space reclaimed from cataloged files</Metric>
      <Metric icon="bolt" tone="yellow" title="Estimated monthly savings" value={currency.format(monthlySavings)}>Based on current S3-only volume</Metric>
    </div>

    <section className="panel distribution">
      <div className="panel-heading"><div><h2>Storage tier distribution</h2><p>How your files are split between local storage and S3</p></div><div className="distribution-total"><strong>{bytes(volume)}</strong><span>Total volume</span></div></div>
      <div className="stacked-bar" role="img" aria-label={legend}>
        {tiers.filter(tier => tier.volume > 0).map(tier => {
          const share = percent(tier.volume, volume)
          return <div key={tier.color} className={tier.color} style={{ flex: share }}>
            {share >= 5 && <span>{share.toFixed(1)}%</span>}
          </div>
        })}
      </div>
      <div className="tier-legend">{tiers.map(tier => <div key={tier.name}><i className={tier.color} /><span>{tier.name}</span><strong>{bytes(tier.volume)}</strong><span>{count(tier.files)} {tier.files === 1 ? 'file' : 'files'}</span></div>)}</div>
    </section>

    <div className="storage-detail-grid">
      <section className="panel storage-detail">
        <div className="panel-heading"><div><h2>S3 storage classes</h2><p>Archived file counts and volume</p></div><span className="icon-tile blue"><Icon name="cloud" /></span></div>
        <ul className="storage-breakdown">{!archiveClasses.length && <li>No archived files.</li>}{archiveClasses.map(tier => <li key={tier.name}><strong>{tier.name}</strong><span>{count(tier.files)} {tier.files === 1 ? 'file' : 'files'}</span><strong>{bytes(tier.volume)}</strong></li>)}</ul>
      </section>
      <section className="panel storage-detail">
        <div className="panel-heading"><div><h2>Savings estimate</h2><p>Monthly and annual storage savings</p></div><span className="icon-tile green"><Icon name="check" /></span></div>
        <div className="storage-savings"><div><strong>{currency.format(monthlySavings)}</strong><span>per month</span></div><div><strong>{currency.format(monthlySavings * 12)}</strong><span>per year</span></div></div>
        <p className="storage-assumption">Illustrative estimate using £0.02 net savings per GB of S3-only storage per month.</p>
      </section>
      <section className="panel storage-detail">
        <div className="panel-heading"><div><h2>Transfer activity</h2><p>Current archive and recall work</p></div><span className="icon-tile yellow"><Icon name="activity" /></span></div>
        <div className="storage-savings"><div><strong>{count(transfers.length)}</strong><span>active transfers</span></div><div><strong>{bytes(throughput)}/s</strong><span>average active transfer rate</span></div></div>
      </section>
    </div>
    <StorageConnectionsPage navigate={navigate} embedded />
  </>
}
