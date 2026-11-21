import { describe, expect, it } from 'vitest'
import { ApiError } from '../../api/client'
import type { TransferJobDTO } from '../../api/types'
import { errorDetail, reconcileJobs, type ActivityNotice } from './activity'

const notice: ActivityNotice = { id: 1, tone: 'info', title: 'Archive started', jobId: 'current-job', pending: true }
const job: TransferJobDTO = {
  id: 'current-job', operation: 'ARCHIVE', status: 'RUNNING',
  sourceConnectionId: 'source', targetConnectionId: 'target', requestedPaths: ['docs/'],
  totalFiles: 2, processedFiles: 1, failedFiles: 0, totalBytes: 100, transferredBytes: 50,
  createdAt: '2026-11-20T00:00:00Z', completedAt: null, details: null, triggeredBy: null,
}

describe('activity notifications', () => {
  it('includes server-supplied field validation in the notification message', () => {
    const error = new ApiError(400, 'Validation failed', '', { name: 'Must not be blank', role: 'Choose a role' })
    expect(errorDetail(error)).toBe('Validation failed\nname: Must not be blank\nrole: Choose a role')
  })

  it('updates the same pop-up from in progress to success when the job completes', () => {
    const active = reconcileJobs([notice], [job])
    expect(active).toMatchObject([{ id: 1, tone: 'info', title: 'Archive in progress', pending: true }])
    const completed = reconcileJobs(active, [{ ...job, status: 'COMPLETED', processedFiles: 2 }])
    expect(completed).toMatchObject([{ id: 1, tone: 'success', title: 'Archive completed',
      detail: '2 files processed.', pending: false }])
    expect(reconcileJobs(completed, [{ ...job, status: 'COMPLETED' }])).toBe(completed)
  })

  it('reports partial failures as errors and shows the result after dismissing the progress notice', () => {
    const active = [{ ...notice, dismissed: true }]
    const failed = reconcileJobs(active, [{ ...job, status: 'PARTIAL_FAILURE', details: 'Could not copy report.txt' }])
    expect(failed).toMatchObject([{ tone: 'error', title: 'Archive completed with errors',
      detail: 'Could not copy report.txt', pending: false, dismissed: false }])
  })

  it('makes completion unread even when the progress notification was read and timed out', () => {
    const active = [{ ...notice, read: true, dismissed: true }]
    const completed = reconcileJobs(active, [{ ...job, status: 'COMPLETED' }])
    expect(completed).toMatchObject([{ tone: 'success', read: false, dismissed: false }])
  })

  it('ignores jobs the user did not start in this session', () => {
    const active = [notice]
    expect(reconcileJobs(active, [{ ...job, id: 'another-job', status: 'FAILED' }])).toBe(active)
  })
})
