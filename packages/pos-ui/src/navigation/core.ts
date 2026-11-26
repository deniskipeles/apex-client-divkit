/**
 * React-free navigation core.
 *
 * Re-exports only the modules that carry no React/JSX, so a non-React host (the dev-server's
 * Nitro + Vite playground) can drive the same navigation state machine without pulling
 * `@divkitframework/react` or pos-ui's React components.
 */
export { DivCardNavigator, type Entry } from './DivCardNavigator'
export { CardRegistry } from './CardRegistry'
export { parseNavigationUrl, type NavigationOp } from './parseNavigationUrl'
