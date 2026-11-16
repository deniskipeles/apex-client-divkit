import { defineEventHandler, getHeader, readRawBody, createError } from 'h3'
import { PayloadVerifier } from '../../src/contract/payloadVerifier'

const PUBLIC_KEY = process.env.APEX_EDGE_PUBLIC_KEY ?? ''

export default defineEventHandler(async (event) => {
  if (event.method !== 'POST' || !event.path.startsWith('/api/schema')) return
  if (!PUBLIC_KEY) return

  const envelope = await readRawBody(event, 'utf8')
  if (!envelope) throw createError({ statusCode: 400, message: 'missing signed envelope' })

  const tenant = getHeader(event, 'x-apex-tenant') ?? 'current'
  const verifier = new PayloadVerifier(tenant, PUBLIC_KEY)
  try {
    event.context.payload = await verifier.verify(envelope)
  } catch (e) {
    throw createError({ statusCode: 401, message: `Envelope rejected: ${(e as Error).message}` })
  }
})
