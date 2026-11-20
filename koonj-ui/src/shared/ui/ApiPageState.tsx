import { ApiError } from '../../api/client'
import { Button, Icon } from './primitives'

type ApiPageStateProps = {
  loadingLabel: string
} & ({
  error: Error
  retry: () => void
  retrying: boolean
  reset?: () => void
} | {
  error?: undefined
})

export function ApiPageState(props: ApiPageStateProps) {
  const error = props.error
  const unavailable = error instanceof ApiError && (error.status === null || [502, 503, 504].includes(error.status))
  return <section className="api-page-state" aria-labelledby="api-page-title">
    <div className="panel api-page-card">
      <div role={error ? 'alert' : 'status'}>
        <span className={error ? 'icon-tile yellow' : 'icon-tile blue'}>
          <Icon name={error ? 'warning' : 'server'} size={24} />
        </span>
        <h1 id="api-page-title">{error
          ? unavailable ? 'Koonj Server is unavailable' : 'Couldn’t load this page'
          : props.loadingLabel}</h1>
        {error ? <p>{error.message}</p> : <div className="api-page-progress" aria-hidden="true" />}
      </div>
      {props.error && <div className="row-actions api-page-actions">
        <Button variant="primary" icon="retry" disabled={props.retrying} onClick={props.retry}>
          {props.retrying ? 'Retrying…' : 'Try again'}
        </Button>
        {props.reset && <Button onClick={props.reset}>Reset filters</Button>}
      </div>}
    </div>
  </section>
}
