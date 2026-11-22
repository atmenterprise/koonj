import { Fragment, useState } from 'react'
import { useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { isActiveJob, jobLabels, operationLabels } from '../../api/mappers'
import type { TransferJobDTO } from '../../api/types'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { JobStatusBadge } from '../../shared/ui/feedback'
import { Button, Icon, count, percent } from '../../shared/ui/primitives'
import { JobDetails, JobTime } from './JobDetails'

const pageSize = 25
const filters = ['All activity', 'Active', 'Completed', 'Needs attention'] as const
const operationIcons = { ARCHIVE: 'upload', RECALL: 'download', SCAN: 'scan' } as const
const needsAttention = (job: TransferJobDTO) => job.status === 'FAILED' || job.status === 'PARTIAL_FAILURE'

function JobProgress({ job }: { job: TransferJobDTO }) {
  const running = job.status === 'RUNNING'
  const indeterminate = running && !job.totalFiles
  const value = job.status === 'COMPLETED' ? 100 : percent(job.processedFiles, job.totalFiles)
  const unit = job.operation === 'SCAN' ? (job.totalFiles === 1 ? 'entry' : 'entries') : (job.totalFiles === 1 ? 'file' : 'files')
  return <div className="activity-progress" data-status={job.status}>
    <div className="activity-progress-caption">
      {running && <span className="activity-progress-spinner"><Icon name="retry" size={13} /></span>}
      <span>{job.status === 'PENDING' ? 'Queued' : indeterminate ? 'Preparing…' : `${count(job.processedFiles)} / ${count(job.totalFiles)} ${unit}`}</span>
    </div>
    <div className={`activity-progress-meter${indeterminate ? ' indeterminate' : ''}`}
      role="progressbar" aria-label={`Progress of job ${job.id}`} aria-valuenow={indeterminate ? undefined : value}
      aria-valuetext={indeterminate ? 'Preparing' : undefined}>
      <span style={indeterminate ? undefined : { width: `${value}%` }} />
    </div>
  </div>
}

export default function AuditLogPage({ initialJob }: { initialJob: string | null }) {
  const jobs = useTransferJobs(true, true)
  const connections = useCatalogLocations()
  const [filter, setFilter] = useState<typeof filters[number]>('All activity')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState<number>()
  const [expanded, setExpanded] = useState(initialJob)
  const nameOf = (id: string | null) => connections.data?.find(item => item.id === id)?.name ?? id ?? '—'
  const query = search.trim().toLowerCase()
  const filtered = (jobs.data ?? []).filter(job => {
    const matches = filter === 'All activity' || (filter === 'Active' && isActiveJob(job))
      || (filter === 'Completed' && job.status === 'COMPLETED')
      || (filter === 'Needs attention' && needsAttention(job))
    return matches && [job.id, operationLabels[job.operation], jobLabels[job.status], nameOf(job.sourceConnectionId),
      nameOf(job.targetConnectionId), job.triggeredBy ?? '', ...job.requestedPaths].join(' ').toLowerCase().includes(query)
  })
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize))
  const selectedPage = Math.min(pageCount - 1, page ?? Math.max(0, Math.floor(filtered.findIndex(job => job.id === initialJob) / pageSize)))
  const visible = filtered.slice(selectedPage * pageSize, (selectedPage + 1) * pageSize)

  if (jobs.isError && !jobs.data) return <ApiPageState loadingLabel="Loading activity" error={jobs.error}
    retry={() => void jobs.refetch()} retrying={jobs.isFetching} />
  if (jobs.isPending) return <ApiPageState loadingLabel="Loading activity" />

  return <>
    <div className="page-heading"><div><h1>Activity log</h1><p>Track archive, recall and scan jobs.</p></div></div>
    <section className="panel activity-panel" aria-label="Job history">
      <div className="table-tools">
        <div className="filters" aria-label="Activity filters">{filters.map(value =>
          <button key={value} aria-pressed={value === filter} onClick={() => { setFilter(value); setPage(0) }}>{value}</button>)}</div>
        <label className="search"><Icon name="search" size={15} /><input type="search" aria-label="Search activity" placeholder="Search activity"
          value={search} onChange={event => { setSearch(event.target.value); setPage(0) }} /></label>
      </div>
      {visible.length > 0 ?
        <div className="activity-viewport" role="region" aria-label="Scrollable activity table" tabIndex={0}>
        <table className="activity-table activity-ledger">
          <colgroup><col className="activity-disclosure-column" /><col className="activity-name-column" />
            <col /><col className="activity-actor-column" />
            <col className="activity-time-column" /><col className="activity-time-column" />
            <col className="activity-progress-column" /><col className="activity-status-column" /></colgroup>
          <thead><tr>
          <th scope="col"><span className="sr-only">Details</span></th><th scope="col">Activity</th>
          <th scope="col">Storage locations</th><th scope="col">Triggered by</th><th scope="col">Started</th>
          <th scope="col">Finished</th><th scope="col">Progress</th><th scope="col">Status</th>
        </tr></thead><tbody>{visible.map(job => {
          const open = expanded === job.id
          const detailId = 'job-details-' + job.id
          return <Fragment key={job.id}>
            <tr className={open ? 'expanded' : undefined}>
              <td><Button className="icon-button activity-disclosure" variant="quiet" icon={open ? 'down' : 'chevron'}
                aria-label={`Details for ${operationLabels[job.operation].toLowerCase()} job ${job.id}`}
                aria-expanded={open} aria-controls={open ? detailId : undefined} onClick={() => setExpanded(open ? null : job.id)} /></td>
              <td><div className="activity-operation">
                <span className={`icon-tile ${job.operation === 'RECALL' ? 'blue' : job.operation === 'ARCHIVE' ? 'yellow' : 'neutral'}`}>
                  <Icon name={operationIcons[job.operation]} size={15} />
                </span>
                <span><strong>{operationLabels[job.operation]}</strong><small><code title={job.id}>{job.id.slice(0, 8)}</code></small></span>
              </div></td>
              <td><div className="activity-storage">
                <span title={nameOf(job.sourceConnectionId)}>{nameOf(job.sourceConnectionId)}</span>
                {job.targetConnectionId && <small title={nameOf(job.targetConnectionId)}>
                  <Icon name="arrow" size={12} /><span>{nameOf(job.targetConnectionId)}</span></small>}
              </div></td>
              <td><span className="activity-actor" title={job.triggeredBy ?? undefined}>{job.triggeredBy ?? 'Not recorded'}</span></td>
              <td><JobTime timestamp={job.startedAt} fallback={job.status === 'PENDING' ? 'Queued' : 'Not recorded'} /></td>
              <td><JobTime timestamp={job.completedAt} /></td>
              <td><JobProgress job={job} /></td>
              <td><JobStatusBadge status={job.status} />
                {job.failedFiles > 0 && <small className="activity-failure-count">{count(job.failedFiles)} {job.failedFiles === 1 ? 'failure' : 'failures'}</small>}
              </td>
            </tr>
            {open && <tr><td colSpan={8} className="details-cell"><JobDetails job={job} id={detailId} /></td></tr>}
          </Fragment>
        })}</tbody></table></div> : <p className="empty">No matching jobs.</p>}
      <div className="catalog-pagination">
        <span aria-live="polite">{count(filtered.length)} {filtered.length === 1 ? 'activity' : 'activities'}</span>
        <div className="catalog-page-actions">
          <Button disabled={selectedPage === 0} onClick={() => setPage(selectedPage - 1)}>Previous</Button>
          <span>Page {selectedPage + 1} of {pageCount}</span>
          <Button disabled={selectedPage + 1 === pageCount} onClick={() => setPage(selectedPage + 1)}>Next</Button>
        </div>
      </div>
    </section>
  </>
}
