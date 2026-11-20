import { apiClient } from '../client'
import type { ServerTelemetryDTO } from '../types'

export const adminService = {
  async getTelemetry(signal?: AbortSignal) {
    return (await apiClient.get<ServerTelemetryDTO>('/admin/telemetry', { signal })).data
  },
}
