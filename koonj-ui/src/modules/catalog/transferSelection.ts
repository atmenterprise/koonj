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
}

function selectedFolderFor(path: string, folders: ReadonlySet<string>) {
  for (let slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1)) {
    const prefix = path.slice(0, slash + 1)
    if (folders.has(prefix)) return prefix
    if (slash === 0) break
  }
}

export function planTransfers(location: CatalogLocationDTO | undefined, locations: CatalogLocationDTO[],
  files: FileMetadataDTO[], folders: FolderNode[], catalog: FileMetadataDTO[]): { archive?: TransferPlan; recall?: TransferPlan } {
  if (!location?.active || !(files.length || folders.length)) return {}
  const chosen = new Set(folders.map(folder => folder.prefix))
  const paths = [...chosen].filter(path => !selectedFolderFor(path.slice(0, -1), chosen))
  const prefixes = new Set(paths)
  const explicitFiles = files.filter(file => !selectedFolderFor(file.relativePath, prefixes))
  const fileIds = explicitFiles.map(file => file.id)
  const cloud = isObjectStorage(location.providerType)
  const regular = (file: FileMetadataDTO) => file.connectionId === location.id && file.entryType === 'FILE'
  const regularFolders = new Set<string>()
  const linkFolders = new Set<string>()
  let regularCount = 0
  let linkCount = 0
  let source: CatalogLocationDTO | undefined
  let compatibleLinks = true
  const checkLinks = !cloud && explicitFiles.every(file => file.entryType === 'SYMLINK')

  function includeLink(file: FileMetadataDTO, path?: string) {
    if (!compatibleLinks) return
    const bucket = linkedBucket(file, locations)
    if (file.connectionId !== location?.id || !bucket || (source && bucket.id !== source.id)) {
      compatibleLinks = false
      return
    }
    source = bucket
    linkCount++
    if (path) linkFolders.add(path)
  }

  if (checkLinks) explicitFiles.forEach(file => includeLink(file))
  if (paths.length) {
    for (const file of catalog) {
      if (file.entryType === 'DIRECTORY') continue
      const path = selectedFolderFor(file.relativePath, prefixes)
      if (!path) continue
      if (regular(file)) {
        regularFolders.add(path)
        regularCount++
      } else if (checkLinks && file.entryType === 'SYMLINK') includeLink(file, path)
    }
  }
  const regularPlan = explicitFiles.every(regular) && regularFolders.size === paths.length
    ? { source: location, paths, fileIds, fileCount: regularCount + fileIds.length } : undefined
  if (cloud) return { recall: regularPlan }
  const archive = location.role !== 'TARGET' ? regularPlan : undefined
  const recall = source && compatibleLinks && linkFolders.size === paths.length
    ? { source, paths: [], fileIds, targetFolderPaths: paths, destinationId: location.id, fileCount: linkCount } : undefined
  return { archive, recall }
}

export function transferTargets(action: 'archive' | 'recall', source: CatalogLocationDTO, locations: CatalogLocationDTO[]) {
  return locations.filter(location => location.active && location.id !== source.id && (action === 'archive'
    ? location.role !== 'SOURCE' && isObjectStorage(location.providerType)
    : !isObjectStorage(location.providerType)))
}
