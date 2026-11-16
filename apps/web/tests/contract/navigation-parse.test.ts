import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { parseNavigationUrl } from '@apex/pos-ui'

// Fixtures live in the shared contract package; this test fails if the web parser drifts.
const DIR = join(__dirname, '../../../../packages/contract/fixtures/navigation/v1')

describe('navigation URL parse fixtures', () => {
  const files = readdirSync(DIR).filter((f) => f.endsWith('.json')).sort()

  it('covers every contract fixture', () => {
    expect(files).toEqual([
      'pop-parse.json',
      'push-parse.json',
      'replace-parse.json',
      'reset-parse.json',
      'sheet-dismiss-parse.json',
      'sheet-present-parse.json',
    ])
  })

  for (const file of files) {
    test(file, () => {
      const { input, expected } = JSON.parse(readFileSync(join(DIR, file), 'utf8'))
      expect(parseNavigationUrl(input)).toEqual(expected)
    })
  }

  it('returns null for URLs the navigation layer does not own', () => {
    expect(parseNavigationUrl('nav://push')).toBeNull()
    expect(parseNavigationUrl('nav://push?id=')).toBeNull()
    expect(parseNavigationUrl('sheet://present')).toBeNull()
    expect(parseNavigationUrl('tools://device.haptics')).toBeNull()
    expect(parseNavigationUrl('https://example.com')).toBeNull()
    expect(parseNavigationUrl('not a url')).toBeNull()
  })
})
