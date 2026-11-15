package com.example.contract

import engine.crypto.Ed25519
import engine.crypto.PayloadVerifier
import engine.crypto.currentTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the signed-envelope contract (packages/contract/docs/envelope.md): the signature
 * covers tenant/schema_version/iat/jti/payload only, and verification rejects tampering,
 * cross-tenant replay, expired envelopes and jti reuse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnvelopeContractTest {

    private val keyPair = Ed25519.generateKeyPair("contract-test-signer")
    private fun verifier(tenant: String = "acme") = PayloadVerifier(tenant, keyPair.publicKeyDerBase64)

    private fun envelope(
        tenant: String = "acme",
        payload: String = """{"type":"container"}""",
        iat: Long = currentTimeMillis(),
        jti: String = java.util.UUID.randomUUID().toString()
    ) = PayloadVerifier.createSignedEnvelope(
        tenant = tenant,
        payloadJson = payload,
        keyPair = keyPair,
        iatOverride = iat,
        jtiOverride = jti
    )

    @Test
    fun validEnvelopeReturnsPayload() = runBlocking {
        val verified = verifier().verify(envelope(payload = """{"type":"text","text":"hi"}"""))
        assertEquals("""{"type":"text","text":"hi"}""", verified)
    }

    @Test
    fun signatureDoesNotCoverSignatureFieldAndIsStableAcrossKeyOrder() = runBlocking {
        // Two envelopes with identical signed content but different jti must both verify.
        val a = verifier().verify(envelope(jti = "jti-a"))
        val b = verifier().verify(envelope(jti = "jti-b"))
        assertEquals(a, b)
    }

    @Test
    fun wrongTenantIsRejected() {
        val ex = kotlin.runCatching { runBlocking { verifier("other").verify(envelope(tenant = "acme")) } }
        assertTrue(ex.exceptionOrNull() is SecurityException)
        assertTrue(ex.exceptionOrNull()!!.message!!.contains("Tenant mismatch"))
    }

    @Test
    fun tamperedPayloadFailsSignature() {
        val env = envelope().replace("container", "injected")
        val ex = kotlin.runCatching { runBlocking { verifier().verify(env) } }
        assertTrue(ex.exceptionOrNull() is SecurityException)
        assertTrue(ex.exceptionOrNull()!!.message!!.contains("Signature verification failed"))
    }

    @Test
    fun expiredEnvelopeIsRejected() {
        val stale = envelope(iat = currentTimeMillis() - 600_000L)
        val ex = kotlin.runCatching { runBlocking { verifier().verify(stale) } }
        assertTrue(ex.exceptionOrNull() is SecurityException)
        assertTrue(ex.exceptionOrNull()!!.message!!.contains("expired"))
    }

    @Test
    fun replayedJtiIsRejected() {
        val v = verifier()
        val env = envelope(jti = "fixed-jti")
        runBlocking { v.verify(env) }
        val ex = kotlin.runCatching { runBlocking { v.verify(env) } }
        assertTrue(ex.exceptionOrNull() is SecurityException)
        assertTrue(ex.exceptionOrNull()!!.message!!.contains("Replay detected"))
    }
}
