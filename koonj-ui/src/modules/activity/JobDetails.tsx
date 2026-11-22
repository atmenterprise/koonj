import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../api/hooks/queries'
import { isActiveJob } from '../../api/mappers'
import { transferService } from '../../api/services/transferService'
import type { PageResponse, TransferJobDTO } from '../../api/types'
import { LoadingRows } from '../../shared/ui/feedback'
import { Badge, Button, bytes, count, date, time } from '../../shared/ui/primitives'

const views = ['Overview', 'Failures', 'File results', 'Execution log'] as const
const pageSize = 25

export function JobTime({ timestamp, fallback = '—' }: { timestamp?: string | null; fallback?: string }) {
  return timestamp ? <time dateTime={timestamp}>{date(timestamp)}<small>{time(timestamp)}</small></time> : <span className="muted">{fallback}</span>
}

function DetailPage<T>({ result, page, setPage }: { result: PageResponse<T>; page: number; setPage: (page: number) => void }) {
  if (result.totalPages < 2) return null
  return <div className="activity-detail-pagination">
    <span aria-live="polite">{count(page * pageSize + 1)}–{count(page * pageSize + result.content.length)} of {count(result.totalElements)}</span>
    <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
    <Button disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>Next</Button>
  </div>
}

function DetailError({ children, retry }: { children: ReactNode; retry: () => void }) {
  return <div className="row-actions activity-detail-error"><p>{children}</p><Button icon="retry" onClick={retry}>Retry</Button></div>
}

function JobEvents({ job, errorsOnly }: { job: TransferJobDTO; errorsOnly: boolean }) {
  const [page, setPage] = useState(0)
  const events = useQuery({
    queryKey: [...queryKeys.jobs, job.id, 'events', errorsOnly, job.status, page],
    queryFn: ({ signal }) => transferService.getJobEvents(job.id, { page, size: pageSize, errorsOnly }, signal),
    refetchInterval: isActiveJob(job) ? 3000 : false,
    meta: { errorTitle: 'Could not load execution log' },
  })
  const label = errorsOnly ? 'Job and selection errors' : 'Execution log'
  return <section className="activity-detail-section" aria-label={label}>
    <h3>{label}</h3>
    {events.isPending ? <LoadingRows label="Loading execution log" />
      : events.isError ? <DetailError retry={() => void events.refetch()}>Execution log could not be loaded.</DetailError>
      : events.data.content.length ? <>
        <table className="activity-detail-table" aria-label={label}>
          <thead><tr><th scope="col">Time</th><th scope="col">Level</th><th scope="col">Path and message</th></tr></thead>
          <tbody>{events.data.content.map(event => <tr key={event.id}>
            <td><JobTime timestamp={event.recordedAt} /></td>
            <td><Badge tone={event.level === 'ERROR' ? 'red' : event.level === 'WARNING' ? 'yellow' : 'neutral'}>
              {event.level === 'ERROR' ? 'Error' : event.level === 'WARNING' ? 'Warning' : 'Info'}
            </Badge></td>
            <td>{event.path && <code className="activity-path">{event.path}</code>}<span className="activity-message">{event.message}</span></td>
          </tr>)}</tbody>
        </table>
        <DetailPage result={events.data} page={page} setPage={setPage} />
      </> : <p className="muted">{errorsOnly ? 'No job or selection errors were logged.' : 'No execution events were recorded.'}</p>}
  </section>
}

function FileResults({ job, failedOnly }: { job: TransferJobDTO; failedOnly: boolean }) {
  const [page, setPage] = useState(0)
  const results = useQuery({
    queryKey: [...queryKeys.jobs, job.id, 'files', failedOnly, job.status, page],
    queryFn: ({ signal }) => transferService.getJobFiles(job.id, { page, size: pageSize, failedOnly }, signal),
    refetchInterval: isActiveJob(job) ? 3000 : false,
    meta: { errorTitle: 'Could not load file results' },
  })
  const label = failedOnly ? 'Failed files' : 'File results'
  return <section className="activity-detail-section" aria-label={label}>
    <h3>{label}</h3>
    {results.isPending ? <LoadingRows label="Loading file results" />
      : results.isError ? <DetailError retry={() => void results.refetch()}>File results could not be loaded.</DetailError>
      : results.data.content.length ? <>
        <table className="activity-detail-table activity-file-results" aria-label={label}>
          <thead><tr><th scope="col">File</th><th scope="col">Time</th><th scope="col">{failedOnly ? 'Failure reason' : 'Result'}</th></tr></thead>
          <tbody>{results.data.content.map(file => <tr key={file.id}>
            <td><code className="activity-path">{file.sourcePath}</code>
              {file.targetPath !== file.sourcePath && <small>Destination · {file.targetPath}</small>}</td>
            <td><JobTime timestamp={file.recordedAt} /></td>
            <td>{file.outcome === 'VERIFIED' ? <Badge tone="green" dot>Checksums match</Badge> :
              <span className="activity-message error-text">{file.error || 'The file transfer failed.'}</span>}</td>
          </tr>)}</tbody>
        </table>
        <DetailPage result={results.data} page={page} setPage={setPage} />
      </> : <p className="muted">{failedOnly ? 'No individual file failures were recorded.' : 'No per-file transfer results were recorded.'}</p>}
  </section>
}

export function JobDetails({ job, id }: { job: TransferJobDTO; id: string }) {
  const [view, setView] = useState<typeof views[number]>(() =>
    job.status === 'FAILED' || job.status === 'PARTIAL_FAILURE' ? 'Failures' : 'Overview')
  return <div className="execution-detail" id={id}>
    <div className="filters" role="group" aria-label="Job details">{views.map(value =>
      <button key={value} type="button" aria-pressed={view === value} onClick={() => setView(value)}>{value}</button>)}
    </div>
    {view === 'Overview' && <section className="activity-detail-section" aria-label="Job overview">
      <dl className="activity-job-facts">
        <div><dt>Job ID</dt><dd><code>{job.id}</code></dd></div>
        <div><dt>Triggered by</dt><dd>{job.triggeredBy ?? 'Not recorded'}</dd></div>
        <div><dt>Submitted</dt><dd><JobTime timestamp={job.createdAt} /></dd></div>
        <div><dt>Started</dt><dd><JobTime timestamp={job.startedAt} fallback={job.status === 'PENDING' ? 'Queued' : 'Not recorded'} /></dd></div>
        <div><dt>Finished</dt><dd><JobTime timestamp={job.completedAt} /></dd></div>
        <div><dt>Transferred</dt><dd>{bytes(job.transferredBytes)} / {bytes(job.totalBytes)}</dd></div>
        <div><dt>Failures</dt><dd>{count(job.failedFiles)}</dd></div>
        {job.operation === 'ARCHIVE' && job.archiveMode && <div><dt>Archive mode</dt><dd>{job.archiveMode === 'PURGE' ? 'Purge' : 'Copy'}</dd></div>}
        {job.requestedPaths.length > 0 && <div className="activity-job-paths"><dt>Requested paths</dt>
          <dd><ul>{job.requestedPaths.map(path => <li key={path}><code>{path}</code></li>)}</ul></dd></div>}
      </dl>
    </section>}
    {view === 'Failures' && <><FileResults key="failed" job={job} failedOnly /><JobEvents key="errors" job={job} errorsOnly /></>}
    {view === 'File results' && <FileResults key="all-files" job={job} failedOnly={false} />}
    {view === 'Execution log' && <JobEvents key="all-events" job={job} errorsOnly={false} />}
    {job.details && (view === 'Overview' || (!job.startedAt && view === 'Failures')) && <section className="activity-detail-section">
      <h3>Recorded job messages</h3><p className="activity-message">{job.details}</p>
    </section>}
  </div>
}
