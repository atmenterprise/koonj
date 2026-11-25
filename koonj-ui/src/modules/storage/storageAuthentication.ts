import { isObjectStorage } from '../../api/mappers'
import type { CreateConnectionRequest, ProviderType, StorageAuthMode, StorageConnectionDTO } from '../../api/types'

export const authModes: Record<StorageAuthMode, string> = {
  HOST_IDENTITY: 'Host filesystem identity',
  AWS_DEFAULT_CHAIN: 'AWS workload identity',
  AWS_ASSUME_ROLE: 'Assume AWS IAM role',
  SECRET_REF: 'AWS Secrets Manager',
  ENCRYPTED_CREDENTIALS: 'Encrypted access keys',
  MIGRATION_REQUIRED: 'Migration required',
}

export function availableAuthModes(provider: ProviderType, awsDefaultChainAllowed: boolean, encryptionEnabled: boolean) {
  if (!isObjectStorage(provider)) return ['HOST_IDENTITY'] as const
  const staticModes = encryptionEnabled
    ? ['ENCRYPTED_CREDENTIALS', 'SECRET_REF'] as const
    : ['SECRET_REF', 'ENCRYPTED_CREDENTIALS'] as const
  if (provider !== 'AWS_S3') return staticModes
  return awsDefaultChainAllowed
    ? ['AWS_DEFAULT_CHAIN', 'AWS_ASSUME_ROLE', ...staticModes] as const
    : ['AWS_ASSUME_ROLE', ...staticModes] as const
}

export function sameStorageSettings(payload: CreateConnectionRequest, saved: StorageConnectionDTO) {
  return payload.name === saved.name && payload.providerType === saved.providerType && payload.role === saved.role
    && payload.basePathOrBucket === saved.basePathOrBucket && payload.endpointUrl === saved.endpointUrl
    && payload.region === saved.region && payload.active === saved.active
    && (payload.pathStyleAccessEnabled ?? saved.pathStyleAccessEnabled) === saved.pathStyleAccessEnabled
    && payload.authMode === saved.authMode && (payload.secretRef ?? null) === saved.secretRef
    && (payload.awsRoleArn ?? null) === saved.awsRoleArn
    && (payload.objectPrefix?.replace(/\/$/, '') ?? null) === saved.objectPrefix
}
