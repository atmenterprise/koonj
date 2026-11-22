import { useState } from 'react'
import { FavoriteButton } from '../catalog/FavoriteButton'
import { favoriteHref, favoriteKey, useCatalogFavorites } from '../catalog/catalogFavorites'
import type { Navigate } from '../../shared/ui/feedback'
import { Button, Icon, count } from '../../shared/ui/primitives'

const pageSize = 8

export function QuickAccess({ navigate }: { navigate: Navigate }) {
  const { favorites, change } = useCatalogFavorites()
  const [requestedPage, setPage] = useState(0)
  const saved = favorites.data ?? []
  const ordered = [...saved.filter(item => item.scope === 'FOLDER'), ...saved.filter(item => item.scope === 'FILE')]
  const totalPages = Math.max(1, Math.ceil(ordered.length / pageSize))
  const page = Math.min(requestedPage, totalPages - 1)
  const start = page * pageSize
  const entries = ordered.slice(start, start + pageSize)

  return <section className="panel quick-access" aria-labelledby="quick-access-heading">
    <div className="panel-heading"><h2 id="quick-access-heading">Quick access</h2></div>
    {favorites.isError && <div className="quick-access-status">
      <p>Favorites could not be loaded.</p>
      <Button icon="retry" disabled={favorites.isFetching} onClick={() => void favorites.refetch()}>Retry</Button>
    </div>}
    {favorites.isPending ? <p className="quick-access-status" role="status">Loading favorites…</p> : <>
      {!favorites.isError && !entries.length && <p className="quick-access-status">Star a folder or file in Catalog to pin it here.</p>}
      {entries.length > 0 && <ul className="quick-access-list">{entries.map(item => {
        const name = item.path.split('/').at(-1) || item.path
        const href = favoriteHref(item)
        const detail = item.connectionName + ' · ' + item.path
        return <li key={favoriteKey(item)}>
          <a className="quick-access-link" href={href} title={detail} onClick={event => {
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return
            event.preventDefault()
            navigate(href)
          }}>
            <span className={`icon-tile ${item.scope === 'FOLDER' ? 'yellow' : 'neutral'}`}><Icon name={item.scope === 'FOLDER' ? 'folder' : 'file'} size={17} /></span>
            <span className="quick-access-label"><strong>{name}</strong><small>{detail}</small></span>
            <Icon name="chevron" size={14} />
          </a>
          <FavoriteButton name={name} favorited disabled={change.isPending}
            onClick={() => change.mutate({ connectionId: item.connectionId, scope: item.scope, path: item.path, favorite: false })} />
        </li>
      })}</ul>}
      {totalPages > 1 && <div className="quick-access-pagination">
        <span aria-live="polite">{count(start + 1)}–{count(start + entries.length)} of {count(ordered.length)} favorites</span>
        <Button disabled={!page} onClick={() => setPage(page - 1)}>Previous</Button>
        <Button disabled={page + 1 === totalPages} onClick={() => setPage(page + 1)}>Next</Button>
      </div>}
    </>}
  </section>
}
