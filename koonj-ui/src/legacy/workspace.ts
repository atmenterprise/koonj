import { useEffect, useState } from 'react'
import { scaleDemoFolders } from './catalogSource'
import { describeTransfer, isMovement, lineageKey, projectMovements, recallLocation, verifyMovement } from './fileMovements'
import { demoEvents, readEvents } from './progress'
import { entries, entryName, initialJobs, parentPath, type Endpoint, type Entry, type Job, type Operation } from './sampleData'

export type JobOptions = Pick<Job, 'bucket' | 'bucketId' | 'storageClass' | 'purge' | 'destination' | 'retrieval' | 'folderPaths'>
const storageKey = 'koonj-demo-jobs-v1'
export const isActive = (job: Job) => job.status === 'Running' || job.status === 'Paused'
export const needsAttention = (job: Job) => job.status === 'Failed' || job.status === 'Partial Failure'

function startingJobs(): Job[] {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) ?? 'null')
    if (Array.isArray(saved)) {
      const seededFailure = initialJobs.find(job => job.id === 'ACT-1045')
      return (saved as Job[]).map(job => job.id !== seededFailure?.id ? job : {
        ...job, results: job.results.map(result => {
          const sample = seededFailure.results.find(item => item.name === result.name && item.error === result.error)
          return sample?.transfer && !result.transfer ? { ...result, entryId: sample.entryId, transfer: sample.transfer } : result
        }),
      })
    }
  } catch {
    // The demo also works when browser storage is unavailable.
  }
  return initialJobs
}

function advance(jobs: Job[], tick: number) {
  return jobs.map((job): Job => {
    if (job.status !== 'Running') return job
    const elapsed = job.elapsed + 2
    const step = job.entryIds || job.id.startsWith('ACT-D') ? Math.max(1, Math.ceil(job.total / 6)) : 2
    const done = Math.min(job.total, job.done + step)
    const finished = done === job.total
    const speed = job.operation === 'ARCHIVE' || job.operation === 'RECALL' ? 38 + (Math.floor(tick / 2400) + job.total) % 47 : 0
    const results = finished ? job.results.map(result => verifyMovement({ ...result, speed })) : job.results
    const failures = finished ? results.filter(result => result.error).length : 0
    const status = !finished ? 'Running' : !failures ? 'Completed' : failures === job.total ? 'Failed' : 'Partial Failure'
    return {
      ...job, elapsed, done, speed: finished ? 0 : speed, status,
      results,
    }
  })
}

function buildCatalog(jobs: Job[], configuredEndpoints: Endpoint[]) {
  const catalog = new Map([...entries, ...scaleDemoFolders].map(entry => [entry.id, entry]))
  projectMovements(catalog, jobs, configuredEndpoints)
  const transfers = jobs.filter(job => job.entryIds?.length && isMovement(job.operation))
  for (const job of transfers.filter(isActive)) {
    for (const id of job.entryIds ?? []) {
      const entry = catalog.get(id)
      if (entry) catalog.set(id, { ...entry, state: 'In Progress' })
    }
  }
  const activeEndpoints = new Set(configuredEndpoints.filter(endpoint => endpoint.enabled !== false).map(endpoint => endpoint.id))
  const catalogEntries = [...catalog.values()].filter(entry => activeEndpoints.has(entry.endpoint))
  return catalogEntries.map(entry => entry.folder && !entry.generated ? { ...entry, count: catalogEntries.filter(child =>
    child.endpoint === entry.endpoint && !child.folder && child.path.startsWith(entry.path + '/')).length } : entry)
}

export function useWorkspace(actor: string, configuredEndpoints: Endpoint[], includeCatalog: boolean) {
  const [jobs, setJobs] = useState(startingJobs)
  const [notice, setNotice] = useState('')
  const [streaming, setStreaming] = useState(false)
  const catalog = includeCatalog ? buildCatalog(jobs, configuredEndpoints) : []

  useEffect(() => {
    const controller = new AbortController()
    void readEvents<{ tick: number }>(demoEvents(controller.signal), ({ tick }) => {
      setStreaming(true)
      setJobs(previous => advance(previous, tick))
    }).catch(() => setStreaming(false))
    return () => controller.abort()
  }, [])

  useEffect(() => {
    try { localStorage.setItem(storageKey, JSON.stringify(jobs)) } catch { /* Storage is optional. */ }
  }, [jobs])

  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 4000)
    return () => window.clearTimeout(timer)
  }, [notice])

  function addJob(input: Omit<Job, 'id' | 'actor' | 'done' | 'elapsed' | 'status' | 'started' | 'speed'>) {
    const id = 'ACT-D' + Date.now().toString(36).toUpperCase() + Math.random().toString(36).slice(2, 4).toUpperCase()
    setJobs(previous => [{
      ...input, id, actor, done: 0, elapsed: 0, status: 'Running', started: Date.now(), speed: 0,
    }, ...previous])
    setNotice(input.title + ' queued')
    return id
  }

  function scan(endpoint: Endpoint) {
    const files = entries.filter(entry => entry.endpoint === endpoint.id && !entry.folder)
    return addJob({ operation: 'SCAN', title: 'Endpoint scan', target: endpoint.path, total: files.length,
      bytes: files.reduce((sum, entry) => sum + entry.bytes, 0),
      results: files.map(entry => ({ name: entry.path, bytes: entry.bytes, speed: 0 })) })
  }

  function run(operation: Operation, selection: Entry[], options: JobOptions) {
    const first = selection[0] ?? options.folderPaths?.[0]
    if (!first) return
    const endpoint = configuredEndpoints.find(item => item.id === first.endpoint)
    let directory = parentPath(first.path)
    while (directory && !selection.every(entry => entry.path.startsWith(directory + '/'))) directory = parentPath(directory)
    const location = endpoint?.path ?? first.endpoint
    const target = selection.length === 1 && !options.folderPaths?.length ? `${location}/${first.path}`
      : options.folderPaths?.length ? `${location}/${options.folderPaths[0].path}`
        : `${selection.length.toLocaleString('en-GB')} files in ${location}${directory ? '/' + directory : ''}`
    const folderTransfer = Boolean(options.folderPaths?.length)
    const title = operation === 'ARCHIVE' ? (folderTransfer ? 'Archive folder tree' : 'Archive to S3')
      : operation === 'RECALL' ? (folderTransfer ? 'Recall folder tree' : 'Recall files') : 'Verify checksums / re-scan'
    const destinations = new Map<string, string>()
    const filesById = new Map(catalog.map(entry => [entry.id, entry]))
    const results = selection.map((entry, index) => {
      const transfer = isMovement(operation) ? describeTransfer(operation, entry,
        configuredEndpoints.find(item => item.id === entry.endpoint), options) : undefined
      let error = isMovement(operation) && !transfer ? 'Transfer location unavailable.' : undefined
      if (transfer?.operation === 'RECALL') {
        const restored = recallLocation(transfer.destination, configuredEndpoints)
        const existing = restored && filesById.get(restored.root.id + ':' + restored.path)
        const occupiedBy = existing ? lineageKey(existing) : destinations.get(transfer.destination)
        if (occupiedBy && occupiedBy !== lineageKey(entry)) error = 'A different file already uses the recall destination.'
        if (!error) destinations.set(transfer.destination, lineageKey(entry))
      }
      return {
        name: entryName(entry), bytes: entry.bytes, speed: 0, entryId: entry.id, transfer,
        // The demo copies the source digest to simulate a successful destination read.
        sourceChecksum: transfer && entry.checksum, destinationChecksum: transfer && entry.checksum,
        version: entry.bucket || options.bucket ? `v-demo-${index + 1}` : undefined,
        etag: entry.etag ?? (options.bucket ? entry.checksum?.slice(0, 32) : undefined),
        error,
      }
    })
    return addJob({ operation, title, target, total: selection.length, bytes: selection.reduce((sum, file) => sum + file.bytes, 0),
      entryIds: selection.map(entry => entry.id), results, ...options })
  }

  function control(id: string, action: 'pause' | 'resume' | 'cancel') {
    setJobs(previous => previous.map(job => job.id === id && isActive(job)
      ? { ...job, speed: 0, status: action === 'pause' ? 'Paused' : action === 'resume' ? 'Running' : 'Cancelled' } : job))
    setNotice(action === 'pause' ? 'Transfer paused' : action === 'resume' ? 'Transfer resumed' : 'Transfer cancelled')
  }

  function retry(job: Job) {
    const results = job.results.filter(result => result.error).map(result => ({
      ...result, error: result.transfer && result.transfer.checksum !== 'Matched' ? result.error : undefined,
    }))
    if (!results.length) return
    return addJob({ operation: job.operation, title: 'Retry failed files', target: job.target, total: results.length,
      bytes: results.reduce((sum, result) => sum + result.bytes, 0), results, bucket: job.bucket, bucketId: job.bucketId, storageClass: job.storageClass,
      destination: job.destination, retrieval: job.retrieval, purge: job.purge })
  }

  return {
    jobs, personalJobs: jobs.filter(job => job.actor === actor), notice, dismissNotice: () => setNotice(''), streaming, scan, run, control, retry,
    endpoints: configuredEndpoints.filter(endpoint => endpoint.enabled !== false),
    catalog,
  }
}

export type Workspace = ReturnType<typeof useWorkspace>
