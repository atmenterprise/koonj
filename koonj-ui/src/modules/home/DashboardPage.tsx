import { useCatalogSnapshot, useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { dashboardSummary, isActiveJob, operationLabels } from '../../api/mappers'
import type { TransferJobDTO } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { usePermissions } from '../../auth/WorkspacePermissions'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { JobStatusBadge, type Navigate } from '../../shared/ui/feedback'
import { Button, Icon, Metric, Progress, bytes, count, date, percent, time } from '../../shared/ui/primitives'
import { QuickAccess } from './QuickAccess'

const activityIcons = {
  ARCHIVE: { name: 'upload', tone: 'yellow' },
  RECALL: { name: 'download', tone: 'blue' },
  SCAN: { name: 'search', tone: 'neutral' },
} as const

function RecentActivity({ jobs, navigate }: { jobs: TransferJobDTO[]; navigate: Navigate }) {
  const recent = [...jobs].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt)).slice(0, 4)

  return <section className="panel home-list dashboard-recent" aria-labelledby="recent-activity-heading">
    <div className="panel-heading">
      <h2 id="recent-activity-heading">Recent activity</h2>
      <Button variant="quiet" onClick={() => navigate('/activities')}>View activity log <Icon name="arrow" size={14} /></Button>
    </div>
    {recent.length ? <ul className="dashboard-activity-list">{recent.map(job => {
      const icon = activityIcons[job.operation]
      return <li key={job.id}>
        <button type="button" onClick={() => navigate('/activities?' + new URLSearchParams({ job: job.id }))}>
          <span className={`icon-tile ${icon.tone}`}><Icon name={icon.name} size={16} /></span>
          <span className="dashboard-activity-description">
            <strong>{operationLabels[job.operation]}</strong>
            <time dateTime={job.createdAt}>{date(job.createdAt)} · {time(job.createdAt)}</time>
          </span>
          <JobStatusBadge status={job.status} />
          <Icon name="chevron" size={14} />
        </button>
      </li>
    })}</ul> : <p className="empty compact">Recent scans, archives and recalls will appear here.</p>}
  </section>
}

export default function DashboardPage({ navigate }: { navigate: Navigate }) {
  const { user } = useAuth()
  const { can } = usePermissions()
  const canBrowse = can('CATALOG_VIEW')
  const canViewActivity = can('ACTIVITY_VIEW')
  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'

  return <>
    <div className="page-heading personal-heading dashboard-welcome">
      <div>
        <h1>{user?.name ? `${greeting}, ${user.name}` : greeting}</h1>
        {canBrowse && <p>{canViewActivity ? 'Explore your storage catalog and see how recent scans, archives and recalls are progressing.'
          : 'Browse files and folders in your catalog.'}</p>}
      </div>
      {canBrowse && <Button variant="primary" icon="folder" onClick={() => navigate('/explorer')}>
        Open explorer <Icon name="arrow" size={15} />
      </Button>}
    </div>
    {canBrowse && <QuickAccess navigate={navigate} />}
    {(canBrowse || canViewActivity) && <DashboardOverview navigate={navigate} canBrowse={canBrowse} canViewActivity={canViewActivity} />}
  </>
}

function DashboardOverview({ navigate, canBrowse, canViewActivity }: {
  navigate: Navigate; canBrowse: boolean; canViewActivity: boolean
}) {
  const connections = useCatalogLocations(canBrowse && canViewActivity)
  const jobs = useTransferJobs(canViewActivity)
  const catalog = useCatalogSnapshot(undefined, canBrowse)
  const active = jobs.data?.filter(job => job.operation !== 'SCAN' && isActiveJob(job))
  const summary = dashboardSummary(catalog.data ?? [], jobs.data ?? [])
  const nameOf = (id: string | null) =>
    (canBrowse ? connections.data?.find(item => item.id === id)?.name : undefined) ?? id ?? '—'

  const queries = [...(canBrowse ? [catalog] : []), ...(canViewActivity ? [jobs] : []),
    ...(canBrowse && canViewActivity ? [connections] : [])]
  const failed = queries.find(query => query.isError)
  if (failed?.error) return <ApiPageState loadingLabel="Loading dashboard" error={failed.error}
    retry={() => { for (const query of queries) if (query.isError) void query.refetch() }}
    retrying={queries.some(query => query.isError && query.isFetching)} />
  if (queries.some(query => query.isPending)) return <ApiPageState loadingLabel="Loading dashboard" />

  return <>
    <div className="metrics live-metrics">
      {canBrowse && <><Metric icon="drive" tone="green" title="Reclaimed local storage" value={catalog.data ? bytes(summary.reclaimedLocalBytes) : '—'} />
      <Metric icon="cloud" tone="blue" title="Cataloged S3 archive" value={catalog.data ? bytes(summary.s3ArchivedBytes) : '—'} />
      <Metric icon="files" tone="yellow" title="Cataloged files" value={catalog.data ? count(summary.totalCatalogedFiles) : '—'} /></>}
      {canViewActivity && <Metric icon="activity" tone="blue" title="Active transfers" value={jobs.data ? count(summary.activeTransferCount) : '—'} />}
    </div>
    {canViewActivity && <div className="home-grid dashboard-work">
      <RecentActivity jobs={jobs.data ?? []} navigate={navigate} />
      <section className="panel home-list" aria-labelledby="active-transfers-heading">
        <div className="panel-heading"><h2 id="active-transfers-heading">Active transfers</h2></div>
        {active?.length ? active.map(job => <div className="transfer" key={job.id}>
          <div className="transfer-title">
            <button className="text-button transfer-name" onClick={() => navigate('/activities?' + new URLSearchParams({ job: job.id }))}>
              <strong>{operationLabels[job.operation]} to {nameOf(job.targetConnectionId)}</strong>
            </button>
            <JobStatusBadge status={job.status} />
          </div>
          <Progress label={operationLabels[job.operation]} value={percent(job.processedFiles, job.totalFiles)} />
          <div className="transfer-meta">{count(job.processedFiles)} / {count(job.totalFiles)} files · {bytes(job.transferredBytes)}</div>
        </div>) : <p className="empty compact">No active transfers.</p>}
      </section>
    </div>}
  </>
}
