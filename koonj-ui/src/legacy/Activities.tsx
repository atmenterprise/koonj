import { Fragment, useState } from 'react'
import TransferControls from './TransferControls'
import type { Job } from './sampleData'
import { Badge, Button, Icon, Progress, bytes, count, date, duration, percent, statusTone, storageName, time } from '../shared/ui/primitives'
import { isActive, needsAttention, type Workspace } from './workspace'

const filters = ['All activity', 'Active', 'Completed', 'Needs attention']
const operationTone = { ARCHIVE: 'yellow', RECALL: 'blue', SCAN: 'neutral', VERIFY: 'green', PURGE: 'purple' }

function ExecutionDetails({ job, onRetry, control }: { job: Job; onRetry: () => void; control: Workspace['control'] }) {
  const [resultPage, setResultPage] = useState(0)
  const [failedOnly, setFailedOnly] = useState(false)
  const errors = job.results.filter(result => result.error).length
  const results = failedOnly ? job.results.filter(result => result.error) : job.results
  const pageSize = 12
  const pageCount = Math.ceil(results.length / pageSize)

  return <div className="execution-detail">
    <div className="panel-heading"><div><h3>File execution results</h3><p>{count(job.results.length)} sampled file results{errors ? ` · ${errors} failed` : ''}</p></div>
      {(job.operation === 'ARCHIVE' || job.operation === 'RECALL') && <TransferControls job={job} control={control} />}
      {errors > 0 && <div className="row-actions">
        <Button aria-pressed={failedOnly} onClick={() => { setFailedOnly(!failedOnly); setResultPage(0) }}>{failedOnly ? 'Show all files' : 'Show failures'}</Button>
        <Button variant="primary" icon="retry" onClick={onRetry}>Retry failed files</Button>
      </div>}
    </div>
    {(job.bucket || job.destination) && <p className="execution-options">
      {job.bucket ? `${job.bucket} · ${storageName[job.storageClass ?? 'STANDARD']}${job.purge ? ' · Purge after verification' : ''}` : `${job.destination} · ${job.retrieval} retrieval`}
    </p>}
    {results.length ? <div className="table-scroll"><table className="results-table"><thead><tr>
      <th>File</th><th>Size</th><th>Transfer speed</th><th>S3 version / ETag</th><th>Result</th>
    </tr></thead><tbody>{results.slice(resultPage * pageSize, (resultPage + 1) * pageSize).map((result, index) => {
      const done = !isActive(job) || resultPage * pageSize + index < job.done
      return <tr key={result.name + index}><td><span className="result-file"><Icon name="file" size={14} /> {result.name}</span></td><td>{bytes(result.bytes)}</td>
        <td>{result.speed ? result.speed + ' MB/s' : '—'}</td><td><code>{result.version ?? '—'}{result.etag && <small>{result.etag}</small>}</code></td>
        <td>{result.error ? <span className="error-text">{result.error}</span> : <Badge tone={job.status === 'Cancelled' ? 'neutral' : done ? 'green' : 'blue'}>{job.status === 'Cancelled' ? 'Cancelled' : done ? 'Processed' : 'Pending'}</Badge>}</td>
      </tr>
    })}</tbody></table></div> : <p className="empty compact">No file results are available yet.</p>}
    {pageCount > 1 && <div className="pagination"><Button disabled={resultPage === 0} onClick={() => setResultPage(resultPage - 1)}>Previous</Button><span>Page {resultPage + 1} of {pageCount}</span><Button disabled={resultPage + 1 >= pageCount} onClick={() => setResultPage(resultPage + 1)}>Next</Button></div>}
  </div>
}

export default function Activities({ workspace, initialJob }: { workspace: Workspace; initialJob: string | null }) {
  const [filter, setFilter] = useState('All activity')
  const [search, setSearch] = useState('')
  const [expanded, setExpanded] = useState(initialJob)
  const [showEveryone, setShowEveryone] = useState(() => Boolean(initialJob && workspace.jobs.some(job => job.id === initialJob)
    && !workspace.personalJobs.some(job => job.id === initialJob)))
  const scopedJobs = showEveryone ? workspace.jobs : workspace.personalJobs
  const jobs = [...scopedJobs].sort((left, right) => right.started - left.started).filter(job => {
    const matches = filter === 'All activity' || (filter === 'Active' && isActive(job))
      || (filter === 'Completed' && job.status === 'Completed')
      || (filter === 'Needs attention' && needsAttention(job))
    return matches && [job.id, job.operation, job.title, job.target, job.actor].some(value => value.toLowerCase().includes(search.trim().toLowerCase()))
  })

  return <>
    <div className="page-heading"><div><h1>{showEveryone ? 'Activity log' : 'Your activity'}</h1><p>Track operations and review individual file results.</p></div>
      <Badge tone={workspace.streaming ? 'green' : 'neutral'} dot>{workspace.streaming ? 'Live updates' : 'Connecting'}</Badge>
    </div>
    <div className="activity-summary">
      <div className="panel"><span className="icon-tile blue"><Icon name="activity" /></span><div><strong>{scopedJobs.filter(isActive).length}</strong><span>Active operations</span></div></div>
      <div className="panel"><span className="icon-tile green"><Icon name="check" /></span><div><strong>{scopedJobs.filter(job => job.status === 'Completed').length}</strong><span>Completed</span></div></div>
      <div className="panel"><span className="icon-tile yellow"><Icon name="warning" /></span><div><strong>{scopedJobs.filter(needsAttention).length}</strong><span>Needs attention</span></div></div>
    </div>
    <section className="panel activity-panel" aria-label="Operation history">
      <div className="table-tools">
        <label className="activity-scope">Show<select value={showEveryone ? 'everyone' : 'mine'} onChange={event => setShowEveryone(event.target.value === 'everyone')}>
          <option value="mine">My activity</option><option value="everyone">Everyone’s activity</option>
        </select></label>
        <div className="filters" aria-label="Activity filters">{filters.map(item => <button type="button" key={item} aria-pressed={filter === item} onClick={() => setFilter(item)}>{item}</button>)}</div>
        <label className="search"><Icon name="search" size={16} /><input type="search" placeholder="Search activity" aria-label="Search activity" value={search} onChange={event => setSearch(event.target.value)} /></label>
      </div>
      {initialJob && !workspace.jobs.some(job => job.id === initialJob) && <p className="empty compact">This activity was not found.</p>}
      <div className="table-scroll"><table className="activity-table"><thead><tr><th scope="col"><span className="sr-only">Details</span></th><th scope="col">Activity ID</th><th scope="col">Operation</th><th scope="col">Target / selection</th><th scope="col">Progress</th><th scope="col">Duration</th><th scope="col">Status</th><th scope="col">Timestamp</th></tr></thead>
        <tbody>{jobs.map(job => {
          const open = expanded === job.id
          const toggle = () => setExpanded(open ? null : job.id)
          const progress = percent(job.done, job.total)
          return <Fragment key={job.id}>
            <tr className={open ? 'expanded' : ''} onClick={toggle}>
              <td><Button variant="quiet" className="icon-button" icon={open ? 'down' : 'chevron'} aria-label={(open ? 'Hide ' : 'Show ') + job.id + ' details'} aria-expanded={open} onClick={event => { event.stopPropagation(); toggle() }} /></td>
              <td><code>{job.id}</code></td><td><Badge tone={operationTone[job.operation]}>{job.operation}</Badge></td>
              <td className="activity-target"><strong>{job.title}</strong><small title={job.target}>{job.target}</small>{showEveryone && <small>{job.actor}</small>}</td>
              <td className="activity-progress"><Progress label={job.title} value={progress} tone={job.status === 'Completed' ? 'green-progress' : ''} /><small>{count(job.done)} / {count(job.total)} files · {bytes(job.bytes)}</small></td>
              <td>{duration(job.elapsed)}</td><td><Badge tone={statusTone[job.status]} dot>{job.status}</Badge></td>
              <td>{date(job.started)}<small>{time(job.started)}</small></td>
            </tr>
            {open && <tr><td colSpan={8} className="details-cell"><ExecutionDetails job={job} control={workspace.control} onRetry={() => {
              const id = workspace.retry(job)
              if (id) { setSearch(''); setFilter('All activity'); setExpanded(id) }
            }} /></td></tr>}
          </Fragment>
        })}</tbody>
      </table></div>
      {!jobs.length && <p className="empty">{scopedJobs.length ? 'No operations match your filters.' : showEveryone ? 'No operations yet.' : 'Work you start will appear here.'}</p>}
      <div className="table-footer">{count(jobs.length)} {jobs.length === 1 ? 'operation' : 'operations'}</div>
    </section>
  </>
}
