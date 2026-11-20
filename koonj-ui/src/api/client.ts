import axios, { type InternalAxiosRequestConfig } from 'axios'
import { accessToken } from '../auth/keycloak'

const defaultBaseURL = 'http://localhost:8080/api/v1'
const configuredBaseURL = import.meta.env.VITE_API_BASE_URL || defaultBaseURL

export const apiClient = axios.create({
  baseURL: import.meta.env.DEV && configuredBaseURL === defaultBaseURL ? '/api/v1' : configuredBaseURL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
})

interface SpringError {
  status?: number
  message?: string
  timestamp?: string
  validationErrors?: Record<string, string>
}

export class ApiError extends Error {
  constructor(
    public readonly status: number | null,
    message: string,
    public readonly timestamp: string,
    public readonly validationErrors: Record<string, string> = {},
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface AuthenticatedRequest extends InternalAxiosRequestConfig {
  retriedAfterUnauthorized?: boolean
}

apiClient.interceptors.request.use(async request => {
  if (request.signal?.aborted) throw new axios.CanceledError()
  const token = await accessToken(30)
  if (request.signal?.aborted) throw new axios.CanceledError()
  if (!token) throw new ApiError(401, 'Sign in to continue.', new Date().toISOString())
  request.headers.set('Authorization', `Bearer ${token}`)
  return request
})

apiClient.interceptors.response.use(undefined, async (error: unknown) => {
  if (axios.isCancel(error) || error instanceof ApiError) throw error
  const failure = axios.isAxiosError<SpringError>(error) ? error : undefined
  const response = failure?.response
  const request: AuthenticatedRequest | undefined = failure?.config
  if (response?.status === 401 && request && !request.retriedAfterUnauthorized && !request.signal?.aborted) {
    if (await accessToken(-1)) {
      request.retriedAfterUnauthorized = true
      return apiClient.request(request)
    }
  }
  const body = response?.data
  const permissionMessage = response?.status === 403 ? 'Insufficient permissions for this action' : undefined
  const message = permissionMessage ?? (typeof body?.message === 'string' && body.message.trim()
    ? body.message
    : response ? `The server returned HTTP ${response.status}.`
      : axios.isAxiosError(error) && ['ECONNABORTED', 'ETIMEDOUT'].includes(error.code ?? '')
        ? 'The request timed out.' : 'Could not reach Koonj Server.')
  return Promise.reject(new ApiError(
    response?.status ?? null,
    message,
    typeof body?.timestamp === 'string' ? body.timestamp : new Date().toISOString(),
    body?.validationErrors && typeof body.validationErrors === 'object' ? body.validationErrors : {},
  ))
})
