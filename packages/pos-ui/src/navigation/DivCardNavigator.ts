import type { DivJson } from '@divkitframework/divkit'

export interface Entry {
  id: string
  data: DivJson
}

/**
 * Pointer stack + bounded LRU over card ids.
 *
 * The stack holds references to parsed `DivJson` (immutable), never copies. The LRU bounds
 * how many cards stay mounted at once; an evicted card re-renders from its pointer on the
 * next push with no re-parse. The visible card is never evicted.
 */
export class DivCardNavigator {
  private stack: Entry[]
  private index: number
  private readonly cacheLimit: number
  private readonly lruOrder: string[] = []

  constructor(initialCards: Entry[], cacheLimit = 8) {
    if (initialCards.length === 0) throw new Error('navigator needs at least one card')
    if (cacheLimit < 1) throw new Error('cacheLimit must be >= 1')
    this.stack = [...initialCards]
    this.index = this.stack.length - 1
    this.cacheLimit = cacheLimit
    initialCards.forEach((e) => this.touch(e.id))
  }

  get current(): Entry | null {
    return this.stack[this.index] ?? null
  }

  get currentIndex(): number {
    return this.index
  }

  get entries(): readonly Entry[] {
    return this.stack
  }

  get liveCardIds(): string[] {
    return this.lruOrder.filter((id) => this.stack.some((e) => e.id === id))
  }

  push(entry: Entry): void {
    this.stack.push(entry)
    this.index = this.stack.length - 1
    this.touch(entry.id)
  }

  replace(entry: Entry): void {
    if (this.stack.length === 0) {
      this.push(entry)
      return
    }
    this.stack[this.index] = entry
    this.touch(entry.id)
  }

  reset(entry: Entry): void {
    this.stack = [entry]
    this.index = 0
    this.lruOrder.length = 0
    this.touch(entry.id)
  }

  pop(): void {
    if (this.index === 0) return
    this.stack.pop()
    this.index -= 1
    if (this.current) this.touch(this.current.id)
  }

  private touch(id: string): void {
    const i = this.lruOrder.indexOf(id)
    if (i >= 0) this.lruOrder.splice(i, 1)
    this.lruOrder.push(id)
    this.evictIfNeeded()
  }

  private evictIfNeeded(): void {
    while (this.lruOrder.length > this.cacheLimit) {
      const oldest = this.lruOrder[0]
      if (oldest === this.current?.id) {
        // Never evict the visible card; rotate it to the back and stop.
        this.lruOrder.shift()
        this.lruOrder.push(oldest)
        continue
      }
      this.lruOrder.shift()
    }
  }
}
