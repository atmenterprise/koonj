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
}

export const storageStates = ['LOCAL_ONLY', 'QUEUED_ARCHIVE', 'ARCHIVING', 'ARCHIVED', 'PURGED_LOCAL',
  'QUEUED_RECALL', 'RECALLING', 'FAILED'] as const
export type StorageState = typeof storageStates[number]

export interface FileMetadataDTO {
  id: string
  sourceConnectionId: string
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

export type TransferOperation = 'ARCHIVE' | 'RECALL' | 'SCAN'
export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL_FAILURE' | 'FAILED'

export interface TransferJobDTO {
  id: string
  operation: TransferOperation
  status: JobStatus
  sourceConnectionId: string
  targetConnectionId: string | null
  requestedPaths: string[]
  totalFiles: number
  processedFiles: number
  failedFiles: number
  totalBytes: number
  transferredBytes: number
  createdAt: string
  completedAt: string | null
  details: string | null
  triggeredBy: string | null
}

type TransferSelection = { paths: string[]; fileIds?: string[] } | { fileIds: string[]; paths?: string[] }
type TransferRequest = { sourceConnectionId: string; targetConnectionId: string } & TransferSelection
export type ArchiveRequest = TransferRequest & { storageClass?: string; purgeSource?: boolean }
export type RecallRequest = TransferRequest

export interface DashboardSummaryDTO {
  reclaimedLocalBytes: number
  s3ArchivedBytes: number
  totalCatalogedFiles: number
  activeTransferCount: number
}


export interface ServerTelemetryDTO {
  health: string
  sampledAt: string
  uptimeSeconds: number
  javaVersion: string
  heapUsedBytes: number
  heapCommittedBytes: number
  livePlatformThreads: number
  availableProcessors: number
}

export interface CatalogMetadataValues {
  tags: string[]
  properties: { name: string; value: string }[]
}

export interface CatalogMetadataTarget {
  scope: 'FILE' | 'FOLDER'
  path: string
}

export interface CatalogMetadataEntry extends CatalogMetadataTarget {
  values: CatalogMetadataValues
}

export interface CatalogMetadataWriteRequest {
  targets: CatalogMetadataTarget[]
  values: CatalogMetadataValues
  mode: 'REPLACE' | 'MERGE'
}
