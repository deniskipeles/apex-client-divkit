package engine.tools.libsignal

import engine.sqlite.SqlDriver
import engine.db.WorkspaceDriverFactory
import engine.db.currentAndroidContext
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.SessionBuilder
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.fingerprint.NumericFingerprintGenerator
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import org.whispersystems.libsignal.state.PreKeyBundle
import org.whispersystems.libsignal.util.KeyHelper
import java.util.Base64

data class ExportedPreKeyBundle(
    val registrationId: Int,
    val deviceId: Int,
    val identityKeyB64: String,
    val signedPreKeyId: Int,
    val signedPreKeyB64: String,
    val signedPreKeySignatureB64: String,
    val preKeyId: Int,
    val preKeyB64: String
)

data class EncryptedEnvelope(
    val type: Int, // 3 = PreKeySignalMessage, 2 = SignalMessage (CiphertextMessage constants)
    val address: String,
    val deviceId: Int,
    val ciphertextB64: String
)

/**
 * Lightweight telemetry snapshot for an active Signal session with a peer.
 */
data class SignalSession(
    val sessionId: String,
    val peerId: String,
    val deviceId: Int,
    val fingerprint: String,
    val isVerified: Boolean,
    val sendCounter: Long,
    val recvCounter: Long
)

/**
 * Outcome of a safety-number comparison with a peer identity.
 */
data class SignalVerification(
    val peerId: String,
    val deviceId: Int,
    val safetyNumber: String,
    val verified: Boolean,
    /** VERIFIED | MISMATCH | UNVERIFIED | KEY_CHANGED */
    val status: String
)

/**
 * Universal E2EE Cryptographic Engine powered by the official Signal Foundation library.
 * Zero custom crypto. Operates directly on the official Double Ratchet and X3DH standards.
 *
 * The local party owns one identity per workspace. Each peer owns its own identity,
 * persisted in a peer-scoped namespace of the same workspace database. Encrypting
 * advances the local party's ratchet; decrypting advances the peer's ratchet — the
 * same round trip a pair of terminals performs over the wire.
 */
object LibSignalEngine {

    private fun driverFor(workspaceId: String): SqlDriver {
        val ctx = currentAndroidContext?.invoke() ?: error("Android Context not initialized")
        return WorkspaceDriverFactory(ctx).create(workspaceId)
    }

    private fun localStore(workspaceId: String) =
        SqliteSignalProtocolStore(driverFor(workspaceId), workspaceId)

    private fun peerStore(workspaceId: String, peerId: String) =
        SqliteSignalProtocolStore(driverFor(workspaceId), "peer::$workspaceId::$peerId")

    private fun address(peerId: String, deviceId: Int) = SignalProtocolAddress(peerId, deviceId)

    /**
     * Initializes or verifies the identity key pair and prekeys for the workspace.
     */
    fun setupIdentity(workspaceId: String): Pair<Int, String> {
        val store = localStore(workspaceId)
        return try {
            val regId = store.localRegistrationId
            val identity = store.identityKeyPair
            regId to Base64.getEncoder().encodeToString(identity.publicKey.serialize())
        } catch (_: Exception) {
            generateIdentity(store)
        }
    }

    /**
     * Ensures the peer's identity exists, generating it via the official KeyHelper
     * on first use so it can serve a stable PreKeyBundle.
     */
    private fun ensurePeerIdentity(workspaceId: String, peerId: String): SqliteSignalProtocolStore {
        val store = peerStore(workspaceId, peerId)
        try {
            store.localRegistrationId
            store.identityKeyPair
        } catch (_: Exception) {
            generateIdentity(store)
        }
        return store
    }

    private fun generateIdentity(store: SqliteSignalProtocolStore): Pair<Int, String> {
        val identityKeyPair = KeyHelper.generateIdentityKeyPair()
        val registrationId = KeyHelper.generateRegistrationId(false)
        val preKeys = KeyHelper.generatePreKeys(1, 100)
        val signedPreKey = KeyHelper.generateSignedPreKey(identityKeyPair, 1)

        store.saveIdentityKeyPair(registrationId, identityKeyPair)
        for (preKey in preKeys) {
            store.storePreKey(preKey.id, preKey)
        }
        store.storeSignedPreKey(signedPreKey.id, signedPreKey)

        return registrationId to Base64.getEncoder().encodeToString(identityKeyPair.publicKey.serialize())
    }

    /**
     * Exports the local party's PreKeyBundle to be exchanged with remote peers.
     */
    fun exportBundle(workspaceId: String): ExportedPreKeyBundle {
        setupIdentity(workspaceId)
        return buildBundle(localStore(workspaceId))
    }

    /**
     * Builds the PreKeyBundle that the given peer would advertise.
     */
    private fun peerBundle(workspaceId: String, peerId: String): ExportedPreKeyBundle =
        buildBundle(ensurePeerIdentity(workspaceId, peerId))

    private fun buildBundle(store: SqliteSignalProtocolStore): ExportedPreKeyBundle {
        val identityKey = store.identityKeyPair.publicKey
        val regId = store.localRegistrationId
        val signedPreKey = store.loadSignedPreKey(1)
        val preKey = store.loadPreKey(1)

        return ExportedPreKeyBundle(
            registrationId = regId,
            deviceId = 1,
            identityKeyB64 = Base64.getEncoder().encodeToString(identityKey.serialize()),
            signedPreKeyId = signedPreKey.id,
            signedPreKeyB64 = Base64.getEncoder().encodeToString(signedPreKey.keyPair.publicKey.serialize()),
            signedPreKeySignatureB64 = Base64.getEncoder().encodeToString(signedPreKey.signature),
            preKeyId = preKey.id,
            preKeyB64 = Base64.getEncoder().encodeToString(preKey.keyPair.publicKey.serialize())
        )
    }

    /**
     * Establishes a local session with a remote peer by processing their PreKeyBundle
     * via the official SessionBuilder.
     */
    fun processPreKeyBundle(
        workspaceId: String,
        peerId: String,
        deviceId: Int = 1,
        bundle: ExportedPreKeyBundle
    ) {
        val store = localStore(workspaceId)
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            bundle.preKeyId,
            IdentityKey(Base64.getDecoder().decode(bundle.preKeyB64), 0).publicKey,
            bundle.signedPreKeyId,
            IdentityKey(Base64.getDecoder().decode(bundle.signedPreKeyB64), 0).publicKey,
            Base64.getDecoder().decode(bundle.signedPreKeySignatureB64),
            IdentityKey(Base64.getDecoder().decode(bundle.identityKeyB64), 0)
        )
        SessionBuilder(store, address(peerId, deviceId)).process(preKeyBundle)
    }

    /**
     * Ensures the local party holds a session with the peer, seeding it from the
     * peer's advertised bundle on first use.
     */
    private fun ensureLocalSession(workspaceId: String, peerId: String, deviceId: Int) {
        val store = localStore(workspaceId)
        if (!store.containsSession(address(peerId, deviceId))) {
            processPreKeyBundle(workspaceId, peerId, deviceId, peerBundle(workspaceId, peerId))
        }
    }

    /**
     * Encrypts arbitrary data using the official SessionCipher (Double Ratchet).
     */
    fun encrypt(
        workspaceId: String,
        peerId: String,
        deviceId: Int = 1,
        plaintext: String
    ): EncryptedEnvelope {
        setupIdentity(workspaceId)
        ensureLocalSession(workspaceId, peerId, deviceId)

        val cipher = SessionCipher(localStore(workspaceId), address(peerId, deviceId))
        val ciphertextMessage = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))

        return EncryptedEnvelope(
            type = ciphertextMessage.type,
            address = peerId,
            deviceId = deviceId,
            ciphertextB64 = Base64.getEncoder().encodeToString(ciphertextMessage.serialize())
        )
    }

    /**
     * Decrypts an incoming message envelope on the peer's side using the official
     * SessionCipher. PreKey messages establish the peer's session implicitly.
     */
    fun decrypt(
        workspaceId: String,
        peerId: String,
        deviceId: Int = 1,
        ciphertextB64: String,
        messageType: Int = 3
    ): String {
        setupIdentity(workspaceId)
        ensurePeerIdentity(workspaceId, peerId)

        val peerCipher = SessionCipher(peerStore(workspaceId, peerId), address(workspaceId, deviceId))
        val rawBytes = Base64.getDecoder().decode(ciphertextB64)

        val decryptedBytes = if (messageType == 3) {
            peerCipher.decrypt(PreKeySignalMessage(rawBytes))
        } else {
            peerCipher.decrypt(SignalMessage(rawBytes))
        }

        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Computes the official 60-digit Signal Safety Number (Fingerprint) between the
     * local party and the peer.
     */
    fun getSafetyNumber(workspaceId: String, peerId: String, deviceId: Int = 1): String {
        setupIdentity(workspaceId)
        ensureLocalSession(workspaceId, peerId, deviceId)

        val store = localStore(workspaceId)
        val localIdentity = store.identityKeyPair.publicKey
        val remoteIdentity = store.getIdentity(address(peerId, deviceId)) ?: localIdentity

        val generator = NumericFingerprintGenerator(5200) // 5200 iterations matching official Signal standard
        val fingerprint = generator.createFor(
            0,
            store.localRegistrationId.toString().toByteArray(Charsets.UTF_8),
            localIdentity,
            peerId.toByteArray(Charsets.UTF_8),
            remoteIdentity
        )

        return fingerprint.displayableFingerprint.displayText
    }

    fun hasSession(workspaceId: String, peerId: String, deviceId: Int = 1): Boolean =
        localStore(workspaceId).containsSession(address(peerId, deviceId))

    /**
     * Ensures a session exists with the peer and returns a telemetry snapshot.
     */
    fun createOrGetSession(workspaceId: String, peerId: String, deviceId: Int = 1): SignalSession {
        setupIdentity(workspaceId)
        ensureLocalSession(workspaceId, peerId, deviceId)

        val fingerprint = getSafetyNumber(workspaceId, peerId, deviceId)
        val sessionId = "sig_sess_" + java.util.UUID.nameUUIDFromBytes(
            "$workspaceId:$peerId:$deviceId".toByteArray(Charsets.UTF_8)
        ).toString().take(12)

        return SignalSession(
            sessionId = sessionId,
            peerId = peerId,
            deviceId = deviceId,
            fingerprint = fingerprint,
            isVerified = isSessionVerified(workspaceId, peerId, deviceId),
            sendCounter = 0L,
            recvCounter = 0L
        )
    }

    /**
     * Marks a peer session as verified (or unverified) after a safety-number comparison.
     * Verification state is persisted with the safety number it was granted for, so a peer
     * that later rotates its identity key stops being reported as verified.
     */
    fun setSessionVerified(workspaceId: String, peerId: String, verified: Boolean, deviceId: Int = 1) {
        val store = localStore(workspaceId)
        val addr = address(peerId, deviceId)
        val safetyNumber = getSafetyNumber(workspaceId, peerId, deviceId)
        if (verified) {
            store.saveVerification(addr, safetyNumber, verified = true)
        } else {
            store.deleteVerification(addr)
        }
    }

    fun isSessionVerified(workspaceId: String, peerId: String, deviceId: Int = 1): Boolean {
        val store = localStore(workspaceId)
        val record = store.loadVerification(address(peerId, deviceId)) ?: return false
        // A verification only covers the exact safety number it was granted for.
        return record.verified && record.safetyNumber == getSafetyNumber(workspaceId, peerId, deviceId)
    }

    /**
     * Compares a user-supplied safety number against the peer's current one and records the
     * outcome. Returns the stored verification snapshot.
     */
    fun verifyIdentity(
        workspaceId: String,
        peerId: String,
        providedSafetyNumber: String,
        deviceId: Int = 1
    ): SignalVerification {
        setupIdentity(workspaceId)
        ensureLocalSession(workspaceId, peerId, deviceId)

        val expected = getSafetyNumber(workspaceId, peerId, deviceId)
        val matches = normalizeSafetyNumber(providedSafetyNumber) == normalizeSafetyNumber(expected)
        val store = localStore(workspaceId)
        val addr = address(peerId, deviceId)
        val previous = store.loadVerification(addr)
        store.saveVerification(addr, expected, matches)

        // A peer that was verified before but now presents a different safety number is a
        // key change, not a typo — surfacing it separately is what makes the warning useful.
        val status = when {
            matches -> "VERIFIED"
            previous?.verified == true &&
                normalizeSafetyNumber(previous.safetyNumber) != normalizeSafetyNumber(expected) -> "KEY_CHANGED"
            else -> "MISMATCH"
        }

        return SignalVerification(
            peerId = peerId,
            deviceId = deviceId,
            safetyNumber = expected,
            verified = matches,
            status = status
        )
    }

    /** Current verification state without mutating it. */
    fun verificationStatus(workspaceId: String, peerId: String, deviceId: Int = 1): SignalVerification {
        val expected = getSafetyNumber(workspaceId, peerId, deviceId)
        val record = localStore(workspaceId).loadVerification(address(peerId, deviceId))
        val verified = record != null && record.verified &&
            normalizeSafetyNumber(record.safetyNumber) == normalizeSafetyNumber(expected)
        return SignalVerification(
            peerId = peerId,
            deviceId = deviceId,
            safetyNumber = expected,
            verified = verified,
            status = when {
                verified -> "VERIFIED"
                record == null -> "UNVERIFIED"
                record.verified -> "KEY_CHANGED"
                else -> "MISMATCH"
            }
        )
    }

    /**
     * Safety numbers are grouped and may be compared in either grouping or case, so separators
     * and case are ignored. Letters are kept: Signal's numeric fingerprint contains none, but
     * dropping them would let a hex-formatted input silently match a truncated prefix.
     */
    private fun normalizeSafetyNumber(value: String) =
        value.filter { it.isLetterOrDigit() }.lowercase()

    fun resetSession(workspaceId: String, peerId: String, deviceId: Int = 1) {
        val store = localStore(workspaceId)
        val addr = address(peerId, deviceId)
        store.deleteSession(addr)
        store.deleteVerification(addr)
    }
}
