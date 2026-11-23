import { queryOptions, useQuery } from '@tanstack/react-query'
import { catalogTablePage, isActiveJob } from '../mappers'
import { catalogService } from '../services/catalogService'
import { connectionService } from '../services/connectionService'
import { transferService } from '../services/transferService'
import { managementService } from '../services/managementService'
import type { CatalogParams, PageResponse, TransferJobDTO } from '../types'

export const queryKeys = {
  connections: ['connections'] as const,
  catalogLocations: ['catalog', 'locations'] as const,
  activeArchives: ['catalog', 'active-archives'] as const,
  files: ['catalog', 'files'] as const,
  jobs: ['transfers', 'jobs'] as const,
  temperaturePolicy: ['management', 'temperature-policy'] as const,
}

export function useConnections() {
  return useQuery({ queryKey: queryKeys.connections, meta: { errorTitle: 'Could not load storage connections' },
    queryFn: ({ signal }) => connectionService.getConnections(signal) })
}

export function useStorageLocations() {
  return useQuery({
    queryKey: ['connections', 'status-locations'],
    queryFn: ({ signal }) => connectionService.getStatusLocations(signal),
    meta: { errorTitle: 'Could not load storage locations' },
  })
}

export function useTemperaturePolicy() {
  return useQuery({ queryKey: queryKeys.temperaturePolicy,
    queryFn: ({ signal }) => managementService.getTemperaturePolicy(signal),
    meta: { errorTitle: 'Could not load the temperature policy' } })
}

export function connectionStatusOptions(id: string, active: boolean) {
  return queryOptions({
    queryKey: ['connection-status', id], enabled: active,
    queryFn: ({ signal }) => connectionService.getStatus(id, signal),
    refetchOnMount: 'always', refetchOnWindowFocus: false, retry: false,
    meta: { inlineError: true },
  })
}

export function useStorageUsage() {
  return useQuery({
    queryKey: [...queryKeys.files, 'storage-usage'],
    queryFn: ({ signal }) => connectionService.getUsage(signal),
    meta: { errorTitle: 'Could not load storage usage' },
  })
}

export function useCatalogLocations(enabled = true) {
  return useQuery({ queryKey: queryKeys.catalogLocations, enabled, meta: { errorTitle: 'Could not load catalog locations' },
    queryFn: ({ signal }) => catalogService.getLocations(signal) })
}

export function useActiveArchives(connectionId?: string) {
  return useQuery({ queryKey: [...queryKeys.activeArchives, connectionId], enabled: !!connectionId,
    queryFn: ({ signal }) => catalogService.getActiveArchives(connectionId!, signal), refetchInterval: 2000,
    meta: { errorTitle: 'Could not load active archives' } })
}

export function useCatalogFiles(params: CatalogParams, enabled = true) {
  return useQuery({ queryKey: [...queryKeys.files, params], enabled,
    meta: { errorTitle: 'Could not load catalog files' },
    queryFn: ({ signal }) => catalogService.getFiles(params, signal), select: catalogTablePage })
}

export function useCatalogFileAttributes(fileId?: string) {
  return useQuery({ queryKey: [...queryKeys.files, 'attributes', fileId], enabled: !!fileId, retry: false,
    meta: { errorTitle: 'Could not load file attributes from storage' },
    queryFn: ({ signal }) => catalogService.getFileAttributes(fileId!, signal) })
}

async function collectPages<T extends { id: string }>(read: (page: number) => Promise<PageResponse<T>>) {
  const first = await read(0)
  const records = [...first.content]
  for (let page = 1; page < first.totalPages; page++) records.push(...(await read(page)).content)
  return [...new Map(records.map(record => [record.id, record])).values()]
}

export function useCatalogSnapshot(connectionId?: string, enabled = true) {
  return useQuery({ queryKey: [...queryKeys.files, 'snapshot', connectionId], enabled, staleTime: 30_000,
    meta: { errorTitle: 'Could not load catalog files' },
    queryFn: ({ signal }) => collectPages(page => catalogService.getFiles({ connectionId, page, size: 200 }, signal)) })
}

export function jobPollingInterval(jobs: TransferJobDTO[] | undefined) {
  return jobs?.some(isActiveJob) ? 3000 : false
}

export const jobHistoryKey = [...queryKeys.jobs, 'history'] as const

export const transferJobsOptions = queryOptions({
  queryKey: jobHistoryKey,
  meta: { errorTitle: 'Could not load activity' },
  queryFn: async ({ signal, client, queryKey }) => {
    const jobs = await collectPages(page => transferService.getJobs({ page, size: 200 }, signal))
    const previous = client.getQueryData<TransferJobDTO[]>(queryKey)
    if (previous && !signal.aborted) {
      const active = new Set(previous.filter(isActiveJob).map(job => job.id))
      if (jobs.some(job => !isActiveJob(job) && active.has(job.id))) {
        void client.invalidateQueries({ queryKey: queryKeys.files })
      }
    }
    return jobs
  },
  refetchInterval: query => jobPollingInterval(query.state.data),
  refetchIntervalInBackground: true,
})

export function useTransferJobs(enabled = true, watchForNewJobs = false) {
  return useQuery({ ...transferJobsOptions, enabled,
    refetchInterval: query => jobPollingInterval(query.state.data) || (watchForNewJobs ? 15_000 : false) })
}
