import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { Button, Icon } from '../ui/primitives'
import { useActivityNotifications } from './ActivityNotifications'
import type { ActivityNotice } from './activity'

const presentation = {
  info: { label: 'Info', icon: 'activity', tile: 'blue' },
  success: { label: 'Success', icon: 'check', tile: 'green' },
  error: { label: 'Error', icon: 'warning', tile: 'red' },
} as const

function Notice({ notice, dismiss, navigate }: {
  notice: ActivityNotice; dismiss: (id: number) => void; navigate?: (path: string) => void
}) {
  const { id, tone, title, detail, jobId } = notice
  const { label, icon, tile } = presentation[tone]

  useEffect(() => {
    if (tone !== 'success') return
    const timer = window.setTimeout(() => dismiss(id), 9000)
    return () => window.clearTimeout(timer)
  }, [tone, id, dismiss])
  return <article className={`activity-notice activity-notice-${tone}`} data-tone={tone}>
    <span className={`icon-tile ${tile}`}><Icon name={icon} size={17} /></span>
    <div className="activity-notice-message" role={tone === 'error' ? 'alert' : 'status'} aria-atomic="true">
      <span className="activity-notice-label">{label}</span>
      <strong>{title}</strong>
      {detail && <p>{detail}</p>}
    </div>
    <Button className="icon-button" variant="quiet" icon="close" aria-label={`Dismiss notification: ${title}`}
      onClick={() => dismiss(id)} />
    {jobId && navigate && <Button variant="quiet" className="activity-notice-link" onClick={() => {
      dismiss(id)
      navigate('/activities?' + new URLSearchParams({ job: jobId }))
    }}>View activity <Icon name="arrow" size={14} /></Button>}
  </article>
}

export function NotificationPopup({ navigate }: { navigate?: (path: string) => void }) {
  const { notices, dialog, dismiss } = useActivityNotifications()
  const visible = notices.filter(notice => !notice.dismissed)
  const show = visible.length > 0
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const popup = ref.current
    if (show && popup?.showPopover && !popup.matches(':popover-open')) popup.showPopover()
    return () => {
      if (popup?.isConnected && popup.hidePopover && popup.matches(':popover-open')) popup.hidePopover()
    }
  }, [show, dialog])

  return createPortal(<div className="activity-notifications" ref={ref} popover="manual"
    role="region" aria-label="Activity notifications" hidden={!show}>
    {visible.map(notice => <Notice key={notice.id} notice={notice} dismiss={dismiss} navigate={navigate} />)}
  </div>, dialog ?? document.body)
}
