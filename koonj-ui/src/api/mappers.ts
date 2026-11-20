import { bytes } from '../shared/format'
import type { DashboardSummaryDTO, FileMetadataDTO, JobStatus, PaginatedFileResponse, ProviderType,
  StorageState, TransferJobDTO } from './types'

export function isObjectStorage(provider: ProviderType) {
  return provider !== 'LOCAL' && provider !== 'NAS' && provider !== 'SMB'
}

export function isActiveJob(job: TransferJobDTO) {
  return job.status === 'PENDING' || job.status === 'RUNNING'
}

export const stateLabels: Record<StorageState, string> = {
  LOCAL_ONLY: 'Local only', QUEUED_ARCHIVE: 'Queued for archive', ARCHIVING: 'Archiving',
  ARCHIVED: 'Archived', PURGED_LOCAL: 'Purged locally', QUEUED_RECALL: 'Queued for recall',
  RECALLING: 'Recalling', FAILED: 'Failed',
}

export const jobLabels: Record<JobStatus, string> = {
  PENDING: 'Pending', RUNNING: 'Running', COMPLETED: 'Completed', PARTIAL_FAILURE: 'Partial failure', FAILED: 'Failed',
}

export const operationLabels = { ARCHIVE: 'Archive', RECALL: 'Recall', SCAN: 'Scan' } as const

export function catalogTablePage(page: PaginatedFileResponse) {
  return { ...page, content: page.content.map(file => ({
    file, sizeLabel: bytes(file.sizeBytes), stateLabel: stateLabels[file.storageState],
  })) }
}

export interface FolderNode { name: string; prefix: string; children: FolderNode[] }

export function folderTree(paths: string[]): FolderNode[] {
  const roots: FolderNode[] = []
  const byPrefix = new Map<string, FolderNode>()
  for (const path of paths) {
    const parts = path.split('/').slice(0, -1)
    let prefix = ''
    let siblings = roots
    for (const name of parts) {
      prefix += name + '/'
      let node = byPrefix.get(prefix)
      if (!node) {
        node = { name, prefix, children: [] }
        siblings.push(node)
        byPrefix.set(prefix, node)
      }
      siblings = node.children
    }
  }
  return roots
}

export function dashboardSummary(files: FileMetadataDTO[], jobs: TransferJobDTO[]): DashboardSummaryDTO {
  const archives = new Map<string, number>()
  let reclaimedLocalBytes = 0
  for (const file of files) {
    if (file.storageState === 'PURGED_LOCAL') reclaimedLocalBytes += file.sizeBytes
    if (!file.s3Key) continue
    const key = JSON.stringify([file.targetConnectionId ?? file.sourceConnectionId, file.s3Key])
    // An S3 scan reports the object size directly. Local entries can retain an
    // archive binding after the local file has changed.
    if (!file.targetConnectionId || (!archives.has(key) && ['ARCHIVED', 'PURGED_LOCAL'].includes(file.storageState))) {
      archives.set(key, file.sizeBytes)
    }
  }
  return { reclaimedLocalBytes, s3ArchivedBytes: [...archives.values()].reduce((sum, size) => sum + size, 0),
    totalCatalogedFiles: files.length, activeTransferCount: jobs.filter(job => job.operation !== 'SCAN' && isActiveJob(job)).length }
}
