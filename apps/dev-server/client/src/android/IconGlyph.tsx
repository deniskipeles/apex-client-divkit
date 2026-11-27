import { findIcon, type NativeIcon } from '@apex/pos-ui/android-runtime'

/**
 * Icon rendering for the preview.
 *
 * Resolution is exact (the generated catalog is the device's catalog), but the
 * glyph is drawn from a small inline SVG set; anything outside that set falls
 * back to a labelled chip. The device draws the Material vector, so an unlisted
 * icon is a visual difference, never a behavioural one — the resolved name and
 * category stay inspectable through `data-icon`.
 */

const PATHS: Record<string, string> = {
  shopping_cart: 'M7 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm10 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM7 16h11l2-9H6L5 4H2v2h2l3 10Z',
  receipt: 'M6 2h12v20l-3-2-3 2-3-2-3 2V2Zm3 5h6v2H9V7Zm0 4h6v2H9v-2Z',
  payments: 'M3 6h18v12H3V6Zm2 2v2h14V8H5Zm0 4v4h6v-4H5Zm8 0v4h6v-4h-6Z',
  inventory: 'M3 4h18v4H3V4Zm1 6h16v10H4V10Zm4 2v2h8v-2H8Z',
  print: 'M6 3h12v5H6V3ZM4 9h16v7h-3v5H7v-5H4V9Zm4 7v3h8v-3H8Z',
  qr_code: 'M3 3h8v8H3V3Zm2 2v4h4V5H5Zm8-2h8v8h-8V3Zm2 2v4h4V5h-4ZM3 13h8v8H3v-8Zm2 2v4h4v-4H5Zm8-2h3v3h-3v-3Zm5 0h3v3h-3v-3Zm-5 5h3v3h-3v-3Zm5 0h3v3h-3v-3Z',
  storefront: 'M4 4h16l1 5a3 3 0 0 1-6 0 3 3 0 0 1-6 0 3 3 0 0 1-6 0l1-5Zm1 8v8h6v-5h2v5h6v-8a5 5 0 0 1-4-1 5 5 0 0 1-8 0 5 5 0 0 1-2 1Z',
  person: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm0 2c-4 0-7 2-7 5v1h14v-1c0-3-3-5-7-5Z',
  search: 'M10 4a6 6 0 1 0 3.9 10.6l4.2 4.2 1.4-1.4-4.2-4.2A6 6 0 0 0 10 4Zm0 2a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z',
  add: 'M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6V5Z',
  remove: 'M5 11h14v2H5v-2Z',
  arrow_back: 'M11 5 4 12l7 7 1.4-1.4L7.8 13H20v-2H7.8l4.6-4.6L11 5Z',
  check: 'M9.6 16.2 5.4 12l-1.4 1.4 5.6 5.6L20 8.6 18.6 7.2 9.6 16.2Z',
  warning: 'M12 3 1 21h22L12 3Zm1 14h-2v2h2v-2Zm0-6h-2v4h2v-4Z',
  chevron_right: 'M9.4 6 8 7.4l4.6 4.6L8 16.6 9.4 18l6-6-6-6Z',
  notifications: 'M12 2a6 6 0 0 0-6 6v4l-2 3v1h16v-1l-2-3V8a6 6 0 0 0-6-6Zm0 20a3 3 0 0 0 3-3H9a3 3 0 0 0 3 3Z',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm9 4-2-1 1-3-2-2-3 1-2-2-1-2h-2l-1 2-2 2-3-1-2 2 1 3-2 1v2l2 1-1 3 2 2 3-1 2 2 1 2h2l1-2 2-2 3 1 2-2-1-3 2-1v-2Z',
  lock: 'M6 10V8a6 6 0 1 1 12 0v2h2v12H4V10h2Zm2 0h8V8a4 4 0 0 0-8 0v2Z',
  cloud_upload: 'M12 3 6 9h3v6h6V9h3l-6-6ZM4 18h16v3H4v-3Z',
  download: 'M11 3h2v8h3l-4 5-4-5h3V3ZM4 18h16v3H4v-3Z',
  scan: 'M3 3h6v2H5v4H3V3Zm18 0v6h-2V5h-4V3h6ZM3 15h2v4h4v2H3v-6Zm16 0h2v6h-6v-2h4v-4ZM7 11h10v2H7v-2Z',
  refresh: 'M12 4a8 8 0 1 1-7.4 5H2l3.5-4L9 9H6.6A6 6 0 1 0 12 6V4Z',
  home: 'M12 3 2 12h3v9h6v-6h2v6h6v-9h3L12 3Z',
  dashboard: 'M3 3h8v8H3V3Zm10 0h8v5h-8V3ZM3 13h8v8H3v-8Zm10-3h8v11h-8V10Z',
  trending_up: 'M3 17 9 11l4 4 7-7v3h2V4h-7v2h3l-5 5-4-4-7 7 1 1Z',
  schedule: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm1 5h-2v6l5 3 1-1.7-4-2.3V7Z',
  receipt_long: 'M5 2h14v20l-2-1.5L15 22l-3-2-3 2-2-1.5L5 22V2Zm3 5h8v2H8V7Zm0 4h8v2H8v-2Z',
  sell: 'M3 12 12 3h9v9l-9 9-9-9Zm13-5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Z',
}

const NAME_BY_MATERIAL: Record<string, string> = {
  ShoppingCart: 'shopping_cart',
  Receipt: 'receipt',
  ReceiptLong: 'receipt_long',
  Payments: 'payments',
  Inventory: 'inventory',
  Print: 'print',
  QrCode2: 'qr_code',
  QrCode: 'qr_code',
  Storefront: 'storefront',
  Person: 'person',
  Search: 'search',
  Add: 'add',
  Remove: 'remove',
  ArrowBack: 'arrow_back',
  Check: 'check',
  Warning: 'warning',
  ChevronRight: 'chevron_right',
  Notifications: 'notifications',
  Settings: 'settings',
  Lock: 'lock',
  CloudUpload: 'cloud_upload',
  Download: 'download',
  QrCodeScanner: 'scan',
  Refresh: 'refresh',
  Home: 'home',
  Dashboard: 'dashboard',
  TrendingUp: 'trending_up',
  Schedule: 'schedule',
  Sell: 'sell',
}

export function IconGlyph({ reference, size = 20, color }: { reference: string; size?: number; color?: string }) {
  const icon: NativeIcon | null = findIcon(reference)
  const tone = color ?? '#CBD5E1'
  const path = icon ? (PATHS[icon.name] ?? PATHS[NAME_BY_MATERIAL[icon.material] ?? '']) : undefined
  const label = (icon?.name ?? reference.replace(/^icons:\/\/(native\/)?/, '')).slice(0, 2).toUpperCase()

  if (path) {
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" fill={tone} aria-label={icon?.name} data-icon={icon?.name} data-material={icon?.material}>
        <path d={path} />
      </svg>
    )
  }

  return (
    <span
      title={icon ? `${icon.name} · ${icon.material} · ${icon.categoryLabel}` : `unknown icon "${reference}"`}
      data-icon={icon?.name ?? ''}
      data-material={icon?.material ?? ''}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: size,
        height: size,
        borderRadius: 6,
        fontSize: Math.max(8, size * 0.42),
        fontWeight: 700,
        color: tone,
        border: `1px solid ${tone}55`,
        fontFamily: 'ui-monospace, monospace',
      }}
    >
      {label || '?'}
    </span>
  )
}
