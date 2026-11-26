import { TopBar } from './components/TopBar'
import { PlaygroundView } from './views/PlaygroundView'
import { DocsView } from './views/DocsView'
import { WorkspaceView } from './views/WorkspaceView'
import { useRoute } from './lib/router'

export default function App() {
  const route = useRoute()

  return (
    <div className="relative flex min-h-screen flex-col bg-canvas text-ink">
      {/* ambient glows */}
      <div className="pointer-events-none fixed inset-0 overflow-hidden" aria-hidden="true">
        <div className="absolute -top-44 left-1/2 h-[420px] w-[720px] -translate-x-1/2 rounded-full bg-emerald-500/10 blur-3xl" />
        <div className="absolute -bottom-24 right-[-80px] h-[320px] w-[420px] rounded-full bg-amber-500/[0.07] blur-3xl" />
      </div>

      <TopBar />

      {route.path === '/docs' ? <DocsView /> : route.path === '/workspace' ? <WorkspaceView /> : <PlaygroundView />}

      <footer className="relative mt-auto border-t border-panel-line py-4 text-center text-[10.5px] text-ink-faint">
        Boda Playground · DivKit server-driven UI · Nitro + Vite SPA · fares in KES · demo data lives in
        card variables
      </footer>
    </div>
  )
}
