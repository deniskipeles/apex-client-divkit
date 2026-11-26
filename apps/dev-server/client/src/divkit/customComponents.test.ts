// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { evalProp } from './customComponents'

const vars = (entries: Record<string, unknown>) => new Map<string, unknown>(Object.entries(entries))

describe('evalProp', () => {
  it('resolves a plain variable reference', () => {
    expect(evalProp('@{user.status}', vars({ 'user.status': 'IDLE' }))).toBe('IDLE')
  })

  it('resolves a quoted literal branch of a ternary', () => {
    expect(evalProp("@{user.error == '' ? 'No errors' : user.error}", vars({ 'user.error': '' }))).toBe('No errors')
  })

  it('resolves the else branch of a ternary', () => {
    expect(evalProp("@{user.error == '' ? 'No errors' : user.error}", vars({ 'user.error': 'disk full' }))).toBe('disk full')
  })

  it('handles the parenthesised form the projection emits', () => {
    expect(evalProp("@{((user.count > 0) ? 'items' : 'empty')}", vars({ 'user.count': 2 }))).toBe('items')
  })

  it('resolves concatenations', () => {
    expect(evalProp("@{'Hub ' + user.port}", vars({ 'user.port': '8089' }))).toBe('Hub 8089')
  })

  it('passes plain strings and numbers through', () => {
    expect(evalProp('Ready', vars({}))).toBe('Ready')
    expect(evalProp(42, vars({}))).toBe(42)
  })
})
