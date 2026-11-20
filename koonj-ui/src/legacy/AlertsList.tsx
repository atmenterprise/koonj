import type { Navigate } from './Home'
import { alerts } from './sampleData'
import { Icon } from '../shared/ui/primitives'

export function alertsForJobs(jobs: { id: string }[]) {
  const ids = new Set(jobs.map(job => job.id))
  return alerts.filter(alert => ids.has(alert.job))
}

export function activityHref(job: string, operationsOnly: boolean) {
  const params = new URLSearchParams(operationsOnly ? { section: 'activity', job } : { job })
  return (operationsOnly ? '/admin?' : '/activities?') + params
}

export default function AlertsList({ navigate, items, operationsOnly = false }: { navigate: Navigate; items: ReturnType<typeof alertsForJobs>; operationsOnly?: boolean }) {
  return <div className="notifications">
    {items.length ? items.map(alert => <button type="button" className="notification" key={alert.title} onClick={() => navigate(activityHref(alert.job, operationsOnly))}>
      <span className={`icon-tile ${alert.tone}`}><Icon name={alert.icon} size={16} /></span>
      <span className="notification-text"><strong>{alert.title}</strong>{!operationsOnly && <small>{alert.detail}</small>}</span>
      <span className="notification-time">{alert.time}</span><Icon name="chevron" size={14} />
    </button>) : <p className="empty compact">No notifications.</p>}
  </div>
}
