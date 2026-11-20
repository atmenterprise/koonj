import { mutationOptions, useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useActivityNotifications } from '../../shared/notifications/ActivityNotifications'
import { catalogService } from '../services/catalogService'
import { connectionService } from '../services/connectionService'
import { transferService } from '../services/transferService'
import type { CreateConnectionRequest, TransferJobDTO } from '../types'
import { jobHistoryKey, queryKeys } from './queries'

async function invalidateJobAndCatalog(client: QueryClient, accepted: TransferJobDTO) {
  client.setQueryData<TransferJobDTO[]>(jobHistoryKey, previous =>
    [accepted, ...(previous ?? []).filter(job => job.id !== accepted.id)])

  await Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.files }),
    client.invalidateQueries({ queryKey: queryKeys.jobs }),
  ])
}

export const archiveMutationOptions = (client: QueryClient, submit = transferService.archiveFiles) => mutationOptions({
  mutationFn: (payload: Parameters<typeof transferService.archiveFiles>[0]) => submit(payload),
  onSuccess: job => invalidateJobAndCatalog(client, job),
})

export const recallMutationOptions = (client: QueryClient, submit = transferService.recallFiles) => mutationOptions({
  mutationFn: (payload: Parameters<typeof transferService.recallFiles>[0]) => submit(payload),
  onSuccess: job => invalidateJobAndCatalog(client, job),
})

export function useArchiveMutation() {
  const { runJob } = useActivityNotifications()
  return useMutation(archiveMutationOptions(useQueryClient(), payload => runJob('Archive', () => transferService.archiveFiles(payload))))
}

export function useRecallMutation() {
  const { runJob } = useActivityNotifications()
  return useMutation(recallMutationOptions(useQueryClient(), payload => runJob('Recall', () => transferService.recallFiles(payload))))
}

export function useScanMutation() {
  const client = useQueryClient()
  const { runJob } = useActivityNotifications()
  return useMutation({ mutationFn: (id: string) => runJob('Scan', () => catalogService.triggerScan(id)),
    onSuccess: job => invalidateJobAndCatalog(client, job) })
}

export function useSaveConnection() {
  const client = useQueryClient()
  const { run } = useActivityNotifications()
  return useMutation({ mutationFn: ({ id, payload }: { id?: string; payload: CreateConnectionRequest }) =>
    run({
      started: id ? 'Updating connection' : 'Adding connection',
      failed: 'Could not save connection',
      complete: connection => ({ tone: 'success', title: id ? 'Connection updated' : 'Connection added', detail: connection.name }),
    }, () => id ? connectionService.updateConnection(id, payload) : connectionService.createConnection(payload)),
    onSuccess: () => invalidateConnections(client) })
}

function invalidateConnections(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.connections }),
    client.invalidateQueries({ queryKey: queryKeys.catalogLocations }),
  ])
}

export function useDeleteConnection() {
  const client = useQueryClient()
  const { run } = useActivityNotifications()
  return useMutation({ mutationFn: (id: string) => run({
    started: 'Deleting connection',
    failed: 'Could not delete connection',
    complete: () => ({ tone: 'success', title: 'Connection deleted' }),
  }, () => connectionService.deleteConnection(id)),
    onSuccess: () => invalidateConnections(client) })
}

export function useTestConnection() {
  const { run } = useActivityNotifications()
  return useMutation({ mutationFn: (id: string) => run({
    started: 'Testing connection',
    failed: 'Connection test failed',
    complete: health => ({ tone: health.healthy ? 'success' : 'error',
      title: health.healthy ? 'Connection test passed' : 'Connection test failed', detail: health.message }),
  }, () => connectionService.testConnection(id)) })
}
