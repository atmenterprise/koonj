import { bytes } from '../shared/format'
import type { DashboardSummaryDTO, FileMetadataDTO, JobStatus, PaginatedFileResponse, ProviderType,
  StorageState, TransferJobDTO } from './types'

export function isObjectStorage(provider: ProviderType) {
  return provider !== 'LOCAL' && provider !== 'NAS' && provider !== 'SMB'
}

export function isActiveJob(job: TransferJobDTO) {
  return ['PENDING', 'RUNNING', 'PAUSING', 'STOPPING'].includes(job.status)
}

export const stateLabels: Record<StorageState, string> = {
  LOCAL_ONLY: 'Local', QUEUED_ARCHIVE: 'Queued for archive', ARCHIVING: 'Archiving',
  ARCHIVED: 'Archived', ARCHIVED_COPY: 'Archived · Copy', ARCHIVED_SYMLINK: 'Archived', QUEUED_RECALL: 'Queued for recall',
  RECALLING: 'Recalling', FAILED: 'Failed',
}

export const jobLabels: Record<JobStatus, string> = {
  PENDING: 'Pending', RUNNING: 'Running', PAUSING: 'Pausing', PAUSED: 'Paused', STOPPING: 'Stopping',
  STOPPED: 'Stopped', COMPLETED: 'Completed', PARTIAL_FAILURE: 'Partial failure', FAILED: 'Failed',
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
  const objects = files.filter(file => file.entryType === 'FILE' && file.storageState === 'ARCHIVED' && !file.targetConnectionId)
  const archiveSizes = new Map(objects.map(file => [JSON.stringify([file.connectionId, file.relativePath]), file.sizeBytes]))
  const reclaimedLocalBytes = files.reduce((sum, file) => file.entryType === 'SYMLINK' && file.targetConnectionId && file.s3Key
    ? sum + (archiveSizes.get(JSON.stringify([file.targetConnectionId, file.s3Key])) ?? 0) : sum, 0)
  return { reclaimedLocalBytes, s3ArchivedBytes: objects.reduce((sum, file) => sum + file.sizeBytes, 0),
    totalCatalogedFiles: files.filter(file => file.entryType !== 'DIRECTORY').length,
    activeTransferCount: jobs.filter(job => job.operation !== 'SCAN' && isActiveJob(job)).length }
}
