import { apiClient } from '../client'

export interface Project {
  id: string
  role: "ADMIN" | "MEMBER"
  defaultProjectId: string
  name: string
}

export interface ProjectAccount {
  subject: string
  role: Project["role"]
  personalOwner: boolean
  displayName: string
  email: string | null
}

export const projectService = {
  session: async (signal?: AbortSignal) => (await apiClient.post<Project[]>('/projects/session', undefined, { signal })).data,
  list: async (signal?: AbortSignal) => (await apiClient.get<Project[]>('/projects', { signal })).data,
  create: async (name: string) => (await apiClient.post<Project>('/projects', { name })).data,
  members: async (id: string, signal?: AbortSignal) =>
    (await apiClient.get<ProjectAccount[]>(`/projects/${id}/members`, { signal })).data,
  emailSettings: async (id: string, signal?: AbortSignal) =>
    (await apiClient.get<{ selected: string | null; channels: { id: string; name: string }[] }>(
      `/projects/${id}/invitation-channel`, { signal })).data,
  selectChannel: (id: string, channelId: string) => apiClient.put(`/projects/${id}/invitation-channel`, { channelId }),
  invitations: async (id: string, signal?: AbortSignal) =>
    (await apiClient.get<{ id: string; email: string; expiresAt: string; delivery: string }[]>(`/projects/${id}/invitations`, { signal })).data,
  invite: async (id: string, email: string) => apiClient.post(`/projects/${id}/invitations`, { email }),
  resend: async (id: string, invite: string) => apiClient.post(`/projects/${id}/invitations/${invite}/resend`),
  revoke: async (id: string, invite: string) => apiClient.delete(`/projects/${id}/invitations/${invite}`),
  accept: async (token: string) => (await apiClient.post<string>('/projects/invitations/accept', { token })).data,
  remove: async (id: string, subject: string) => { await apiClient.delete(`/projects/${id}/members`, { params: { subject } }) },
}
