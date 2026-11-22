import type { FolderNode } from '../../api/mappers'
import { isUuidArchiveLink } from './transferSelection'
import type { CatalogMetadataTarget, FileMetadataDTO } from '../../api/types'

export function folderTotals(folders: FolderNode[], files: FileMetadataDTO[]) {
  const totals = new Map<string, { sizeBytes: number; files: number; folders: number }>()

  function totalsFor(prefix: string) {
    const existing = totals.get(prefix)
    if (existing) return existing
    const total = { sizeBytes: 0, files: 0, folders: 0 }
    totals.set(prefix, total)
    return total
  }

  for (const file of files) {
    if (file.entryType === 'DIRECTORY') continue
    const prefix = file.relativePath.slice(0, file.relativePath.lastIndexOf('/') + 1)
    const total = totalsFor(prefix)
    if (!isUuidArchiveLink(file)) total.sizeBytes += file.sizeBytes
    total.files++
  }

  function aggregate(prefix: string, children: FolderNode[]) {
    const total = totalsFor(prefix)
    for (const child of children) {
      const nested = aggregate(child.prefix, child.children)
      total.sizeBytes += nested.sizeBytes
      total.files += nested.files
      total.folders += 1 + nested.folders
    }
    return total
  }

  aggregate('', folders)
  return totals
}

function childrenOf(folders: FolderNode[], prefix: string): FolderNode[] {
  if (!prefix) return folders
  const parent = folders.find(folder => prefix.startsWith(folder.prefix))
  if (!parent) return []
  return parent.prefix === prefix ? parent.children : childrenOf(parent.children, prefix)
}

export function folderContents(folders: FolderNode[], files: FileMetadataDTO[], prefix: string,
  matches: (target: CatalogMetadataTarget) => boolean = () => true) {
  return {
    folders: childrenOf(folders, prefix).filter(folder => matches({ scope: 'FOLDER', path: folder.prefix })),
    files: files.filter(file => file.entryType !== 'DIRECTORY' && file.relativePath.startsWith(prefix)
      && !file.relativePath.slice(prefix.length).includes('/')
      && matches({ scope: 'FILE', path: file.relativePath })),
  }
}
