import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { catalogService } from '../../api/services/catalogService'
import type { CatalogFavoriteTarget } from '../../api/types'
import { useAuth } from '../../auth/AuthProvider'
import { useActivityNotifications } from '../../shared/notifications/ActivityNotifications'
import { errorDetail } from '../../shared/notifications/activity'

const normalizedPath = (target: CatalogFavoriteTarget) => target.path.replace(/\/+$/, '')

export function favoriteKey(target: CatalogFavoriteTarget) {
  return `${target.connectionId}:${target.scope}:${normalizedPath(target)}`
}

export function favoriteHref(target: CatalogFavoriteTarget) {
  const path = normalizedPath(target) + (target.scope === 'FOLDER' ? '/' : '')
  return '/explorer?' + new URLSearchParams({ endpoint: target.connectionId, path })
}

export function useCatalogFavorites() {
  const { user } = useAuth()
  const client = useQueryClient()
  const { notify } = useActivityNotifications()
  const queryKey = ['catalog', 'favorites', user?.id]
  const favorites = useQuery({ queryKey, enabled: !!user?.id,
    meta: { errorTitle: 'Could not load favorites' },
    queryFn: ({ signal }) => catalogService.getFavorites(signal) })
  const change = useMutation({
    mutationFn: catalogService.setFavorite,
    onError: (error, request) => notify({ tone: 'error', title: request.favorite ? 'Could not add favorite' : 'Could not remove favorite',
      detail: errorDetail(error) }),
    onSuccess: () => client.invalidateQueries({ queryKey }),
  })
  return { favorites, change }
}
