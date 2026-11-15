import type { CustomComponentDescription } from '@divkitframework/divkit'
import { defineOnce } from './customElement'
import { ApexChartElement } from './sdui_chart'
import { ApexSignatureElement } from './sdui_signature'
import { ApexNumpadElement } from './sdui_numpad'

defineOnce('apex-chart', ApexChartElement)
defineOnce('apex-signature', ApexSignatureElement)
defineOnce('apex-numpad', ApexNumpadElement)

export const customComponents = new Map<string, CustomComponentDescription>([
  ['sdui_chart', { element: 'apex-chart' }],
  ['sdui_signature', { element: 'apex-signature' }],
  ['sdui_numpad', { element: 'apex-numpad' }],
])
