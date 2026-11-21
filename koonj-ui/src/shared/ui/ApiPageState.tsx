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
  return <section className="api-page-state" aria-labelledby={error ? undefined : 'api-page-title'}
    aria-label={error ? 'Page recovery' : undefined}>
    <div className="panel api-page-card">
      {!error && <div role="status">
        <span className="icon-tile blue"><Icon name="server" size={24} /></span>
        <h1 id="api-page-title">{props.loadingLabel}</h1>
        <div className="api-page-progress" aria-hidden="true" />
      </div>}
      {props.error && <div className="row-actions api-page-actions">
        <Button variant="primary" icon="retry" disabled={props.retrying} onClick={props.retry}>
          {props.retrying ? 'Retrying…' : 'Try again'}
        </Button>
        {props.reset && <Button onClick={props.reset}>Reset filters</Button>}
      </div>}
    </div>
  </section>
}
