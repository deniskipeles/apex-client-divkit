import { defineEventHandler, createError, getRouterParam, setHeader } from 'h3'
import { buildSchemaPayload } from '../../../utils/schemas/registry'

export default defineEventHandler(async (event) => {
  const id = getRouterParam(event, 'id')
  if (!id) {
    throw createError({ statusCode: 400, statusMessage: 'Missing schema id' })
  }

  const payload = buildSchemaPayload(id)
  if (!payload) {
    throw createError({
      statusCode: 404,
      statusMessage: `Unknown schema "${id}". Available: boda-driver, waiter-terminal, custom-components-demo`,
    })
  }

  setHeader(event, 'Cache-Control', 'no-store')
  return payload
})
