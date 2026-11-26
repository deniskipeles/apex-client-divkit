import { defineEventHandler, setHeader } from 'h3'
import { SCHEMAS } from '../../../utils/schemas/registry'

export default defineEventHandler((event) => {
  setHeader(event, 'Cache-Control', 'no-store')
  return {
    count: SCHEMAS.length,
    schemas: SCHEMAS.map((entry) => entry.meta),
  }
})
