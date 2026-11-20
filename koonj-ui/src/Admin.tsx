import { useState } from 'react'
import AdminActivity from './AdminActivity'
import AdminStorage from './AdminStorage'
import type { Navigate } from './Home'
import type { StorageConfiguration } from './storageConfiguration'
import { Badge, Icon, count, statusTone } from './ui'
import { isActive, type Workspace } from './workspace'

export const adminSections = [
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

function Telemetry({ workspace }: { workspace: Workspace }) {
  const [signal, setSignal] = useState('Metrics')
  const [service, setService] = useState('all')
  const [highlightedTrace, setHighlightedTrace] = useState('')
  const running = workspace.jobs.filter(job => job.status === 'Running')
  const metrics = [
    { service: 'koonj-api', title: 'API response time (p95)', value: '42 ms', detail: 'http.server.request.duration' },
    { service: 'koonj-worker', title: 'Transfer throughput', value: running.reduce((total, job) => total + job.speed, 0) + ' MB/s', detail: 'koonj.transfer.throughput' },
    { service: 'koonj-worker', title: 'Active operations', value: count(running.length), detail: 'koonj.worker.operations.active' },
    { service: 'postgres', title: 'Database connections', value: '7 / 24', detail: 'db.client.connection.count' },
  ]
  const matches = (item: { service: string }) => service === 'all' || item.service === service

  return <section className="panel admin-group">
    <div className="panel-heading"><div><h2>OpenTelemetry</h2><p>Sample metrics, logs and traces. Awaiting a telemetry backend connection.</p></div><Badge>Sample data</Badge></div>
    <div className="admin-filters">
      <div className="filters" role="group" aria-label="Telemetry signal">{['Metrics', 'Logs', 'Traces'].map(item => <button type="button" key={item} aria-pressed={signal === item} onClick={() => setSignal(item)}>{item}</button>)}</div>
      <label>Service<select value={service} onChange={event => { setService(event.target.value); setHighlightedTrace('') }}>
        <option value="all">All services</option>{services.map(item => <option key={item.name}>{item.name}</option>)}
      </select></label>
    </div>
    {signal === 'Metrics' && <div className="admin-metrics">{metrics.filter(matches).map(metric => <article className="endpoint" key={metric.title}>
      <h3>{metric.title}</h3><strong>{metric.value}</strong><code>{metric.detail}</code><small>{metric.service}</small>
    </article>)}</div>}
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

export default function Admin({ workspace, configuration, navigate, section, job }: {
  workspace: Workspace; configuration: StorageConfiguration; navigate: Navigate; section: string | null; job: string | null
}) {
  const active = adminSections.find(item => item.id === section) ?? adminSections[0]
  const enabled = configuration.endpoints.filter(endpoint => endpoint.enabled !== false)
  return <>
    <div className="page-heading"><div><h1>Administration</h1><p>Monitor services, review operations, and manage storage endpoints.</p></div></div>
    <nav className="admin-tabs filters" aria-label="Administration sections">
      {adminSections.map(item => <button key={item.id} type="button" aria-pressed={item.id === active.id}
        onClick={() => navigate(item.id === 'overview' ? '/admin' : '/admin?section=' + item.id)}>
        <Icon name={item.icon} size={15} />{item.title}
      </button>)}
    </nav>
    {active.id === 'overview' && <div className="admin-stack">
      <section className="panel admin-group">
        <div className="panel-heading"><div><h2>System health</h2><p>Demo workspace status</p></div><Badge tone="green" dot>Systems operational</Badge></div>
        <ul className="admin-endpoints">{services.map(service => <li key={service.name}>
          <span className="icon-tile neutral"><Icon name="server" size={16} /></span>
          <div className="admin-endpoint-label"><strong>{service.title}</strong><small>{service.detail}</small></div><Badge tone="green" dot>{service.health}</Badge>
        </li>)}</ul>
      </section>
      <div className="admin-metrics">
        <article className="panel endpoint"><h3>Active operations</h3><strong>{workspace.jobs.filter(isActive).length}</strong></article>
        <article className="panel endpoint"><h3>Enabled source servers</h3><strong>{enabled.filter(endpoint => endpoint.origin !== 'S3').length}</strong></article>
        <article className="panel endpoint"><h3>Enabled cloud targets</h3><strong>{enabled.filter(endpoint => endpoint.origin === 'S3').length}</strong></article>
      </div>
    </div>}
    {active.id === 'telemetry' && <Telemetry workspace={workspace} />}
    {active.id === 'activity' && <AdminActivity key={job} jobs={workspace.jobs} audit={configuration.audit} initialJob={job} />}
    {active.id === 'storage' && <AdminStorage configuration={configuration} />}
  </>
}
