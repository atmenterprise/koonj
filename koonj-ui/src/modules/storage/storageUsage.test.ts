import { expect, it } from 'vitest'
import type { StorageConnectionDTO } from '../../api/types'
import { monthlySavings, summarizeUsage } from './storageUsage'

function connection(id: string, providerType: StorageConnectionDTO['providerType']): StorageConnectionDTO {
  return { id, name: id, providerType, role: 'BOTH', active: true, basePathOrBucket: id,
    endpointUrl: null, region: null, accessKeyOrUsername: null, secretKeyOrPassword: null,
    pathStyleAccessEnabled: false, createdAt: '', updatedAt: '' }
}

it('keeps per-location usage and counts reclaimed space only once in the total savings', () => {
  const connections = [connection('nas', 'NAS'), connection('aws', 'AWS_S3'), connection('minio', 'MINIO'), connection('empty', 'SMB')]
  const summary = summarizeUsage(connections, {
    footprints: [
      { connectionId: 'nas', storageClass: null, fileCount: 1, sizeBytes: 9e9 },
      { connectionId: 'aws', storageClass: 'STANDARD', fileCount: 2, sizeBytes: 8e9 },
      { connectionId: 'minio', storageClass: 'CUSTOM_CLASS', fileCount: 1, sizeBytes: 4e9 },
    ],
    reclaimed: [
      { sourceConnectionId: 'nas', targetConnectionId: 'aws', fileCount: 1, sizeBytes: 5e9 },
      { sourceConnectionId: 'nas', targetConnectionId: 'minio', fileCount: 1, sizeBytes: 4e9 },
    ],
  })
  expect(summary.mounted.sizeBytes).toBe(9e9)
  expect(summary.object.sizeBytes).toBe(12e9)
  expect(summary.locations.get('empty')?.stored.fileCount).toBe(0)
  expect(summary.locations.get('minio')?.classes.get('CUSTOM_CLASS')?.sizeBytes).toBe(4e9)
  expect(summary.locations.get('aws')?.reclaimed.sizeBytes).toBe(5e9)
  expect(summary.locations.get('nas')?.reclaimed.sizeBytes).toBe(9e9)
  expect(monthlySavings(summary.reclaimed.sizeBytes)).toBeCloseTo(0.18)
})
