import { describe, expect, it } from 'vitest'
import type { CatalogLocationDTO, FileMetadataDTO } from '../../api/types'
import { metadataFor, metadataIndex } from './catalogMetadata'
import { planTransfers, transferTargets } from './transferSelection'
import { folderContents } from './folderContents'
import { folderTree } from '../../api/mappers'

const local: CatalogLocationDTO = { id: 'local', name: 'Local', providerType: 'LOCAL', bucket: null, role: 'BOTH', active: true }
const bucket: CatalogLocationDTO = { id: 's3', name: 'Bucket', providerType: 'AWS_S3', bucket: 'archive', role: 'BOTH', active: true }
const folders = [{ name: 'docs', prefix: 'docs/', children: [] }, { name: 'photos', prefix: 'photos/', children: [] }]

function file(id: string, relativePath: string, archived = false): FileMetadataDTO {
  return { id, relativePath, fileName: relativePath.split('/').at(-1)!, sourceConnectionId: local.id,
    targetConnectionId: archived ? bucket.id : null, s3Key: archived ? relativePath : null,
    sizeBytes: 1, checksumSha256: null, storageState: archived ? 'ARCHIVED' : 'LOCAL_ONLY',
    s3StorageClass: null, lastModifiedAt: null, archivedAt: null, errorMessage: null }
}

describe('folder transfer selection', () => {
  it('sends multiple folder prefixes alongside explicitly selected file IDs for archive', () => {
    const selected = file('standalone', 'readme.txt')
    expect(planTransfers(local, [local, bucket], [selected], folders, [selected]).archive).toEqual({
      source: local, paths: ['docs/', 'photos/'], fileIds: ['standalone'],
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
      source: bucket, paths: [], fileIds: ['a', 'b'],
    })
    expect(planTransfers(bucket, [local, bucket], [], folders, files).recall).toEqual({
      source: bucket, paths: ['docs/', 'photos/'], fileIds: [],
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
