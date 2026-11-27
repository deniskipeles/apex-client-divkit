#!/usr/bin/env node
// ---------------------------------------------------------------------------
// Regenerates `packages/divkit-subset/schema.json` from the runtime vocabulary.
//
// The vocabulary mirrors the Android dispatch statements, so the schema can no
// longer advertise a node type or custom block the device ignores. Run with
// `--check` to fail on drift.
//
//   node scripts/build-divkit-schema.mjs [--check]
// ---------------------------------------------------------------------------

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  BLOCK_TYPES,
  CHART_TYPES,
  NODE_TYPES,
  UNRENDERED_CUSTOM_TYPES,
} from '../../../packages/pos-ui/src/android-runtime/vocabulary.ts'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..', '..', '..')
const TARGET = join(REPO, 'packages/divkit-subset/schema.json')
const GENERATED = 'apps/dev-server/scripts/build-divkit-schema.mjs'
const check = process.argv.includes('--check')

const componentTypes = [...NODE_TYPES, ...BLOCK_TYPES]
const customTypes = ['json_list', ...CHART_TYPES, ...UNRENDERED_CUSTOM_TYPES]

const schema = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://apex-client-divkit/contract/divkit-subset/v1/schema.json',
  title: 'DivKit Subset (v1)',
  description:
    'The DivKit document surface the terminal renders. Generated from packages/pos-ui/src/android-runtime/vocabulary.ts, which mirrors the Android renderer dispatch. `custom_type` values listed under web-only are rendered by the web/desktop custom-component registry but NOT by apps/android, where they render nothing.',
  $ref: '#/$defs/component',
  $defs: {
    component: {
      type: 'object',
      required: ['type'],
      properties: {
        type: { enum: componentTypes },
        id: { type: 'string' },
        visibility: {
          description:
            '`gone` removes the node from layout, `invisible` keeps its space. Accepts an @{...} expression.',
          type: 'string',
          enum: ['visible', 'invisible', 'gone'],
        },
        items: { type: 'array', items: { $ref: '#/$defs/component' } },
        text: { type: 'string' },
        subtitle: { type: 'string' },
        action: { $ref: '#/$defs/action' },
        actions: { type: 'array', items: { $ref: '#/$defs/action' } },
        data_var: { type: 'string', minLength: 1 },
        empty_text: { type: 'string' },
        row_card: { $ref: '#/$defs/component' },
        width: { $ref: '#/$defs/size' },
        height: { $ref: '#/$defs/size' },
        margins: { $ref: '#/$defs/spacing' },
        paddings: { $ref: '#/$defs/spacing' },
        custom_type: {
          type: 'string',
          enum: customTypes,
          description:
            'Only `json_list` and the chart aliases are implemented by apps/android. sdui_signature and sdui_numpad are web-only.',
        },
        custom_props: { type: 'object', additionalProperties: true },
      },
      additionalProperties: true,
    },
    action: {
      type: 'object',
      properties: {
        type: {
          enum: ['set_variable', 'array_insert_value', 'array_remove_value', 'array_set_value', 'array_delete_value'],
        },
        variable_name: { type: 'string', minLength: 1 },
        value: {},
        index: {},
        position: { enum: ['start', 'end'] },
        url: { type: 'string', minLength: 1 },
        payload: { type: 'object' },
        log_id: { type: 'string' },
      },
      anyOf: [{ required: ['url'] }, { required: ['type'] }],
      additionalProperties: true,
    },
    size: {
      type: 'object',
      required: ['type'],
      properties: {
        type: { enum: ['match_parent', 'wrap_content', 'fixed', 'weighted'] },
        value: { type: 'number' },
        weight: { type: 'number' },
      },
      additionalProperties: true,
    },
    spacing: {
      type: 'object',
      properties: {
        left: { type: 'integer', minimum: 0 },
        top: { type: 'integer', minimum: 0 },
        right: { type: 'integer', minimum: 0 },
        bottom: { type: 'integer', minimum: 0 },
        start: { type: 'integer', minimum: 0 },
        end: { type: 'integer', minimum: 0 },
      },
      additionalProperties: false,
    },
  },
}

const serialized = `${JSON.stringify(schema, null, 2)}\n`

if (check) {
  const current = readFileSync(TARGET, 'utf8')
  if (current !== serialized) {
    console.error('build-divkit-schema: schema is stale — run `pnpm divkit:schema`')
    process.exit(1)
  }
  console.log('build-divkit-schema: schema in sync')
} else {
  writeFileSync(TARGET, serialized)
  console.log(`build-divkit-schema: wrote ${componentTypes.length} node types, ${customTypes.length} custom types`)
}
