/** Planning contract. Routes tagged extension require backend support. */
export type Id = string
export type Timestamp = string
export type Bytes = string // Decimal integer on the wire to preserve bigint precision.
export type StorageClass = 'STANDARD' | 'GLACIER_IR' | 'GLACIER' | 'DEEP_ARCHIVE'
export type RestoreTier = 'EXPEDITED' | 'STANDARD' | 'BULK'
export type LocationType = 'LOCAL' | 'SMB' | 'NAS' | 'S3'
export type StorageState = 'LOCAL_ONLY' | 'QUEUED_ARCHIVE' | 'ARCHIVING' | 'ARCHIVED'
  | 'PURGED_LOCAL' | 'QUEUED_RECALL' | 'RECALLING' | 'FAILED'
export type ActivityType = 'ARCHIVE' | 'RECALL' | 'PURGE_LOCAL' | 'SCAN' | 'VERIFY_CHECKSUM'
export type ActivityStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'CANCELLED'
  | 'COMPLETED' | 'PARTIAL_FAILURE' | 'FAILED'
export type Control = 'pause' | 'resume' | 'cancel'
export type Capability = 'globalSearch' | 'reversePaging' | 'pause' | 'resume' | 'cancel'
  | 'scan' | 'verify' | 'retry' | 'chooseBucket' | 'chooseDestination' | 'verifiedPurge'
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json }

export interface PageQuery { cursor?: string; limit?: number }
export interface Page<T> { items: T[]; nextCursor: string | null; previousCursor?: string | null }
export interface Scope { locationId: Id; parentPath: string }
export interface ItemQuery extends PageQuery, Scope {
  q?: string
  storageState?: StorageState
  storageStates?: StorageState[]
  sort?: 'name' | 'sizeBytes' | 'lastModifiedAt'
  direction?: 'asc' | 'desc'
}
export interface Location {
  id: Id
  name: string
  type: LocationType
  root: string
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
  capacityBytes: Bytes | null
  usedBytes: Bytes | null
  canScan: boolean
  archiveTarget?: boolean
  recallDestination?: boolean
}
export interface Item {
  id: Id
  locationId: Id
  origin: LocationType
  parentPath: string
  fullPath: string
  name: string
  isDirectory: boolean
  childCount?: number
  sizeBytes: Bytes | null
  storageState: StorageState | null
  storageClass: StorageClass | null
  modifiedAt: Timestamp | null
  sha256: string | null
}
export interface ItemDetail extends Item {
  s3Uri: string | null
  etag: string | null
  archivedAt: Timestamp | null
  tags: Record<string, Json>
  actions: Array<'archive' | 'recall' | 'verify' | 'scan'>
}
export type AlertTarget = { activityId: Id } | { scope: Scope; itemId?: Id }
export interface Alert { id: Id; title: string; severity: 'info' | 'warning' | 'error'; at: Timestamp; target: AlertTarget }
export interface Throughput { bytesPerSecond: number; activeWorkers: number | null; at: Timestamp }
export interface Dashboard {
  asOf: Timestamp
  reclaimedBytes: Bytes
  catalogedFiles: number
  archives: Array<{ storageClass: StorageClass; objects: number; bytes: Bytes }>
  tierBytes: { localOnly: Bytes; synced: Bytes; purged: Bytes } | null
  throughput: Throughput
  alerts: Alert[]
}
export interface Activity {
  id: Id
  type: ActivityType
  target: string
  actor: string
  status: ActivityStatus
  completed: number
  failed: number
  total: number
  transferredBytes: Bytes
  expectedBytes: Bytes | null
  createdAt: Timestamp
  startedAt: Timestamp | null
  finishedAt: Timestamp | null
  revision: number
  controls: Control[]
  controlPending?: Control
  canRetry: boolean
}
export interface Job {
  id: Id
  fileId: Id
  path: string
  status: 'PENDING' | 'RUNNING' | 'WAITING_RESTORE' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  bytesPerSecond: number | null
  s3VersionId: string | null
  etag: string | null
  error: { code: string; message: string } | null
}
export interface Attempt { number: number; at: Timestamp; message: string; code?: string }
export interface Accepted { activityId: Id; statusUrl: string }
export interface RequestOptions { signal?: AbortSignal; idempotencyKey?: string }
export interface ApiError { status: number; code: string; message: string; field?: string }
export interface ArchiveRequest { ids: Id[]; storageClass?: StorageClass; targetLocationId?: Id; purgeAfterVerification?: boolean }
export interface RecallRequest { ids: Id[]; restoreTier?: RestoreTier; destination?: { locationId: Id; relativePath: string } }
export type LiveEvent =
  | { type: 'activity.progress' | 'activity.finished'; activity: Activity }
  | { type: 'worker.throughput'; sample: Throughput }
export type ConnectionState = 'connecting' | 'open' | 'retrying' | 'closed'

/** All HTTP paths below are relative to /api/v1. */
export interface KoonjApi {
  /** Extension GET /capabilities */
  capabilities(o?: RequestOptions): Promise<Capability[]>
  /** GET /dashboard/summary, with tier, action target and worker fields extended */
  dashboard(o?: RequestOptions): Promise<Dashboard>
  /** Extension GET /storage/locations */
  locations(q: PageQuery, o?: RequestOptions): Promise<Page<Location>>
  /** GET /explorer/tree */
  tree(q: Scope & PageQuery, o?: RequestOptions): Promise<Page<Item>>
  /** GET /explorer/items */
  items(q: ItemQuery, o?: RequestOptions): Promise<Page<Item>>
  /** Extension GET /explorer/items/{id} */
  item(id: Id, o?: RequestOptions): Promise<ItemDetail>
  /** Extension GET /explorer/search */
  search(q: PageQuery & { q: string }, o?: RequestOptions): Promise<Page<Item>>
  /** POST /actions/archive, with optional extended target and purge */
  archive(body: ArchiveRequest, o?: RequestOptions): Promise<Accepted>
  /** POST /actions/recall, with optional extended destination */
  recall(body: RecallRequest, o?: RequestOptions): Promise<Accepted>
  /** Extension POST /actions/scan */
  scan(body: { locationId: Id; ids?: Id[] }, o?: RequestOptions): Promise<Accepted>
  /** Extension POST /actions/verify-checksum */
  verify(body: { ids: Id[] }, o?: RequestOptions): Promise<Accepted>
  /** GET /activities, with extended status filtering */
  activities(q: PageQuery & { status?: ActivityStatus[] }, o?: RequestOptions): Promise<Page<Activity>>
  /** GET /activities/{id} */
  activity(id: Id, o?: RequestOptions): Promise<Activity>
  /** GET /activities/{id}/jobs */
  jobs(id: Id, q: PageQuery, o?: RequestOptions): Promise<Page<Job>>
  /** GET /activities/{id}/jobs/{jobId}/attempts */
  attempts(id: Id, jobId: Id, q: PageQuery, o?: RequestOptions): Promise<Page<Attempt>>
  /** Extension POST /activities/{id}/controls. Resolve on acknowledgement. */
  control(id: Id, action: Control, o?: RequestOptions): Promise<Activity>
  /** Extension POST /activities/{id}/retry-failed */
  retryFailed(id: Id, o?: RequestOptions): Promise<Accepted>
  /** GET /events/stream. Return an unsubscribe function. */
  subscribe(onEvent: (event: LiveEvent) => void, onState: (state: ConnectionState) => void): () => void
}
