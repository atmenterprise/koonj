import { stateLabels } from '../../api/mappers'
import type { CatalogLocationDTO, CatalogMetadataEntry, CatalogMetadataTarget, FileMetadataDTO } from '../../api/types'
import { metadataFor, metadataIndex } from './catalogMetadata'

export function catalogSearchResults(files: FileMetadataDTO[], locations: CatalogLocationDTO[],
  metadata: ReadonlyMap<string, CatalogMetadataEntry[]>, matches: (value: string) => boolean) {
  const endpoints = new Map(locations.map(location => [location.id, location]))
  const indexes = new Map([...metadata].map(([id, entries]) => [id, metadataIndex(entries)]))
  const folders = new Map<string, Set<string>>()

  function addFolder(endpoint: string, path: string) {
    if (!path) return
    let paths = folders.get(endpoint)
    if (!paths) folders.set(endpoint, paths = new Set())
    paths.add(path)
  }

  function metadataMatch(endpoint: string, target: CatalogMetadataTarget) {
    const index = indexes.get(endpoint)
    if (!index?.size) return undefined
    const { effective } = metadataFor(index, target)
    return [...effective.tags, ...effective.properties.map(({ name, value }) => name + ': ' + value)].find(matches)
  }

  const fileResults = files.flatMap(file => {
    const endpoint = endpoints.get(file.connectionId)
    if (!endpoint) return []
    const parts = file.relativePath.replace(/\/+$/, '').split('/')
    if (file.entryType !== 'DIRECTORY') parts.pop()
    let prefix = ''
    for (const part of parts) {
      prefix += (prefix ? '/' : '') + part
      addFolder(endpoint.id, prefix)
    }

    if (file.entryType === 'DIRECTORY') return []

    const metadataValue = metadataMatch(endpoint.id, { scope: 'FILE', path: file.relativePath })
    const fileValue = [file.fileName, file.relativePath, stateLabels[file.storageState], file.storageState,
      file.sizeBytes.toString(), file.checksumSha256, file.s3Key, file.s3StorageClass, file.lastModifiedAt,
      file.archivedAt, file.symlinkTarget].find(value => value && matches(value))
    if (!metadataValue && !fileValue && !(file.errorMessage && matches(file.errorMessage))) return []
    const detail = [endpoint.name, file.relativePath, metadataValue ?? (fileValue === file.fileName || fileValue === file.relativePath ? undefined : fileValue)]
    return [{ title: file.fileName, detail: detail.filter(Boolean).join(' · '), icon: file.entryType === 'SYMLINK' ? 'link' as const : 'file' as const,
      href: '/explorer?' + new URLSearchParams({ endpoint: endpoint.id, entry: file.id, path: file.relativePath }) }]
  })

  const folderResults = [...folders].flatMap(([id, paths]) => [...paths].flatMap(path => {
    const endpoint = endpoints.get(id)
    const metadataValue = metadataMatch(id, { scope: 'FOLDER', path })
    if (!endpoint || (!matches(path) && !metadataValue)) return []
    return [{ title: path.split('/').at(-1) ?? path,
      detail: [endpoint.name, path, metadataValue].filter(Boolean).join(' · '), icon: 'folder' as const,
      href: '/explorer?' + new URLSearchParams({ endpoint: id, path: path + '/' }) }]
  }))
  return { files: fileResults, folders: folderResults }
}
