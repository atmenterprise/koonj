import { useQuery } from '@tanstack/react-query'
import { usePermissions } from '../../auth/WorkspacePermissions'
import { RolePermissionSettings } from './RolePermissionSettings'
import { adminService, type TelemetrySignal } from '../../api/services/adminService'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import type { Navigate } from '../../shared/ui/feedback'
import { Badge, Icon, Metric, bytes, count, duration, time } from '../../shared/ui/primitives'
import { IntegrationSettings } from './IntegrationSettings'
import { ProjectSettings } from './ProjectSettings'

const sections = [
  { id: 'servers', title: 'Servers', icon: 'server', permission: 'OBSERVABILITY_VIEW' },
  { id: 'telemetry', title: 'Observability', icon: 'activity', permission: 'OBSERVABILITY_VIEW' },
  { id: 'api', title: 'APIs', icon: 'link', permission: 'INTEGRATIONS_MANAGE' },
  { id: 'mcp', title: 'MCP servers', icon: 'layers', permission: 'INTEGRATIONS_MANAGE' },
  { id: 'projects', title: 'Projects', icon: 'folder', permission: 'PROJECTS_MANAGE' },
  { id: 'permissions', title: 'Permissions', icon: 'check', permission: 'PERMISSIONS_MANAGE' },
] as const

const componentLabels: Record<string, string> = {
  db: 'PostgreSQL', diskSpace: 'Server filesystem', ping: 'Application heartbeat',
  livenessState: 'Liveness', readinessState: 'Readiness',
}

function ServerOverview() {
  const telemetry = useQuery({ queryKey: ['admin', 'telemetry'], meta: { errorTitle: 'Could not load server telemetry' },
    queryFn: ({ signal }) => adminService.getTelemetry(signal), refetchInterval: 10_000 })
  if (telemetry.isError) return <ApiPageState loadingLabel="Loading server telemetry" error={telemetry.error}
    retry={() => void telemetry.refetch()} retrying={telemetry.isFetching} />
  if (!telemetry.data) return <ApiPageState loadingLabel="Loading server telemetry" />

  const server = telemetry.data
  const healthTone = (health: string) => health === 'UP' ? 'green' : health === 'DOWN' || health === 'OUT_OF_SERVICE' ? 'red' : 'neutral'
  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>System health</h2><p>Koonj Server · {server.instance}</p></div>
        <Badge tone={healthTone(server.health)} dot>{server.health}</Badge>
      </div>
      <ul className="admin-endpoints">{Object.entries(server.components ?? {}).map(([name, status]) => <li key={name}>
        <span className="icon-tile neutral"><Icon name="server" size={16} /></span>
        <div className="admin-endpoint-label"><strong>{componentLabels[name] ?? name}</strong></div>
        <Badge tone={healthTone(status)} dot>{status}</Badge>
      </li>)}</ul>
    </section>
    <section aria-labelledby="server-metrics">
      <div className="panel-heading admin-telemetry-heading"><h2 id="server-metrics">Server metrics</h2><small>Sampled at {time(server.sampledAt)}</small></div>
      <div className="metrics live-metrics">
        <Metric icon="clock" tone="blue" title="Uptime" value={duration(server.uptimeSeconds)} />
        <Metric icon="server" tone="yellow" title="JVM heap used" value={bytes(server.heapUsedBytes)}>{bytes(server.heapCommittedBytes)} committed</Metric>
        <Metric icon="activity" tone="blue" title="Live platform threads" value={count(server.livePlatformThreads)} />
        <Metric icon="bolt" tone="green" title="Available processors" value={count(server.availableProcessors)}>Java {server.javaVersion}</Metric>
      </div>
    </section>
  </div>
}

const signals = [
  { id: 'logs', title: 'Logs', icon: 'files', description: 'Search application and infrastructure events.' },
  { id: 'metrics', title: 'Metrics', icon: 'activity', description: 'Explore runtime metrics and trends.' },
  { id: 'traces', title: 'Traces', icon: 'link', description: 'Inspect request spans and latency across services.' },
] as const

function observeUrl(base: string, organization: string, signal: TelemetrySignal) {
  const url = new URL(base)
  const path = url.pathname.replace(/\/+$/, '')
  url.pathname = (path.endsWith('/web') ? path : path + '/web') + '/' + signal
  url.searchParams.set('org_identifier', organization)
  return url.href
}

function Observability() {
  const observability = useQuery({ queryKey: ['admin', 'observability'],
    queryFn: ({ signal }) => adminService.getObservability(signal),
    meta: { errorTitle: 'Could not load observability settings' } })
  if (observability.isError) return <ApiPageState error={observability.error} loadingLabel="Loading observability settings"
    retry={() => void observability.refetch()} retrying={observability.isFetching} />
  if (!observability.data) return <ApiPageState loadingLabel="Loading observability settings" />

  const { openObserveUrl, organization, exporters } = observability.data
  return <section className="panel admin-group">
    <div className="panel-heading"><div><h2>OpenTelemetry and OpenObserve</h2>
      <p>Koonj exports telemetry over OTLP. OpenObserve provides the search and investigation tools.</p>
    </div></div>
    {!openObserveUrl && <p className="ops-observability-note">Set <code>KOONJ_OPENOBSERVE_URL</code> to the browser-accessible OpenObserve URL to enable these links.</p>}
    <div className="ops-signal-grid">
      {signals.map(signal => <article className="endpoint" key={signal.id}>
        <div className="endpoint-heading"><span className="icon-tile blue"><Icon name={signal.icon} size={16} /></span>
          <Badge tone={exporters[signal.id] ? 'green' : 'neutral'} dot>{exporters[signal.id] ? 'Export enabled' : 'Export disabled'}</Badge>
        </div>
        <h3>{signal.title}</h3><p className="endpoint-detail">{signal.description}</p>
        {openObserveUrl && <a className="button ops-link" href={observeUrl(openObserveUrl, organization, signal.id)}
          target="_blank" rel="noopener noreferrer">Open {signal.title.toLowerCase()} <Icon name="external" size={14} /></a>}
      </article>)}
    </div>
  </section>
}

export default function AdminPage({ section, navigate, projectId, refreshMemberships }: {
  section: string | null; navigate: Navigate; projectId: string; refreshMemberships: () => void
}) {
  const { can } = usePermissions()
  const available = sections.filter(item => can(item.permission))
  const active = available.find(item => item.id === section) ?? available[0]
  if (!active) return null
  return <>
    <div className="page-heading"><div><h1>Administration</h1><p>Monitor server health, investigate telemetry, and manage projects and integrations.</p></div></div>
    <nav className="admin-tabs filters" aria-label="Administration sections">
      {available.map(item => <button key={item.id} type="button" aria-pressed={item.id === active.id}
        onClick={() => navigate(item.id === 'servers' ? '/admin' : '/admin?section=' + item.id)}>
        <Icon name={item.icon} size={15} />{item.title}
      </button>)}
    </nav>
    {active.id === 'servers' && <ServerOverview />}
    {active.id === 'telemetry' && <Observability />}
    {active.id === 'api' && <IntegrationSettings key="api" kind="API" />}
    {active.id === 'permissions' && <RolePermissionSettings />}
    {active.id === 'projects' && <ProjectSettings projectId={projectId} refreshMemberships={refreshMemberships} />}
    {active.id === 'mcp' && <IntegrationSettings key="mcp" kind="MCP" />}
  </>
}
