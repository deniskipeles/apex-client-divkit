export function readCustomProps(el: HTMLElement): Record<string, unknown> {
  const props: Record<string, unknown> = {}
  for (const name of el.getAttributeNames()) {
    const raw = el.getAttribute(name)
    if (raw === null) continue
    try {
      props[name] = JSON.parse(raw)
    } catch {
      props[name] = raw
    }
  }
  return props
}

export function defineOnce(tag: string, ctor: CustomElementConstructor): void {
  if (!customElements.get(tag)) customElements.define(tag, ctor)
}
