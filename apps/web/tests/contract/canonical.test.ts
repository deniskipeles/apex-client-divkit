import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import canonicalize from 'canonicalize'

const DIR = fileURLToPath(new URL('../../../../packages/contract/fixtures/canonical/v1', import.meta.url))

describe('canonical-json fixtures', () => {
  for (const file of readdirSync(DIR).filter((f) => f.endsWith('.json'))) {
    test(file, () => {
      const input = JSON.parse(readFileSync(join(DIR, file), 'utf8'))
      const expected = readFileSync(join(DIR, file.replace('.json', '.expected.bin')), 'utf8')
      expect(canonicalize(input)).toBe(expected)
    })
  }
})
