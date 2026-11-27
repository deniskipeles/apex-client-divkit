import { defineEventHandler, setHeader } from 'h3'
import { listServerTools } from '../../../utils/tools/index.ts'

// GET /api/tools
//
// Advertises the server inventory so the UI can show which capabilities the
// current execution mode can actually run. `supported: false` is honest: those
// tools answer `UNSUPPORTED` (hardware/host gaps) rather than faking a result.
export default defineEventHandler((event) => {
  setHeader(event, 'Cache-Control', 'no-store')
  const tools = listServerTools()
  return {
    count: tools.length,
    supported: tools.filter((tool) => tool.supported).length,
    tools,
  }
})
