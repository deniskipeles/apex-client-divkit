import { ServerTool, text } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://image-manipulation` needs an image decoder/encoder. The dev-server
// bundles none, so it refuses with `NO_INPUT` when there is no image and
// `UNSUPPORTED` when there is one — never a fabricated output URI.

export class ImageManipulationTool extends ServerTool {
  readonly id = 'image-manipulation'
  readonly aliases = ['vision.image_manipulation', 'vision.image_manipulation@1', 'image-ops']
  readonly operations = ['process', 'remove_background', 'smooth', 'adjust_color', 'crop', 'resize', 'threshold']
  readonly supported = false

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    const image =
      text(payload.image) ?? text(payload.uri) ?? text(payload.source) ?? text(payload.image_uri)
    if (image === null || image.length === 0) {
      return this.refuse('NO_INPUT', `image-manipulation/${operation || 'process'} needs an image argument`)
    }
    return this.refuse('UNSUPPORTED', 'No image decoder on the dev-server', { operation })
  }
}
