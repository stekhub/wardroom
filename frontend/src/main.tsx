import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from '@/App'
import '@/index.css'
import { startTheme } from '@/theme/themeState'

startTheme()

const root = document.getElementById('root')
if (!root) {
  throw new Error('index.html has no element with the id "root"')
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
