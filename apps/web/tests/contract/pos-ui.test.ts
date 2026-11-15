import { capabilities } from '@apex/contract'
import { EngineStateBridge, dispatchTool, customComponents, extensions } from '@apex/pos-ui'
import type { ToolBridge, ToolResult } from '@apex/pos-ui'

describe('EngineStateBridge (shared)', () => {
  it('accepts user.* and rejects other namespaces', () => {
    const bridge = new EngineStateBridge()
    bridge.pushUpdate('user.total', 42)
    expect(bridge.getVariable('user.total')).toBe(42)
    expect(() => bridge.pushUpdate('system.total', 1)).toThrow(/user\./)
    expect(() => bridge.pushBatch({ 'user.a': 1, 'system.b': 2 })).toThrow(/user\./)
  })
})

describe('dispatchTool (shared)', () => {
  const recorded: Array<{ capability: string; args: Record<string, unknown> }> = []
  const bridge: ToolBridge = {
    async invoke(capability, args): Promise<ToolResult> {
      recorded.push({ capability, args })
      return { kind: 'success', data: { capability } }
    },
  }

  it('resolves every canonical id and alias from tools.json', async () => {
    for (const cap of capabilities) {
      await dispatchTool(bridge, cap.id, {})
      for (const alias of cap.aliases) await dispatchTool(bridge, alias, {})
    }
    expect(recorded).toHaveLength(capabilities.reduce((n, c) => n + 1 + c.aliases.length, 0))
    for (const call of recorded) {
      expect(capabilities.some((c) => c.id === call.capability)).toBe(true)
    }
  })

  it('returns unsupported for an unknown alias', async () => {
    const result = await dispatchTool(bridge, 'not.a.tool', {})
    expect(result).toMatchObject({ kind: 'unsupported', tool: 'not.a.tool' })
  })
})

describe('custom blocks (shared)', () => {
  it('registers exactly the contract custom types', () => {
    expect([...customComponents.keys()].sort()).toEqual(['sdui_chart', 'sdui_numpad', 'sdui_signature'])
  })

  it('ships no DivKit extensions by default', () => {
    expect(extensions.size).toBe(0)
  })
})
