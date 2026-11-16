import type { DivJson } from '@divkitframework/divkit'

/**
 * Cross-platform card registry. Cards are resolved by id from here and never passed inline
 * as JSON through a URL scheme, so the parsed `DivJson` object is shared by reference
 * (a pointer, not a copy) between the navigation stack and any bottom sheet that hosts it.
 */
class CardRegistryImpl {
  private cards = new Map<string, DivJson>()

  register(id: string, data: DivJson): void {
    this.cards.set(id, data)
  }

  card(id: string): DivJson | null {
    return this.cards.get(id) ?? null
  }

  has(id: string): boolean {
    return this.cards.has(id)
  }

  clear(): void {
    this.cards.clear()
  }
}

export const CardRegistry = new CardRegistryImpl()
