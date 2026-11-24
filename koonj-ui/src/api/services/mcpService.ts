import { apiClient, projectHeader } from '../client'
import type { Permission } from './permissionService'

export interface PersonalAccessToken {
  id: string
  name: string
  tokenPrefix: string
  createdAt: string
  lastUsedAt?: string | null
  expiresAt?: string | null
}

export interface CreatedToken {
  metadata: PersonalAccessToken
  token: string
}

export interface ToolAvailability {
  name: string
  description: string
  permission: Permission
  available: boolean
}

export function mcpUrl() {
  const base = (apiClient.defaults.baseURL ?? '/api/v1').replace(/\/+$/, '')
  return new URL(base + '/mcp/sse', window.location.origin)
}

export const mcpService = {
  async tokens(signal?: AbortSignal) {
    return (await apiClient.get<PersonalAccessToken[]>('/account/tokens', { signal })).data
  },
  async create(name: string, expiresAt?: string) {
    return (await apiClient.post<CreatedToken>('/account/tokens', { name, expiresAt })).data
  },
  async revoke(id: string) {
    await apiClient.delete('/account/tokens/' + id)
  },
  async permissions(signal?: AbortSignal) {
    return (await apiClient.get<ToolAvailability[]>('/account/mcp-tools', { signal })).data
  },
  async reachable(signal: AbortSignal) {
    const response = await fetch(mcpUrl(), {
      headers: { Accept: 'text/event-stream' }, signal: AbortSignal.any([signal, AbortSignal.timeout(8000)]),
      credentials: 'omit', redirect: 'error', cache: 'no-store',
    })
    await response.body?.cancel()
    return response.status === 401
  },
}

interface RpcResponse {
  id?: number
  result?: { protocolVersion?: string; tools?: { name: string }[] }
  error?: { message: string }
}

export async function testMcpConnection(token: string, projectId: string, abort: AbortController) {
  const url = mcpUrl()
  const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(12_000)])
  const headers = { Authorization: 'Bearer ' + token, [projectHeader]: projectId }
  const response = await fetch(url, { headers: { ...headers, Accept: 'text/event-stream' }, signal, redirect: 'error' })
  if (!response.ok || !response.body || !response.headers.get('content-type')?.startsWith('text/event-stream')) {
    await response.body?.cancel()
    throw new Error(`Could not open the MCP event stream (HTTP ${response.status}).`)
  }

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  async function next(expected: string): Promise<string> {
    for (;;) {
      const end = /\r?\n\r?\n/.exec(buffer)
      if (end) {
        const frame = buffer.slice(0, end.index).split(/\r?\n/)
        buffer = buffer.slice(end.index + end[0].length)
        const event = frame.find(line => line.startsWith('event:'))?.slice(6).trim()
        if (event === expected) return frame.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
        continue
      }
      const chunk = await reader.read()
      if (chunk.done) throw new Error('The MCP event stream closed before the test completed.')
      buffer += chunk.value
    }
  }

  try {
    const endpoint = new URL(await next('endpoint'), url)
    const expectedPath = url.pathname.replace(/\/sse$/, '/message')
    if (endpoint.origin !== url.origin || endpoint.pathname !== expectedPath || !endpoint.searchParams.has('sessionId')) {
      throw new Error('The server returned an unexpected MCP message endpoint.')
    }

    async function post(method: string, id?: number, params?: object) {
      const accepted = await fetch(endpoint, {
        method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, signal, redirect: 'error',
        body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
      })
      await accepted.body?.cancel()
      if (accepted.status !== 202) throw new Error(`MCP rejected ${method} (HTTP ${accepted.status}).`)
      if (id === undefined) return
      const result = JSON.parse(await next('message')) as RpcResponse
      if (result.id !== id || !result.result) throw new Error(result.error?.message ?? 'Unexpected MCP response.')
      return result.result
    }

    await post('initialize', 1, { protocolVersion: '2024-11-05', capabilities: {},
      clientInfo: { name: 'Koonj UI', version: '1.0' } })
    await post('notifications/initialized')
    const result = await post('tools/list', 2)
    if (!Array.isArray(result?.tools)) throw new Error('The server did not return an MCP tool list.')
    return new Set(result.tools.map(tool => tool.name))
  } finally {
    abort.abort()
    reader.releaseLock()
  }
}
