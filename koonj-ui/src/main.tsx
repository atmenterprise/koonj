import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import Console from './Console'
import './styles.css'

const root = document.getElementById('root')
if (!root) throw new Error('Missing console root')

createRoot(root).render(<StrictMode><Console /></StrictMode>)
