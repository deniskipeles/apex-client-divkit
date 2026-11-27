import { ServerTool, text } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://haptics` and `tools://beeper` are hardware actuators. The dev-server
// has neither, so every operation refuses with `UNSUPPORTED` and the card shows
// the refusal instead of a fabricated "played".

export class HapticsTool extends ServerTool {
  readonly id = 'haptics'
  readonly aliases = ['device.haptics', 'device.haptics@1', 'haptic', 'vibrate']
  readonly operations = ['perform', 'vibrate', 'click', 'heavy', 'tick', 'success', 'warning', 'error', 'stop', 'status']
  readonly supported = false

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    return this.refuse('UNSUPPORTED', 'No haptic actuator on the dev-server', {
      pattern: text(payload.pattern) ?? operation,
      available: false,
    })
  }
}

export class BeeperTool extends ServerTool {
  readonly id = 'beeper'
  readonly aliases = ['device.beeper', 'device.beeper@1', 'tone', 'buzzer']
  readonly operations = ['tone', 'play', 'beep', 'chime', 'stop', 'status']
  readonly supported = false

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    return this.refuse('UNSUPPORTED', 'No audio actuator on the dev-server', {
      frequency_hz: text(payload.frequency_hz) ?? '',
      available: false,
    })
  }
}
