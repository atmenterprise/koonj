import { ApiError } from '../../api/client'
import type { TransferJobDTO } from '../../api/types'

export type NotificationTone = 'info' | 'success' | 'error'

export interface ActivityMessage {
  tone: NotificationTone
  title: string
  detail?: string
  jobId?: string
}

export interface ActivityNotice extends ActivityMessage {
  id: number
  pending: boolean
  dismissed?: boolean
  read?: boolean
}

export function errorDetail(error: unknown) {
  if (!(error instanceof Error)) return undefined
  const fields = error instanceof ApiError ? Object.entries(error.validationErrors) : []
  return [error.message, ...fields.map(([field, message]) => `${field}: ${message}`)].filter(Boolean).join('\n') || undefined
}

export function jobMessage(job: TransferJobDTO): ActivityMessage {
  const action = { ARCHIVE: 'Archive', RECALL: 'Recall', SCAN: 'Scan' }[job.operation]
  const jobId = job.id
  const detail = job.details?.trim() || undefined
  switch (job.status) {
    case 'COMPLETED':
      return { tone: 'success', title: action + ' completed', jobId,
        detail: `${job.processedFiles} ${job.processedFiles === 1 ? 'file' : 'files'} processed.` }
    case 'PARTIAL_FAILURE':
      return { tone: 'error', title: action + ' completed with errors', jobId,
        detail: detail ?? `${job.failedFiles} ${job.failedFiles === 1 ? 'file' : 'files'} failed.` }
    case 'FAILED':
      return { tone: 'error', title: action + ' failed', jobId, detail }
    case 'RUNNING':
      return { tone: 'info', title: action + ' in progress', jobId }
    case 'PENDING':
      return { tone: 'info', title: action + ' queued', jobId }
    case 'PAUSING':
      return { tone: 'info', title: action + ' pausing', jobId }
    case 'PAUSED':
      return { tone: 'info', title: action + ' paused', jobId }
    case 'STOPPING':
      return { tone: 'info', title: action + ' stopping', jobId }
    case 'STOPPED':
      return { tone: 'info', title: action + ' stopped', jobId }
  }
}

export function reconcileJobs(notices: ActivityNotice[], jobs: TransferJobDTO[]) {
  const tracked = new Map(jobs.map(job => [job.id, job]))
  let changed = false
  const next = notices.map(notice => {
    if (!notice.pending || !notice.jobId) return notice
    const job = tracked.get(notice.jobId)
    if (!job) return notice
    const update = jobMessage(job)
    if (update.tone === 'info' && update.title === notice.title) return notice
    changed = true
    return { ...notice, ...update, pending: update.tone === 'info' && job.status !== 'STOPPED',
      dismissed: update.tone === 'info' ? notice.dismissed : false,
      read: update.tone === 'info' ? notice.read : false }
  })
  return changed ? next : notices
}
