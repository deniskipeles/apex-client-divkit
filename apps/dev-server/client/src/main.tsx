import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'
// vendor styles: pure-CSS device frames + the DivKit runtime stylesheet
import 'devices.css/dist/devices.min.css'
import '@divkitframework/divkit/dist/client.css'

// SPA entry — mounted once, no SSR. Hash routing keeps every view reachable
// from a static file server or a Tauri shell.
const container = document.getElementById('root')
if (!container) throw new Error('#root not found')
createRoot(container).render(<App />)
