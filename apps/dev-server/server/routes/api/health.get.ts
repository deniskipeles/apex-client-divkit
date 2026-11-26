import { defineEventHandler } from 'h3'

export default defineEventHandler(() => ({
  ok: true,
  runtime: 'nitro',
  preset: process.env.NITRO_PRESET || 'node-server',
  divkit: '33.10.0',
}))
