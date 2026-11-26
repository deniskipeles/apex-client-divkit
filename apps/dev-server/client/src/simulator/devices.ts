// Device catalog backed by devices.css (picturepan2) — pure-CSS frames.
// Each device is mounted as:
//   <div class="device {className}">
//     <div class="device-frame"><div class="device-screen"> …live DivKit… </div></div>
//     <div class="device-stripe"/><div class="device-header"/> …buttons…
//   </div>

export type DeviceKind = 'phone' | 'foldable' | 'tablet'

export interface DeviceMeta {
  id: string
  name: string
  className: string
  kind: DeviceKind
  /** approximate logical screen size — used for the initial scale guess */
  screen: [number, number]
  badge?: string
}

export const DEVICES: DeviceMeta[] = [
  { id: 'iphone-14-pro', name: 'iPhone 14 Pro', className: 'device-iphone-14-pro', kind: 'phone', screen: [390, 830], badge: 'Dynamic Island' },
  { id: 'iphone-14', name: 'iPhone 14', className: 'device-iphone-14', kind: 'phone', screen: [390, 830] },
  { id: 'iphone-x', name: 'iPhone X', className: 'device-iphone-x', kind: 'phone', screen: [375, 812] },
  { id: 'iphone-8', name: 'iPhone 8', className: 'device-iphone-8', kind: 'phone', screen: [375, 667], badge: 'Home button' },
  { id: 'pixel-6-pro', name: 'Pixel 6 Pro', className: 'device-google-pixel-6-pro', kind: 'phone', screen: [412, 892] },
  { id: 'pixel', name: 'Pixel', className: 'device-google-pixel', kind: 'phone', screen: [411, 731] },
  { id: 'galaxy-s8', name: 'Galaxy S8', className: 'device-galaxy-s8', kind: 'phone', screen: [360, 740] },
  { id: 'galaxy-fold', name: 'Galaxy Z Fold4', className: 'device-galaxy-z-fold4', kind: 'foldable', screen: [673, 841] },
  { id: 'ipad-pro', name: 'iPad Pro 11″', className: 'device-ipad-pro', kind: 'tablet', screen: [834, 1194] },
]

export function getDevice(id: string): DeviceMeta {
  return DEVICES.find((d) => d.id === id) ?? DEVICES[0]
}
