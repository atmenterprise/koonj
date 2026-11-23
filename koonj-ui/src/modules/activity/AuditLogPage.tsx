import { Fragment, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { jobHistoryKey, queryKeys, useCatalogLocations, useTransferJobs } from '../../api/hooks/queries'
import { isActiveJob, jobLabels, operationLabels } from '../../api/mappers'
import type { JobAction, TransferJobDTO } from '../../api/types'
import { transferService } from '../../api/services/transferService'
import { useAuth } from '../../auth/AuthProvider'
import { useActivityNotifications } from '../../shared/notifications/ActivityNotifications'
import { errorDetail } from '../../shared/notifications/activity'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { JobStatusBadge } from '../../shared/ui/feedback'
import { Button, Icon, count, percent } from '../../shared/ui/primitives'
import { JobDetails, JobTime } from './JobDetails'

const pageSize = 25
const filters = ['All activity', 'Active', 'Completed', 'Needs attention'] as const
const operationIcons = { ARCHIVE: 'upload', RECALL: 'download', SCAN: 'scan' } as const
const needsAttention = (job: TransferJobDTO) => job.status === 'FAILED' || job.status === 'PARTIAL_FAILURE'
const controls = {
  PAUSE: { label: 'Pause', icon: 'pause' },
  RESUME: { label: 'Resume', icon: 'play' },
  STOP: { label: 'Stop', icon: 'stop' },
  RESTART: { label: 'Restart', icon: 'retry' },
} as const

function actionsFor(job: TransferJobDTO, isAdmin: boolean): JobAction[] {
  if (!job.controllable) return []
  const canRun = job.operation !== 'ARCHIVE' || job.archiveMode !== 'PURGE' || isAdmin
  if (job.status === 'RUNNING' || job.status === 'PENDING') return ['PAUSE', 'STOP']
  if (job.status === 'PAUSING') return ['STOP']
  if (job.status === 'PAUSED') return canRun ? ['RESUME', 'STOP'] : ['STOP']
  if (job.status === 'STOPPED' && !job.restartedAs && canRun) return ['RESTART']
  return []
}

function JobProgress({ job }: { job: TransferJobDTO }) {
  const running = ['RUNNING', 'PAUSING', 'STOPPING'].includes(job.status)
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
  const { hasRole, user } = useAuth()
  const { notify } = useActivityNotifications()
  const client = useQueryClient()
  const jobs = useTransferJobs(true, true)
  const connections = useCatalogLocations()
  const [filter, setFilter] = useState<typeof filters[number]>('All activity')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState<number>()
  const [expanded, setExpanded] = useState(initialJob)
  const control = useMutation({
    mutationFn: ({ id, action }: { id: string; action: JobAction }) => transferService.controlJob(id, action),
    onSuccess: (updated, { id, action }) => {
      client.setQueryData<TransferJobDTO[]>(jobHistoryKey, previous => action === 'RESTART'
        ? [updated, ...(previous ?? []).filter(job => job.id !== updated.id)
          .map(job => job.id === id ? { ...job, restartedAs: updated.id } : job)]
        : (previous ?? []).map(job => job.id === id ? updated : job))
      if (action === 'RESTART') {
        setFilter('All activity')
        setSearch('')
        setPage(0)
        setExpanded(updated.id)
      }
      void client.invalidateQueries({ queryKey: queryKeys.jobs })
      void client.invalidateQueries({ queryKey: queryKeys.activeArchives })
      if (updated.status === 'PAUSED' || updated.status === 'STOPPED') void client.invalidateQueries({ queryKey: queryKeys.files })
    },
    onError: (error, { action }) => {
      notify({ tone: 'error', title: `Could not ${controls[action].label.toLowerCase()} job`, detail: errorDetail(error) })
      void client.invalidateQueries({ queryKey: queryKeys.jobs })
    },
  })
  const nameOf = (id: string | null) => {
    if (!id) return '—'
    return connections.data?.find(item => item.id === id)?.name
      ?? (connections.isPending ? 'Loading connection…' : 'Unknown connection')
  }
  const actorOf = (job: TransferJobDTO) => {
    if (job.triggeredByName) return job.triggeredByName
    const account = job.triggeredBy
    if (user && account && (account === user.id || account.toLowerCase() === user.email?.toLowerCase())) return user.name
    return account ?? 'Not recorded'
  }
  const query = search.trim().toLowerCase()
  const filtered = (jobs.data ?? []).filter(job => {
    const matches = filter === 'All activity' || (filter === 'Active' && (isActiveJob(job) || job.status === 'PAUSED'))
      || (filter === 'Completed' && job.status === 'COMPLETED')
      || (filter === 'Needs attention' && needsAttention(job))
    return matches && [job.id, operationLabels[job.operation], jobLabels[job.status], nameOf(job.sourceConnectionId),
      nameOf(job.targetConnectionId), actorOf(job), job.triggeredBy ?? '', ...job.requestedPaths].join(' ').toLowerCase().includes(query)
  })
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize))
  const selectedPage = Math.min(pageCount - 1, page ?? Math.max(0, Math.floor(filtered.findIndex(job => job.id === initialJob) / pageSize)))
  const visible = filtered.slice(selectedPage * pageSize, (selectedPage + 1) * pageSize)

  if (jobs.isError && !jobs.data) return <ApiPageState loadingLabel="Loading activity" error={jobs.error}
    retry={() => void jobs.refetch()} retrying={jobs.isFetching} />
  if (jobs.isPending) return <ApiPageState loadingLabel="Loading activity" />

  return <>
    <div className="page-heading"><div><h1>Activity log</h1><p>Track the archive, recall and scan jobs you started in this project.</p></div></div>
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
            <col className="activity-storage-column" /><col className="activity-actor-column" />
            <col className="activity-time-column" /><col className="activity-time-column" />
            <col className="activity-progress-column" /><col className="activity-status-column" /></colgroup>
          <thead><tr>
          <th scope="col"><span className="sr-only">Details</span></th><th scope="col">Activity</th>
          <th scope="col">Storage locations</th><th scope="col">Triggered by</th><th scope="col">Started</th>
          <th scope="col">Finished</th><th scope="col">Progress</th><th scope="col">Status</th>
        </tr></thead><tbody>{visible.map(job => {
          const open = expanded === job.id
          const detailId = 'job-details-' + job.id
          const source = nameOf(job.sourceConnectionId)
          const destination = job.targetConnectionId ? nameOf(job.targetConnectionId) : undefined
          const actor = actorOf(job)
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
                <span>{source}</span>
                {destination && <><span className="activity-storage-arrow" aria-hidden="true">→</span>
                  <span className="sr-only"> to </span><span>{destination}</span></>}
              </div></td>
              <td><span className="activity-actor">{actor}</span></td>
              <td><JobTime timestamp={job.startedAt} fallback={job.status === 'PENDING' ? 'Queued' : 'Not recorded'} /></td>
              <td><JobTime timestamp={job.completedAt} /></td>
              <td><JobProgress job={job} /></td>
              <td><JobStatusBadge status={job.status} />
                {job.failedFiles > 0 && <small className="activity-failure-count">{count(job.failedFiles)} {job.failedFiles === 1 ? 'failure' : 'failures'}</small>}
                <div className="activity-job-controls">{actionsFor(job, hasRole('KOONJ_ADMIN')).map(action =>
                  <Button key={action} variant="quiet" icon={controls[action].icon} disabled={control.isPending}
                    aria-label={`${controls[action].label} ${operationLabels[job.operation].toLowerCase()} job ${job.id}`}
                    title={action === 'PAUSE' || action === 'STOP' ? 'Takes effect at the next safe checkpoint' : undefined}
                    onClick={() => control.mutate({ id: job.id, action })}>{controls[action].label}</Button>)}</div>
              </td>
            </tr>
            {open && <tr><td colSpan={8} className="details-cell"><JobDetails job={job} id={detailId} actor={actor} /></td></tr>}
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
