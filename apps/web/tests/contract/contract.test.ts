import { canonicalize } from '../../src/contract/canonicalJson';
import { EngineStateBridge } from '../../src/contract/engineStateBridge';
import { PayloadVerifier, createSignedEnvelope } from '../../src/contract/payloadVerifier';
import { ALIASES, TerminalRegistry, Handler } from '../../src/contract/registry';
import { SyncEngine } from '../../src/contract/syncEngine';
import { webcrypto } from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';

const contractDir = path.resolve(__dirname, '../../../../packages/contract');

describe('canonical JSON fixtures', () => {
  const fixtureDir = path.join(contractDir, 'fixtures/canonical/v1');
  const inputs = fs.readdirSync(fixtureDir).filter((f) => f.endsWith('.json')).sort();

  it('has three fixtures', () => {
    expect(inputs.length).toBe(3);
  });

  it.each(inputs)('matches expected bytes for %s', (name) => {
    const raw = JSON.parse(fs.readFileSync(path.join(fixtureDir, name), 'utf8'));
    const actual = canonicalize(raw);
    const expected = fs.readFileSync(path.join(fixtureDir, name.replace(/\.json$/, '.expected.bin')), 'utf8');
    expect(actual).toBe(expected);
    expect(Buffer.byteLength(actual)).toBe(Buffer.byteLength(expected));
  });
});

describe('canonicalize', () => {
  it('sorts object keys', () => {
    expect(canonicalize({ z: 1, a: 2, m: 3 })).toBe('{"a":2,"m":3,"z":1}');
  });
  it('collapses whole doubles to integers', () => {
    expect(canonicalize(2.0)).toBe('2');
    expect(canonicalize(2.5)).toBe('2.5');
  });
});

describe('EngineStateBridge', () => {
  it('accepts user.* and rejects others', () => {
    const bridge = new EngineStateBridge();
    bridge.pushUpdate('user.probe', 'ok');
    expect(bridge.getVariable('user.probe')).toBe('ok');
    expect(() => bridge.pushUpdate('system.probe', 'nope')).toThrow();
  });
});

describe('capability registry (tools.json)', () => {
  const tools = JSON.parse(fs.readFileSync(path.join(contractDir, 'tools.json'), 'utf8')) as {
    capabilities: Array<{ id: string; aliases: string[] }>;
  };

  function stub(capability: string): Handler {
    return { capability, invoke: async () => ({}) };
  }

  it('resolves every canonical id and alias', () => {
    const registry = new TerminalRegistry();
    for (const cap of tools.capabilities) registry.register(stub(cap.id));

    for (const cap of tools.capabilities) {
      expect(registry.resolve(cap.id)?.capability).toBe(cap.id);
      for (const alias of cap.aliases) {
        expect(registry.resolve(alias)?.capability).toBe(cap.id);
      }
    }
  });

  it('keeps ALIASES in sync with tools.json', () => {
    for (const cap of tools.capabilities) {
      expect(ALIASES[cap.id] ?? []).toEqual(cap.aliases);
    }
    expect(Object.keys(ALIASES).sort()).toEqual(tools.capabilities.map((c) => c.id).sort());
  });
});

describe('SyncEngine', () => {
  it('classifies retryable reasons', () => {
    expect(SyncEngine.isRetryable('503')).toBe(true);
    expect(SyncEngine.isRetryable('timeout')).toBe(true);
    expect(SyncEngine.isRetryable('rate_limited')).toBe(true);
    expect(SyncEngine.isRetryable('validation_error')).toBe(false);
  });

  it('succeeds on an empty batch without a request', async () => {
    const engine = new SyncEngine('https://example.test');
    await expect(engine.sync([])).resolves.toBe('success');
  });
});

describe('PayloadVerifier', () => {
  async function newKeyPair() {
    const pair = (await webcrypto.subtle.generateKey({ name: 'Ed25519' } as any, true, ['sign', 'verify'])) as CryptoKeyPair;
    // Ed25519 private keys only export as pkcs8; the trailing 32 bytes are the raw seed.
    const pkcs8 = new Uint8Array(await webcrypto.subtle.exportKey('pkcs8', pair.privateKey));
    const priv = Buffer.from(pkcs8.slice(pkcs8.length - 32)).toString('base64');
    const pub = Buffer.from(await webcrypto.subtle.exportKey('raw', pair.publicKey)).toString('base64');
    return { priv, pub };
  }

  it('verifies a freshly signed envelope', async () => {
    const { priv, pub } = await newKeyPair();
    const envelope = await createSignedEnvelope(
      { tenant: 'acme', schema_version: '1.0', iat: Date.now(), jti: 'jti-1', payload: { type: 'text' } },
      priv,
    );
    const verifier = new PayloadVerifier('acme', pub);
    await expect(verifier.verify(envelope)).resolves.toEqual({ type: 'text' });
  });

  it('rejects a tampered payload', async () => {
    const { priv, pub } = await newKeyPair();
    const envelope = await createSignedEnvelope(
      { tenant: 'acme', schema_version: '1.0', iat: Date.now(), jti: 'jti-2', payload: { type: 'text' } },
      priv,
    );
    const tampered = envelope.replace('text', 'injected');
    const verifier = new PayloadVerifier('acme', pub);
    await expect(verifier.verify(tampered)).rejects.toThrow(/Signature verification failed/);
  });

  it('rejects a wrong tenant', async () => {
    const { priv, pub } = await newKeyPair();
    const envelope = await createSignedEnvelope(
      { tenant: 'acme', schema_version: '1.0', iat: Date.now(), jti: 'jti-3', payload: {} },
      priv,
    );
    const verifier = new PayloadVerifier('other', pub);
    await expect(verifier.verify(envelope)).rejects.toThrow(/Tenant mismatch/);
  });

  it('rejects a replayed jti', async () => {
    const { priv, pub } = await newKeyPair();
    const envelope = await createSignedEnvelope(
      { tenant: 'acme', schema_version: '1.0', iat: Date.now(), jti: 'fixed', payload: {} },
      priv,
    );
    const verifier = new PayloadVerifier('acme', pub);
    await verifier.verify(envelope);
    await expect(verifier.verify(envelope)).rejects.toThrow(/Replay detected/);
  });
});
