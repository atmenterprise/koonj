import { MutationObserver, QueryClient, QueryObserver } from '@tanstack/react-query'
import AxiosMockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from './client'
import { accessToken } from '../auth/keycloak'

vi.mock('../auth/keycloak', () => ({ accessToken: vi.fn() }))
import { archiveMutationOptions, recallMutationOptions } from './hooks/mutations'
import { jobHistoryKey, jobPollingInterval, queryKeys, transferJobsOptions } from './hooks/queries'
import { catalogTablePage } from './mappers'
import { catalogService } from './services/catalogService'
import { transferService } from './services/transferService'
import type { ArchiveRequest, FileMetadataDTO, PageResponse, RecallRequest, TransferJobDTO } from './types'

const file: FileMetadataDTO = {
  id: 'file-id', sourceConnectionId: 'local-id', targetConnectionId: null, relativePath: 'docs/report.txt',
  fileName: 'report.txt', sizeBytes: 1200, checksumSha256: null, storageState: 'LOCAL_ONLY', s3Key: null,
  s3StorageClass: 'STANDARD', lastModifiedAt: '2026-11-20T16:00:00Z', archivedAt: null, errorMessage: null,
}
const job: TransferJobDTO = {
  id: 'job-id', operation: 'ARCHIVE', status: 'PENDING', sourceConnectionId: 'local-id', targetConnectionId: 's3-id',
  requestedPaths: ['docs/report.txt'], totalFiles: 0, processedFiles: 0, failedFiles: 0, totalBytes: 0,
  transferredBytes: 0, createdAt: '2026-11-20T16:00:00Z', completedAt: null, details: null, triggeredBy: 'operator@appsthatmatter.uk',
}

let mock: AxiosMockAdapter
let client: QueryClient
beforeEach(() => {
  vi.mocked(accessToken).mockReset().mockResolvedValue('test-token')
  mock = new AxiosMockAdapter(apiClient, { onNoMatch: 'throwException' })
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})
afterEach(() => { mock.restore(); client.clear() })

describe('catalog API', () => {
  it('forwards server pagination, filters and cancellation, and maps Explorer rows', async () => {
    const abort = new AbortController()
    const params = { connectionId: file.sourceConnectionId, page: 1, size: 50, pathPrefix: 'docs/', storageState: 'LOCAL_ONLY' } as const
    const response: PageResponse<FileMetadataDTO> = { content: [file], page: 1, size: 50, totalElements: 51, totalPages: 2 }
    mock.onGet('/catalog/files', { params }).reply(config => {
      expect(config.signal).toBe(abort.signal)
      return [200, response]
    })
    const mapped = catalogTablePage(await catalogService.getFiles(params, abort.signal))
    expect(mapped).toEqual({ ...response, content: [{ file, sizeLabel: '1.2 KB', stateLabel: 'Local only' }] })
  })

  it('preserves cancellation instead of returning an API error', async () => {
    const abort = new AbortController()
    abort.abort()
    await expect(catalogService.getFiles({}, abort.signal)).rejects.toMatchObject({ code: 'ERR_CANCELED' })
    expect(mock.history.get).toHaveLength(0)
  })
})

describe('transfers and cache invalidation', () => {
  const filesKey = [...queryKeys.files, { page: 0 }]
  beforeEach(() => client.setQueryData(filesKey, []))

  it('posts the exact archive payload and invalidates catalog and jobs', async () => {
    const payload: ArchiveRequest = { sourceConnectionId: 'local-id', targetConnectionId: 's3-id',
      paths: ['docs/report.txt'], storageClass: 'STANDARD', purgeSource: true }
    mock.onPost('/transfers/archive').reply(config => {
      expect(JSON.parse(config.data)).toEqual(payload)
      return [202, job]
    })
    expect(await new MutationObserver(client, archiveMutationOptions(client)).mutate(payload)).toEqual(job)
    expect(client.getQueryState(filesKey)?.isInvalidated).toBe(true)
    expect(client.getQueryState(jobHistoryKey)?.isInvalidated).toBe(true)
    expect(client.getQueryData(jobHistoryKey)).toEqual([job])
  })

  it('posts the exact recall payload and invalidates catalog and jobs', async () => {
    const payload: RecallRequest = { sourceConnectionId: 's3-id', targetConnectionId: 'local-id', fileIds: ['file-id'] }
    const recall = { ...job, ...payload, operation: 'RECALL' }
    mock.onPost('/transfers/recall').reply(config => {
      expect(JSON.parse(config.data)).toEqual(payload)
      return [202, recall]
    })
    expect(await new MutationObserver(client, recallMutationOptions(client)).mutate(payload)).toEqual(recall)
    expect(client.getQueryState(filesKey)?.isInvalidated).toBe(true)
    expect(client.getQueryState(jobHistoryKey)?.isInvalidated).toBe(true)
  })

  it('reads through every job page before deciding whether to poll', async () => {
    const done = { ...job, id: 'complete', status: 'COMPLETED' }
    mock.onGet('/transfers/jobs', { params: { page: 0, size: 200 } }).reply(200, { totalPages: 2, content: [done] })
    mock.onGet('/transfers/jobs', { params: { page: 1, size: 200 } }).replyOnce(200, { content: [job] })
      .onGet('/transfers/jobs', { params: { page: 1, size: 200 } }).reply(200, { content: [{ ...job, status: 'COMPLETED' }] })
    expect(jobPollingInterval(await client.fetchQuery(transferJobsOptions))).toBe(3000)
    expect(jobPollingInterval(await client.fetchQuery(transferJobsOptions))).toBe(false)
    expect(client.getQueryState(filesKey)?.isInvalidated).toBe(true)
  })
})

describe('API errors', () => {
  it.each([[400, 'Provide at least one path or file ID'], [404, 'Transfer job was not found'],
    [500, 'The request could not be completed']])('normalizes HTTP %i into query error state', async (status, message) => {
    mock.onGet('/transfers/jobs/missing').reply(status, { status, error: 'HTTP error', message })
    const observer = new QueryObserver(client, { queryKey: ['job', 'missing'],
      queryFn: ({ signal }) => transferService.getJobById('missing', signal) })
    const result = await observer.refetch()
    expect(result.isError).toBe(true)
    expect(result.error).toBeInstanceOf(ApiError)
    expect(result.error).toMatchObject({ status, message, timestamp: expect.any(String), validationErrors: {} })
  })
})

describe('API authentication', () => {
  it('attaches a token and retries a 401 once after forced refresh', async () => {
    vi.mocked(accessToken).mockImplementation(async validity => {
      if (validity === -1) vi.mocked(accessToken).mockResolvedValue('refreshed-token')
      return 'test-token'
    })
    mock.onGet('/transfers/jobs/job-id').replyOnce(config => {
      expect(config.headers?.Authorization).toBe('Bearer test-token')
      return [401, {}]
    }).onGet('/transfers/jobs/job-id').reply(config => {
      expect(config.headers?.Authorization).toBe('Bearer refreshed-token')
      return [200, job]
    })
    await expect(transferService.getJobById('job-id')).resolves.toEqual(job)
    expect(vi.mocked(accessToken).mock.calls.map(([validity]) => validity)).toEqual([30, -1, 30])
  })

  it('stops after one retry and normalizes a denied role', async () => {
    mock.onGet('/transfers/jobs/job-id').reply(401, {})
    mock.onPost('/transfers/archive').reply(403, {})
    await expect(transferService.getJobById('job-id')).rejects.toMatchObject({ status: 401 })
    expect(mock.history.get).toHaveLength(2)
    await expect(transferService.archiveFiles({
      sourceConnectionId: 'local-id', targetConnectionId: 's3-id', paths: ['docs/report.txt'],
    })).rejects.toMatchObject({ status: 403, message: 'Insufficient permissions for this action' })
  })
})
