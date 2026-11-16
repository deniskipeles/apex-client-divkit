import { createRoot, type Root } from 'react-dom/client'
import { createPortal } from 'react-dom'
import { DivKit } from '@divkitframework/react'
import type { DivJson } from '@divkitframework/divkit'
import { customComponents } from '../customBlocks/index'
import { extensions } from '../extensions'

let hostEl: HTMLElement | null = null
let hostRoot: Root | null = null

/**
 * Presents a card in a bottom sheet using the same `DivJson` object held by CardRegistry —
 * no re-fetch and no re-parse. Idempotent: presenting while a sheet is open replaces it.
 *
 * `dispatch` is the caller's action router; it is passed in rather than imported so this
 * module never depends on `handleAction` (which depends on it).
 */
export function presentSheet(
  cardId: string,
  data: DivJson,
  dispatch: (url: string, payload: unknown) => void,
): void {
  dismissSheet()
  hostEl = document.createElement('div')
  hostEl.id = `apex-sheet-${cardId}`
  document.body.appendChild(hostEl)
  hostRoot = createRoot(hostEl)

  hostRoot.render(
    createPortal(
      <div
        role="dialog"
        aria-modal="true"
        style={{
          position: 'fixed',
          inset: 0,
          background: 'rgba(0,0,0,0.4)',
          display: 'flex',
          alignItems: 'flex-end',
          zIndex: 1000,
        }}
        onClick={(e) => {
          if (e.target === e.currentTarget) dismissSheet()
        }}
      >
        <div
          style={{
            background: 'white',
            borderTopLeftRadius: 16,
            borderTopRightRadius: 16,
            width: '100%',
            maxHeight: '70vh',
            overflow: 'auto',
          }}
        >
          <DivKit
            id={`apex-sheet-${cardId}`}
            json={data}
            customComponents={customComponents}
            extensions={extensions}
            onCustomAction={({ url, payload }) => dispatch(url, payload)}
          />
        </div>
      </div>,
      hostEl,
    ),
  )
}

export function dismissSheet(): void {
  hostRoot?.unmount()
  hostRoot = null
  hostEl?.remove()
  hostEl = null
}
