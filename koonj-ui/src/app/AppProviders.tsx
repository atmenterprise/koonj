import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { errorDetail, type ActivityMessage } from '../shared/notifications/activity'
import { ApiError } from '../api/client'

export function createQueryClient(notify: (message: ActivityMessage) => void) {
  const unresolved = new WeakMap<object, string | undefined>()
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        const detail = errorDetail(error)
        if (unresolved.has(query) && unresolved.get(query) === detail) return
        unresolved.set(query, detail)
        const title = typeof query.meta?.errorTitle === 'string' ? query.meta.errorTitle : 'Could not load workspace data'
        notify({ tone: 'error', title, detail })
      },
      onSuccess: (_data, query) => { unresolved.delete(query) },
    }),
    defaultOptions: { queries: {
      staleTime: 10_000,
      retry: (attempt, error) => attempt < 1 && (!(error instanceof ApiError) || error.status === null || error.status >= 500),
    } },
  })
}

export function AppProviders({ children }: { children: ReactNode }) {
  const { notify } = useActivityNotifications()
  const [queryClient] = useState(() => createQueryClient(notify))
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
