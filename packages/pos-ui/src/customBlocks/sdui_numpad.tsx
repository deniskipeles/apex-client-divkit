import { readCustomProps } from './customElement'

/**
 * sdui_numpad — a 0-9/. /C keypad that emits `apex-numpad` events for `user.*` targets.
 * DivKit sets `custom_props` as attributes, so props are read in `connectedCallback`.
 */
export class ApexNumpadElement extends HTMLElement {
  connectedCallback(): void {
    const props = readCustomProps(this)
    const target = typeof props.target === 'string' ? props.target : 'user.numpad_value'
    const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 'C']
    this.innerHTML = `<div role="group" aria-label="Numpad" style="display:grid;grid-template-columns:repeat(3,1fr);gap:4px">${keys
      .map((key) => `<button type="button" data-key="${key}">${key}</button>`)
      .join('')}</div>`
    this.addEventListener('click', (event) => {
      const button = (event.target as HTMLElement).closest('button')
      if (!button) return
      this.dispatchEvent(
        new CustomEvent('apex-numpad', {
          bubbles: true,
          detail: { target, key: button.dataset.key ?? '' },
        }),
      )
    })
  }
}
