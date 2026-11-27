/**
 * Typed DivKit node builders.
 *
 * The official `@divkitframework/jsonbuilder` supplies every node the runtime
 * understands except `button` (the library has no `DivButton`). Workspace cards
 * are built through this layer so a prop typo is a TypeScript error instead of a
 * node the handset silently drops.
 */
export {
  DivContainer,
  DivCustom,
  DivGallery,
  DivGrid,
  DivImage,
  DivInput,
  DivSeparator,
  DivState,
  DivText,
} from '@divkitframework/jsonbuilder'
export type { Div } from '@divkitframework/jsonbuilder'
