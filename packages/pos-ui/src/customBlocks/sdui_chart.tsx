import { readCustomProps } from './customElement'

/**
 * sdui_chart — a bar chart driven by `custom_props.values`.
 * DivKit sets `custom_props` as attributes, so props are read in `connectedCallback`.
 */
export class ApexChartElement extends HTMLElement {
  connectedCallback(): void {
    const props = readCustomProps(this)
    const values = Array.isArray(props.values) ? (props.values as unknown[]).map(Number) : []
    const max = values.length > 0 ? Math.max(...values, 1) : 1
    const bars = values
      .map((value) => {
        const height = Math.round((value / max) * 100)
        return `<span part="bar" style="display:inline-block;width:12px;height:${height}%;background:currentColor;vertical-align:bottom;margin:0 1px"></span>`
      })
      .join('')
    const title = typeof props.title === 'string' ? props.title : ''
    this.innerHTML = `<figure style="display:flex;flex-direction:column;align-items:center;margin:0"><figcaption>${title}</figcaption><div style="display:flex;align-items:flex-end;height:120px">${bars}</div></figure>`
  }
}
