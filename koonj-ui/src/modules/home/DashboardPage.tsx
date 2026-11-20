import { useCatalogFiles, useCatalogSnapshot, useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { dashboardSummary, isActiveJob, operationLabels } from '../../api/mappers'
import { ConnectionCard } from '../storage/ConnectionCard'
import { useAuth } from '../../auth/AuthProvider'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { JobStatusBadge, type Navigate } from '../../shared/ui/feedback'
import { Button, Metric, Progress, bytes, count, percent } from '../../shared/ui/primitives'

export default function DashboardPage({ navigate }: { navigate: Navigate }) {
  const { hasRole } = useAuth()
  const canAdminister = hasRole('KOONJ_ADMIN')
  const canOperate = canAdminister || hasRole('KOONJ_OPERATOR')
  const connections = useCatalogLocations()
  const jobs = useTransferJobs(canOperate)
  const catalog = useCatalogSnapshot()
  const catalogCount = useCatalogFiles({ size: 1 })
  const active = jobs.data?.filter(job => job.operation !== 'SCAN' && isActiveJob(job))
  const summary = dashboardSummary(catalog.data ?? [], jobs.data ?? [])
  const nameOf = (id: string | null) => connections.data?.find(item => item.id === id)?.name ?? id ?? '—'

  const queries = [connections, catalog, catalogCount, ...(canOperate ? [jobs] : [])]
  const failed = queries.find(query => query.isError)
  if (failed?.error) return <ApiPageState loadingLabel="Loading dashboard" error={failed.error}
    retry={() => { for (const query of queries) if (query.isError) void query.refetch() }}
    retrying={queries.some(query => query.isError && query.isFetching)} />
  if (queries.some(query => query.isPending)) return <ApiPageState loadingLabel="Loading dashboard" />

  return <>
    <div className="page-heading"><div><h1>Home</h1></div>
      <Button variant="primary" icon="folder" onClick={() => navigate('/explorer')}>Open explorer</Button></div>
    <div className="metrics live-metrics">
      <Metric icon="drive" tone="green" title="Reclaimed local storage" value={catalog.data ? bytes(summary.reclaimedLocalBytes) : '—'} />
      <Metric icon="cloud" tone="blue" title="Cataloged S3 archive" value={catalog.data ? bytes(summary.s3ArchivedBytes) : '—'} />
      <Metric icon="files" tone="yellow" title="Cataloged files" value={catalogCount.data ? count(catalogCount.data.totalElements) : '—'} />
      {canOperate && <Metric icon="activity" tone="blue" title="Active transfers" value={jobs.data ? count(summary.activeTransferCount) : '—'} />}
    </div>
    {canOperate && <section className="panel home-list">
      <div className="panel-heading"><h2>Active transfers</h2><Button onClick={() => navigate('/activities')}>Activity log</Button></div>
      {active?.length ? active.map(job => <div className="transfer" key={job.id}>
        <div className="transfer-title"><button className="text-button transfer-name" onClick={() => navigate('/activities?job=' + job.id)}>
          <strong>{operationLabels[job.operation]} to {nameOf(job.targetConnectionId)}</strong>
        </button><JobStatusBadge status={job.status} /></div>
        <Progress label={operationLabels[job.operation]} value={percent(job.processedFiles, job.totalFiles)} />
        <div className="transfer-meta">{count(job.processedFiles)} / {count(job.totalFiles)} files · {bytes(job.transferredBytes)}</div>
      </div>) : <p className="empty compact">No active transfers.</p>}
    </section>}
    <section className="panel endpoints live-section">
      <div className="panel-heading"><h2>Storage endpoints</h2>{canAdminister && <Button icon="server" onClick={() => navigate('/storage')}>Manage connections</Button>}</div>
      {connections.data?.length
        ? <div className="live-endpoints">{connections.data.map(connection => <ConnectionCard key={connection.id}
          connection={connection} navigate={navigate} />)}</div>
        : <p className="empty compact">No storage connections configured.</p>}
    </section>
  </>
}
