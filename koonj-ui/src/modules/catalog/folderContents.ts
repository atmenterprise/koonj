import type { FolderNode } from '../../api/mappers'
import type { CatalogMetadataTarget, FileMetadataDTO } from '../../api/types'

function childrenOf(folders: FolderNode[], prefix: string): FolderNode[] {
  if (!prefix) return folders
  const parent = folders.find(folder => prefix.startsWith(folder.prefix))
  if (!parent) return []
  return parent.prefix === prefix ? parent.children : childrenOf(parent.children, prefix)
}

export function folderContents(folders: FolderNode[], files: FileMetadataDTO[], prefix: string,
  matches: (target: CatalogMetadataTarget) => boolean) {
  return {
    folders: childrenOf(folders, prefix).filter(folder => matches({ scope: 'FOLDER', path: folder.prefix })),
    files: files.filter(file => file.relativePath.startsWith(prefix)
      && !file.relativePath.slice(prefix.length).includes('/')
      && matches({ scope: 'FILE', path: file.relativePath })),
  }
}
