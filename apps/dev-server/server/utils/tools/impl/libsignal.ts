import { ServerTool } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://libsignal` is a Double Ratchet implementation. The dev-server does not
// ship one, and a toy ratchet would be worse than an honest refusal.

export class LibSignalTool extends ServerTool {
  readonly id = 'libsignal'
  readonly aliases = [
    'secure.send',
    'secure.send@1',
    'secure_send',
    'secure.status',
    'secure.status@1',
    'signal_status',
    'secure.verify_identity',
    'secure.verify_identity@1',
    'secure_verify',
    'verify_identity',
    'crypto.signal',
    'crypto.signal@1',
    'signal',
  ]
  readonly operations = [
    'setup_identity',
    'init_keys',
    'create_session',
    'encrypt',
    'decrypt',
    'status',
    'verify_identity',
    'reset_session',
  ]
  readonly supported = false

  invoke(operation: string): ToolOutcome {
    return this.refuse('UNSUPPORTED', 'No Signal protocol implementation on the dev-server', {
      verified: false,
      session_id: '',
      operation,
    })
  }
}
