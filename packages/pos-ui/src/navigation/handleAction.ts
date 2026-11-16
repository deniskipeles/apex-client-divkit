import type { DivJson } from '@divkitframework/divkit'
import type { ToolBridge } from '../ToolBridge'
import { dispatchTool } from '../dispatchTool'
import { CardRegistry } from './CardRegistry'
import type { DivCardNavigator } from './DivCardNavigator'
import { parseNavigationUrl } from './parseNavigationUrl'
import { presentSheet, dismissSheet } from './BottomSheetController'

export interface NavigationActionContext {
  navigator: DivCardNavigator
  bridge: ToolBridge
  onChanged: () => void
  onToolResult?: (url: string, result: unknown) => void
}

/**
 * Routes a DivKit custom action to the navigation layer, the bottom-sheet layer, or the
 * tool dispatcher. Unknown URLs are ignored (no throw), matching the contract's
 * "silent no-op" rule for unresolved navigation targets.
 */
export async function handleNavigationAction(
  url: string,
  payload: unknown,
  ctx: NavigationActionContext,
): Promise<void> {
  const op = parseNavigationUrl(url)
  if (op) {
    switch (op.op) {
      case 'push': {
        const data = CardRegistry.card(op.cardId)
        if (!data) return
        ctx.navigator.push({ id: op.cardId, data })
        ctx.onChanged()
        return
      }
      case 'pop':
        ctx.navigator.pop()
        ctx.onChanged()
        return
      case 'replace': {
        const data = CardRegistry.card(op.cardId)
        if (!data) return
        ctx.navigator.replace({ id: op.cardId, data })
        ctx.onChanged()
        return
      }
      case 'reset': {
        const data = CardRegistry.card(op.cardId)
        if (!data) return
        ctx.navigator.reset({ id: op.cardId, data })
        ctx.onChanged()
        return
      }
      case 'sheet-present': {
        const data: DivJson | null = CardRegistry.card(op.cardId)
        if (!data) return
        presentSheet(op.cardId, data, (u, p) => {
          void handleNavigationAction(u, p, ctx)
        })
        return
      }
      case 'sheet-dismiss':
        dismissSheet()
        return
    }
  }

  if (url.startsWith('tools://')) {
    const result = await dispatchTool(
      ctx.bridge,
      url.replace('tools://', ''),
      (payload ?? {}) as Record<string, unknown>,
    )
    ctx.onToolResult?.(url, result)
  }
}
