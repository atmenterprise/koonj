import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AppProviders } from './app/AppProviders'
import { ProjectWorkspace } from './app/ProjectWorkspace'
import { AuthProvider } from './auth/AuthProvider'
import { ActivityNotifications } from './shared/notifications/ActivityNotifications'
import './app/styles.css'

const root = document.getElementById('root')
if (!root) throw new Error('Missing console root')

createRoot(root).render(<StrictMode>
  <ActivityNotifications>
    <AuthProvider><AppProviders><ProjectWorkspace /></AppProviders></AuthProvider>
  </ActivityNotifications>
</StrictMode>)
