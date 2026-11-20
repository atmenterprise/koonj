import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AppProviders } from './app/AppProviders'
import Console from './app/Console'
import { AuthGate } from './auth/AuthGate'
import { AuthProvider } from './auth/AuthProvider'
import './app/styles.css'

const root = document.getElementById('root')
if (!root) throw new Error('Missing console root')

createRoot(root).render(<StrictMode>
  <AuthProvider><AuthGate><AppProviders><Console /></AppProviders></AuthGate></AuthProvider>
</StrictMode>)
