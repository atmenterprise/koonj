import { isObjectStorage, type FolderNode } from '../../api/mappers'
import type { CatalogLocationDTO, FileMetadataDTO } from '../../api/types'

export function isUuidArchiveLink(file: FileMetadataDTO) {
  return file.entryType === 'SYMLINK' && file.symlinkTarget === 'koonj-ref://' + file.id
}

export function linkedBucket(file: FileMetadataDTO, locations: CatalogLocationDTO[]) {
  if (isUuidArchiveLink(file) && file.s3Key) {
    return locations.find(location => location.id === file.targetConnectionId
      && location.active && isObjectStorage(location.providerType))
  }
  if (!file.symlinkTarget?.startsWith('s3://')) return undefined
  const name = file.symlinkTarget.slice(5).split('/')[0]
  const candidates = locations.filter(location => location.active && isObjectStorage(location.providerType)
    && location.bucket === name)
  return file.targetConnectionId
    ? candidates.find(location => location.id === file.targetConnectionId)
    : candidates.length === 1 ? candidates[0] : undefined
}

export interface TransferPlan {
  source: CatalogLocationDTO
  paths: string[]
  fileIds: string[]
  destinationId?: string
  targetFolderPaths?: string[]
  fileCount: number
  skippedCount: number
  scopeCounts: ReadonlyMap<string, number>
}

function selectedFolderFor(path: string, folders: ReadonlySet<string>) {
  if (folders.has('')) return ''
  for (let slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1)) {
    const prefix = path.slice(0, slash + 1)
    if (folders.has(prefix)) return prefix
    if (slash === 0) break
  }
}

function recallSource(file: FileMetadataDTO, locations: CatalogLocationDTO[]) {
  if (file.entryType !== 'SYMLINK') return undefined
  const bucket = linkedBucket(file, locations)
  const key = isUuidArchiveLink(file) ? file.s3Key : bucket && file.symlinkTarget?.slice(('s3://' + bucket.bucket + '/').length)
  if (!key || key.includes('\\') || key.includes('\0') || key.split('/').some(part => !part || part === '.' || part === '..')
    || /^\.koonj-transfer\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:-previous)?$/.test(key)) return undefined
  return bucket
}

export function planTransfers(location: CatalogLocationDTO | undefined, locations: CatalogLocationDTO[],
  files: FileMetadataDTO[], folders: FolderNode[], catalog: FileMetadataDTO[]): { archive?: TransferPlan; recall: TransferPlan[] } {
  if (!location?.active || !(files.length || folders.length)) return { recall: [] }
  const locationId = location.id
  const chosen = new Set(folders.map(folder => folder.prefix))
  const paths = [...chosen].filter(path => path === '' || selectedFolderFor(path.slice(0, -1), chosen) === undefined)
  const prefixes = new Set(paths)
  const explicitFiles = files.filter(file => selectedFolderFor(file.relativePath, prefixes) === undefined)
  const nestedFolders = new Set([...chosen].filter(path => !prefixes.has(path)))
  const filePaths = new Set(files.map(file => file.relativePath))
  const scopes = [...chosen, ...filePaths]
  const cloud = isObjectStorage(location.providerType)
  const counts = new Map<string, number>()
  const regularIds: string[] = []
  const archivedBySource = new Map<string, { source: CatalogLocationDTO; ids: string[]; counts: Map<string, number>; total: number }>()
  let selectedCount = 0
  let regularCount = 0

  function addEligible(filePath: string, scope: string, eligible: Map<string, number>) {
    eligible.set(scope, (eligible.get(scope) ?? 0) + 1)
    if (filePath !== scope && filePaths.has(filePath)) eligible.set(filePath, 1)
    if (!nestedFolders.size) return
    for (let slash = filePath.indexOf('/'); slash !== -1; slash = filePath.indexOf('/', slash + 1)) {
      const nested = filePath.slice(0, slash + 1)
      if (nestedFolders.has(nested)) eligible.set(nested, (eligible.get(nested) ?? 0) + 1)
    }
  }

  function include(file: FileMetadataDTO, scope: string, explicit: boolean) {
    if (file.connectionId !== locationId || file.entryType === 'DIRECTORY') return
    selectedCount++
    if (file.entryType === 'FILE') {
      regularCount++
      addEligible(file.relativePath, scope, counts)
      if (explicit) regularIds.push(file.id)
    } else if (!cloud) {
      const source = recallSource(file, locations)
      if (!source) return
      let links = archivedBySource.get(source.id)
      if (!links) {
        links = { source, ids: [], counts: new Map(), total: 0 }
        archivedBySource.set(source.id, links)
      }
      links.total++
      addEligible(file.relativePath, scope, links.counts)
      if (explicit) links.ids.push(file.id)
    }
  }

  explicitFiles.forEach(file => include(file, file.relativePath, true))
  if (prefixes.size) {
    for (const file of catalog) {
      const scope = selectedFolderFor(file.relativePath, prefixes)
      if (scope !== undefined) include(file, scope, false)
    }
  }

  function scopeSummary(eligible: ReadonlyMap<string, number>, fileCount: number) {
    return { fileCount, skippedCount: selectedCount - fileCount,
      scopeCounts: new Map(scopes.map(scope => [scope, eligible.get(scope) ?? 0])) }
  }
  const eligiblePaths = (eligible: ReadonlyMap<string, number>) => paths.filter(path => eligible.has(path)).map(path => path || '/')
  const regular = regularCount ? { source: location, paths: eligiblePaths(counts), fileIds: regularIds,
    ...scopeSummary(counts, regularCount) } : undefined
  if (cloud) return { recall: regular ? [regular] : [] }
  const archive = location.role !== 'TARGET' ? regular : undefined
  const recall = [...archivedBySource.values()].map(({ source, ids, counts, total }) => ({
    source, paths: [], fileIds: ids, targetFolderPaths: eligiblePaths(counts), destinationId: location.id, ...scopeSummary(counts, total),
  }))
  return { archive, recall }
}

export function transferTargets(action: 'archive' | 'recall', source: CatalogLocationDTO, locations: CatalogLocationDTO[]) {
  return locations.filter(location => location.active && location.id !== source.id && (action === 'archive'
    ? location.role !== 'SOURCE' && isObjectStorage(location.providerType)
    : !isObjectStorage(location.providerType)))
}
