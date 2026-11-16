import { useEffect, useState } from 'react'
import { DivNavigationContainer, CardRegistry } from '@apex/pos-ui'
import type { DivJson } from '@apex/pos-ui'
import { TauriToolBridge } from './TauriToolBridge'

export function App() {
  const [initial, setInitial] = useState<Array<{ id: string; data: DivJson }> | null>(null)
  useEffect(() => {
    fetch(`${import.meta.env.VITE_EDGE_URL}/api/schema/current`)
      .then((r) => r.json())
      .then((launcher) => {
        CardRegistry.register('launcher', launcher)
        setInitial([{ id: 'launcher', data: launcher }])
      })
  }, [])
  if (!initial) return <div>Loading…</div>
  return <DivNavigationContainer initial={initial} bridge={TauriToolBridge} />
}
