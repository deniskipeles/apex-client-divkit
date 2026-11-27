import { ServerTool, text } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// On-device ML models (`mobilenetv4_small_int8.tflite`, U-2-Net) have no runtime
// here. Both tools refuse: `NO_INPUT` without an image, `UNSUPPORTED` with one.

function hasImage(payload: Record<string, unknown>): boolean {
  const image = text(payload.image) ?? text(payload.uri) ?? text(payload.image_uri) ?? text(payload.source)
  return image !== null && image.length > 0
}

export class MobileNetV4Tool extends ServerTool {
  readonly id = 'MobileNetV4-small'
  readonly aliases = ['mobilenetv4', 'vision.identify_product', 'vision.identify_product@1', 'identify_product']
  readonly operations = ['classify', 'detect', 'predict']
  readonly supported = false

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    if (!hasImage(payload)) return this.refuse('NO_INPUT', 'Provide an image to classify')
    return this.refuse('UNSUPPORTED', 'No TFLite runtime on the dev-server', { operation })
  }
}

export class U2NetTool extends ServerTool {
  readonly id = 'u-2-Net.tflite'
  readonly aliases = ['u2net', 'vision.remove_background', 'vision.remove_background@1', 'remove_background']
  readonly operations = ['segment', 'infer', 'mask']
  readonly supported = false

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    if (!hasImage(payload)) return this.refuse('NO_INPUT', 'Provide an image to segment')
    return this.refuse('UNSUPPORTED', 'U-2-Net weights are not bundled on the dev-server', { operation })
  }
}
