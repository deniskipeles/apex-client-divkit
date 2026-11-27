import { ServerTool, text } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://embedding` — deterministic 512-d hashed text embeddings, the same
// fallback the Android build uses when MediaPipe's text JNI is unavailable.
// Identical text yields cosine 1.0; image paths need a decoder the preview does
// not bundle, so they refuse instead of returning random floats.

const DIM = 512
const MODEL = 'hashed-512'

export class EmbeddingTool extends ServerTool {
  readonly id = 'embedding'
  readonly aliases = ['vector', 'embed', 'embed-text', 'embed-image', 'vectorize', 'vectorize@1']
  readonly operations = ['text', 'image', 'image_text', 'text_similarity', 'similarity']

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    switch (operation || 'text') {
      case 'text': {
        const value = text(payload.text) ?? text(payload.input)
        if (value === null || value.length === 0) throw new Error('missing text argument')
        const vector = embed(value)
        return this.ok({
          status: 'SUCCESS',
          source_type: 'text',
          dim: DIM,
          model: MODEL,
          vector_b64: pack(vector),
          vector,
        })
      }
      case 'text_similarity': {
        const left = text(payload.text_a) ?? text(payload.a)
        const right = text(payload.text_b) ?? text(payload.b)
        if (left === null || right === null) throw new Error('missing text_a / text_b arguments')
        const score = cosine(embed(left), embed(right))
        return this.ok({ status: 'SUCCESS', source_type: 'text', dim: DIM, model: MODEL, score, similarity: score })
      }
      case 'similarity': {
        const left = text(payload.vector_a) ?? text(payload.a)
        const right = text(payload.vector_b) ?? text(payload.b)
        if (left === null || right === null) throw new Error('Provide packed vectors (vector_a / vector_b)')
        const score = cosine(unpack(left), unpack(right))
        return this.ok({ status: 'SUCCESS', dim: DIM, model: MODEL, score, similarity: score })
      }
      case 'image':
      case 'image_text': {
        const image = text(payload.image) ?? text(payload.uri) ?? text(payload.image_uri)
        if (image === null || image.length === 0) {
          throw new Error('No decodable image supplied and no image host is attached')
        }
        throw new Error('No decodable image pipeline on the dev-server (use the device for image embeddings)')
      }
      default:
        return this.refuse('UNSUPPORTED', `embedding/${operation} is not supported`)
    }
  }
}

/** FNV-1a over UTF-8 code units; stable across processes and platforms. */
function hash(token: string): number {
  let value = 0x811c9dc5
  for (let index = 0; index < token.length; index += 1) {
    value ^= token.charCodeAt(index)
    value = Math.imul(value, 0x01000193) >>> 0
  }
  return value >>> 0
}

function embed(input: string): number[] {
  const vector = new Array<number>(DIM).fill(0)
  const tokens = input.toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? []
  for (const token of tokens) {
    addToken(vector, token, 1)
    const padded = `^${token}$`
    for (let index = 0; index + 3 <= padded.length; index += 1) {
      addToken(vector, padded.slice(index, index + 3), 0.35)
    }
  }
  if (tokens.length === 0) addToken(vector, input.trim(), 1)
  return normalize(vector)
}

function addToken(vector: number[], token: string, weight: number): void {
  const digest = hash(token)
  const index = digest % DIM
  const sign = (digest & 0x80000000) === 0 ? 1 : -1
  vector[index] += sign * weight
}

function normalize(vector: number[]): number[] {
  const magnitude = Math.sqrt(vector.reduce((sum, value) => sum + value * value, 0))
  return magnitude === 0 ? vector : vector.map((value) => value / magnitude)
}

function cosine(left: number[], right: number[]): number {
  const length = Math.min(left.length, right.length)
  let dot = 0
  let leftNorm = 0
  let rightNorm = 0
  for (let index = 0; index < length; index += 1) {
    dot += left[index] * right[index]
    leftNorm += left[index] * left[index]
    rightNorm += right[index] * right[index]
  }
  if (leftNorm === 0 || rightNorm === 0) return 0
  return Math.max(-1, Math.min(1, dot / Math.sqrt(leftNorm * rightNorm)))
}

function pack(vector: number[]): string {
  const floats = Float32Array.from(vector)
  return Buffer.from(floats.buffer, floats.byteOffset, floats.byteLength).toString('base64')
}

function unpack(packed: string): number[] {
  const buffer = Buffer.from(packed, 'base64')
  if (buffer.length % 4 !== 0) throw new Error('packed vector length is not a Float32 array')
  const floats = new Float32Array(buffer.buffer, buffer.byteOffset, buffer.byteLength / 4)
  return Array.from(floats)
}
