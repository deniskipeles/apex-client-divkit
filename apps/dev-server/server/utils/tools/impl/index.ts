import type { ServerTool } from '../ServerTool.ts'
import { BiometricsTool } from './biometrics.ts'
import { CameraTool } from './camera.ts'
import { ClipboardTool } from './clipboard.ts'
import { CodeGenerateTool } from './codeGenerate.ts'
import { CodecTool } from './codec.ts'
import { EmbeddingTool } from './embedding.ts'
import { FilesTool } from './files.ts'
import { BeeperTool, HapticsTool } from './hapticsBeeper.ts'
import { ImageManipulationTool } from './imageManipulation.ts'
import { LibSignalTool } from './libsignal.ts'
import { MobileNetV4Tool, U2NetTool } from './ml.ts'
import { NetTool } from './net.ts'
import { OfflineCacheTool } from './offlineCache.ts'
import { PrintTool } from './print.ts'
import { PushTool } from './push.ts'
import { RealtimeTool } from './realtime.ts'
import { ScanTool } from './scan.ts'
import { ScreenTool } from './screen.ts'

// Order matters only for aliases shared by two tools. Keep the specific ones
// (`u-2-Net` before `image-manipulation`) ahead of the generic families.
export const TOOLS: ServerTool[] = [
  new CodecTool(),
  new ScanTool(),
  new FilesTool(),
  new OfflineCacheTool(),
  new ClipboardTool(),
  new PushTool(),
  new EmbeddingTool(),
  new NetTool(),
  new PrintTool(),
  new ScreenTool(),
  new HapticsTool(),
  new BeeperTool(),
  new CameraTool(),
  new BiometricsTool(),
  new ImageManipulationTool(),
  new LibSignalTool(),
  new RealtimeTool(),
  new CodeGenerateTool(),
  new MobileNetV4Tool(),
  new U2NetTool(),
]
