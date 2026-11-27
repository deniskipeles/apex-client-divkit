import { ServerTool } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://biometrics` must be able to refuse. The dev-server has no OS
// credential gate and no enrolled secret, so verification fails closed.

export class BiometricsTool extends ServerTool {
  readonly id = 'biometrics'
  readonly aliases = ['auth.biometrics', 'auth.biometrics@1', 'supervisor_auth', 'credential_gate']
  readonly operations = ['authenticate']
  readonly supported = false

  invoke(): ToolOutcome {
    return this.refuse('UNSUPPORTED', 'No biometric credential sink on the dev-server', {
      authenticated: false,
      available: false,
    })
  }
}
