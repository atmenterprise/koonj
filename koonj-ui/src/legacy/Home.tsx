import { useState } from 'react'
import TransferControls from './TransferControls'
import { entryName, fileTemperature, type Job } from './sampleData'
import { Badge, Button, Icon, Metric, Progress, count, date, percent, statusTone, temperatureTone } from '../shared/ui/primitives'
import { isActive, needsAttention, type Workspace } from './workspace'

export type Navigate = (path: string) => void

function Transfer({ job, workspace, navigate }: { job: Job; workspace: Workspace; navigate: Navigate }) {
  const percentage = percent(job.done, job.total)
  const paused = job.status === 'Paused'
  return <div className="transfer">
    <div className="transfer-title">
      <span className={`icon-tile ${job.operation === 'ARCHIVE' ? 'yellow' : 'blue'}`}><Icon name={job.operation === 'ARCHIVE' ? 'upload' : 'download'} size={17} /></span>
      <button className="text-button transfer-name" onClick={() => navigate('/activities?job=' + job.id)}><strong>{job.title}</strong><small>{job.target}</small></button>
      <strong className="transfer-percent">{Math.round(percentage)}%</strong>
    </div>
    <Progress label={job.title} value={percentage} tone={job.operation === 'RECALL' ? 'blue-progress' : ''} />
    <div className="transfer-meta">
      <span>{count(job.done)} / {count(job.total)} files <span className="separator">·</span> {paused ? 'Paused' : job.speed + ' MB/s'}</span>
      <TransferControls job={job} control={workspace.control} />
    </div>
  </div>
}

const temperatures = [
  { label: 'HOT', description: 'Under 15 days' },
  { label: 'WARM', description: '15–29 days' },
  { label: 'COLD', description: '30 days or more' },
] as const
type Temperature = typeof temperatures[number]['label']

function FileSuggestions({ workspace, owner, navigate }: { workspace: Workspace; owner: string; navigate: Navigate }) {
  const [filter, setFilter] = useState<Temperature | 'All'>('All')
  const now = Date.now()
  const sources = new Map(workspace.endpoints.filter(endpoint => endpoint.origin !== 'S3').map(endpoint => [endpoint.id, endpoint.name]))
  const files = workspace.catalog.flatMap(file => {
    if (file.folder || file.owner !== owner || !sources.has(file.endpoint)) return []
    const reading = fileTemperature(file.modified, now)
    return reading ? [{ file, ...reading }] : []
  })
  const reviewPriority = ({ file, temperature }: typeof files[number]) =>
    file.state === 'Local Only' && temperature !== 'HOT' ? 0 : temperature === 'HOT' ? 2 : 1
  const visible = files.filter(item => filter === 'All' || item.temperature === filter)
    .sort((left, right) => reviewPriority(left) - reviewPriority(right) || right.age - left.age)
  const limit = 6

  return <section className="panel home-list personal-files">
    <div className="panel-heading"><div><h2>Files to review</h2><p>Suggestions for your files based on when they were last changed.</p></div>
      <span className="icon-tile yellow"><Icon name="clock" /></span></div>
    <div className="temperature-filters filters" role="group" aria-label="File temperature">
      <button type="button" aria-pressed={filter === 'All'} onClick={() => setFilter('All')}>All files</button>
      {temperatures.map(item => <button type="button" key={item.label} aria-pressed={filter === item.label} onClick={() => setFilter(item.label)}>
        <strong>{item.label}</strong><span>{item.description}</span>
      </button>)}
    </div>
    {visible.length ? <ul className="file-suggestions">
      {visible.slice(0, limit).map(({ file, temperature, age }) => {
        const advice = file.state !== 'Local Only' ? 'Check whether you still need it' : temperature === 'COLD' ? 'Consider archiving'
          : temperature === 'WARM' ? 'Review before it goes cold' : 'Recently updated'
        return <li key={file.id}><button type="button" aria-label={'Review ' + entryName(file)}
          onClick={() => navigate('/explorer?' + new URLSearchParams({ entry: file.id }))}>
          <span className="icon-tile neutral"><Icon name="file" size={16} /></span>
          <span className="suggestion-file"><strong>{entryName(file)}</strong><small>{sources.get(file.endpoint)} · {age === 0 ? 'Changed today' : `Changed ${age} ${age === 1 ? 'day' : 'days'} ago`}</small></span>
          <span className="suggestion-advice">{advice}</span><Badge tone={temperatureTone[temperature]}>{temperature}</Badge><Icon name="chevron" size={14} />
        </button></li>
      })}
    </ul> : <p className="empty compact">{files.length ? 'None of your files match this temperature.' : 'No files are assigned to this profile yet.'}</p>}
    {visible.length > limit && <p className="table-footer">Showing {limit} of {count(visible.length)} files. Use a temperature filter to narrow the list.</p>}
  </section>
}

export default function Home({ workspace, name, isGuest, navigate }: { workspace: Workspace; name: string; isGuest: boolean; navigate: Navigate }) {
  const jobs = workspace.personalJobs
  const active = jobs.filter(isActive)
  const live = active.filter(job => job.operation === 'ARCHIVE' || job.operation === 'RECALL')
  const recent = [...jobs].sort((left, right) => right.started - left.started).slice(0, 4)

  return <>
    <div className="page-heading personal-heading">
      <div><h1>{isGuest ? 'Welcome to Koonj' : `Welcome, ${name}`}</h1><p>{isGuest ? 'Browse the demo and follow the work you start in this profile.' : 'Here’s your activity and a few files worth a closer look.'}</p></div>
      <Button variant="primary" icon="folder" onClick={() => navigate('/explorer')}>Open explorer <Icon name="arrow" size={15} /></Button>
    </div>
    <div className="metrics personal-metrics">
      <Metric icon="activity" tone="blue" title="In progress" value={count(active.length)}>Your running or paused work</Metric>
      <Metric icon="check" tone="green" title="Completed" value={count(jobs.filter(job => job.status === 'Completed').length)}>Your finished operations</Metric>
      <Metric icon="warning" tone="yellow" title="Needs attention" value={count(jobs.filter(needsAttention).length)}>Your operations to review</Metric>
    </div>
    <div className="personal-home-grid">
      <FileSuggestions workspace={workspace} owner={name} navigate={navigate} />
      <div className="personal-activity">
        <section className="panel home-list">
          <div className="panel-heading"><div><h2>Your active transfers</h2><p>Archive and recall work you started</p></div></div>
          {live.length ? live.map(job => <Transfer key={job.id} job={job} workspace={workspace} navigate={navigate} />)
            : <p className="empty compact">You have no active transfers.</p>}
        </section>
        <section className="panel home-list">
          <div className="panel-heading"><div><h2>Your recent activity</h2></div><Button variant="quiet" onClick={() => navigate('/activities')}>View all <Icon name="arrow" size={14} /></Button></div>
          {recent.length ? <ul className="personal-recent">{recent.map(job => <li key={job.id}>
            <button type="button" onClick={() => navigate('/activities?' + new URLSearchParams({ job: job.id }))}>
              <span><strong>{job.title}</strong><small>{date(job.started)}</small></span><Badge tone={statusTone[job.status]}>{job.status}</Badge><Icon name="chevron" size={14} />
            </button>
          </li>)}</ul> : <p className="empty compact">Work you start will appear here.</p>}
        </section>
      </div>
    </div>
  </>
}
