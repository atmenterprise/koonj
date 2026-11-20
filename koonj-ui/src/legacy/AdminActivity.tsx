import { useState } from 'react'
import type { Job } from './sampleData'
import type { StorageConfiguration } from './storageConfiguration'
import { Badge, Progress, bytes, count, date, duration, percent, statusTone, time } from '../shared/ui/primitives'
import { isActive } from './workspace'

export function activityLabel(job: Job) {
  return job.operation === 'SCAN' ? 'Directory scan' : job.operation === 'VERIFY' ? 'Checksum verification' :
    job.operation === 'ARCHIVE' ? 'Archive transfer' : job.operation === 'RECALL' ? 'Recall transfer' : 'Local purge'
}

function ActivityLog({ job }: { job: Job }) {
  const failures = job.results.filter(result => Boolean(result.error)).length
  return <div className="admin-job-log">
    <h3>Activity log</h3>
    <ol>
      <li><time>{time(job.started)}</time><Badge tone="blue">INFO</Badge><span>Operation accepted.</span></li>
      <li><time>{time(job.started + job.elapsed * 1000)}</time>
        <Badge tone={failures || job.status === 'Partial Failure' || job.status === 'Failed' ? 'red' : 'blue'}>{failures || job.status === 'Failed' || job.status === 'Partial Failure' ? 'ERROR' : 'INFO'}</Badge>
        <span>{job.status} · {count(job.done)} of {count(job.total)} items processed{failures ? ' · ' + count(failures) + ' recorded failures' : ''}.</span></li>
    </ol>
  </div>
}

export default function AdminActivity({ jobs, audit, initialJob }: { jobs: Job[]; audit: StorageConfiguration['audit']; initialJob: string | null }) {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('all')
  const term = query.trim().toLowerCase()
  const visible = [...jobs].sort((left, right) => right.started - left.started).filter(job =>
    (status === 'all' || (status === 'active' && isActive(job)) || (status === 'attention' && ['Failed', 'Partial Failure'].includes(job.status)))
    && [job.id, activityLabel(job), job.actor, job.status].some(value => value.toLowerCase().includes(term)))

  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Activities and logs</h2><p>Operation status, progress, and diagnostic events.</p></div></div>
      <div className="admin-filters">
        <label>Search activities<input type="search" placeholder="Operation, ID or operator" value={query} onChange={event => setQuery(event.target.value)} /></label>
        <label>Status<select value={status} onChange={event => setStatus(event.target.value)}><option value="all">All activities</option><option value="active">Active</option><option value="attention">Needs attention</option></select></label>
      </div>
      <div className="admin-jobs">
        {visible.map(job => <details key={job.id} open={job.id === initialJob || undefined}>
          <summary><div><strong>{activityLabel(job)}</strong><small>{job.id} · {date(job.started)} · {job.actor}</small></div>
            <div className="admin-job-progress"><Progress label={activityLabel(job)} value={percent(job.done, job.total)} />
              <small>{count(job.done)} / {count(job.total)} items · {bytes(job.bytes)} · {duration(job.elapsed)}</small></div>
            <Badge tone={statusTone[job.status]} dot>{job.status}</Badge></summary>
          <ActivityLog job={job} />
        </details>)}
        {!visible.length && <p className="empty compact">No matching activities.</p>}
      </div>
    </section>
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Configuration history</h2><p>Recent changes to registered storage endpoints.</p></div></div>
      {audit.length ? <ul className="admin-audit">{audit.map((event, index) => <li key={event.time + '-' + index}><strong>{event.message}</strong><span>{date(event.time)} · {time(event.time)}</span></li>)}</ul>
        : <p className="empty compact">No changes recorded yet.</p>}
    </section>
  </div>
}
