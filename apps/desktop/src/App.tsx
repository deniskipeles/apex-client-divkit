import { useEffect, useState } from 'react'
import { DivKitView } from '@apex/pos-ui'
import type { DivJson } from '@apex/pos-ui'
import { TauriToolBridge } from './TauriToolBridge'

export function App() {
  const [card, setCard] = useState<DivJson | null>(null)
  useEffect(() => {
    fetch(`${import.meta.env.VITE_EDGE_URL}/api/schema/current`)
      .then((r) => r.json())
      .then(setCard)
  }, [])
  if (!card) return <div>Loading…</div>
  return <DivKitView card={card} bridge={TauriToolBridge} />
}
