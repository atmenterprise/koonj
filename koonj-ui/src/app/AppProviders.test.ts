import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { ApiPageState } from '../shared/ui/ApiPageState'
import { createQueryClient } from './AppProviders'

describe('workspace request notifications', () => {
  it('sends permission errors to notifications and leaves retry controls in the workspace', async () => {
    const notify = vi.fn()
    const client = createQueryClient(notify)
    const error = new ApiError(403, 'Insufficient permissions for this action', '')
    await expect(client.fetchQuery({
      queryKey: ['catalog'], meta: { errorTitle: 'Could not load catalog files' },
      queryFn: () => Promise.reject(error),
    })).rejects.toBe(error)

    expect(notify).toHaveBeenCalledExactlyOnceWith({
      tone: 'error', title: 'Could not load catalog files', detail: error.message,
    })
    const workspace = renderToStaticMarkup(createElement(ApiPageState, {
      loadingLabel: 'Loading catalog', error, retry: vi.fn(), retrying: false,
    }))
    expect(workspace).toContain('Try again')
    expect(workspace).not.toContain(error.message)
  })

  it('reports identical background failures once until the query recovers', async () => {
    const notify = vi.fn()
    const client = createQueryClient(notify)
    const queryKey = ['activity']
    const fetch = () => client.fetchQuery({
      queryKey, retry: false,
      queryFn: () => Promise.reject(new ApiError(503, 'Server is unavailable', '')),
    })

    await expect(fetch()).rejects.toThrow('Server is unavailable')
    await expect(fetch()).rejects.toThrow('Server is unavailable')
    expect(notify).toHaveBeenCalledTimes(1)

    await client.fetchQuery({ queryKey, queryFn: async () => [], staleTime: 0 })
    await client.invalidateQueries({ queryKey })
    await expect(fetch()).rejects.toThrow('Server is unavailable')
    expect(notify).toHaveBeenCalledTimes(2)
  })
})
