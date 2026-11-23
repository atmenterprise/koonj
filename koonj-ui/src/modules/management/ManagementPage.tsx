import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { queryKeys, useTemperaturePolicy } from '../../api/hooks/queries'
import { managementService, type TemperaturePolicy } from '../../api/services/managementService'
import { ApiPageState } from '../../shared/ui/ApiPageState'
import type { Navigate } from '../../shared/ui/feedback'
import { Badge, Button, Icon, temperatureTone } from '../../shared/ui/primitives'
import { StorageConnectionsPage } from '../storage/StorageConnectionsPage'

function TemperatureEditor({ policy }: { policy: TemperaturePolicy }) {
  const client = useQueryClient()
  const [hotDays, setHotDays] = useState(String(policy.hotDays))
  const [coldDays, setColdDays] = useState(String(policy.coldDays))
  const hot = Number(hotDays)
  const cold = Number(coldDays)
  const valid = Number.isInteger(hot) && Number.isInteger(cold) && hot >= 1 && hot < cold && cold <= 36500
  const changed = hot !== policy.hotDays || cold !== policy.coldDays
  const save = useMutation({
    mutationFn: managementService.saveTemperaturePolicy,
    onSuccess: saved => client.setQueryData(queryKeys.temperaturePolicy, saved),
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (valid && changed) save.mutate({ hotDays: hot, coldDays: cold })
  }

  return <form className="ops-form" onSubmit={submit}>
    <div className="ops-policy-fields">
      <label>Warm starts after (days)
        <input type="number" required min={1} max={36500} value={hotDays} disabled={save.isPending}
          onChange={event => { save.reset(); setHotDays(event.target.value) }} />
      </label>
      <label>Cold starts after (days)
        <input type="number" required min={2} max={36500} value={coldDays} disabled={save.isPending}
          onChange={event => { save.reset(); setColdDays(event.target.value) }} />
      </label>
    </div>
    <ul className="ops-policy-preview" aria-label="Temperature thresholds">
      <li><Badge tone={temperatureTone.HOT}>HOT</Badge><span>Younger than {hot || '—'} days</span></li>
      <li><Badge tone={temperatureTone.WARM}>WARM</Badge><span>From {hot || '—'} to under {cold || '—'} days</span></li>
      <li><Badge tone={temperatureTone.COLD}>COLD</Badge><span>{cold || '—'} days and older</span></li>
    </ul>
    {!valid && <p className="ops-error" role="status">Enter whole days with the warm threshold below the cold threshold.</p>}
    {save.isError && <p className="ops-error" role="alert">{save.error.message}</p>}
    <div className="ops-form-actions">
      <span role="status">{save.isSuccess ? 'Policy saved' : ''}</span>
      <Button type="submit" variant="primary" disabled={!valid || !changed || save.isPending}>{save.isPending ? 'Saving…' : 'Save policy'}</Button>
    </div>
  </form>
}

function TemperaturePolicies() {
  const policy = useTemperaturePolicy()
  if (policy.isError) return <ApiPageState error={policy.error} loadingLabel="Loading temperature policy"
    retry={() => void policy.refetch()} retrying={policy.isFetching} />
  if (!policy.data) return <ApiPageState loadingLabel="Loading temperature policy" />
  return <section className="panel admin-group ops-settings-panel">
    <div className="panel-heading"><div><h2>File temperature</h2>
      <p>Set HOT, WARM, and COLD by time since the file was last modified. This policy applies across the Catalog.</p>
    </div></div>
    <TemperatureEditor policy={policy.data} />
  </section>
}

export default function ManagementPage({ section, navigate }: { section: string | null; navigate: Navigate }) {
  const policies = section === 'policies'
  return <>
    <div className="page-heading"><div><h1>Management</h1><p>Manage storage connections and workspace policies.</p></div></div>
    <nav className="admin-tabs filters" aria-label="Management sections">
      <button type="button" aria-pressed={!policies} onClick={() => navigate('/management')}><Icon name="server" size={15} />Storage connections</button>
      <button type="button" aria-pressed={policies} onClick={() => navigate('/management?section=policies')}><Icon name="layers" size={15} />Rules &amp; policies</button>
    </nav>
    {policies ? <TemperaturePolicies /> : <StorageConnectionsPage navigate={navigate} />}
  </>
}
