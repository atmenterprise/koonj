import { isObjectStorage, type FolderNode } from '../../api/mappers'
import type { CatalogLocationDTO, FileMetadataDTO } from '../../api/types'

export function linkedBucket(file: FileMetadataDTO, locations: CatalogLocationDTO[]) {
  if (!file.symlinkTarget?.startsWith('s3://')) return undefined
  const name = file.symlinkTarget.slice(5).split('/')[0]
  const candidates = locations.filter(location => location.active && isObjectStorage(location.providerType)
    && location.bucket === name)
  return file.targetConnectionId
    ? candidates.find(location => location.id === file.targetConnectionId)
    : candidates.length === 1 ? candidates[0] : undefined
}

export function planTransfers(location: CatalogLocationDTO | undefined, locations: CatalogLocationDTO[],
  files: FileMetadataDTO[], folders: FolderNode[], catalog: FileMetadataDTO[]) {
  const paths = folders.map(folder => folder.prefix)
  const fileIds = files.map(file => file.id)
  if (!location?.active || !(files.length || folders.length)) return {}
  const cloud = isObjectStorage(location.providerType)
  const regular = (file: FileMetadataDTO) => file.connectionId === location.id && file.entryType === 'FILE'
  const hasRegularFiles = paths.every(path => catalog.some(file => regular(file) && file.relativePath.startsWith(path)))
  const archive = !cloud && location.role !== 'TARGET' && files.every(regular) && hasRegularFiles
    ? { source: location, paths, fileIds, destinationId: undefined } : undefined

  if (cloud) return { archive, recall: files.every(regular) && hasRegularFiles
    ? { source: location, paths, fileIds, destinationId: undefined } : undefined }

  const folderLinks = paths.map(path => catalog.filter(file => file.entryType === 'SYMLINK' && file.relativePath.startsWith(path)))
  if (folderLinks.some(entries => entries.length === 0)) return { archive }
  const selected = [...new Map([...files, ...folderLinks.flat()].map(file => [file.id, file])).values()]
  const source = selected[0] && linkedBucket(selected[0], locations)
  const recall = source && selected.every(file => file.connectionId === location.id && file.entryType === 'SYMLINK'
    && linkedBucket(file, locations)?.id === source.id)
    ? { source, paths: [], fileIds: selected.map(file => file.id), destinationId: location.id } : undefined
  return { archive, recall }
}

export function transferTargets(action: 'archive' | 'recall', source: CatalogLocationDTO, locations: CatalogLocationDTO[]) {
  return locations.filter(location => location.active && location.id !== source.id && (action === 'archive'
    ? location.role !== 'SOURCE' && isObjectStorage(location.providerType)
    : !isObjectStorage(location.providerType)))
}
