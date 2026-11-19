import type { Activity, Alert, Attempt, Dashboard, ItemDetail, Job, LiveEvent, Location, Page, StorageState } from './api-client.ts'

const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`
const at = '2026-11-19T09:00:00Z'
export const locations: Location[] = [
  { id: id(1), name: 'Workstation', type: 'LOCAL', root: '/data', status: 'ONLINE', capacityBytes: '2000000000000', usedBytes: '800000000000', canScan: true, recallDestination: true },
  { id: id(2), name: 'media', type: 'SMB', root: '//nas-01/media', status: 'ONLINE', capacityBytes: '16000000000000', usedBytes: '11000000000000', canScan: true },
  { id: id(3), name: 'nas-01', type: 'NAS', root: '/mnt/nas-01', status: 'ONLINE', capacityBytes: '24000000000000', usedBytes: '15000000000000', canScan: true, recallDestination: true },
  { id: id(4), name: 'koonj-archive', type: 'S3', root: 'koonj-archive', status: 'ONLINE', capacityBytes: null, usedBytes: '17400000000000', canScan: true, archiveTarget: true },
]

const states: StorageState[] = ['LOCAL_ONLY', 'ARCHIVED', 'PURGED_LOCAL', 'RECALLING', 'FAILED']
const digest = 'a'.repeat(64)
export const directory: ItemDetail = {
  id: id(20), locationId: id(3), origin: 'NAS', parentPath: '', fullPath: 'raw-footage',
  name: 'raw-footage', isDirectory: true, childCount: 50_000_001, sizeBytes: null,
  storageState: null, storageClass: null, modifiedAt: at, sha256: null, s3Uri: null,
  etag: null, archivedAt: null, tags: { project: 'Koonj' }, actions: ['archive', 'scan'],
}
export const cloudPrefix: ItemDetail = {
  ...directory, id: id(21), locationId: id(4), origin: 'S3', fullPath: 'finance/',
  name: 'finance', childCount: 420, s3Uri: 's3://koonj-archive/finance/', actions: ['scan'],
}

export const syntheticCount = 50_000_001
/** Fixture-only cursor is the start ordinal. A live client treats all cursor values as opaque. */
export function syntheticPage(cursor?: string, limit = 100): Page<ItemDetail> {
  const start = cursor === undefined ? 0 : Number(cursor)
  if (!Number.isSafeInteger(start) || start < 0 || start > syntheticCount ||
      !Number.isInteger(limit) || limit < 1 || limit > 100) throw new RangeError('Invalid fixture page')
  const end = Math.min(start + limit, syntheticCount)
  const items = Array.from({ length: end - start }, (_, index): ItemDetail => {
    const ordinal = start + index
    const state = states[ordinal % states.length]!
    const name = `clip-${String(ordinal).padStart(8, '0')}.mov`
    const remote = state !== 'LOCAL_ONLY' && state !== 'FAILED'
    return {
      id: id(1000 + ordinal), locationId: id(3), origin: 'NAS', parentPath: directory.fullPath,
      fullPath: `${directory.fullPath}/${name}`, name, isDirectory: false, sizeBytes: '48000000',
      storageState: state, storageClass: remote ? (state === 'PURGED_LOCAL' ? 'DEEP_ARCHIVE' : 'GLACIER_IR') : null,
      modifiedAt: at, sha256: digest, s3Uri: remote ? `s3://koonj-archive/raw-footage/${name}` : null,
      etag: remote ? 'fixture-etag' : null, archivedAt: remote ? at : null,
      tags: { project: 'Koonj', retention: { years: 7 } },
      actions: remote ? ['recall', 'verify'] : ['archive', 'scan', 'verify'],
    }
  })
  return { items, nextCursor: end < syntheticCount ? String(end) : null, previousCursor: start ? String(Math.max(0, start - limit)) : null }
}

const alerts: Alert[] = [
  { id: id(70), title: 'Glacier restore ready for 420 files in /finance/2023', severity: 'info', at, target: { activityId: id(50) } },
  { id: id(71), title: 'SMB mount //nas-01/media scan completed', severity: 'info', at, target: { activityId: id(52) } },
  { id: id(72), title: '3 files failed checksum verification', severity: 'error', at, target: { activityId: id(51) } },
]
export const dashboard: Dashboard = {
  asOf: at, reclaimedBytes: '14800000000000', catalogedFiles: 50_004_200,
  archives: [
    { storageClass: 'STANDARD', objects: 3100, bytes: '2600000000000' },
    { storageClass: 'GLACIER_IR', objects: 600, bytes: '5000000000000' },
    { storageClass: 'DEEP_ARCHIVE', objects: 420, bytes: '9800000000000' },
  ],
  tierBytes: { localOnly: '4200000000000', synced: '2600000000000', purged: '14800000000000' },
  throughput: { bytesPerSecond: 118_000_000, activeWorkers: 8, at }, alerts,
}
const running: Activity = {
  id: id(50), type: 'RECALL', target: '420 files in /finance/2023', actor: 'Maya Patel',
  status: 'RUNNING', completed: 230, failed: 0, total: 420, transferredBytes: '11040000000',
  expectedBytes: '20160000000', createdAt: at, startedAt: at, finishedAt: null, revision: 1,
  controls: ['pause', 'cancel'], canRetry: false,
}
export const activities: Activity[] = [
  running,
  { ...running, id: id(51), type: 'ARCHIVE', target: '1,240 files in /raw-footage', status: 'PARTIAL_FAILURE', completed: 1237, failed: 3, total: 1240, transferredBytes: '59376000000', expectedBytes: '59520000000', createdAt: '2026-11-19T08:50:00Z', startedAt: '2026-11-19T08:50:00Z', finishedAt: at, controls: [], canRetry: true },
  { ...running, id: id(52), type: 'SCAN', target: '//nas-01/media', status: 'COMPLETED', completed: 180, total: 180, transferredBytes: '0', expectedBytes: null, createdAt: '2026-11-19T08:40:00Z', startedAt: '2026-11-19T08:40:00Z', finishedAt: at, controls: [], canRetry: false },
  { ...running, id: id(53), type: 'PURGE_LOCAL', target: '/archives/2024', status: 'COMPLETED', completed: 20, total: 20, transferredBytes: '0', expectedBytes: null, createdAt: '2026-11-19T08:30:00Z', startedAt: '2026-11-19T08:30:00Z', finishedAt: at, controls: [], canRetry: false },
]
export const jobsByActivity: Record<string, Job[]> = {
  [id(50)]: [{ id: id(60), fileId: id(1003), path: 'finance/2023/clip.mov', status: 'WAITING_RESTORE', bytesPerSecond: null, s3VersionId: 'preview-v1', etag: 'fixture-etag', error: null }],
  [id(51)]: [4, 9, 14].map((offset) => ({
    id: id(60 + offset), fileId: id(1000 + offset), path: `raw-footage/clip-${String(offset).padStart(8, '0')}.mov`,
    status: 'FAILED', bytesPerSecond: 27_000_000, s3VersionId: null, etag: null,
    error: { code: 'CHECKSUM_MISMATCH', message: 'SHA-256 did not match the uploaded file.' },
  })),
}
export const attemptsByJob: Record<string, Attempt[]> = {
  [id(64)]: [{ number: 1, at, code: 'CHECKSUM_MISMATCH', message: 'Verification failed. The local copy was retained.' }],
}
export const liveEvents: LiveEvent[] = [
  { type: 'worker.throughput', sample: { ...dashboard.throughput, at: '2026-11-19T09:00:01Z' } },
  { type: 'activity.progress', activity: { ...running, completed: 250, transferredBytes: '12000000000', revision: 2 } },
  { type: 'activity.finished', activity: { ...running, completed: 420, transferredBytes: '20160000000', status: 'COMPLETED', finishedAt: '2026-11-19T09:00:10Z', revision: 3, controls: [] } },
]
