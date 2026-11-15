import { readCustomProps } from './customElement'

/**
 * sdui_signature — a pointer-driven signature pad.
 * DivKit sets `custom_props` as attributes, so props are read in `connectedCallback`.
 */
export class ApexSignatureElement extends HTMLElement {
  connectedCallback(): void {
    const props = readCustomProps(this)
    const label = typeof props.label === 'string' ? props.label : 'Sign here'
    this.innerHTML = `<label style="display:flex;flex-direction:column;gap:4px">${label}<canvas width="320" height="120" style="border:1px solid currentColor;touch-action:none"></canvas></label>`
    const canvas = this.querySelector('canvas')
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    ctx.lineWidth = 2
    ctx.strokeStyle = 'currentColor'
    let drawing = false
    canvas.addEventListener('pointerdown', (event) => {
      drawing = true
      ctx.beginPath()
      ctx.moveTo(event.offsetX, event.offsetY)
    })
    canvas.addEventListener('pointermove', (event) => {
      if (!drawing) return
      ctx.lineTo(event.offsetX, event.offsetY)
      ctx.stroke()
    })
    canvas.addEventListener('pointerup', () => {
      drawing = false
    })
  }
}
