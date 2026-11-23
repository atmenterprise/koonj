import { useRef, useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { connectionStatusOptions, useStorageLocations, useStorageUsage, useTransferJobs } from '../../api/hooks/queries'
import { usePermissions } from '../../auth/WorkspacePermissions'
import { isActiveJob, isObjectStorage } from '../../api/mappers'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Icon, Metric, bytes, count, percent, storageName } from '../../shared/ui/primitives'
import { ConnectionStatus, currentCapacity, providers, roles, type ConnectionProbe } from './StorageConnectivity'
import { currency, monthlySavings, savingsRate, summarizeUsage, type LocationUsage } from './storageUsage'

export default function StorageStatusPage() {
  const { can } = usePermissions()
  const connections = useStorageLocations()
  const usage = useStorageUsage()
  const [locationId, setLocationId] = useState('')
  const locationSelect = useRef<HTMLSelectElement>(null)
  const locations = connections.data ?? []
  const probes = useQueries({ queries: locations.map(connection => connectionStatusOptions(connection.id, connection.active)) })
  const probeById = new Map(locations.map((connection, index) => [connection.id, probes[index]]))
  const summary = usage.data ? summarizeUsage(locations, usage.data) : undefined
  const selected = summary?.locations.get(locationId)
  const selectedConnection = locations.find(connection => connection.id === locationId)
  const scope = selectedConnection?.id ?? ''
  const failure = connections.error ?? usage.error

  return <>
    <div className="page-heading"><div><h1>Storage status</h1><p>Compare capacity, cataloged usage, and estimated savings across storage locations.</p></div></div>
    <div className="panel storage-scope">
      <label htmlFor="storage-location">Storage location</label>
      <select ref={locationSelect} id="storage-location" value={scope} onChange={event => setLocationId(event.target.value)}>
        <option value="">All locations</option>
        {locations.map(connection => <option key={connection.id} value={connection.id}>{connection.name} · {providers[connection.providerType]}</option>)}
      </select>
    </div>
    {failure ? <ApiPageState loadingLabel="Loading storage usage" error={failure}
      retrying={connections.isFetching || usage.isFetching}
      retry={() => { if (connections.isError) void connections.refetch(); if (usage.isError) void usage.refetch() }} />
      : connections.isPending || !summary ? <ApiPageState loadingLabel="Loading storage usage" />
        : selected ? <LocationOverview location={selected} probe={probeById.get(selected.connection.id)} />
          : <PortfolioOverview summary={summary} />}
    {!connections.isError && !connections.isPending && <section className="panel storage-connectivity" aria-labelledby="storage-connectivity-title">
      <div className="panel-heading">
        <div><h2 id="storage-connectivity-title">Storage locations</h2><p>Choose a location to analyze it. Active connections are checked when this page opens.</p></div>
      </div>
      {locations.length ? <div className="storage-location-scroll">
        <table className="storage-location-table">
          <thead><tr><th scope="col">Location</th><th scope="col">Connectivity</th><th scope="col">Cataloged usage</th><th scope="col">Available capacity</th></tr></thead>
          <tbody>{locations.map(connection => {
            const probe = probeById.get(connection.id)
            const stored = summary?.locations.get(connection.id)?.stored
            const capacity = currentCapacity(connection, probe)
            return <tr key={connection.id} data-selected={connection.id === scope || undefined}>
              <th scope="row"><button className="text-button" aria-pressed={connection.id === scope}
                onClick={() => { setLocationId(connection.id); locationSelect.current?.focus() }}>{connection.name}</button>
                <span>{providers[connection.providerType]} · {roles[connection.role]}</span></th>
              <td><ConnectionStatus connection={connection} probe={probe} /></td>
              <td>{stored ? <>{bytes(stored.sizeBytes)}<span>{count(stored.fileCount)} {isObjectStorage(connection.providerType) ? 'objects' : 'files'}</span></> : '—'}</td>
              <td>{capacity ? <>{bytes(capacity.availableBytes)}<span>of {bytes(capacity.totalBytes)}</span></>
                : connection.active && probe?.isFetching ? 'Checking…' : 'Unavailable'}</td>
            </tr>
          })}</tbody>
        </table>
      </div> : <p className="empty compact">No storage locations configured.</p>}
    </section>}
    {can('ACTIVITY_VIEW') && <TransferActivity locationId={scope} />}
  </>
}

function Savings({ reclaimedBytes, description }: { reclaimedBytes: number; description: string }) {
  const monthly = monthlySavings(reclaimedBytes)
  return <section className="panel storage-detail">
    <div className="panel-heading"><div><h2>Estimated cost savings</h2><p>{description}</p></div><span className="icon-tile green"><Icon name="bolt" /></span></div>
    <div className="storage-savings">
      <div><strong>{currency.format(monthly)}</strong><span>per month</span></div>
      <div><strong>{currency.format(monthly * 12)}</strong><span>per year</span></div>
    </div>
    <p className="storage-assumption">Illustrative estimate using {currency.format(savingsRate)} net savings per GB of reclaimed local space per month.</p>
  </section>
}

function PortfolioOverview({ summary }: { summary: ReturnType<typeof summarizeUsage> }) {
  const total = summary.mounted.sizeBytes + summary.object.sizeBytes
  const tiers = [
    { name: 'Mounted storage', data: summary.mounted, color: 'local-tier' },
    { name: 'Object storage', data: summary.object, color: 'cold-tier' },
  ]
  return <>
    <div className="metrics storage-overview-metrics">
      <Metric icon="drive" tone="blue" title="Total cataloged footprint" value={bytes(total)}>Includes each stored copy across {count(summary.locations.size)} locations</Metric>
      <Metric icon="server" tone="blue" title="Mounted storage" value={bytes(summary.mounted.sizeBytes)}>{count(summary.mounted.fileCount)} cataloged files</Metric>
      <Metric icon="cloud" tone="blue" title="Object storage" value={bytes(summary.object.sizeBytes)}>{count(summary.object.fileCount)} cataloged objects</Metric>
      <Metric icon="check" tone="green" title="Local space reclaimed" value={bytes(summary.reclaimed.sizeBytes)}>{count(summary.reclaimed.fileCount)} archived file references</Metric>
    </div>
    <div className="storage-analysis-grid">
      <section className="panel storage-detail">
        <div className="panel-heading"><div><h2>Cataloged footprint by tier</h2><p>Stored bytes across all connections</p></div></div>
        {total > 0 && <div className="stacked-bar" aria-hidden="true">
          {tiers.filter(tier => tier.data.sizeBytes > 0).map(tier => <div key={tier.name} className={tier.color} style={{ flex: percent(tier.data.sizeBytes, total) }} />)}
        </div>}
        <div className="tier-legend">{tiers.map(tier => <div key={tier.name}><i className={tier.color} /><span>{tier.name}</span>
          <strong>{bytes(tier.data.sizeBytes)}</strong><span>{percent(tier.data.sizeBytes, total).toFixed(1)}%</span></div>)}</div>
      </section>
      <Savings reclaimedBytes={summary.reclaimed.sizeBytes} description="Total across all locations" />
    </div>
  </>
}

function LocationOverview({ location, probe }: { location: LocationUsage; probe?: ConnectionProbe }) {
  const { connection, stored, reclaimed, classes } = location
  const cloud = isObjectStorage(connection.providerType)
  const capacity = currentCapacity(connection, probe)
  return <>
    <section className="panel storage-location-profile">
      <div className="panel-heading">
        <div className="storage-location-name"><span className={`icon-tile ${cloud ? 'blue' : 'neutral'}`}><Icon name={cloud ? 'cloud' : 'drive'} /></span>
          <div><h2>{connection.name}</h2><p>{providers[connection.providerType]} · {roles[connection.role]}</p></div>
        </div>
        <ConnectionStatus connection={connection} probe={probe} detailed />
      </div>
      <dl className="storage-location-path">
        <div><dt>{cloud ? 'Bucket' : 'Path'}</dt><dd>{connection.basePathOrBucket}</dd></div>
        {connection.region && <div><dt>Region</dt><dd>{connection.region}</dd></div>}
        {connection.endpointUrl && <div><dt>Endpoint</dt><dd>{connection.endpointUrl}</dd></div>}
      </dl>
    </section>
    <div className="metrics storage-overview-metrics">
      <Metric icon={cloud ? 'cloud' : 'drive'} tone="blue" title="Cataloged usage" value={bytes(stored.sizeBytes)}>{count(stored.fileCount)} {cloud ? 'cataloged objects' : 'cataloged files'}</Metric>
      <Metric icon="check" tone="green" title={cloud ? 'Local space reclaimed by archives here' : 'Local space reclaimed'} value={bytes(reclaimed.sizeBytes)}>{count(reclaimed.fileCount)} archived file references</Metric>
    </div>
    <div className="storage-analysis-grid">
      <section className="panel storage-detail">
        {cloud ? <>
          <div className="panel-heading"><div><h2>Object storage classes</h2><p>Cataloged usage by class</p></div></div>
          <ul className="storage-breakdown">{!classes.size && <li>No cataloged objects.</li>}
            {[...classes.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([name, data]) => <li key={name}>
              <strong>{storageName[name] ?? name}</strong><span>{count(data.fileCount)} objects</span><strong>{bytes(data.sizeBytes)}</strong>
            </li>)}
          </ul>
          <p className="storage-assumption">Capacity and free space are unavailable for this connection.</p>
        </> : <>
          <div className="panel-heading"><div><h2>Filesystem capacity</h2><p>Capacity reported by the filesystem hosting this path</p></div></div>
          {capacity ? <>
            <meter className="storage-capacity-meter" min={0} max={capacity.totalBytes} value={capacity.usedBytes} aria-label="Filesystem space used" />
            <div className="storage-savings">
              <div><strong>{bytes(capacity.totalBytes)}</strong><span>Total</span></div>
              <div><strong>{bytes(capacity.usedBytes)}</strong><span>Used</span></div>
              <div><strong>{bytes(capacity.availableBytes)}</strong><span>Available to Koonj</span></div>
            </div>
          </> : <p className="storage-assumption">{connection.active && probe?.isFetching ? 'Checking capacity…' : 'Filesystem capacity is unavailable.'}</p>}
        </>}
      </section>
      <Savings reclaimedBytes={reclaimed.sizeBytes} description={cloud ? 'Savings attributed to archives at this location' : 'Savings from space reclaimed at this location'} />
    </div>
  </>
}

function TransferActivity({ locationId }: { locationId: string }) {
  const jobs = useTransferJobs()
  const transfers = jobs.data?.filter(job => job.operation !== 'SCAN' && isActiveJob(job)
    && (!locationId || job.sourceConnectionId === locationId || job.targetConnectionId === locationId))
  const throughput = transfers?.reduce((sum, job) => sum + job.transferredBytes / Math.max(1,
    (Date.now() - Date.parse(job.startedAt ?? job.createdAt)) / 1000), 0)
  return <section className="panel storage-detail">
    <div className="panel-heading"><div><h2>Transfer activity</h2><p>{locationId ? 'Archives and recalls involving this location' : 'Archives and recalls across all locations'}</p></div><span className="icon-tile yellow"><Icon name="activity" /></span></div>
    {transfers ? <div className="storage-savings"><div><strong>{count(transfers.length)}</strong><span>active transfers</span></div>
      <div><strong>{bytes(throughput ?? 0)}/s</strong><span>average active transfer rate</span></div></div>
      : <p className="storage-assumption">{jobs.isError ? 'Transfer activity is unavailable.' : 'Loading transfer activity…'}</p>}
  </section>
}
