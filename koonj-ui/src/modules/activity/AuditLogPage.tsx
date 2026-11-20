import { Fragment, useState } from 'react'
import { useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { isActiveJob, jobLabels, operationLabels } from '../../api/mappers'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { JobStatusBadge } from '../../shared/ui/feedback'
import { Button, Progress, bytes, count, date, percent, time } from '../../shared/ui/primitives'

const pageSize = 25
const filters = ['All activity', 'Active', 'Completed', 'Needs attention'] as const

export default function AuditLogPage({ initialJob }: { initialJob: string | null }) {
  const jobs = useTransferJobs()
  const connections = useCatalogLocations()
  const [filter, setFilter] = useState<typeof filters[number]>('All activity')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState<number>()
  const [expanded, setExpanded] = useState(initialJob)
  const nameOf = (id: string | null) => connections.data?.find(item => item.id === id)?.name ?? id ?? '—'
  const filtered = (jobs.data ?? []).filter(job => {
    const matches = filter === 'All activity' || (filter === 'Active' && isActiveJob(job))
      || (filter === 'Completed' && job.status === 'COMPLETED')
      || (filter === 'Needs attention' && ['FAILED', 'PARTIAL_FAILURE'].includes(job.status))
    return matches && [job.id, operationLabels[job.operation], jobLabels[job.status], nameOf(job.sourceConnectionId),
      nameOf(job.targetConnectionId), job.triggeredBy ?? '', ...job.requestedPaths].join(' ').toLowerCase().includes(search.trim().toLowerCase())
  })
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize))
  const selectedPage = Math.min(pageCount - 1, page ?? Math.max(0, Math.floor(filtered.findIndex(job => job.id === initialJob) / pageSize)))
  const visible = filtered.slice(selectedPage * pageSize, (selectedPage + 1) * pageSize)

  const failed = jobs.isError ? jobs : connections
  if (failed.error) return <ApiPageState loadingLabel="Loading activity" error={failed.error}
    retry={() => { if (jobs.isError) void jobs.refetch(); if (connections.isError) void connections.refetch() }}
    retrying={(jobs.isError && jobs.isFetching) || (connections.isError && connections.isFetching)} />
  if (jobs.isPending || connections.isPending) return <ApiPageState loadingLabel="Loading activity" />

  return <>
    <div className="page-heading"><div><h1>Activity log</h1></div></div>
    <section className="panel activity-panel" aria-label="Job history">
      <div className="table-tools">
        <div className="filters" aria-label="Activity filters">{filters.map(value =>
          <button key={value} aria-pressed={value === filter} onClick={() => { setFilter(value); setPage(0) }}>{value}</button>)}</div>
        <label className="search"><input type="search" aria-label="Search activity" placeholder="Search activity"
          value={search} onChange={event => { setSearch(event.target.value); setPage(0) }} /></label>
      </div>
      {visible.length > 0 ?
        <div className="table-scroll"><table className="activity-table"><thead><tr>
          <th>Job</th><th>Operation</th><th>Source</th><th>Destination</th><th>Progress</th><th>Status</th><th>Created</th>
        </tr></thead><tbody>{visible.map(job => {
          const open = expanded === job.id
          return <Fragment key={job.id}>
            <tr>
              <td><Button className="icon-button" icon={open ? 'down' : 'chevron'} aria-label={'Details for job ' + job.id}
                aria-expanded={open} onClick={() => setExpanded(open ? null : job.id)} /><code title={job.id}>{job.id.slice(0, 8)}</code></td>
              <td>{operationLabels[job.operation]}</td><td>{nameOf(job.sourceConnectionId)}</td><td>{nameOf(job.targetConnectionId)}</td>
              <td className="activity-progress"><Progress label={'Progress of job ' + job.id}
                value={job.status === 'COMPLETED' ? 100 : percent(job.processedFiles, job.totalFiles)} />
                <small>{count(job.processedFiles)} / {count(job.totalFiles)} files</small></td>
              <td><JobStatusBadge status={job.status} /></td><td>{date(job.createdAt)}<small>{time(job.createdAt)}</small></td>
            </tr>
            {open && <tr><td colSpan={7} className="details-cell"><div className="execution-detail">
              <dl className="live-metadata">
                <div><dt>Job ID</dt><dd>{job.id}</dd></div>
                {job.triggeredBy && <div><dt>Triggered by</dt><dd>{job.triggeredBy}</dd></div>}
                <div><dt>Files failed</dt><dd>{count(job.failedFiles)}</dd></div>
                <div><dt>Transferred</dt><dd>{bytes(job.transferredBytes)} / {bytes(job.totalBytes)}</dd></div>
                {job.completedAt && <div><dt>Finished</dt><dd>{date(job.completedAt)} · {time(job.completedAt)}</dd></div>}
                {job.requestedPaths.length > 0 && <div><dt>Requested paths</dt><dd>{job.requestedPaths.join(', ')}</dd></div>}
              </dl>
              {job.details && <pre className="job-details">{job.details}</pre>}
            </div></td></tr>}
          </Fragment>
        })}</tbody></table></div> : <p className="empty">No matching jobs.</p>}
      {pageCount > 1 && <div className="pagination">
        <Button disabled={selectedPage === 0} onClick={() => setPage(selectedPage - 1)}>Previous</Button>
        <span>Page {selectedPage + 1} of {pageCount}</span>
        <Button disabled={selectedPage + 1 === pageCount} onClick={() => setPage(selectedPage + 1)}>Next</Button>
      </div>}
    </section>
  </>
}
