import { afterEach, expect, it, vi } from 'vitest'
import { testMcpConnection } from './mcpService'

vi.mock('../../auth/keycloak', () => ({ accessToken: vi.fn() }))

const project = '00000000-0000-0000-0000-000000000001'
const token = 'kj_live_' + 'x'.repeat(43)
const origin = 'https://koonj.example'
const messagePath = '/api/v1/mcp/message?sessionId=123'

afterEach(() => vi.unstubAllGlobals())

function browser() {
  vi.stubGlobal('window', { location: { origin } })
}

it('connects with project-bound headers and lists tools after the MCP handshake', async () => {
  browser()
  let stream!: ReadableStreamDefaultController<Uint8Array>
  const data = new ReadableStream<Uint8Array>({ start: controller => { stream = controller } })
  const encoder = new TextEncoder()
  const emit = (part: string) => stream.enqueue(encoder.encode(part))
  const methods: string[] = []
  const fetcher = vi.fn<typeof fetch>(async (url, options) => {
    expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer ' + token)
    expect(new Headers(options?.headers).get('X-Koonj-Project')).toBe(project)
    if (options?.method !== 'POST') {
      expect(String(url)).toBe(origin + '/api/v1/mcp/sse')
      emit('event: endpoint\r\n')
      emit('data: ' + messagePath + '\r\n\r\n')
      return new Response(data, { headers: { 'Content-Type': 'text/event-stream' } })
    }

    expect(String(url)).toBe(origin + messagePath)
    const rpc = JSON.parse(String(options.body))
    methods.push(rpc.method)
    if (rpc.id) {
      const result = rpc.method === 'initialize'
        ? { protocolVersion: '2024-11-05' }
        : { tools: [{ name: 'search_catalog_files' }] }
      emit(':keep-alive\n\nevent: message\ndata:' + JSON.stringify({ id: rpc.id, result }) + '\n\n')
    }
    return new Response(null, { status: 202 })
  })
  vi.stubGlobal('fetch', fetcher)

  await expect(testMcpConnection(token, project, new AbortController())).resolves.toEqual(new Set(['search_catalog_files']))
  expect(methods).toEqual(['initialize', 'notifications/initialized', 'tools/list'])
})

it('rejects an SSE message endpoint on a different origin before sending a PAT to it', async () => {
  browser()
  const fetcher = vi.fn(async () => new Response('event:endpoint\ndata:https://other.example' + messagePath + '\n\n',
    { headers: { 'Content-Type': 'text/event-stream' } }))
  vi.stubGlobal('fetch', fetcher)

  await expect(testMcpConnection(token, project, new AbortController())).rejects.toThrow('unexpected MCP message endpoint')
  expect(fetcher).toHaveBeenCalledTimes(1)
})
