import type { FolderNode } from '../../api/mappers'
import { isUuidArchiveLink } from './transferSelection'
import type { FileMetadataDTO } from '../../api/types'

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

export function indexFolderContents(folders: FolderNode[], files: FileMetadataDTO[]) {
  const foldersByPath = new Map<string, FolderNode>()
  const filesByFolder = new Map<string, FileMetadataDTO[]>()

  function indexFolders(nodes: FolderNode[]) {
    for (const node of nodes) {
      foldersByPath.set(node.prefix, node)
      indexFolders(node.children)
    }
  }
  indexFolders(folders)

  for (const file of files) {
    if (file.entryType === 'DIRECTORY') continue
    const prefix = file.relativePath.slice(0, file.relativePath.lastIndexOf('/') + 1)
    const siblings = filesByFolder.get(prefix)
    if (siblings) siblings.push(file)
    else filesByFolder.set(prefix, [file])
  }

  return {
    foldersByPath,
    contents(prefix: string) {
      return { folders: prefix ? foldersByPath.get(prefix)?.children ?? [] : folders, files: filesByFolder.get(prefix) ?? [] }
    },
  }
}
