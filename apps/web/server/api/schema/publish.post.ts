import { defineEventHandler, readBody, createError } from 'h3'
import { putSchema } from '../../lib/store'
import { validateCard } from '../../lib/validateCard'

export default defineEventHandler(async (event) => {
  const { tenantId, divkit } = await readBody<{ tenantId: string; divkit: unknown }>(event)
  if (!tenantId) throw createError({ statusCode: 400, message: 'tenantId required' })

  const result = validateCard(divkit)
  if (!result.ok) {
    throw createError({
      statusCode: 422,
      message: `DivKit card rejected: ${result.errors.join('; ')}`,
    })
  }

  putSchema(tenantId, divkit)
  return { ok: true }
})
