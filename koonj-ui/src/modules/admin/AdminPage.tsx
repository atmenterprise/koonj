import { useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useState } from 'react'
import { useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { isActiveJob, isObjectStorage } from '../../api/mappers'
import { adminService } from '../../api/services/adminService'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { LoadingRows, type Navigate } from '../../shared/ui/feedback'
import { Badge, Button, Icon, Metric, bytes, count, duration, statusTone, time } from '../../shared/ui/primitives'
import { StorageConnectionsPage } from '../storage/StorageConnectionsPage'

const AuditLogPage = lazy(() => import('../activity/AuditLogPage'))

const sections = [
  { id: 'overview', title: 'Overview', icon: 'home' },
  { id: 'telemetry', title: 'Telemetry', icon: 'activity' },
  { id: 'activity', title: 'Activities', icon: 'clock' },
  { id: 'storage', title: 'Storage configuration', icon: 'server' },
] as const

const services = [
  { name: 'koonj-api', title: 'API server', health: 'Healthy', detail: 'Request handling and job scheduling' },
  { name: 'koonj-worker', title: 'Transfer worker', health: 'Healthy', detail: 'Background transfer processing' },
  { name: 'postgres', title: 'Database', health: 'Healthy', detail: 'Job and configuration storage' },
] as const

const traceId = '4bf92f3577b34da6a3ce929d0e0e4736'
const systemLogs = [
  { time: '10:42:12 UTC', service: 'koonj-worker', severity: 'WARN', event: 'Transfer verification reported failures.', trace: traceId },
  { time: '10:42:08 UTC', service: 'koonj-api', severity: 'INFO', event: 'Worker health check passed.', trace: 'a6e115f716a1450788615dd9ba5124a4' },
  { time: '10:41:59 UTC', service: 'postgres', severity: 'INFO', event: 'Database connection pool check passed.', trace: 'd4a45a0b0d8b4cb1a72e0c5a47cc5ac3' },
]
const traces = [
  { id: traceId, operation: 'transfer.verify', service: 'koonj-worker', duration: '184 ms', status: 'Failed', spans: ['transfer.verify', 'storage.read', 'checksum.compare'] },
  { id: 'a6e115f716a1450788615dd9ba5124a4', operation: 'worker.health_check', service: 'koonj-api', duration: '18 ms', status: 'Completed', spans: ['worker.health_check', 'worker.status'] },
  { id: 'd4a45a0b0d8b4cb1a72e0c5a47cc5ac3', operation: 'db.connection_check', service: 'postgres', duration: '6 ms', status: 'Completed', spans: ['db.connection_check'] },
]

function useServerTelemetry() {
  return useQuery({ queryKey: ['admin', 'telemetry'], meta: { errorTitle: 'Could not load server telemetry' },
    queryFn: ({ signal }) => adminService.getTelemetry(signal), refetchInterval: 10_000 })
}

function ServerMetrics() {
  const telemetry = useServerTelemetry()
  if (telemetry.error) return <ApiPageState loadingLabel="Loading server telemetry" error={telemetry.error}
    retry={() => void telemetry.refetch()} retrying={telemetry.isFetching} />
  if (!telemetry.data) return <ApiPageState loadingLabel="Loading server telemetry" />

  const server = telemetry.data
  return <section aria-labelledby="server-telemetry">
    <div className="panel-heading admin-telemetry-heading">
      <h2 id="server-telemetry">Server telemetry</h2>
      <Badge tone={server.health === 'UP' ? 'green' : 'red'} dot>{server.health}</Badge>
    </div>
    <div className="metrics live-metrics">
      <Metric icon="clock" tone="blue" title="Server uptime" value={duration(server.uptimeSeconds)} />
      <Metric icon="server" tone="yellow" title="JVM heap used" value={bytes(server.heapUsedBytes)}>
        {bytes(server.heapCommittedBytes)} committed
      </Metric>
      <Metric icon="activity" tone="blue" title="Live platform threads" value={count(server.livePlatformThreads)} />
      <Metric icon="bolt" tone="green" title="Available processors" value={count(server.availableProcessors)}>
        Java {server.javaVersion}
      </Metric>
    </div>
    <p className="endpoint-detail">Sampled at {time(server.sampledAt)}</p>
  </section>
}

function Telemetry() {
  const [signal, setSignal] = useState('Metrics')
  const [service, setService] = useState('all')
  const [highlightedTrace, setHighlightedTrace] = useState('')
  const matches = (item: { service: string }) => service === 'all' || item.service === service
  return <section className="panel admin-group">
    <div className="panel-heading"><div><h2>OpenTelemetry</h2>
      <p>{signal === 'Metrics' ? 'Live metrics from Koonj Server' : 'Example telemetry events'}</p></div>
      {signal !== 'Metrics' && <Badge>Sample data</Badge>}</div>
    <div className="admin-filters">
      <div className="filters" role="group" aria-label="Telemetry signal">{['Metrics', 'Logs', 'Traces'].map(item =>
        <button type="button" key={item} aria-pressed={signal === item} onClick={() => setSignal(item)}>{item}</button>)}</div>
      {signal !== 'Metrics' && <label>Service<select value={service} onChange={event => { setService(event.target.value); setHighlightedTrace('') }}>
        <option value="all">All services</option>{services.map(item => <option key={item.name}>{item.name}</option>)}
      </select></label>}
    </div>
    {signal === 'Metrics' && <ServerMetrics />}
    {signal === 'Logs' && <div className="admin-signal-list">{systemLogs.filter(matches).map(log => <article key={log.trace}>
      <div className="admin-signal-heading"><Badge tone={log.severity === 'WARN' ? 'yellow' : 'blue'}>{log.severity}</Badge><strong>{log.event}</strong><small>{log.time} · {log.service}</small></div>
      <button className="text-button" type="button" onClick={() => { setHighlightedTrace(log.trace); setSignal('Traces') }}>View trace <Icon name="arrow" size={13} /></button>
    </article>)}</div>}
    {signal === 'Traces' && <div className="admin-jobs">{traces.filter(matches).map(trace => <details key={trace.id} open={trace.id === highlightedTrace || undefined}>
      <summary><div><strong>{trace.operation}</strong><small>{trace.service}</small></div><span>{trace.duration}</span><Badge tone={statusTone[trace.status]}>{trace.status}</Badge></summary>
      <div className="admin-job-log"><h3>Spans</h3><p>Trace <code>{trace.id}</code></p><ul>{trace.spans.map(span => <li key={span}>{span}</li>)}</ul></div>
    </details>)}</div>}
  </section>
}

function Overview() {
  const telemetry = useServerTelemetry()
  const locations = useCatalogLocations()
  const jobs = useTransferJobs()
  const enabled = locations.data?.filter(location => location.active) ?? []
  const health = telemetry.data?.health

  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>System health</h2><p>Koonj Server and supporting services</p></div>
        <Badge tone={health === 'UP' ? 'green' : 'neutral'} dot>{health ?? (telemetry.isPending ? 'Checking' : 'Unavailable')}</Badge></div>
      <ul className="admin-endpoints">{services.map(service => <li key={service.name}>
        <span className="icon-tile neutral"><Icon name="server" size={16} /></span>
        <div className="admin-endpoint-label"><strong>{service.title}</strong><small>{service.detail}</small></div>
        {service.name === 'koonj-api' ? <Badge tone={health === 'UP' ? 'green' : 'neutral'} dot>{health ?? '—'}</Badge>
          : <Badge>Sample · {service.health}</Badge>}
      </li>)}</ul>
    </section>
    <div className="admin-metrics">
      <article className="panel endpoint"><h3>Active operations</h3><strong>{jobs.data ? count(jobs.data.filter(isActiveJob).length) : '—'}</strong></article>
      <article className="panel endpoint"><h3>Enabled source servers</h3><strong>{locations.data ? count(enabled.filter(item => !isObjectStorage(item.providerType)).length) : '—'}</strong></article>
      <article className="panel endpoint"><h3>Enabled cloud targets</h3><strong>{locations.data ? count(enabled.filter(item => isObjectStorage(item.providerType)).length) : '—'}</strong></article>
    </div>
  </div>
}

export default function AdminPage({ section, job, navigate }: {
  section: string | null; job: string | null; navigate: Navigate
}) {
  const active = sections.find(item => item.id === section) ?? sections[0]
  return <>
    <div className="page-heading"><div><h1>Administration</h1><p>Monitor services, review operations, and manage storage endpoints.</p></div></div>
    <nav className="admin-tabs filters" aria-label="Administration sections">
      {sections.map(item => <button key={item.id} type="button" aria-pressed={item.id === active.id}
        onClick={() => navigate(item.id === 'overview' ? '/admin' : '/admin?section=' + item.id)}>
        <Icon name={item.icon} size={15} />{item.title}
      </button>)}
    </nav>
    {active.id === 'overview' && <Overview />}
    {active.id === 'telemetry' && <Telemetry />}
    {active.id === 'activity' && <Suspense fallback={<LoadingRows label="Loading activity" />}>
      <AuditLogPage key={job} initialJob={job} />
    </Suspense>}
    {active.id === 'storage' && <StorageConnectionsPage navigate={navigate} embedded grouped />}
  </>
}
