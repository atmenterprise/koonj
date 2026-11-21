import { useEffect, useRef, type FormEvent, type ReactNode } from 'react'
import { jobLabels } from '../../api/mappers'
import type { JobStatus } from '../../api/types'
import { useActivityNotifications } from '../notifications/ActivityNotifications'
import { Badge, Button } from './primitives'

export type Navigate = (path: string) => void

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
  const validationPending = useRef(false)
  const { notify, registerDialog } = useActivityNotifications()
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

  function onInvalid(event: FormEvent<HTMLDialogElement>) {
    const input = event.target
    if (!(input instanceof HTMLInputElement || input instanceof HTMLSelectElement || input instanceof HTMLTextAreaElement)) return
    event.preventDefault()
    if (validationPending.current) return
    validationPending.current = true
    window.setTimeout(() => { validationPending.current = false }, 0)
    input.focus()
    const label = input.getAttribute('aria-label') ?? (input instanceof HTMLSelectElement
      ? input.labels?.[0]?.firstChild?.textContent?.trim() : input.labels?.[0]?.textContent?.trim())
    const detail = label ? `${label}: ${input.validationMessage}` : input.validationMessage
    notify({ tone: 'error', title: 'Check the form', detail })
  }

  return <dialog ref={ref} aria-label={title} className={drawer ? 'drawer' : 'operation-dialog'}
    onInvalid={onInvalid} onCancel={event => { event.preventDefault(); onClose() }}>
    <div className="dialog-content">
      <div className="dialog-header"><h2>{title}</h2>
        <Button type="button" icon="close" className="icon-button" aria-label="Close" onClick={onClose} /></div>
      {children}
    </div>
  </dialog>
}
