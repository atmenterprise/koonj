export type Origin = 'LOCAL' | 'SMB' | 'NAS' | 'S3'
export type StorageClass = 'STANDARD' | 'GLACIER_IR' | 'DEEP_ARCHIVE'
export type FileState = 'Local Only' | 'Archived' | 'Purged Stubs' | 'In Progress'
export type ChecksumResult = 'Matched' | 'Mismatch' | 'Unavailable'
export type FileTransfer = {
  operation: 'ARCHIVE' | 'RECALL'; source: string; destination: string; objectKey: string
  checksum: ChecksumResult; storageClass?: StorageClass; purged?: boolean
}
export type FileMovement = FileTransfer & { at: string; activityId?: string; outcome: 'Completed' | 'Failed' }
export type StorageAuth = 'host-mount' | 'aws-default' | 'reference'
export type S3Provider = 'aws' | 'minio' | 'scality' | 'compatible'
export type StorageConnection = { auth: StorageAuth; provider?: S3Provider; server?: string; remotePath?: string; endpointUrl?: string; credentialReference?: string }
export type Endpoint = { id: string; name: string; origin: Origin; path: string; items: number; health: 'Online' | 'Syncing' | 'Not checked'; detail: string; region?: string; enabled?: boolean; connection?: StorageConnection; verifiedAt?: number }
export type Entry = {
  id: string; endpoint: string; path: string; folder: boolean; bytes: number; state: FileState; modified: string
  owner?: string
  generated?: true
  checksum?: string; bucket?: string; key?: string; storageClass?: StorageClass; archivedAt?: string
  etag?: string; count?: number
  lineageId?: string; originalPath?: string; movements?: FileMovement[]
}
export type Operation = 'ARCHIVE' | 'RECALL' | 'SCAN' | 'VERIFY' | 'PURGE'
export type JobStatus = 'Running' | 'Paused' | 'Completed' | 'Partial Failure' | 'Failed' | 'Cancelled'
export type FileResult = { name: string; bytes: number; speed: number; version?: string; etag?: string; error?: string
  entryId?: string; transfer?: FileTransfer; sourceChecksum?: string; destinationChecksum?: string }
export type Job = {
  id: string; operation: Operation; title: string; target: string; actor: string; total: number
  done: number; bytes: number; elapsed: number; status: JobStatus; started: number; speed: number
  results: FileResult[]; entryIds?: string[]; bucket?: string; bucketId?: string; storageClass?: StorageClass; purge?: boolean
  destination?: string; retrieval?: string
  folderPaths?: { endpoint: string; path: string }[]
}

export const endpoints: Endpoint[] = [
  { id: 'nas', name: 'nas-01', origin: 'NAS', path: '/mnt/nas-01', items: 62148, health: 'Online', detail: '8.4 TB indexed' },
  { id: 'smb', name: 'media-share', origin: 'SMB', path: '//nas-01/media', items: 30452, health: 'Online', detail: '5.1 TB indexed' },
  { id: 'local', name: 'edge-02', origin: 'LOCAL', path: '/srv/koonj', items: 35820, health: 'Online', detail: '3.7 TB indexed' },
  { id: 'archive', name: 'koonj-archive-prod', origin: 'S3', path: 's3://koonj-archive-prod', items: 78324, health: 'Online', detail: 'eu-west-2', region: 'eu-west-2' },
  { id: 'cold', name: 'koonj-cold-store', origin: 'S3', path: 's3://koonj-cold-store', items: 25484, health: 'Syncing', detail: 'eu-west-2', region: 'eu-west-2' },
]

const gb = 1e9
export const demoOperatorName = 'Demo operator'
const day = 24 * 60 * 60 * 1000
const demoDay = Date.now()
const personalFileAges: Record<string, number> = {
  'nas:manifest.json': 4,
  'nas:projects/2026/raw-footage/audio/session-01.wav': 11,
  'nas:projects/2026/raw-footage/A001_C001.mov': 24,
  'nas:projects/2026/deliverables/campaign-notes.pdf': 28,
  'nas:finance/2023/annual-report.pdf': 36,
  'nas:shared/readme.md': 45,
  'local:exports/metadata-snapshot.json': 61,
}
const checksum = 'bf2c8a71e3d94f8c5aab48783eff3bbf2cb999011d020598b261b7beba'
function folder(endpoint: string, path: string, count: number): Entry {
  return { id: endpoint + ':' + path, endpoint, path, count, folder: true, bytes: 0, state: 'Local Only', modified: '2026-11-19T09:45:00Z' }
}
function file(endpoint: string, path: string, bytes: number, state: FileState, index: number, storageClass?: StorageClass): Entry {
  const id = endpoint + ':' + path
  const age = personalFileAges[id]
  const cloud = state === 'Archived' || state === 'Purged Stubs' || endpoint === 'archive' || endpoint === 'cold'
  const bucket = endpoint === 'cold' || storageClass === 'DEEP_ARCHIVE' ? 'koonj-cold-store' : 'koonj-archive-prod'
  return { id, endpoint, path, bytes, state, folder: false,
    owner: age === undefined ? undefined : demoOperatorName,
    modified: age === undefined ? `2026-11-${18 - index % 5}T09:${String(10 + index % 48).padStart(2, '0')}:00Z` : new Date(demoDay - age * day).toISOString(),
    checksum: (Math.imul(index, 0x9e3779b1) >>> 0).toString(16).padStart(8, '0') + checksum.slice(0, 56),
    ...(cloud && { bucket, key: path, storageClass: storageClass ?? 'STANDARD',
      archivedAt: '2026-11-17T16:42:00Z', etag: '7d8f94ba891b4a1681ec2e6a6bb64ad2' }),
  }
}

export const entries: Entry[] = [
  folder('nas', 'projects', 162), folder('nas', 'projects/2026', 160), folder('nas', 'projects/2026/raw-footage', 158),
  folder('nas', 'projects/2026/raw-footage/audio', 1), folder('nas', 'projects/2026/raw-footage/proxies', 1),
  file('nas', 'projects/2026/raw-footage/audio/session-01.wav', 72e6, 'Local Only', 153),
  file('nas', 'projects/2026/raw-footage/proxies/A001_proxy.mp4', 215e6, 'Archived', 154),
  folder('nas', 'projects/2026/deliverables', 2), folder('nas', 'projects/2025', 0), folder('nas', 'finance', 2),
  folder('nas', 'finance/2023', 2), folder('nas', 'backups', 1), folder('nas', 'shared', 1),
  file('nas', 'manifest.json', 2880, 'Local Only', 1), file('nas', 'storage-report.csv', 14820, 'Archived', 2),
  file('nas', 'projects/2026/deliverables/campaign-final.mp4', 2.4 * gb, 'Archived', 3),
  file('nas', 'projects/2026/deliverables/campaign-notes.pdf', 750000, 'Local Only', 4),
  file('nas', 'finance/2023/quarterly-ledger.parquet', 4.8 * gb, 'Purged Stubs', 5, 'GLACIER_IR'),
  file('nas', 'finance/2023/annual-report.pdf', 5200000, 'Archived', 6, 'GLACIER_IR'),
  file('nas', 'backups/nas-01-full.tar.zst', 67 * gb, 'Purged Stubs', 7, 'DEEP_ARCHIVE'),
  file('nas', 'shared/readme.md', 4820, 'Local Only', 8),
  ...['A001_C001.mov', 'A001_C002.mov', 'A001_C003.mov', 'A002_C001.mov', 'camera-notes.pdf', 'shoot-manifest.csv']
    .map((name, i) => file('nas', 'projects/2026/raw-footage/' + name, i < 4 ? (14 - i * 1.7) * gb : (i + 2) * 500000,
      (['Local Only', 'Archived', 'Purged Stubs', 'In Progress', 'Local Only', 'Archived'] as const)[i], i + 10, i === 2 ? 'GLACIER_IR' : undefined)),
  ...Array.from({ length: 150 }, (_, i) => file('nas', `projects/2026/raw-footage/B${String(i + 1).padStart(3, '0')}_C001.mov`,
    (1.3 + i % 9) * gb, i % 3 === 0 ? 'Archived' : 'Local Only', i + 20, i % 6 === 0 ? 'GLACIER_IR' : undefined)),
  folder('smb', 'campaigns', 1), folder('smb', 'campaigns/spring-launch', 1), folder('smb', 'renders', 1),
  file('smb', 'campaigns/spring-launch/hero-master.mov', 7.6 * gb, 'In Progress', 180),
  file('smb', 'renders/showreel-v3.mp4', 1.9 * gb, 'Local Only', 181),
  folder('local', 'exports', 1), file('local', 'exports/metadata-snapshot.json', 2120000, 'Local Only', 182),
  folder('archive', 'projects', 2), folder('archive', 'projects/2026', 2), folder('archive', 'projects/2026/raw-footage', 2),
  file('archive', 'projects/2026/raw-footage/A001_C002.mov', 12.3 * gb, 'Archived', 11),
  file('archive', 'projects/2026/raw-footage/A001_C003.mov', 10.6 * gb, 'Purged Stubs', 12, 'GLACIER_IR'),
  folder('cold', 'backups', 1), file('cold', 'backups/nas-01-full.tar.zst', 67 * gb, 'Purged Stubs', 7, 'DEEP_ARCHIVE'),
]

const sampleResults: FileResult[] = [
  { name: 'A001_C001.mov', bytes: 14 * gb, speed: 84, version: 'V52Yj6nx.9kU', etag: '7d8f94ba891b' },
  { name: 'A001_C002.mov', bytes: 12.3 * gb, speed: 71, version: '7KQ14KPq.28L', etag: '2341912cede4' },
  { name: 'A001_C003.mov', bytes: 10.6 * gb, speed: 0, error: 'SHA-256 checksum mismatch after transfer.' },
  { name: 'A002_C001.mov', bytes: 8.9 * gb, speed: 0, error: 'SHA-256 checksum mismatch after transfer.' },
  { name: 'camera-notes.pdf', bytes: 3000000, speed: 0, error: 'SHA-256 checksum mismatch after transfer.' },
]
const sampleTime = Date.parse('2026-11-19T10:42:00Z')
export const initialJobs: Job[] = [
  { id: 'ACT-1048', operation: 'ARCHIVE', title: 'Archive production footage', target: '/nas-01/projects/2026/raw-footage', actor: 'Demo operator', total: 1240, done: 864, bytes: 1200 * gb, elapsed: 402, status: 'Running', started: sampleTime, speed: 81, results: sampleResults.slice(0, 2) },
  { id: 'ACT-1047', operation: 'RECALL', title: 'Recall finance records', target: '/nas-01/finance/2023', actor: 'Demo operator', total: 420, done: 156, bytes: 240 * gb, elapsed: 289, status: 'Running', started: sampleTime - 300000, speed: 43, results: [{ name: 'annual-report.pdf', bytes: 5200000, speed: 43, version: 'J08AdZ2', etag: '085332831c8e' }] },
  { id: 'ACT-1046', operation: 'SCAN', title: 'Directory scan', target: '//nas-01/media', actor: 'Jamie Chen', total: 30452, done: 30452, bytes: 5100 * gb, elapsed: 98, status: 'Completed', started: sampleTime - 900000, speed: 0, results: [{ name: 'renders/showreel-v3.mp4', bytes: 1.9 * gb, speed: 0 }] },
  { id: 'ACT-1045', operation: 'ARCHIVE', title: 'Batch archive', target: '/nas-01/projects/2026/raw-footage', actor: 'Jamie Chen', total: 5, done: 5, bytes: 45.8 * gb, elapsed: 196, status: 'Partial Failure', started: sampleTime - 1800000, speed: 0,
    results: sampleResults.map(result => {
      if (!result.error) return result
      const path = 'projects/2026/raw-footage/' + result.name
      return { ...result, entryId: 'nas:' + path, transfer: {
        operation: 'ARCHIVE', source: '/mnt/nas-01/' + path, destination: 's3://koonj-archive-prod/' + path,
        objectKey: path, checksum: 'Mismatch',
      } }
    }) },
  { id: 'ACT-1044', operation: 'PURGE', title: 'Local purge', target: '/nas-01/backups', actor: 'Demo operator', total: 38, done: 38, bytes: 86 * gb, elapsed: 63, status: 'Completed', started: sampleTime - 3600000, speed: 0, results: [{ name: 'nas-01-full.tar.zst', bytes: 67 * gb, speed: 0, version: 'Ef62c17y', etag: '17c283dfaa79' }] },
]

export const alerts = [
  { icon: 'cloud', tone: 'yellow', title: 'Glacier restore ready', detail: '420 files in /finance/2023 are ready to recall.', time: '2 min ago', job: 'ACT-1047' },
  { icon: 'check', tone: 'green', title: 'SMB scan completed', detail: '//nas-01/media · 30,452 files cataloged', time: '18 min ago', job: 'ACT-1046' },
  { icon: 'warning', tone: 'red', title: 'Checksum verification failed', detail: '3 files need review before the archive can finish.', time: '32 min ago', job: 'ACT-1045' },
] as const

export const entryName = (entry: Entry) => entry.path.split('/').at(-1) ?? entry.path
export const parentPath = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf('/')))

export function fileTemperature(modified: string, now = Date.now()) {
  const age = Math.max(0, Math.floor((now - Date.parse(modified)) / day))
  if (!Number.isFinite(age)) return null
  return { age, temperature: age < 15 ? 'HOT' : age < 30 ? 'WARM' : 'COLD' } as const
}

