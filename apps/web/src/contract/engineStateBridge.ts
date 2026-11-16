/**
 * Web mirror of the DivKit variable bridge. Only `user.*` variables may be written.
 * See packages/contract/docs/variables.md.
 */
export class EngineStateBridge {
  private state = new Map<string, unknown>();

  pushUpdate(name: string, value: unknown): void {
    if (!name.startsWith('user.')) {
      throw new Error(`Variable must be user.*-namespaced: ${name}`);
    }
    this.state.set(name, value);
  }

  pushBatch(updates: Record<string, unknown>): void {
    for (const key of Object.keys(updates)) {
      if (!key.startsWith('user.')) {
        throw new Error(`Variable must be user.*: ${key}`);
      }
    }
    for (const [key, value] of Object.entries(updates)) this.state.set(key, value);
  }

  getVariable(name: string): unknown {
    return this.state.get(name);
  }

  clear(): void {
    this.state.clear();
  }
}
