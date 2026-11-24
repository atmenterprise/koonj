import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { projectHeader } from '../../api/client'
import { mcpService, mcpUrl, testMcpConnection, type CreatedToken, type PersonalAccessToken } from '../../api/services/mcpService'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import { Dialog } from '../../shared/ui/feedback'
import { Badge, Button, date, time } from '../../shared/ui/primitives'

const clients = [
  { id: 'claude', label: 'Claude Desktop' },
  { id: 'cursor', label: 'Cursor' },
  { id: 'windsurf', label: 'Windsurf' },
  { id: 'generic', label: 'Generic MCP / LangChain' },
] as const
type Client = typeof clients[number]['id']

const instructions: Record<Client, string> = {
  claude: 'In claude_desktop_config.json, set the absolute paths to Node and the Koonj mcp-bridge.mjs script, then restart Claude Desktop.',
  cursor: 'Save in ~/.cursor/mcp.json or a private .cursor/mcp.json file.',
  windsurf: 'Save in ~/.codeium/windsurf/mcp_config.json, then refresh MCP servers in Windsurf.',
  generic: 'Leave the first curl command running. Reuse the exports in a second terminal, set KOONJ_MCP_MESSAGE_URL from the endpoint event, then send the JSON-RPC requests.'
}

function configuration(client: Client, projectId: string, bridge: string, node: string, secret?: string) {
  const url = mcpUrl().href
  const token = secret ?? 'YOUR_KOONJ_TOKEN'
  const headers = { Authorization: 'Bearer ' + token, [projectHeader]: projectId }
  const shell = (value: string) => "'" + value.replaceAll("'", "'\"'\"'") + "'"
  if (client === 'generic') {
    const messageUrl = new URL(url)
    messageUrl.pathname = messageUrl.pathname.replace(/\/sse$/, '/message')
    const rpc = (method: string, id?: number, params?: object) =>
      'mcp_post ' + shell(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
    return [
      'export KOONJ_MCP_URL=' + shell(url),
      'export KOONJ_PROJECT=' + shell(projectId),
      'export KOONJ_TOKEN=' + shell(token),
      '',
      'curl -N -H "Accept: text/event-stream" \\',
      '  -H "Authorization: Bearer $KOONJ_TOKEN" \\',
      '  -H "X-Koonj-Project: $KOONJ_PROJECT" "$KOONJ_MCP_URL"',
      '',
      'export KOONJ_MCP_MESSAGE_URL=' + shell(messageUrl.href + '?sessionId=SESSION_ID_FROM_ENDPOINT_EVENT'),
      'mcp_post() {',
      '  curl -fsS "$KOONJ_MCP_MESSAGE_URL" -H "Authorization: Bearer $KOONJ_TOKEN" \\',
      '    -H "X-Koonj-Project: $KOONJ_PROJECT" -H "Content-Type: application/json" -d "$1"',
      '}',
      rpc('initialize', 1, { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'curl', version: '1' } }),
      '# Wait for the initialize response on the SSE stream.',
      rpc('notifications/initialized'),
      rpc('tools/list', 2),
    ].join('\n')
  }
  const entry = client === 'claude' ? {
    command: node || '/absolute/path/to/node',
    args: [bridge || '/absolute/path/to/mcp-bridge.mjs', '--url', url, '--project', projectId],
    env: { KOONJ_TOKEN: token },
  } : client === 'windsurf' ? { serverUrl: url, headers } : { url, headers }
  return JSON.stringify({ mcpServers: { koonj: entry } }, null, 2)
}

function timestamp(value?: string | null, empty = 'Never') {
  return value ? <time dateTime={value}>{date(value)} · {time(value)}</time> : empty
}

export function McpSettings({ projectId }: { projectId: string }) {
  const client = useQueryClient()
  const tokenKey = ['account', projectId, 'tokens']
  const tokens = useQuery({ queryKey: tokenKey, queryFn: ({ signal }) => mcpService.tokens(signal) })
  const permissions = useQuery({ queryKey: ['account', projectId, 'mcp-tools'], queryFn: ({ signal }) => mcpService.permissions(signal) })
  const reachable = useQuery({ queryKey: ['mcp', 'reachable'], queryFn: ({ signal }) => mcpService.reachable(signal), retry: false })
  const [generating, setGenerating] = useState(false)
  const [pending, setPending] = useState(false)
  const [generationError, setGenerationError] = useState<string>()
  const [generated, setGenerated] = useState<CreatedToken>()
  const [revoking, setRevoking] = useState<PersonalAccessToken>()
  const [selected, setSelected] = useState<Client>('claude')
  const [bridge, setBridge] = useState('')
  const [node, setNode] = useState('')
  const [copyStatus, setCopyStatus] = useState('')
  const [secretCopied, setSecretCopied] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<Set<string>>()
  const [testError, setTestError] = useState('')
  const testAbort = useRef<AbortController | null>(null)
  useEffect(() => () => testAbort.current?.abort(), [])

  function clearGenerated() {
    testAbort.current?.abort()
    testAbort.current = null
    setGenerated(undefined)
    setTesting(false)
    setTestResult(undefined)
    setTestError('')
    setCopyStatus('')
    setSecretCopied(false)
  }

  const revoke = useMutation({
    mutationFn: mcpService.revoke,
    onSuccess: async (_, id) => {
      if (generated?.metadata.id === id) clearGenerated()
      setRevoking(undefined)
      await client.invalidateQueries({ queryKey: tokenKey })
    },
  })

  async function generate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const expires = String(form.get('expiresAt') ?? '')
    setPending(true)
    setGenerationError(undefined)
    try {
      const result = await mcpService.create(String(form.get('name')).trim(), expires ? new Date(expires).toISOString() : undefined)
      clearGenerated()
      setGenerated(result)
      void client.invalidateQueries({ queryKey: tokenKey })
    } catch (failure) {
      setGenerationError(failure instanceof Error ? failure.message : 'Could not generate the token.')
    } finally {
      setPending(false)
    }
  }

  async function copy(value: string, secret = false) {
    try {
      await navigator.clipboard.writeText(value)
      if (secret) setSecretCopied(true)
      setCopyStatus(secret ? 'Token copied.' : 'Configuration copied.')
    } catch {
      setCopyStatus('Clipboard unavailable. Select and copy the text manually.')
    }
  }

  async function liveTest() {
    if (!generated) {
      void reachable.refetch()
      return
    }
    const abort = new AbortController()
    testAbort.current = abort
    setTesting(true)
    setTestResult(undefined)
    setTestError('')
    try {
      const names = await testMcpConnection(generated.token, projectId, abort)
      if (testAbort.current === abort) setTestResult(names)
      void client.invalidateQueries({ queryKey: tokenKey })
    } catch (failure) {
      if (testAbort.current === abort) setTestError(failure instanceof Error ? failure.message : 'MCP connection failed.')
    } finally {
      if (testAbort.current === abort) {
        testAbort.current = null
        setTesting(false)
      }
    }
  }

  const reachableStatus = testing || reachable.isFetching ? 'Checking…' : testError ? 'Connection failed' : testResult ? 'Connected'
    : reachable.data ? 'Reachable · PAT required' : 'Unreachable'
  const snippet = configuration(selected, projectId, bridge, node, generated?.token)

  return <div className="admin-stack">
    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>MCP &amp; AI Assistant</h2>
        <p>Connect AI assistants to this project through Koonj’s MCP endpoint.</p></div>
        <Badge tone={testing || reachable.isFetching ? 'neutral' : !testError && (testResult || reachable.data) ? 'green' : 'red'} dot>
          {reachableStatus}
        </Badge>
      </div>
      <div className="mcp-actions">
        <code>{mcpUrl().href}</code>
        <Button icon="bolt" disabled={testing || reachable.isFetching} onClick={() => void liveTest()}>
          {generated ? 'Test connection' : 'Check endpoint'}
        </Button>
      </div>
      {testError && <p className="ops-error" role="alert">{testError}</p>}
      <h3 className="mcp-subheading">{testResult ? 'Tools available to this token' : 'Tool permissions for your account'}</h3>
      {permissions.isError ? <ApiPageState error={permissions.error} loadingLabel="Loading MCP tools"
        retry={() => void permissions.refetch()} retrying={permissions.isFetching} />
        : permissions.isPending ? <p role="status">Loading MCP tools…</p>
        : <ul className="mcp-tools">{permissions.data.map(tool => {
          const available = testResult?.has(tool.name) ?? tool.available
          return <li key={tool.name} title={tool.description}><code>{tool.name}</code>
            <Badge tone={available ? 'green' : 'neutral'}>{available ? 'Available' : tool.permission}</Badge></li>
        })}</ul>}
    </section>

    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Personal Access Tokens</h2>
        <p>Each token is bound to this project and inherits your roles when created.</p></div>
        <Button variant="primary" icon="user" onClick={() => {
          clearGenerated()
          setGenerationError(undefined)
          setGenerating(true)
        }}>Generate New Token</Button>
      </div>
      {tokens.isError ? <ApiPageState error={tokens.error} loadingLabel="Loading personal access tokens"
        retry={() => void tokens.refetch()} retrying={tokens.isFetching} />
        : tokens.isPending ? <p role="status">Loading tokens…</p>
        : tokens.data.length ? <div className="table-scroll mcp-token-table"><table aria-label="Active personal access tokens">
          <thead><tr><th scope="col">Name</th><th scope="col">Created</th><th scope="col">Last used</th>
            <th scope="col">Expiration</th><th scope="col">Action</th></tr></thead>
          <tbody>{tokens.data.map(token => <tr key={token.id}>
            <td><strong>{token.name}</strong><br /><code>{token.tokenPrefix}…</code></td>
            <td>{timestamp(token.createdAt)}</td><td>{timestamp(token.lastUsedAt)}</td><td>{timestamp(token.expiresAt)}</td>
            <td><Button aria-label={`Revoke ${token.name}`} onClick={() => { revoke.reset(); setRevoking(token) }}>Revoke</Button></td>
          </tr>)}</tbody>
        </table></div> : <p className="empty compact">No active tokens in this project.</p>}
    </section>

    <section className="panel admin-group">
      <div className="panel-heading"><div><h2>Client configuration</h2>
        <p>{generated ? 'This configuration contains your new token. Keep the file private.' : 'Generate a token to fill in the configuration for this project.'}</p></div>
        {generated && <Button onClick={clearGenerated}>Clear token from screen</Button>}
      </div>
      <div className="filters" role="group" aria-label="MCP clients">
        {clients.map(option => <button key={option.id} type="button" aria-pressed={selected === option.id}
          onClick={() => { setSelected(option.id); setCopyStatus('') }}>{option.label}</button>)}
      </div>
      <p className="mcp-instructions">{instructions[selected]}</p>
      {selected === 'claude' && <div className="mcp-paths">
        <label>Node executable<input value={node} onChange={event => setNode(event.target.value)} placeholder="/absolute/path/to/node" /></label>
        <label>Bridge script<input value={bridge} onChange={event => setBridge(event.target.value)} placeholder="/absolute/path/to/mcp-bridge.mjs" /></label>
      </div>}
      <pre className="mcp-code"><code>{snippet}</code></pre>
      <div className="mcp-actions">
        <Button icon="files" onClick={() => void copy(snippet)}>Copy configuration</Button>
        <span role="status">{!generating && copyStatus}</span>
      </div>
    </section>

    {generating && <Dialog title={generated ? 'Token generated' : 'Generate New Token'}
      onClose={() => { if (!pending) { setGenerating(false); setCopyStatus('') } }}>
      {generated ? <>
        <div className="dialog-body"><p>Copy this token or a client configuration before leaving this page. Koonj only reveals the secret when it is created.</p>
          <code className="mcp-secret">{generated.token}</code><span role="status">{copyStatus}</span></div>
        <div className="dialog-footer">
          <Button icon="files" disabled={secretCopied} onClick={() => void copy(generated.token, true)}>{secretCopied ? 'Copied' : 'Copy token'}</Button>
          <Button variant="primary" onClick={() => { setGenerating(false); setCopyStatus('') }}>Done</Button>
        </div>
      </> : <form onSubmit={event => void generate(event)}>
        <div className="dialog-body">
          <label>Name<input name="name" maxLength={120} required /></label>
          <label>Expiration (optional, local time)<input name="expiresAt" type="datetime-local" /></label>
          {generationError && <p className="ops-error" role="alert">{generationError}</p>}
        </div>
        <div className="dialog-footer">
          <Button onClick={() => setGenerating(false)} disabled={pending}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={pending}>{pending ? 'Generating…' : 'Generate token'}</Button>
        </div>
      </form>}
    </Dialog>}

    {revoking && <Dialog title="Revoke token" onClose={() => { if (!revoke.isPending) setRevoking(undefined) }}>
      <div className="dialog-body"><p>Revoke {revoking.name}? Clients using this token will lose access.</p>
        {revoke.isError && <p className="ops-error" role="alert">{revoke.error.message}</p>}
      </div>
      <div className="dialog-footer"><Button onClick={() => setRevoking(undefined)} disabled={revoke.isPending}>Cancel</Button>
        <Button variant="danger" onClick={() => revoke.mutate(revoking.id)} disabled={revoke.isPending}>Revoke token</Button>
      </div>
    </Dialog>}
  </div>
}
