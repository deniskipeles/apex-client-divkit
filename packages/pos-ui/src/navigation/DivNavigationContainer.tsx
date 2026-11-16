import { useReducer, useRef } from 'react'
import { DivKit } from '@divkitframework/react'
import type { DivJson } from '@divkitframework/divkit'
import { DivCardNavigator, type Entry } from './DivCardNavigator'
import { CardRegistry } from './CardRegistry'
import { handleNavigationAction } from './handleAction'
import { customComponents } from '../customBlocks/index'
import { extensions } from '../extensions'
import type { ToolBridge } from '../ToolBridge'

interface Props {
  initial: Entry[]
  bridge: ToolBridge
  cacheLimit?: number
  onToolResult?: (url: string, result: unknown) => void
}

/**
 * Host-side navigation container. Every live card id mounts a `<DivKit>` inside its own
 * `<div>`; inactive cards stay in the DOM with `display: none` so DOM identity — and with
 * it scroll position — survives push/pop. This is the web equivalent of iOS
 * `removeFromSuperview()` / Android `removeView()`.
 */
export function DivNavigationContainer({ initial, bridge, cacheLimit = 8, onToolResult }: Props) {
  const navigatorRef = useRef<DivCardNavigator | null>(null)
  if (!navigatorRef.current) {
    navigatorRef.current = new DivCardNavigator(initial, cacheLimit)
    initial.forEach((e) => CardRegistry.register(e.id, e.data))
  }
  const navigator = navigatorRef.current

  // Re-render after any nav:// or sheet:// action.
  const [, rerender] = useReducer((x: number) => x + 1, 0)

  const activeId = navigator.current?.id
  const liveIds = navigator.liveCardIds

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%' }}>
      {liveIds.map((id) => {
        const data: DivJson | null = CardRegistry.card(id)
        if (!data) return null
        const isActive = id === activeId
        return (
          <div
            key={id}
            data-card-id={id}
            data-active={isActive}
            style={{ position: 'absolute', inset: 0, display: isActive ? 'block' : 'none' }}
            aria-hidden={!isActive}
          >
            <DivKit
              id={`apex-card-${id}`}
              json={data}
              customComponents={customComponents}
              extensions={extensions}
              onCustomAction={({ url, payload }) => {
                void handleNavigationAction(url, payload, {
                  navigator,
                  bridge,
                  onChanged: rerender,
                  onToolResult,
                })
              }}
            />
          </div>
        )
      })}
    </div>
  )
}
