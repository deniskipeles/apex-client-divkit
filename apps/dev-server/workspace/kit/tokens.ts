/**
 * Shared visual tokens for generated workspaces.
 *
 * One palette keeps every workspace on the same contrast ladder: the generated JSON is copied
 * into the Android demo untouched, so a colour chosen here is a colour shipped on device.
 *
 * The ladder is tuned for 10-inch handheld POS terminals viewed at arm's length:
 *   - `bg`        canvas behind every card (near-black navy, keeps OLED duty low)
 *   - `surface`   card surface, always one step above the canvas
 *   - `surfaceAlt` inset wells inside a card (inputs, list rows, chips)
 *   - `surfaceHi` hover/active well — the only surface lighter than `surfaceAlt`
 *   - strokes     three steps (`line` < `lineStrong` < `lineFocus`) so focus is obvious
 *   - accents     one primary, four semantic; never used as large fills except `primary`
 */
export const palette = {
  bg: '#0A101F',
  surface: '#101A2C',
  surfaceAlt: '#16233A',
  surfaceHi: '#1D2C46',
  line: '#223047',
  lineStrong: '#2E3F5C',
  lineFocus: '#3E5480',
  text: '#F5F8FE',
  textMuted: '#9AA8BD',
  textFaint: '#6B7A93',
  primary: '#6D5AE8',
  primarySoft: '#241F45',
  onPrimary: '#FFFFFF',
  accent: '#2FD6A3',
  accentSoft: '#10352C',
  warn: '#F5A83C',
  warnSoft: '#3A2A10',
  danger: '#F0645C',
  dangerSoft: '#3B1614',
  info: '#41BDF0',
  infoSoft: '#0E2C42',
}

/** Type ramp: display > title > subtitle > body > small > label > mono. Sizes are sp. */
export const type = {
  display: 28,
  title: 20,
  subtitle: 16,
  body: 14,
  small: 12.5,
  label: 11,
  mono: 12,
}

export const radius = {
  sm: 10,
  md: 14,
  lg: 20,
  xl: 26,
  pill: 999,
}

/** 4dp grid helper — every gap in a generated card comes from here. */
export const space = (steps: number): number => steps * 4

/**
 * Elevation ladder for card surfaces. DivKit Android renders solid backgrounds
 * with a stroke (no real shadows in the subset), so depth is faked with a
 * slightly lighter fill plus a matching top hairline.
 */
export const elevation = {
  flat: { background: palette.surface, stroke: { color: palette.line, width: 1 } },
  raised: { background: palette.surface, stroke: { color: palette.lineStrong, width: 1 } },
  inset: { background: palette.surfaceAlt, stroke: { color: palette.line, width: 1 } },
} as const

/**
 * Tone chips: (text, background) pairs used by status pills and accent tiles.
 * Backgrounds are deep tints of the accent so text always passes AA on them.
 */
export const tones: Record<'primary' | 'accent' | 'warn' | 'danger' | 'info' | 'neutral', { fg: string; bg: string }> = {
  primary: { fg: '#B7ABFF', bg: palette.primarySoft },
  accent: { fg: palette.accent, bg: palette.accentSoft },
  warn: { fg: palette.warn, bg: palette.warnSoft },
  danger: { fg: palette.danger, bg: palette.dangerSoft },
  info: { fg: palette.info, bg: palette.infoSoft },
  neutral: { fg: palette.textMuted, bg: palette.surfaceAlt },
}
