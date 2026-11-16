import { DivKit } from '@divkitframework/react'
import type { DivJson } from '@divkitframework/divkit'
import type { ToolBridge } from './ToolBridge'
import { dispatchTool } from './dispatchTool'
import { customComponents } from './customBlocks/index'
import { extensions } from './extensions'

interface Props {
  card: DivJson
  bridge: ToolBridge
  onToolResult?: (url: string, result: unknown) => void
}

export function DivKitView({ card, bridge, onToolResult }: Props) {
  return (
    <DivKit
      id="apex-pos-card"
      json={card}
      customComponents={customComponents}
      extensions={extensions}
      onCustomAction={async (action) => {
        const { url, payload } = action
        if (typeof url === 'string' && url.startsWith('tools://')) {
          const result = await dispatchTool(
            bridge,
            url.replace('tools://', ''),
            (payload ?? {}) as Record<string, unknown>,
          )
          onToolResult?.(url, result)
        }
      }}
    />
  )
}
