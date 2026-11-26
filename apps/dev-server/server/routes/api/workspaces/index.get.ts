import { defineEventHandler, setHeader } from 'h3'
import { buildWorkspace, indexEntry } from '../../../../workspace/build.ts'
import { WORKSPACES } from '../../../../workspace/registry.ts'

export default defineEventHandler((event) => {
  setHeader(event, 'Cache-Control', 'no-store')
  const workspaces = WORKSPACES.map((definition) => indexEntry(buildWorkspace(definition)))
  return { count: workspaces.length, workspaces }
})
