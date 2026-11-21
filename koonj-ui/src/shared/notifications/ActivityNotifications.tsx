import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import type { TransferJobDTO } from '../../api/types'
import { jobMessage, reconcileJobs, type ActivityMessage, type ActivityNotice } from './activity'

interface ActivityOptions<T> {
  started: string
  failed: string
  complete: (result: T) => ActivityMessage
}

interface NotificationContextValue {
  notices: ActivityNotice[]
  dialog: HTMLDialogElement | undefined
  dismiss: (id: number) => void
  markRead: (id: number) => void
  registerDialog: (dialog: HTMLDialogElement) => () => void
  syncJobs: (jobs: TransferJobDTO[]) => void
  run: <T>(options: ActivityOptions<T>, action: () => Promise<T>) => Promise<T>
  runJob: (name: string, action: () => Promise<TransferJobDTO>) => Promise<TransferJobDTO>
}

const NotificationContext = createContext<NotificationContextValue | null>(null)

export function ActivityNotifications({ children }: { children: ReactNode }) {
  const [notices, setNotices] = useState<ActivityNotice[]>([])
  const [dialogs, setDialogs] = useState<HTMLDialogElement[]>([])
  const nextId = useRef(0)

  const registerDialog = useCallback((dialog: HTMLDialogElement) => {
    setDialogs(previous => [...previous, dialog])
    return () => setDialogs(previous => previous.filter(item => item !== dialog))
  }, [])

  const dismiss = useCallback((id: number) => {
    setNotices(previous => previous.flatMap(notice => notice.id !== id ? [notice]
      : notice.pending || !notice.read ? [{ ...notice, dismissed: true }] : []))
  }, [])

  const markRead = useCallback((id: number) => {
    setNotices(previous => previous.flatMap(notice => notice.id !== id ? [notice]
      : notice.pending || !notice.dismissed ? [{ ...notice, read: true }] : []))
  }, [])

  const syncJobs = useCallback((jobs: TransferJobDTO[]) => {
    setNotices(previous => reconcileJobs(previous, jobs))
  }, [])

  const run = useCallback(async <T,>(options: ActivityOptions<T>, action: () => Promise<T>) => {
    const id = ++nextId.current
    setNotices(previous => [...previous, { id, tone: 'info', title: options.started, pending: true }])

    function publish(message: ActivityMessage) {
      const pending = message.tone === 'info' && !!message.jobId
      setNotices(previous => previous.map(notice => notice.id === id
        ? { id, ...message, pending, dismissed: pending && notice.dismissed, read: pending && notice.read } : notice))
    }

    try {
      const result = await action()
      publish(options.complete(result))
      return result
    } catch (failure) {
      publish({ tone: 'error', title: options.failed, detail: failure instanceof Error ? failure.message : undefined })
      throw failure
    }
  }, [])

  const runJob = useCallback((name: string, action: () => Promise<TransferJobDTO>) =>
    run({ started: name + ' started', failed: name + ' could not start', complete: jobMessage }, action), [run])

  const value = useMemo(() => ({ notices, dialog: dialogs.at(-1), dismiss, markRead, registerDialog, syncJobs, run, runJob }),
    [notices, dialogs, dismiss, markRead, registerDialog, syncJobs, run, runJob])

  return <NotificationContext.Provider value={value}>{children}</NotificationContext.Provider>
}

export function useActivityNotifications() {
  const context = useContext(NotificationContext)
  if (!context) throw new Error('ActivityNotifications is required')
  return context
}
