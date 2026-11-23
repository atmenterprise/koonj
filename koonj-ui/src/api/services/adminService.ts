import { apiClient } from '../client'
import type { ServerTelemetryDTO } from '../types'

export type IntegrationKind = 'API' | 'MCP'
export interface IntegrationSettings {
  kind: IntegrationKind
  name: string
  url: string
  enabled: boolean
}
export interface IntegrationEndpoint extends IntegrationSettings {
  id: string
}
export type TelemetrySignal = 'logs' | 'metrics' | 'traces'
export interface ObservabilitySettings {
  openObserveUrl: string | null
  organization: string
  exporters: Record<TelemetrySignal, boolean>
}

export const adminService = {
  async getTelemetry(signal?: AbortSignal) {
    return (await apiClient.get<ServerTelemetryDTO>('/admin/telemetry', { signal })).data
  },
  async getObservability(signal?: AbortSignal) {
    return (await apiClient.get<ObservabilitySettings>('/admin/observability', { signal })).data
  },
  async getIntegrations(kind: IntegrationKind, signal?: AbortSignal) {
    return (await apiClient.get<IntegrationEndpoint[]>('/admin/integrations', { params: { kind }, signal })).data
  },
  async saveIntegration(settings: IntegrationSettings, id?: string) {
    const response = id
      ? await apiClient.put<IntegrationEndpoint>(`/admin/integrations/${id}`, settings)
      : await apiClient.post<IntegrationEndpoint>('/admin/integrations', settings)
    return response.data
  },
  async deleteIntegration(id: string) {
    await apiClient.delete(`/admin/integrations/${id}`)
  },
}
