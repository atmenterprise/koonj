import { apiClient } from '../client'

export const notificationEvents = {
  LARGE_BATCH_COMPLETED: 'Large batch completed',
  TRANSFER_FAILED: 'Transfer failed',
  QUOTA_THRESHOLD: 'Quota threshold',
  LICENSE_ALERT: 'License alert',
} as const

export type NotificationEvent = keyof typeof notificationEvents
export const channelTypes = { WEBHOOK: 'Generic webhook', SLACK: 'Slack', TEAMS: 'Microsoft Teams', EMAIL: 'SMTP email' } as const
export type ChannelType = keyof typeof channelTypes
type SmtpTls = 'NONE' | 'STARTTLS' | 'SSL'

interface ChannelOptions {
  adminRecipients?: string[]
  smtpHost?: string | null
  smtpPort?: number | null
  smtpTls?: SmtpTls | null
  smtpUsername?: string | null
  from?: string | null
  subjectTemplate?: string | null
  bodyTemplate?: string | null
}

interface ChannelIdentity {
  name: string
  enabled: boolean
  type: ChannelType
}

export interface NotificationChannel extends ChannelOptions, ChannelIdentity {
  id: string
  hasEndpoint: boolean
  hasHeaders: boolean
  hasSigningSecret: boolean
  hasSmtpPassword: boolean
}

export interface ChannelSettings extends ChannelOptions, ChannelIdentity {
  endpoint?: string
  headers?: Record<string, string>
  signingSecret?: string
  smtpPassword?: string
}

export interface Thresholds {
  fileCount?: number | null
  bytes?: number | null
  failureCount?: number | null
  warningPercent?: number | null
  expiryWarningDays?: number | null
  cooldownMinutes?: number | null
}
export interface RuleSettings {
  eventType: NotificationEvent
  thresholdConfig: Thresholds
  channelIds: string[]
  enabled: boolean
}
export interface NotificationRule extends RuleSettings {
  id: string
}
export interface NotificationDelivery {
  id: string
  channelId: string
  eventType: NotificationEvent
  status: 'PENDING' | 'SENT' | 'FAILED' | 'SUPPRESSED_OPT_OUT'
  recipient: string | null
  attemptCount: number
  errorMessage: string | null
  createdAt: string
  testDelivery: boolean
}
export interface NotificationPreferences {
  emailNotificationsEnabled: boolean
  mutedEventTypes: NotificationEvent[]
  silencedUntil: string | null
}

const base = '/notifications'
export const notificationService = {
  async channels(signal?: AbortSignal) {
    return (await apiClient.get<NotificationChannel[]>(base + '/channels', { signal })).data
  },
  async rules(signal?: AbortSignal) {
    return (await apiClient.get<NotificationRule[]>(base + '/rules', { signal })).data
  },
  async deliveries(page: number, signal?: AbortSignal) {
    return (await apiClient.get<{ content: NotificationDelivery[]; totalPages: number }>(base + '/deliveries', {
      signal, params: { page, size: 25 },
    })).data
  },
  saveChannel(settings: ChannelSettings, id?: string) {
    return id ? apiClient.put(base + '/channels/' + id, settings) : apiClient.post(base + '/channels', settings)
  },
  saveRule(settings: RuleSettings, id?: string) {
    return id ? apiClient.put(base + '/rules/' + id, settings) : apiClient.post(base + '/rules', settings)
  },
  remove(kind: 'channels' | 'rules', id: string) {
    return apiClient.delete(base + '/' + kind + '/' + id)
  },
  test(id: string) {
    return apiClient.post(base + '/channels/' + id + '/test')
  },
  async preferences(signal?: AbortSignal) {
    return (await apiClient.get<NotificationPreferences>(base + '/preferences/me', { signal })).data
  },
  async savePreferences(settings: NotificationPreferences) {
    return (await apiClient.put<NotificationPreferences>(base + '/preferences/me', settings)).data
  },
}
