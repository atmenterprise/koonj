import { describe, expect, it } from 'vitest'
import type { CatalogLocationDTO, FileMetadataDTO } from '../../api/types'
import { metadataFor, metadataIndex } from './catalogMetadata'
import { planTransfers, transferTargets } from './transferSelection'
import { folderTotals, indexFolderContents } from './folderContents'
import { folderTree } from '../../api/mappers'
import { compareCatalogValues } from './catalogSort'
import { catalogSearchResults } from './catalogSearchResults'
import { fileAge, temperatureOf } from './fileDates'
import { favoriteHref, favoriteKey } from './catalogFavorites'

const local: CatalogLocationDTO = { id: 'local', name: 'Local', providerType: 'LOCAL', bucket: null, role: 'BOTH', active: true }
const bucket: CatalogLocationDTO = { id: 's3', name: 'Bucket', providerType: 'AWS_S3', bucket: 'archive', role: 'BOTH', active: true }
const folders = [{ name: 'docs', prefix: 'docs/', children: [] }, { name: 'photos', prefix: 'photos/', children: [] }]

it('applies the configured temperature thresholds at both boundaries', () => {
  const now = Date.parse('2026-11-23T00:00:00Z')
  const policy = { hotDays: 5, coldDays: 10 }
  expect(temperatureOf('2026-11-19T00:00:00Z', policy, now)).toBe('HOT')
  expect(temperatureOf('2026-11-18T00:00:00Z', policy, now)).toBe('WARM')
  expect(temperatureOf('2026-11-13T00:00:00Z', policy, now)).toBe('COLD')
  expect(temperatureOf(null, policy, now)).toBeUndefined()
})

it('links favorite folders and files to their exact catalog paths', () => {
  const folder = { connectionId: local.id, scope: 'FOLDER' as const, path: 'docs & notes/' }
  const file = { connectionId: local.id, scope: 'FILE' as const, path: 'docs & notes/report #2.txt' }
  expect(favoriteKey(folder)).toBe(favoriteKey({ ...folder, path: 'docs & notes' }))
  expect(favoriteHref(folder)).toBe('/explorer?endpoint=local&path=docs+%26+notes%2F')
  expect(favoriteHref(file)).toBe('/explorer?endpoint=local&path=docs+%26+notes%2Freport+%232.txt')
})

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
    expect(planTransfers(local, [local, bucket], [selected], folders, inventory).archive).toMatchObject({
      source: local, paths: ['docs/', 'photos/'], fileIds: ['standalone'], fileCount: 3,
    })
  })

  it('recalls a file from an archive target back to its source-only mounted location', () => {
    const sourceOnly: CatalogLocationDTO = { ...local, role: 'SOURCE' }
    const archiveBucket: CatalogLocationDTO = { ...bucket, role: 'TARGET' }
    const archived = file('archived', 'docs/report.txt', true)
    const locations = [sourceOnly, archiveBucket]
    expect(planTransfers(sourceOnly, locations, [archived], [], [archived]).recall[0]?.source).toEqual(archiveBucket)
    expect(transferTargets('recall', archiveBucket, locations)).toEqual([sourceOnly])
    expect(transferTargets('archive', sourceOnly, locations)).toEqual([archiveBucket])
    expect(planTransfers(sourceOnly, locations, [file('new', 'docs/new.txt')], [], []).recall).toEqual([])
  })

  it('recalls cloud folders by source prefix and mounted folders by destination prefix', () => {
    const files = [file('a', 'docs/a.txt', true), file('b', 'photos/b.jpg', true), file('c', 'docs/not-archived.txt'),
      file('d', 'docs-old/d.txt', true)]
    expect(planTransfers(local, [local, bucket], [files[0]], folders, files).recall[0]).toMatchObject({
      source: bucket, paths: [], fileIds: [], targetFolderPaths: ['docs/', 'photos/'], destinationId: local.id, fileCount: 2, skippedCount: 1,
    })
    const objects = files.map(file => ({ ...file, connectionId: bucket.id, entryType: 'FILE' as const }))
    expect(planTransfers(bucket, [local, bucket], [], folders, objects).recall[0]).toMatchObject({
      source: bucket, paths: ['docs/', 'photos/'], fileIds: [], fileCount: 3,
    })
  })

  it('filters mixed folder contents and explicit selections and groups recalls by archive source', () => {
    const secondBucket = { ...bucket, id: 'secondary', name: 'Secondary', bucket: 'secondary' }
    const document = file('doc', 'docs/ready.txt')
    const archived = file('archived', 'docs/archived.txt', true)
    const secondary = { ...file('second', 'photos/only-link.txt', true), targetConnectionId: secondBucket.id,
      symlinkTarget: 's3://secondary/photos/only-link.txt' }
    const external = { ...file('external', 'external', true), symlinkTarget: '/mount/other/file', targetConnectionId: null }
    const invalid = { ...file('invalid', 'docs/invalid', true), symlinkTarget: 's3://archive/../file' }
    const inventory = [document, archived, secondary, external, invalid]
    const plan = planTransfers(local, [local, bucket, secondBucket], [external, document], folders, inventory)
    expect(plan.archive).toMatchObject({ paths: ['docs/'], fileIds: [], fileCount: 1, skippedCount: 4,
      scopeCounts: new Map([['docs/', 1], ['photos/', 0], ['external', 0], ['docs/ready.txt', 1]]) })
    expect(plan.recall).toEqual([
      expect.objectContaining({ source: bucket, targetFolderPaths: ['docs/'], fileIds: [], fileCount: 1, skippedCount: 4,
        scopeCounts: new Map([['docs/', 1], ['photos/', 0], ['external', 0], ['docs/ready.txt', 0]]) }),
      expect.objectContaining({ source: secondBucket, targetFolderPaths: ['photos/'], fileIds: [], fileCount: 1, skippedCount: 4 }),
    ])
  })

  it('uses one root scope for select-all without sending individual IDs', () => {
    const direct = file('root-file', 'root-file')
    const nested = file('nested-file', 'photos/nested-file', true)
    const selection = planTransfers(local, [local, bucket], [direct], [{ prefix: '', name: 'Root', children: [] }, folders[1]],
      [direct, nested])
    expect(selection.archive).toMatchObject({ paths: ['/'], fileIds: [], fileCount: 1, skippedCount: 1 })
    expect(selection.recall[0]).toMatchObject({ targetFolderPaths: ['/'], fileIds: [], fileCount: 1, skippedCount: 1 })
  })

  it('counts a 500,001-file folder once and keeps redundant paths and IDs out of the request', () => {
    const first = file('first', 'docs/nested/first.txt')
    const catalog = [first, ...Array.from({ length: 500_000 }, (_, index) => ({
      ...first, id: String(index), relativePath: `docs/nested/${index}.txt`,
    }))]
    const nested = { prefix: 'docs/nested/', name: 'nested', children: [] }
    const plan = planTransfers(local, [local, bucket], [first], [folders[0], nested], catalog).archive
    expect(plan).toMatchObject({ source: local, paths: ['docs/'], fileIds: [], fileCount: 500_001 })
    const link = file('archived', 'docs/nested/archived.txt', true)
    const links = Array.from({ length: 500_001 }, (_, index) => ({
      ...link, id: String(index), relativePath: `docs/nested/${index}.txt`,
      symlinkTarget: `koonj-ref://${index}`, s3Key: `archive/${index}.txt`,
    }))
    expect(planTransfers(local, [local, bucket], [links[0]], [folders[0], nested], links).recall[0]).toMatchObject({
      source: bucket, paths: [], fileIds: [], targetFolderPaths: ['docs/'], destinationId: local.id, fileCount: 500_001,
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

it('keeps descriptions on their own items and indicates when age is based on modification', () => {
  const index = metadataIndex([
    { scope: 'FOLDER', path: 'docs', values: { description: 'All documents', tags: ['Finance'], properties: [] } },
    { scope: 'FILE', path: 'docs/report.pdf', values: { description: 'Quarterly report', tags: [], properties: [] } },
  ])
  const metadata = metadataFor(index, { scope: 'FILE', path: 'docs/report.pdf' })
  expect(metadata.direct.description).toBe('Quarterly report')
  expect(metadata.inherited.description).toBeUndefined()
  expect(metadata.effective.tags).toEqual(['Finance'])
  const now = Date.parse('2026-11-22')
  expect(fileAge('2026-11-01', '2026-11-20', now)).toBe('21 days')
  expect(fileAge(null, '2026-11-20', now)).toBe('2 days since last modified')
})

it('indexes the direct files and child folders for each expandable path', () => {
  const files = [file('root', 'root.txt'), file('doc', 'docs/report.txt'),
    file('nested', 'docs/2026/report.txt'), file('sibling', 'docs-old/report.txt')]
  const tree = folderTree(files.map(item => item.relativePath))
  const catalog = indexFolderContents(tree, files)
  const root = catalog.contents('')
  expect(root.files.map(item => item.id)).toEqual(['root'])
  expect(root.folders.map(item => item.name)).toEqual(['docs', 'docs-old'])
  const docs = catalog.contents('docs/')
  expect(docs.files.map(item => item.id)).toEqual(['doc'])
  expect(docs.folders.map(item => item.name)).toEqual(['2026'])
  expect(catalog.contents('docs/2026/').files.map(item => item.id)).toEqual(['nested'])
  expect(catalog.foldersByPath.get('docs/2026/')?.name).toBe('2026')
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
  const catalog = indexFolderContents(tree, [empty, link])
  expect(catalog.contents('').folders.map(folder => folder.name)).toEqual(['empty', 'docs'])
  expect(catalog.contents('').files).toEqual([])
  expect(catalog.contents('empty/')).toEqual({ folders: [], files: [] })
  expect(catalog.contents('docs/').files).toEqual([link])
  expect(planTransfers(local, [local, bucket], [link], [], [link]).archive).toBeUndefined()
  expect(catalogSearchResults([link], [local, bucket], new Map(), value => value.includes('docs/link')).files)
    .toHaveLength(1)
})
