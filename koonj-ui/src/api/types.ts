export const providerTypes = ['LOCAL', 'SMB', 'NAS', 'AWS_S3', 'MINIO', 'RUSTFS', 'SCALITY', 'CUSTOM_S3'] as const
export type ProviderType = typeof providerTypes[number]
export const storageRoles = ['SOURCE', 'TARGET', 'BOTH'] as const
export type StorageRole = typeof storageRoles[number]

export interface CreateConnectionRequest {
  name: string
  providerType: ProviderType
  role: StorageRole
  basePathOrBucket: string
  endpointUrl?: string | null
  region?: string | null
  accessKeyOrUsername?: string | null
  secretKeyOrPassword?: string | null
  pathStyleAccessEnabled?: boolean
  active?: boolean
}

export interface ConnectionIdentity {
  id: string
  name: string
  providerType: ProviderType
  role: StorageRole
  active: boolean
}

export interface CatalogLocationDTO extends ConnectionIdentity {
  bucket: string | null
}

export interface StorageConnectionDTO extends ConnectionIdentity {
  basePathOrBucket: string
  endpointUrl: string | null
  region: string | null
  accessKeyOrUsername: string | null
  secretKeyOrPassword: string | null
  pathStyleAccessEnabled: boolean
  createdAt: string
  updatedAt: string
}

export interface ConnectionHealthDTO {
  connectionId: string
  healthy: boolean
  message: string
  capacity?: { totalBytes: number; usedBytes: number; availableBytes: number } | null
}

export interface StorageUsageDTO {
  footprints: { connectionId: string; storageClass: string | null; fileCount: number; sizeBytes: number }[]
  reclaimed: { sourceConnectionId: string; targetConnectionId: string; fileCount: number; sizeBytes: number }[]
}

export const storageStates = ['LOCAL_ONLY', 'QUEUED_ARCHIVE', 'ARCHIVING', 'ARCHIVED', 'ARCHIVED_COPY', 'ARCHIVED_SYMLINK',
  'QUEUED_RECALL', 'RECALLING', 'FAILED'] as const
export type StorageState = typeof storageStates[number]

export type EntryType = 'FILE' | 'DIRECTORY' | 'SYMLINK'

export interface FileMetadataDTO {
  id: string
  connectionId: string
  entryType: EntryType
  symlinkTarget: string | null
  targetConnectionId: string | null
  relativePath: string
  fileName: string
  sizeBytes: number
  checksumSha256: string | null
  storageState: StorageState
  s3Key: string | null
  s3StorageClass: string | null
  lastModifiedAt: string | null
  archivedAt: string | null
  errorMessage: string | null
}

export interface FileAttributesDTO {
  owner: string | null
  createdAt: string | null
  lastAccessedAt: string | null
  author: string | null
  createdBy: string | null
  lastModifiedBy: string | null
  lastAccessedBy: string | null
}

export interface FileMovementDTO {
  id: number
  jobId: string
  recordedAt: string
  operation: 'ARCHIVE_COPY' | 'ARCHIVE_PURGE' | 'RECALL'
  outcome: 'VERIFIED' | 'FAILED'
  sourceLocation: string
  sourcePath: string
  targetLocation: string
  targetPath: string
  symlinkReference: string | null
  sourceChecksum: string | null
  targetChecksum: string | null
  sizeBytes: number
  archiveRemoved: boolean
  error: string | null
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export type PaginatedFileResponse = PageResponse<FileMetadataDTO>
export interface PageParams { page?: number; size?: number }
export interface CatalogParams extends PageParams {
  connectionId?: string
  storageState?: StorageState
  pathPrefix?: string
}

export type ArchiveMode = 'COPY' | 'PURGE'
export type TransferOperation = 'ARCHIVE' | 'RECALL' | 'SCAN'
export type JobStatus = 'PENDING' | 'RUNNING' | 'PAUSING' | 'PAUSED' | 'STOPPING' | 'STOPPED' | 'COMPLETED' | 'PARTIAL_FAILURE' | 'FAILED'
export type JobAction = 'PAUSE' | 'RESUME' | 'STOP' | 'RESTART'
export interface ArchiveActivityDTO { currentFile: string | null; requestedPaths: string[]; currentFiles?: string[] }

export interface TransferJobDTO {
  id: string
  operation: TransferOperation
  status: JobStatus
  sourceConnectionId: string
  targetConnectionId: string | null
  requestedPaths: string[]
  archiveMode?: ArchiveMode
  deleteFromTarget?: boolean
  totalFiles: number
  processedFiles: number
  failedFiles: number
  totalBytes: number
  transferredBytes: number
  createdAt: string
  startedAt?: string | null
  completedAt: string | null
  details: string | null
  triggeredBy: string | null
  triggeredByName?: string | null
  controllable?: boolean
  restartedFrom?: string | null
  restartedAs?: string | null
}

export interface JobEventDTO {
  id: number
  recordedAt: string
  level: 'INFO' | 'WARNING' | 'ERROR'
  path: string | null
  message: string
}

type TransferSelection = { paths: string[]; fileIds?: string[] } | { fileIds: string[]; paths?: string[] }
type TransferEndpoints = { sourceConnectionId: string; targetConnectionId: string }
export type ArchiveRequest = TransferEndpoints & TransferSelection & { storageClass?: string; archiveMode?: ArchiveMode }
type MountedFolderSelection = { targetFolderPaths: string[]; paths?: string[]; fileIds?: string[] }
export type RecallRequest = TransferEndpoints & (TransferSelection | MountedFolderSelection) & { deleteFromTarget?: boolean }

export interface DashboardSummaryDTO {
  reclaimedLocalBytes: number
  s3ArchivedBytes: number
  totalCatalogedFiles: number
  activeTransferCount: number
}


export interface ServerTelemetryDTO {
  health: string
  instance: string
  sampledAt: string
  uptimeSeconds: number
  javaVersion: string
  heapUsedBytes: number
  heapCommittedBytes: number
  livePlatformThreads: number
  availableProcessors: number
  components: Record<string, string>
}

export interface CatalogMetadataValues {
  tags: string[]
  properties: { name: string; value: string }[]
  description?: string | null
}

export interface CatalogMetadataTarget {
  scope: 'FILE' | 'FOLDER'
  path: string
}

export interface CatalogFavoriteTarget extends CatalogMetadataTarget {
  connectionId: string
}

export interface CatalogFavorite extends CatalogFavoriteTarget {
  connectionName: string
}

export interface CatalogMetadataEntry extends CatalogMetadataTarget {
  values: CatalogMetadataValues
}

export interface CatalogMetadataWriteRequest {
  targets: CatalogMetadataTarget[]
  values: CatalogMetadataValues
  mode: 'REPLACE' | 'MERGE'
}
