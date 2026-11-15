import { webcrypto } from 'node:crypto';
import { canonicalize } from './canonicalJson';

const subtle = webcrypto.subtle;

function base64ToBytes(b64: string): Uint8Array {
  return new Uint8Array(Buffer.from(b64, 'base64'));
}

function bytesToBase64(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString('base64');
}

/** Raw 32-byte Ed25519 key from either a raw key or a DER SubjectPublicKeyInfo. */
function rawKey(der: Uint8Array): Uint8Array {
  return der.length === 32 ? der : der.slice(der.length - 32);
}

// PKCS#8 prefix for an Ed25519 private key; WebCrypto only imports private keys as pkcs8.
const ED25519_PKCS8_PREFIX = Uint8Array.from([
  0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
]);

function pkcs8FromSeed(seed: Uint8Array): Uint8Array {
  const out = new Uint8Array(ED25519_PKCS8_PREFIX.length + seed.length);
  out.set(ED25519_PKCS8_PREFIX, 0);
  out.set(seed, ED25519_PKCS8_PREFIX.length);
  return out;
}

export interface EnvelopeFields {
  tenant: string;
  schema_version: string;
  iat: number;
  jti: string;
  payload: unknown;
}

/**
 * Web mirror of the signed-envelope contract (packages/contract/docs/envelope.md).
 */
export class PayloadVerifier {
  private seenJti: string[] = [];
  private readonly maxSkewMs: number;
  private readonly maxSeenJti: number;

  constructor(
    private readonly expectedTenant: string,
    private readonly publicKeyBase64: string,
    maxSkewMs = 300_000,
    maxSeenJti = 10_000,
  ) {
    this.maxSkewMs = maxSkewMs;
    this.maxSeenJti = maxSeenJti;
  }

  private signedInput(fields: EnvelopeFields): Uint8Array {
    return new TextEncoder().encode(canonicalize(fields as unknown as Record<string, unknown>));
  }

  async verify(rawEnvelope: string): Promise<unknown> {
    let obj: Record<string, unknown>;
    try {
      obj = JSON.parse(rawEnvelope);
    } catch (e) {
      throw new Error(`Malformed envelope: ${(e as Error).message}`);
    }

    const tenant = obj.tenant as string;
    const schemaVersion = obj.schema_version as string;
    const iat = obj.iat as number;
    const jti = obj.jti as string;
    const signature = obj.signature as string;

    if (tenant !== this.expectedTenant) {
      throw new Error(`Tenant mismatch: expected ${this.expectedTenant}, got ${tenant}`);
    }

    const key = await subtle.importKey('raw', rawKey(base64ToBytes(this.publicKeyBase64)), { name: 'Ed25519' }, false, ['verify']);
    const ok = await subtle.verify({ name: 'Ed25519' }, key, base64ToBytes(signature), this.signedInput({ tenant, schema_version: schemaVersion, iat, jti, payload: obj.payload }));
    if (!ok) throw new Error('Signature verification failed');

    const now = Date.now();
    if (Math.abs(now - iat) > this.maxSkewMs) {
      throw new Error(`Envelope expired (iat=${iat}, now=${now})`);
    }

    if (this.seenJti.includes(jti)) throw new Error(`Replay detected: jti=${jti}`);
    this.seenJti.push(jti);
    if (this.seenJti.length > this.maxSeenJti) this.seenJti.splice(0, this.seenJti.length - this.maxSeenJti);

    return obj.payload;
  }
}

/**
 * Edge-signer equivalent (`signing.ts`). Returns the signed envelope as a JSON string.
 */
export async function createSignedEnvelope(
  fields: EnvelopeFields,
  privateKeyBase64: string,
): Promise<string> {
  const rawPrivate = rawKey(base64ToBytes(privateKeyBase64));
  const key = await subtle.importKey('pkcs8', pkcs8FromSeed(rawPrivate), { name: 'Ed25519' }, false, ['sign']);
  const message = new TextEncoder().encode(canonicalize(fields as unknown as Record<string, unknown>));
  const signature = await subtle.sign({ name: 'Ed25519' }, key, message);
  const envelope = { ...fields, signature: bytesToBase64(new Uint8Array(signature)) };
  return JSON.stringify(envelope);
}
