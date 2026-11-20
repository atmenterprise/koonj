import { useEffect, useRef, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { jobLabels } from '../../api/mappers'
import type { JobStatus } from '../../api/types'
import { useActivityNotifications } from '../notifications/ActivityNotifications'
import { Badge, Button } from './primitives'

export type Navigate = (path: string) => void

export function RequestError({ error, retry }: { error: Error | null; retry?: () => void }) {
  if (!error) return null
  return <div className="request-error" role="alert">
    <span>{error.message}</span>
    {error instanceof ApiError && Object.keys(error.validationErrors).length > 0 && <ul>
      {Object.entries(error.validationErrors).map(([field, message]) => <li key={field}>{field} {message}</li>)}
    </ul>}
    {retry && <Button icon="retry" onClick={retry}>Retry</Button>}
  </div>
}

export function LoadingRows({ label }: { label: string }) {
  return <div className="loading-rows" role="status"><span className="sr-only">{label}</span>
    {Array.from({ length: 4 }, (_, index) => <div key={index} aria-hidden="true" />)}
  </div>
}

export function JobStatusBadge({ status }: { status: JobStatus }) {
  const tone = { PENDING: 'neutral', RUNNING: 'blue', COMPLETED: 'green', PARTIAL_FAILURE: 'yellow', FAILED: 'red' }[status]
  return <Badge tone={tone} dot>{jobLabels[status]}</Badge>
}

export function Dialog({ title, onClose, drawer, children }: {
  title: string; onClose: () => void; drawer?: boolean; children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const { registerDialog } = useActivityNotifications()
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    dialog.showModal()
    const unregister = registerDialog(dialog)
    return () => {
      unregister()
      dialog.close()
    }
  }, [registerDialog])
  return <dialog ref={ref} aria-label={title} className={drawer ? 'drawer' : 'operation-dialog'}
    onCancel={event => { event.preventDefault(); onClose() }}>
    <div className="dialog-content">
      <div className="dialog-header"><h2>{title}</h2>
        <Button type="button" icon="close" className="icon-button" aria-label="Close" onClick={onClose} /></div>
      {children}
    </div>
  </dialog>
}
