import { describe, expect, it } from 'vitest'
import type { CatalogLocationDTO, FileMetadataDTO } from '../../api/types'
import { metadataFor, metadataIndex } from './catalogMetadata'
import { planTransfers, transferTargets } from './transferSelection'
import { folderContents, folderTotals } from './folderContents'
import { folderTree } from '../../api/mappers'
import { compareCatalogValues } from './catalogSort'
import { catalogSearchResults } from './catalogSearchResults'

const local: CatalogLocationDTO = { id: 'local', name: 'Local', providerType: 'LOCAL', bucket: null, role: 'BOTH', active: true }
const bucket: CatalogLocationDTO = { id: 's3', name: 'Bucket', providerType: 'AWS_S3', bucket: 'archive', role: 'BOTH', active: true }
const folders = [{ name: 'docs', prefix: 'docs/', children: [] }, { name: 'photos', prefix: 'photos/', children: [] }]

it('sorts catalog sizes numerically and keeps missing values at the end in both directions', () => {
  expect(compareCatalogValues(2, 10, 'ascending')).toBeLessThan(0)
  expect(compareCatalogValues(2, 10, 'descending')).toBeGreaterThan(0)
  expect(compareCatalogValues(undefined, 10, 'ascending')).toBeGreaterThan(0)
  expect(compareCatalogValues(undefined, 10, 'descending')).toBeGreaterThan(0)
  expect(compareCatalogValues('file2', 'file10', 'ascending')).toBeLessThan(0)
})

function file(id: string, relativePath: string, archived = false): FileMetadataDTO {
  return { id, relativePath, fileName: relativePath.split('/').at(-1)!, connectionId: local.id, entryType: archived ? 'SYMLINK' : 'FILE',
    symlinkTarget: archived ? 's3://archive/' + relativePath : null,
    targetConnectionId: archived ? bucket.id : null, s3Key: archived ? relativePath : null,
    sizeBytes: 1, checksumSha256: null, storageState: archived ? 'ARCHIVED_SYMLINK' : 'LOCAL_ONLY',
    s3StorageClass: null, lastModifiedAt: null, archivedAt: null, errorMessage: null }
}

it('finds files and folders by inherited tags and custom properties on either endpoint', () => {
  const files = [file('report', 'docs/report.txt', true), file('other', 'docs-old/report.txt'),
    { ...file('object', 'docs/report.txt'), connectionId: bucket.id, storageState: 'ARCHIVED' as const }]
  const metadata = new Map([[bucket.id, [{
    scope: 'FOLDER' as const, path: 'docs', values: { tags: ['Finance'], properties: [{ name: 'Owner', value: 'Alex' }] },
  }]]])
  const search = (term: string) => catalogSearchResults(files, [local, bucket], metadata,
    value => value.toLowerCase().includes(term))

  expect(search('finance').folders.map(result => result.href)).toEqual(['/explorer?endpoint=s3&path=docs%2F'])
  expect(search('alex').files.map(result => result.href)).toEqual(['/explorer?endpoint=s3&entry=object&path=docs%2Freport.txt'])
  expect(search('report.txt').files).toHaveLength(3)
})

describe('folder transfer selection', () => {
  it('sends multiple folder prefixes alongside explicitly selected file IDs for archive', () => {
    const selected = file('standalone', 'readme.txt')
    const inventory = [selected, file('doc', 'docs/doc.txt'), file('photo', 'photos/photo.jpg')]
    expect(planTransfers(local, [local, bucket], [selected], folders, inventory).archive).toEqual({
      source: local, paths: ['docs/', 'photos/'], fileIds: ['standalone'], destinationId: undefined,
    })
  })

  it('recalls a file from an archive target back to its source-only mounted location', () => {
    const sourceOnly: CatalogLocationDTO = { ...local, role: 'SOURCE' }
    const archiveBucket: CatalogLocationDTO = { ...bucket, role: 'TARGET' }
    const archived = file('archived', 'docs/report.txt', true)
    const locations = [sourceOnly, archiveBucket]
    expect(planTransfers(sourceOnly, locations, [archived], [], [archived]).recall?.source).toEqual(archiveBucket)
    expect(transferTargets('recall', archiveBucket, locations)).toEqual([sourceOnly])
    expect(transferTargets('archive', sourceOnly, locations)).toEqual([archiveBucket])
    expect(planTransfers(sourceOnly, locations, [file('new', 'docs/new.txt')], [], []).recall).toBeUndefined()
  })

  it('recalls cloud folders by prefix, or uses matching archive IDs when browsing a mounted location', () => {
    const files = [file('a', 'docs/a.txt', true), file('b', 'photos/b.jpg', true), file('c', 'docs/not-archived.txt'),
      file('d', 'docs-old/d.txt', true)]
    expect(planTransfers(local, [local, bucket], [files[0]], folders, files).recall).toEqual({
      source: bucket, paths: [], fileIds: ['a', 'b'], destinationId: local.id,
    })
    const objects = files.map(file => ({ ...file, connectionId: bucket.id, entryType: 'FILE' as const }))
    expect(planTransfers(bucket, [local, bucket], [], folders, objects).recall).toEqual({
      source: bucket, paths: ['docs/', 'photos/'], fileIds: [], destinationId: undefined,
    })
  })
})

it('inherits folder tags and uses the nearest direct property, matching names without case sensitivity', () => {
  const index = metadataIndex([
    { scope: 'FOLDER', path: 'docs', values: { tags: ['Finance'], properties: [{ name: 'Owner', value: 'Team' }] } },
    { scope: 'FOLDER', path: 'docs/2026', values: { tags: ['Budget'], properties: [{ name: 'owner', value: 'Department' }] } },
    { scope: 'FILE', path: 'docs/2026/report.txt', values: { tags: ['Reviewed'], properties: [{ name: 'OWNER', value: 'Alex' }] } },
  ])
  const metadata = metadataFor(index, { scope: 'FILE', path: 'docs/2026/report.txt' })
  expect(metadata.inherited.properties).toEqual([{ name: 'owner', value: 'Department' }])
  expect(metadata.effective).toEqual({ tags: ['Finance', 'Budget', 'Reviewed'], properties: [{ name: 'OWNER', value: 'Alex' }] })
  expect(metadataFor(index, { scope: 'FILE', path: 'docs-old/report.txt' }).effective.tags).toEqual([])
})

it('lists only the selected folder’s direct files and child folders, including during a search', () => {
  const files = [file('root', 'root.txt'), file('doc', 'docs/report.txt'),
    file('nested', 'docs/2026/report.txt'), file('sibling', 'docs-old/report.txt')]
  const tree = folderTree(files.map(item => item.relativePath))
  const root = folderContents(tree, files, '', () => true)
  expect(root.files.map(item => item.id)).toEqual(['root'])
  expect(root.folders.map(item => item.name)).toEqual(['docs', 'docs-old'])
  const docs = folderContents(tree, files, 'docs/', () => true)
  expect(docs.files.map(item => item.id)).toEqual(['doc'])
  expect(docs.folders.map(item => item.name)).toEqual(['2026'])
  expect(folderContents(tree, files, 'docs/', item => item.path.includes('report')).files.map(item => item.id)).toEqual(['doc'])
  expect(folderContents(tree, files, 'docs/2026/', () => true).files.map(item => item.id)).toEqual(['nested'])
})

it('totals nested catalog files and folders without including similarly named siblings', () => {
  const files = [
    file('root', 'root.txt'),
    { ...file('doc', 'docs/report.txt'), sizeBytes: 12 },
    { ...file('nested', 'docs/2026/report.txt'), sizeBytes: 18 },
    file('sibling', 'docs-old/report.txt'),
  ]
  const totals = folderTotals(folderTree(files.map(item => item.relativePath)), files)

  expect(totals.get('')).toEqual({ sizeBytes: 32, files: 4, folders: 3 })
  expect(totals.get('docs/')).toEqual({ sizeBytes: 30, files: 2, folders: 1 })
  expect(totals.get('docs/2026/')).toEqual({ sizeBytes: 18, files: 1, folders: 0 })
  expect(folderTotals([], []).get('')).toEqual({ sizeBytes: 0, files: 0, folders: 0 })
})

it('shows physical empty directories and limits archive actions to regular files', () => {
  const empty = { ...file('empty', 'empty'), entryType: 'DIRECTORY' as const }
  const link = file('link', 'docs/link', true)
  const tree = folderTree([empty.relativePath + '/', link.relativePath])
  expect(folderContents(tree, [empty, link], '').folders.map(folder => folder.name)).toEqual(['empty', 'docs'])
  expect(folderContents(tree, [empty, link], '').files).toEqual([])
  expect(planTransfers(local, [local, bucket], [link], [], [link]).archive).toBeUndefined()
  expect(catalogSearchResults([link], [local, bucket], new Map(), value => value.includes('docs/link')).files)
    .toHaveLength(1)
})
