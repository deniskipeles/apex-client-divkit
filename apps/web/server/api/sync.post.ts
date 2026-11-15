import { defineEventHandler, readBody, createError } from 'h3'
import { isApplied, markApplied } from '../lib/store'
import { applyBatch, type SyncItem } from '../lib/sync'

export default defineEventHandler(async (event) => {
  const body = await readBody<{ batch: SyncItem[] }>(event)
  if (!body?.batch?.length) throw createError({ statusCode: 400, message: 'empty batch' })
  if (body.batch.length > 50) throw createError({ statusCode: 413, message: 'batch too large' })

  return applyBatch(body.batch, isApplied, markApplied)
})
