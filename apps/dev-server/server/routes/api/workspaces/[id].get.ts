import { createError, defineEventHandler, getRouterParam, setHeader } from 'h3'
import { buildWorkspace, workspacePayload } from '../../../../workspaces/core/build.ts'
import { WORKSPACES } from '../../../../workspaces/core/registry.ts'

/** Serves a whole `apex.workspace/1` package: manifest, hub, every card, catalog. */
export default defineEventHandler((event) => {
  const id = getRouterParam(event, 'id')
  const definition = WORKSPACES.find((workspace) => workspace.slug === id || workspace.manifestId === id)
  if (!definition) {
    throw createError({
      statusCode: 404,
      statusMessage: `Unknown workspace "${id}". Available: ${WORKSPACES.map((w) => w.slug).join(', ')}`,
    })
  }

  setHeader(event, 'Cache-Control', 'no-store')
  return workspacePayload(buildWorkspace(definition))
})
