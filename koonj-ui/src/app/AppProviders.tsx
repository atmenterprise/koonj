import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { ActivityNotifications } from '../shared/notifications/ActivityNotifications'
import { ApiError } from '../api/client'

const createQueryClient = () => new QueryClient({ defaultOptions: { queries: {
  staleTime: 10_000,
  retry: (attempt, error) => attempt < 1 && (!(error instanceof ApiError) || error.status === null || error.status >= 500),
} } })

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient)
  return <QueryClientProvider client={queryClient}><ActivityNotifications>{children}</ActivityNotifications></QueryClientProvider>
}
