import { defineEventHandler, setHeader } from 'h3'
import { buildWorkspace, indexEntry } from '../../../../workspaces/core/build.ts'
import { WORKSPACES } from '../../../../workspaces/core/registry.ts'

export default defineEventHandler((event) => {
  setHeader(event, 'Cache-Control', 'no-store')
  const workspaces = WORKSPACES.map((definition) => indexEntry(buildWorkspace(definition)))
  return { count: workspaces.length, workspaces }
})
