import { useState } from 'react'
import type { Job } from './sampleData'
import { Button } from './ui'
import { isActive, type Workspace } from './workspace'

export default function TransferControls({ job, control }: { job: Job; control: Workspace['control'] }) {
  const [confirm, setConfirm] = useState(false)
  if (!isActive(job)) return null
  const paused = job.status === 'Paused'
  return <div className="row-actions">
    {confirm ? <>
      <Button variant="quiet" onClick={() => setConfirm(false)}>Keep transfer</Button>
      <Button variant="danger" onClick={() => control(job.id, 'cancel')}>Cancel transfer</Button>
    </> : <>
      <Button icon={paused ? 'play' : 'pause'} className="icon-button" aria-label={(paused ? 'Resume ' : 'Pause ') + job.title}
        title={paused ? 'Resume' : 'Pause'} onClick={() => control(job.id, paused ? 'resume' : 'pause')} />
      <Button icon="stop" className="icon-button" aria-label={'Cancel ' + job.title} title="Cancel" onClick={() => setConfirm(true)} />
    </>}
  </div>
}
