#!/usr/bin/env node
/**
 * One-off codemod: turns a raw DivKit JSON object into TypeScript that uses
 * `@divkitframework/jsonbuilder` constructors. Unknown (native) node types stay as plain
 * objects because the builder has no class for them.
 *
 *   node scripts/json-to-jsonbuilder.mjs <module.ts> <exportName> <out.ts>
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const [modulePath, exportName, outPath, divNodeImport = '../../core/kit/div.ts'] = process.argv.slice(2)
if (!modulePath || !exportName || !outPath) {
  console.error('usage: json-to-jsonbuilder.mjs <module.ts> <exportName> <out.ts>')
  process.exit(2)
}

const CLASSES = {
  container: 'DivContainer',
  text: 'DivText',
  image: 'DivImage',
  gallery: 'DivGallery',
  grid: 'DivGrid',
  separator: 'DivSeparator',
  input: 'DivInput',
  custom: 'DivCustom',
  state: 'DivState',
  pager: 'DivPager',
  tabs: 'DivTabs',
  indicator: 'DivIndicator',
  slider: 'DivSlider',
  select: 'DivSelect',
  switch: 'DivSwitch',
  video: 'DivVideo',
  gif_image: 'DivGifImage',
}

const used = new Set()
const indent = (level) => '  '.repeat(level)

function emit(value, level = 0) {
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]'
    return `[\n${value.map((item) => `${indent(level + 1)}${emit(item, level + 1)},`).join('\n')}\n${indent(level)}]`
  }
  if (value && typeof value === 'object') {
    const className = typeof value.type === 'string' ? CLASSES[value.type] : null
    if (className) {
      used.add(className)
      const props = Object.entries(value).filter(([key]) => key !== 'type')
      if (props.length === 0) return `new ${className}({} as any)`
      return `new ${className}({\n${props.map(([key, item]) => `${indent(level + 1)}${JSON.stringify(key)}: ${emit(item, level + 1)},`).join('\n')}\n${indent(level)}} as any)`
    }
    const entries = Object.entries(value)
    if (entries.length === 0) return '{}'
    return `{\n${entries.map(([key, item]) => `${indent(level + 1)}${JSON.stringify(key)}: ${emit(item, level + 1)},`).join('\n')}\n${indent(level)}}`
  }
  return JSON.stringify(value)
}

const mod = await import(pathToFileURL(resolve(modulePath)).href)
const value = mod[exportName]
if (value === undefined) {
  console.error(`export ${exportName} not found in ${modulePath}`)
  process.exit(1)
}

const expression = emit(value)
const imports = [...used].sort()
const header = `import { ${imports.join(', ')} } from '@divkitframework/jsonbuilder'\nimport type { DivNode } from '${divNodeImport}'\n\n`
writeFileSync(outPath, `${header}export const ${exportName}: DivNode = ${expression} as unknown as DivNode\n`)
console.log(`wrote ${outPath} (${imports.length} jsonbuilder classes)`)
