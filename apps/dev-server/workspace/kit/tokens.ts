/**
 * Shared visual tokens for generated workspaces.
 *
 * One palette keeps every workspace on the same contrast ladder: the generated JSON is copied
 * into the Android demo untouched, so a colour chosen here is a colour shipped on device.
 */
export const palette = {
  bg: '#0B1120',
  surface: '#111C2E',
  surfaceAlt: '#16233A',
  line: '#1E293B',
  text: '#F8FAFC',
  textMuted: '#94A3B8',
  textFaint: '#64748B',
  primary: '#7C3AED',
  onPrimary: '#FFFFFF',
  accent: '#34D399',
  warn: '#F59E0B',
  danger: '#EF4444',
  info: '#38BDF8',
}

/** Type ramp: display > title > body > label > mono. Sizes are sp, weights DivKit names. */
export const type = {
  display: 26,
  title: 18,
  subtitle: 15,
  body: 14,
  small: 12,
  label: 11,
  mono: 12,
}

export const radius = {
  sm: 8,
  md: 12,
  lg: 16,
  pill: 999,
}

/** 4dp grid helper — every gap in a generated card comes from here. */
export const space = (steps: number): number => steps * 4
