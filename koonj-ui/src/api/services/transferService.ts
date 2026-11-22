import { apiClient } from '../client'
import type { ArchiveRequest, FileMovementDTO, JobAction, JobEventDTO, PageParams, PageResponse, RecallRequest, TransferJobDTO } from '../types'

export const transferService = {
  async archiveFiles(payload: ArchiveRequest, signal?: AbortSignal) {
    return (await apiClient.post<TransferJobDTO>('/transfers/archive', payload, { signal })).data
  },
  async recallFiles(payload: RecallRequest, signal?: AbortSignal) {
    return (await apiClient.post<TransferJobDTO>('/transfers/recall', payload, { signal })).data
  },
  async recallNow(fileId: string) {
    return (await apiClient.post<{ status: 'RECALLED' | 'RESTORING_GLACIER'; estimatedReadyAt: string | null }>(
      '/transfers/recall/sync', { fileId, timeoutSeconds: 60 }, { timeout: 85_000 },
    )).data
  },
  async getJobs(params: PageParams = {}, signal?: AbortSignal) {
    return (await apiClient.get<PageResponse<TransferJobDTO>>('/transfers/jobs', { params, signal })).data
  },
  async getJobById(jobId: string, signal?: AbortSignal) {
    return (await apiClient.get<TransferJobDTO>(`/transfers/jobs/${jobId}`, { signal })).data
  },
  async controlJob(jobId: string, action: JobAction) {
    return (await apiClient.post<TransferJobDTO>(`/transfers/jobs/${jobId}/control`, { action })).data
  },
  async getJobEvents(jobId: string, params: PageParams & { errorsOnly?: boolean }, signal?: AbortSignal) {
    return (await apiClient.get<PageResponse<JobEventDTO>>(`/transfers/jobs/${jobId}/events`, { params, signal })).data
  },
  async getJobFiles(jobId: string, params: PageParams & { failedOnly?: boolean }, signal?: AbortSignal) {
    return (await apiClient.get<PageResponse<FileMovementDTO>>(`/transfers/jobs/${jobId}/files`, { params, signal })).data
  },
}
