import type { Endpoint, Entry, FileMovement, FileResult, FileTransfer, Job, Operation } from './sampleData'
import { parentPath } from './sampleData'

type TransferOptions = Pick<Job, 'bucket' | 'storageClass' | 'purge' | 'destination'>
type MovementOperation = FileTransfer['operation']

export const isMovement = (operation: Operation): operation is MovementOperation => operation === 'ARCHIVE' || operation === 'RECALL'
const join = (base: string, path: string) => base.replace(/\/+$/, '') + '/' + path
export const archiveUri = (entry: Entry) => entry.bucket && entry.key ? `s3://${entry.bucket}/${entry.key}` : undefined
export const lineageKey = (entry: Entry) => entry.lineageId ?? archiveUri(entry) ?? entry.id

export function describeTransfer(operation: MovementOperation, entry: Entry, endpoint: Endpoint | undefined, options: TransferOptions) {
  const original = endpoint ? join(endpoint.path, entry.path) : entry.path
  const objectKey = operation === 'ARCHIVE' ? join(entry.endpoint, entry.path) : entry.key
  const bucket = operation === 'ARCHIVE' ? options.bucket : entry.bucket
  if (!objectKey || !bucket || (operation === 'RECALL' && !options.destination)) return undefined
  const cloud = `s3://${bucket}/${objectKey}`
  const transfer: FileTransfer = {
    operation, objectKey,
    source: operation === 'ARCHIVE' ? original : cloud,
    destination: operation === 'ARCHIVE' ? cloud : join(options.destination!, entry.originalPath ?? entry.path),
    storageClass: operation === 'ARCHIVE' ? options.storageClass : entry.storageClass,
    checksum: 'Unavailable',
    purged: operation === 'ARCHIVE' && options.purge,
  }
  return transfer
}

export function verifyMovement(result: FileResult): FileResult {
  if (!result.transfer || result.error || result.transfer.checksum !== 'Unavailable') return result
  const { sourceChecksum, destinationChecksum } = result
  const checksum = !sourceChecksum || !destinationChecksum ? 'Unavailable' : sourceChecksum === destinationChecksum ? 'Matched' : 'Mismatch'
  return { ...result, transfer: { ...result.transfer, checksum },
    error: checksum === 'Matched' ? undefined : checksum === 'Mismatch' ? 'Checksum mismatch after transfer.' : 'Checksum comparison unavailable.' }
}

export function recallLocation(destination: string, endpoints: Endpoint[]) {
  const root = endpoints.filter(item => item.origin !== 'S3' && destination.startsWith(join(item.path, '')))
    .sort((left, right) => right.path.length - left.path.length)[0]
  return root ? { root, path: destination.slice(join(root.path, '').length) } : undefined
}

export function resolveTransferSelection(operation: Operation, selection: Entry[], catalog: Entry[], endpoints: Endpoint[]) {
  const ids = new Set(selection.map(entry => entry.id))
  const folders = selection.filter(entry => entry.folder && !entry.generated)
  const files = catalog.filter(entry => !entry.generated && !entry.folder && (ids.has(entry.id)
    || folders.some(folder => folder.endpoint === entry.endpoint && entry.path.startsWith(folder.path + '/'))))
  const cloud = new Set(endpoints.filter(endpoint => endpoint.origin === 'S3').map(endpoint => endpoint.id))
  const eligible = files.filter(entry => operation === 'ARCHIVE' ? entry.state === 'Local Only' && !cloud.has(entry.endpoint)
    : operation === 'RECALL' ? ['Archived', 'Purged Stubs'].includes(entry.state) && Boolean(archiveUri(entry)) : true)
  const emptyFolders = operation === 'VERIFY' ? folders.filter(folder => !files.some(file =>
    file.endpoint === folder.endpoint && file.path.startsWith(folder.path + '/'))) : []
  const folderPaths = catalog.filter(item => item.folder && !item.generated && folders.some(folder =>
    item.endpoint === folder.endpoint && (item.path === folder.path || item.path.startsWith(folder.path + '/'))))
    .map(item => ({ endpoint: item.endpoint, path: item.originalPath ?? item.path }))
  const copyEmpty = files.length === 0 && folders.length > 0 && isMovement(operation)
    && folders.every(folder => cloud.has(folder.endpoint) === (operation === 'RECALL'))
  return { files: [...eligible, ...emptyFolders], folders, folderPaths, skipped: files.length - eligible.length, copyEmpty }
}

function ensureFolder(catalog: Map<string, Entry>, endpoint: Endpoint, path: string, modified: string, originalPath?: string) {
  const id = endpoint.id + ':' + path
  if (!catalog.has(id)) catalog.set(id, { id, endpoint: endpoint.id, path, folder: true,
    bytes: 0, state: 'Local Only', modified, originalPath })
}

function addParentFolders(catalog: Map<string, Entry>, endpoint: Endpoint, path: string, modified: string, namespace = '') {
  for (let parent = parentPath(path); parent; parent = parentPath(parent)) {
    const original = namespace ? parent === namespace ? '' : parent.slice(namespace.length + 1) : undefined
    ensureFolder(catalog, endpoint, parent, modified, original)
  }
}

export function projectMovements(catalog: Map<string, Entry>, jobs: Job[], endpoints: Endpoint[]) {
  const histories = new Map<string, FileMovement[]>()
  function record(key: string, movement: FileMovement) {
    const history = histories.get(key) ?? []
    history.push(movement)
    histories.set(key, history)
  }

  const endpointById = new Map(endpoints.map(endpoint => [endpoint.id, endpoint]))
  const originalLocations = new Map<string, string>()
  for (const entry of catalog.values()) {
    const endpoint = endpointById.get(entry.endpoint)
    if (!entry.folder && archiveUri(entry) && endpoint && endpoint.origin !== 'S3') {
      originalLocations.set(lineageKey(entry), join(endpoint.path, entry.path))
    }
  }
  for (const entry of catalog.values()) {
    const uri = archiveUri(entry)
    if (!entry.archivedAt || !entry.key || !uri || histories.has(lineageKey(entry))) continue
    record(lineageKey(entry), { operation: 'ARCHIVE', source: originalLocations.get(lineageKey(entry)) ?? 'Original source unavailable',
      destination: uri, objectKey: entry.key, storageClass: entry.storageClass, at: entry.archivedAt,
      // Archived fixtures represent verified transfers in the demo.
      checksum: entry.checksum ? 'Matched' : 'Unavailable', outcome: 'Completed', purged: entry.state === 'Purged Stubs' })
  }

  const finished = jobs.filter(job => isMovement(job.operation) && ['Completed', 'Partial Failure', 'Failed'].includes(job.status))
    .sort((left, right) => left.started + left.elapsed * 1000 - right.started - right.elapsed * 1000)
  for (const job of finished) {
    const at = new Date(job.started + job.elapsed * 1000).toISOString()
    const target = endpoints.find(endpoint => endpoint.origin === 'S3'
      && (job.bucketId ? endpoint.id === job.bucketId : endpoint.path === 's3://' + job.bucket))
    if (job.status === 'Completed') {
      for (const folder of job.folderPaths ?? []) {
        if (job.operation === 'ARCHIVE' && target) {
          const path = join(folder.endpoint, folder.path)
          ensureFolder(catalog, target, path, at, folder.path)
          addParentFolders(catalog, target, path, at, folder.endpoint)
        } else if (job.operation === 'RECALL' && job.destination && folder.path) {
          const destination = join(job.destination, folder.path)
          const restored = recallLocation(destination, endpoints)
          if (!restored) continue
          const { root, path } = restored
          ensureFolder(catalog, root, path, at)
          addParentFolders(catalog, root, path, at)
        }
      }
    }
    job.results.forEach((result, index) => {
      const id = result.entryId ?? job.entryIds?.[index]
      const entry = id ? catalog.get(id) : undefined
      if (!entry || !isMovement(job.operation)) return
      // Older demo jobs did not record a checksum comparison.
      const legacy = result.transfer ? undefined : describeTransfer(job.operation, entry, endpointById.get(entry.endpoint), job)
      const transfer = result.transfer ?? (legacy?.operation === 'ARCHIVE'
        ? { ...legacy, objectKey: entry.path, destination: `s3://${job.bucket}/${entry.path}` } : legacy)
      if (!transfer) return
      const key = lineageKey(entry)
      const failed = Boolean(result.error) || job.status === 'Failed' || transfer.checksum === 'Mismatch'
      record(key, { ...transfer, at, activityId: job.id, outcome: failed ? 'Failed' : 'Completed' })
      if (failed) return
      const moved: Entry = { ...entry, lineageId: key, originalPath: entry.originalPath ?? entry.path, state: transfer.purged ? 'Purged Stubs' : 'Archived' }
      if (transfer.operation === 'ARCHIVE') {
        const bucket = job.bucket
        if (!target || !bucket) return
        const archived = { ...moved, bucket, key: transfer.objectKey, archivedAt: at, storageClass: transfer.storageClass }
        catalog.set(entry.id, archived)
        const cloudId = target.id + ':' + transfer.objectKey
        catalog.set(cloudId, { ...archived, id: cloudId, endpoint: target.id, path: transfer.objectKey })
        addParentFolders(catalog, target, transfer.objectKey, at, legacy ? '' : entry.endpoint)
        return
      }

      const restored = recallLocation(transfer.destination, endpoints)
      if (!restored) return
      const { root, path } = restored
      const targetId = root.id + ':' + path
      const existing = catalog.get(targetId)
      if (existing && lineageKey(existing) !== key) return
      catalog.set(targetId, { ...moved, id: targetId, endpoint: root.id, path })
      addParentFolders(catalog, root, path, at)
    })
  }

  for (const [id, entry] of catalog) {
    const history = histories.get(lineageKey(entry))
    if (history) catalog.set(id, { ...entry, movements: history })
  }
}
