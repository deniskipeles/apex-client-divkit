import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { DeviceMeta } from './devices'

// ---------------------------------------------------------------------------
// DeviceSimulator — renders a devices.css frame around live DOM.
//
// devices.css gives us pixel-accurate hardware chrome (bezel, notch /
// dynamic island, buttons, home indicator). This component adds the
// "simulator" behaviour: scale-to-fit the available stage, optional
// landscape rotation and a zoom multiplier.
// ---------------------------------------------------------------------------

interface Props {
  device: DeviceMeta
  rotated: boolean
  zoom: number
  children: React.ReactNode
}

export function DeviceSimulator({ device, rotated, zoom, children }: Props) {
  const stageRef = useRef<HTMLDivElement | null>(null)
  const deviceRef = useRef<HTMLDivElement | null>(null)
  const [natural, setNatural] = useState<{ w: number; h: number } | null>(null)
  const [fit, setFit] = useState(1)

  // Measure the frame's layout size whenever the device changes.
  useLayoutEffect(() => {
    const el = deviceRef.current
    if (!el) return
    const w = el.offsetWidth
    const h = el.offsetHeight
    setNatural({ w, h })
  }, [device])

  // Recompute the fit scale when the stage resizes (responsive!) or the
  // device/rotation changes.
  useEffect(() => {
    const stage = stageRef.current
    if (!stage || !natural) return

    const compute = () => {
      const availW = stage.clientWidth - 24
      const availH = stage.clientHeight - 24
      if (availW <= 0 || availH <= 0) return
      const boxW = rotated ? natural.h : natural.w
      const boxH = rotated ? natural.w : natural.h
      const s = Math.min(1, availW / boxW, availH / boxH)
      setFit(Math.max(0.12, Math.min(1.4, s)))
    }

    compute()
    const ro = new ResizeObserver(compute)
    ro.observe(stage)
    return () => ro.disconnect()
  }, [natural, rotated])

  const scale = fit * zoom
  // The device chrome overlays the notch on top of the screen; real hosts inset their
  // content below the status bar, so the preview reserves that space too.
  const insetTop = device.badge === 'Dynamic Island' ? 46 : 24
  const box = natural ?? { w: device.screen[0] + 28, h: device.screen[1] + 28 }

  return (
    <div
      ref={stageRef}
      className="stage-dots relative flex min-h-[420px] w-full items-center justify-center overflow-hidden rounded-3xl border border-panel-line bg-black/25 lg:min-h-0 lg:flex-1"
      style={{ minHeight: 'min(72dvh, 780px)' }}
      aria-label={`Device simulator — ${device.name}${rotated ? ' landscape' : ''}`}
    >
      {/* soft glow behind the device */}
      <div className="pointer-events-none absolute left-1/2 top-1/2 h-[70%] w-[70%] max-h-[560px] max-w-[520px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-emerald-500/10 blur-3xl" />

      <div
        className="relative flex items-center justify-center"
        style={
          rotated
            ? { width: box.h * scale + 8, height: box.w * scale + 8 }
            : { width: box.w * scale + 8, height: box.h * scale + 8 }
        }
      >
        <div
          ref={deviceRef}
          className={`device ${device.className} device-scale-wrap shrink-0`}
          style={{
            transform: `rotate(${rotated ? 90 : 0}deg) scale(${scale})`,
          }}
          data-device-id={device.id}
        >
          <div className="device-frame">
            <div className="device-screen device-screen-live" data-testid="device-screen">
              <div className="h-full w-full" style={{ paddingTop: insetTop }}>
                {children}
              </div>
            </div>
          </div>
          <div className="device-stripe" />
          <div className="device-header" />
          <div className="device-sensors" />
          <div className="device-btns" />
          <div className="device-power" />
          <div className="device-home" />
        </div>
      </div>

      {/* corner HUD */}
      <div className="pointer-events-none absolute bottom-3 right-3 rounded-lg border border-panel-line bg-black/55 px-2.5 py-1 font-mono text-[10px] text-ink-dim backdrop-blur">
        {device.name} · {rotated ? 'landscape' : 'portrait'} · {(scale * 100).toFixed(0)}%
      </div>
    </div>
  )
}
