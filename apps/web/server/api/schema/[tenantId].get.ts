import { defineEventHandler, getRouterParam, createError } from 'h3'
import { getSchema, getLatestSchema } from '../../lib/store'

export default defineEventHandler((event) => {
  const tenantId = getRouterParam(event, 'tenantId')
  if (!tenantId) throw createError({ statusCode: 400, message: 'tenantId required' })
  const divkit = tenantId === 'current' ? (getSchema(tenantId) ?? getLatestSchema()) : getSchema(tenantId)
  if (!divkit) throw createError({ statusCode: 404, message: `No schema for tenant ${tenantId}` })
  return divkit
})
