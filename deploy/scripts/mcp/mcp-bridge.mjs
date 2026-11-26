#!/usr/bin/env node

import { once } from 'node:events'
import { createInterface } from 'node:readline'
import { parseArgs } from 'node:util'

const MAX_REQUEST_BYTES = 64 * 1024
const MAX_EVENT_LENGTH = 8 * 1024 * 1024

async function* events(body) {
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = ''
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true })
    if (buffer.length > MAX_EVENT_LENGTH) throw new Error('An MCP event exceeded the bridge size limit.')
    for (;;) {
      const boundary = /\r?\n\r?\n/.exec(buffer)
      if (!boundary) break
      const lines = buffer.slice(0, boundary.index).split(/\r?\n/)
      buffer = buffer.slice(boundary.index + boundary[0].length)
      const type = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
      const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, ''))
      if (data.length) yield { type, data: data.join('\n') }
    }
  }
}

async function write(message) {
  if (!process.stdout.write(JSON.stringify(message) + '\n')) await once(process.stdout, 'drain')
}

async function run() {
  const { values } = parseArgs({ options: {
    url: { type: 'string' }, token: { type: 'string' }, project: { type: 'string' }, help: { type: 'boolean' },
  } })
  if (values.help) {
    process.stderr.write('Usage: node mcp-bridge.mjs --url <Koonj SSE URL> [--token <PAT>] [--project <UUID>]\n')
    return
  }

  const token = values.token ?? process.env.KOONJ_TOKEN
  const project = values.project ?? process.env.KOONJ_PROJECT
  if (!token || !/^kj_live_[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('Supply a Koonj PAT with --token or KOONJ_TOKEN.')
  let url
  try {
    url = new URL(values.url ?? process.env.KOONJ_MCP_URL)
  } catch {
    throw new Error('Supply the Koonj SSE URL with --url or KOONJ_MCP_URL.')
  }
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash
      || !url.pathname.endsWith('/api/v1/mcp/sse')) throw new Error('Expected an HTTP(S) URL ending in /api/v1/mcp/sse.')
  if (project && !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(project)) {
    throw new Error('Expected a project UUID.')
  }

  const headers = { Authorization: 'Bearer ' + token }
  if (project) headers['X-Koonj-Project'] = project
  const abort = new AbortController()
  const input = createInterface({ input: process.stdin, crlfDelay: Infinity, terminal: false })
  // The client may write initialize before the remote stream is open.
  const lines = input[Symbol.asyncIterator]()
  const stop = () => abort.abort()
  process.once('SIGINT', stop)
  process.once('SIGTERM', stop)
  let startup = setTimeout(stop, 15_000)

  try {
    const response = await fetch(url, {
      headers: { ...headers, Accept: 'text/event-stream' }, signal: abort.signal, redirect: 'error',
    })
    if (!response.ok || !response.body || !response.headers.get('content-type')?.startsWith('text/event-stream')) {
      throw new Error(`Koonj SSE connection failed (HTTP ${response.status}).`)
    }
    const stream = events(response.body)
    const first = await stream.next()
    if (first.done || first.value.type !== 'endpoint') throw new Error('Koonj did not send an MCP message endpoint.')
    let endpoint
    try {
      endpoint = new URL(first.value.data, url)
    } catch {
      throw new Error('Koonj sent an invalid MCP message endpoint.')
    }
    if (endpoint.origin !== url.origin || endpoint.username || endpoint.password || endpoint.hash
        || endpoint.pathname !== url.pathname.replace(/\/sse$/, '/message')
        || !endpoint.searchParams.has('sessionId')) {
      throw new Error('Koonj sent an unexpected MCP message endpoint.')
    }
    clearTimeout(startup)
    startup = undefined

    const pending = new Set()
    let inputEnded = false
    let finish
    const drained = new Promise(resolve => { finish = resolve })
    const resolveIfFinished = () => { if (inputEnded && !pending.size) finish() }

    const forwardOutput = async () => {
      for await (const event of stream) {
        if (event.type !== 'message') continue
        let message
        try {
          message = JSON.parse(event.data)
        } catch {
          throw new Error('Koonj sent invalid JSON-RPC data.')
        }
        await write(message)
        for (const item of Array.isArray(message) ? message : [message]) pending.delete(item?.id)
        resolveIfFinished()
      }
      if (!abort.signal.aborted) throw new Error('Koonj closed the MCP stream. Restart the client connection.')
    }

    const forwardInput = async () => {
      for await (const line of lines) {
        if (!line.trim()) continue
        let message
        try {
          message = JSON.parse(line)
        } catch {
          await write({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Invalid JSON.' } })
          continue
        }
        const ids = (Array.isArray(message) ? message : [message])
          .map(item => item?.id).filter(id => typeof id === 'string' || Number.isInteger(id))
        for (const id of ids) pending.add(id)
        let failure
        if (Buffer.byteLength(line) > MAX_REQUEST_BYTES) {
          failure = 'MCP request exceeds 64 KiB.'
        } else {
          const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(20_000)])
          const accepted = await fetch(endpoint, {
            method: 'POST', body: line, headers: { ...headers, 'Content-Type': 'application/json' }, signal, redirect: 'error',
          })
          await accepted.body?.cancel()
          if (accepted.status !== 202) failure = `Koonj rejected the MCP message (HTTP ${accepted.status}).`
        }
        if (failure) {
          if (!ids.length) process.stderr.write(failure + '\n')
          for (const id of ids) {
            pending.delete(id)
            await write({ jsonrpc: '2.0', id, error: { code: -32000, message: failure } })
          }
        }
      }
      inputEnded = true
      resolveIfFinished()
      const timeout = setTimeout(finish, 70_000)
      try {
        await drained
      } finally {
        clearTimeout(timeout)
      }
    }

    await Promise.race([forwardOutput(), forwardInput()])
  } catch (failure) {
    if (abort.signal.aborted && !startup) return
    if (abort.signal.aborted) throw new Error('Timed out opening the Koonj MCP stream.')
    throw failure
  } finally {
    clearTimeout(startup)
    abort.abort()
    input.close()
    process.stdin.pause()
    process.removeListener('SIGINT', stop)
    process.removeListener('SIGTERM', stop)
  }
}

run().catch(failure => {
  // Restrict diagnostics to our messages and fixed fetch errors. Configuration can include a secret.
  const message = failure instanceof TypeError ? 'Could not connect to Koonj or parse bridge options.' : failure.message
  process.stderr.write('Koonj MCP bridge: ' + message + '\n')
  process.exitCode = 1
})
