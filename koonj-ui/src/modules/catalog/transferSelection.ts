import { isObjectStorage, type FolderNode } from '../../api/mappers'
import type { CatalogLocationDTO, FileMetadataDTO } from '../../api/types'

export function planTransfers(location: CatalogLocationDTO | undefined, locations: CatalogLocationDTO[],
  files: FileMetadataDTO[], folders: FolderNode[], catalog: FileMetadataDTO[]) {
  const paths = folders.map(folder => folder.prefix)
  const fileIds = files.map(file => file.id)
  if (!location || !(files.length || folders.length)) return {}
  const cloud = isObjectStorage(location.providerType)
  const readable = location.active && location.role !== 'TARGET'
  const archive = !cloud && readable && location.providerType !== 'SMB'
    && files.every(file => file.sourceConnectionId === location.id && file.storageState !== 'PURGED_LOCAL')
    ? { source: location, paths, fileIds } : undefined

  if (cloud) return { archive, recall: location.active ? { source: location, paths, fileIds } : undefined }

  const folderEntries = folders.map(folder => catalog.filter(file =>
    file.relativePath.startsWith(folder.prefix) && file.targetConnectionId && file.s3Key))
  if (folderEntries.some(entries => entries.length === 0)) return { archive }
  const selected = [...new Map([...files, ...folderEntries.flat()].map(file => [file.id, file])).values()]
  const source = locations.find(candidate => candidate.active
    && isObjectStorage(candidate.providerType) && selected.every(file =>
      file.sourceConnectionId === candidate.id || (file.targetConnectionId === candidate.id && file.s3Key)))
  return { archive, recall: source ? { source, paths: [], fileIds: selected.map(file => file.id) } : undefined }
}


export function transferTargets(action: 'archive' | 'recall', source: CatalogLocationDTO, locations: CatalogLocationDTO[]) {
  return locations.filter(location => location.active && location.id !== source.id && (action === 'archive'
    ? location.role !== 'SOURCE' && isObjectStorage(location.providerType)
    : location.providerType === 'LOCAL' || location.providerType === 'NAS'))
}
