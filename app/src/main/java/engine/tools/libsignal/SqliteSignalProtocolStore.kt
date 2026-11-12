package engine.tools.libsignal

import app.cash.sqldelight.db.SqlDriver
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.IdentityKeyStore
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignalProtocolStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import java.util.Base64

/**
 * Official Signal Protocol persistence interface backed by the tenant's SQLite sandbox.
 * Persists IdentityKeyPair, PreKeys, SignedPreKeys, and active SessionRecords.
 */
class SqliteSignalProtocolStore(
    private val driver: SqlDriver,
    private val workspaceId: String
) : SignalProtocolStore {

    init {
        ensureSchema()
    }

    private fun ensureSchema() {
        // AndroidSqliteDriver compiles one statement per executeUpdate call, so
        // each table must be issued separately.
        val statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS signal_identities (
                tenant_id TEXT PRIMARY KEY,
                registration_id INTEGER NOT NULL,
                identity_key_pair BLOB NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS signal_trusted_keys (
                tenant_id TEXT NOT NULL,
                name TEXT NOT NULL,
                identity_key BLOB NOT NULL,
                PRIMARY KEY (tenant_id, name)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS signal_prekeys (
                tenant_id TEXT NOT NULL,
                prekey_id INTEGER NOT NULL,
                record_bytes BLOB NOT NULL,
                PRIMARY KEY (tenant_id, prekey_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS signal_signed_prekeys (
                tenant_id TEXT NOT NULL,
                prekey_id INTEGER NOT NULL,
                record_bytes BLOB NOT NULL,
                PRIMARY KEY (tenant_id, prekey_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS signal_sessions (
                tenant_id TEXT NOT NULL,
                address_name TEXT NOT NULL,
                device_id INTEGER NOT NULL,
                record_bytes BLOB NOT NULL,
                PRIMARY KEY (tenant_id, address_name, device_id)
            )
            """
        )
        statements.forEach { driver.executeUpdate(null, it.trimIndent(), 0) }
    }

    // -------------------------------------------------------------
    // IdentityKeyStore
    // -------------------------------------------------------------

    override fun getIdentityKeyPair(): IdentityKeyPair {
        var keyPair: IdentityKeyPair? = null
        driver.executeQuery(
            null,
            "SELECT identity_key_pair FROM signal_identities WHERE tenant_id = ?",
            1
        ) {
            bindString(0, workspaceId)
        }.use { c ->
            if (c.next().value) {
                c.getString(0)?.let { b64 ->
                    keyPair = IdentityKeyPair(Base64.getDecoder().decode(b64))
                }
            }
        }
        return keyPair ?: error("Identity key pair not initialized for workspace $workspaceId")
    }

    override fun getLocalRegistrationId(): Int {
        var regId: Int? = null
        driver.executeQuery(
            null,
            "SELECT registration_id FROM signal_identities WHERE tenant_id = ?",
            1
        ) {
            bindString(0, workspaceId)
        }.use { c ->
            if (c.next().value) {
                regId = c.getLong(0)?.toInt()
            }
        }
        return regId ?: error("Registration ID not initialized for workspace $workspaceId")
    }

    fun saveIdentityKeyPair(registrationId: Int, keyPair: IdentityKeyPair) {
        val b64 = Base64.getEncoder().encodeToString(keyPair.serialize())
        driver.executeUpdate(
            null,
            "INSERT OR REPLACE INTO signal_identities (tenant_id, registration_id, identity_key_pair) VALUES (?, ?, ?)",
            3
        ) {
            bindString(0, workspaceId)
            bindLong(1, registrationId.toLong())
            bindString(2, b64)
        }
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val b64 = Base64.getEncoder().encodeToString(identityKey.serialize())
        var existing: String? = null
        driver.executeQuery(
            null,
            "SELECT identity_key FROM signal_trusted_keys WHERE tenant_id = ? AND name = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
        }.use { c ->
            if (c.next().value) existing = c.getString(0)
        }

        driver.executeUpdate(
            null,
            "INSERT OR REPLACE INTO signal_trusted_keys (tenant_id, name, identity_key) VALUES (?, ?, ?)",
            3
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
            bindString(2, b64)
        }
        return existing != null && existing != b64
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        var stored: IdentityKey? = null
        driver.executeQuery(
            null,
            "SELECT identity_key FROM signal_trusted_keys WHERE tenant_id = ? AND name = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
        }.use { c ->
            if (c.next().value) {
                c.getString(0)?.let { b64 ->
                    stored = IdentityKey(Base64.getDecoder().decode(b64), 0)
                }
            }
        }
        return stored == null || stored == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        var key: IdentityKey? = null
        driver.executeQuery(
            null,
            "SELECT identity_key FROM signal_trusted_keys WHERE tenant_id = ? AND name = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
        }.use { c ->
            if (c.next().value) {
                c.getString(0)?.let { b64 ->
                    key = IdentityKey(Base64.getDecoder().decode(b64), 0)
                }
            }
        }
        return key
    }

    // -------------------------------------------------------------
    // SessionStore
    // -------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        var record: SessionRecord? = null
        driver.executeQuery(
            null,
            "SELECT record_bytes FROM signal_sessions WHERE tenant_id = ? AND address_name = ? AND device_id = ?",
            3
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
            bindLong(2, address.deviceId.toLong())
        }.use { c ->
            if (c.next().value) {
                c.getString(0)?.let { b64 ->
                    record = SessionRecord(Base64.getDecoder().decode(b64))
                }
            }
        }
        return record ?: SessionRecord()
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        val list = mutableListOf<Int>()
        driver.executeQuery(
            null,
            "SELECT device_id FROM signal_sessions WHERE tenant_id = ? AND address_name = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindString(1, name)
        }.use { c ->
            while (c.next().value) {
                c.getLong(0)?.toInt()?.let { list.add(it) }
            }
        }
        return list
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val b64 = Base64.getEncoder().encodeToString(record.serialize())
        driver.executeUpdate(
            null,
            "INSERT OR REPLACE INTO signal_sessions (tenant_id, address_name, device_id, record_bytes) VALUES (?, ?, ?, ?)",
            4
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
            bindLong(2, address.deviceId.toLong())
            bindString(3, b64)
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        var exists = false
        driver.executeQuery(
            null,
            "SELECT 1 FROM signal_sessions WHERE tenant_id = ? AND address_name = ? AND device_id = ? LIMIT 1",
            3
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
            bindLong(2, address.deviceId.toLong())
        }.use { c ->
            exists = c.next().value
        }
        return exists
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        driver.executeUpdate(
            null,
            "DELETE FROM signal_sessions WHERE tenant_id = ? AND address_name = ? AND device_id = ?",
            3
        ) {
            bindString(0, workspaceId)
            bindString(1, address.name)
            bindLong(2, address.deviceId.toLong())
        }
    }

    override fun deleteAllSessions(name: String) {
        driver.executeUpdate(
            null,
            "DELETE FROM signal_sessions WHERE tenant_id = ? AND address_name = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindString(1, name)
        }
    }

    // -------------------------------------------------------------
    // PreKeyStore
    // -------------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        var record: PreKeyRecord? = null
        driver.executeQuery(
            null,
            "SELECT record_bytes FROM signal_prekeys WHERE tenant_id = ? AND prekey_id = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindLong(1, preKeyId.toLong())
        }.use { c ->
            if (c.next().value) {
                c.getString(0)?.let { b64 ->
                    record = PreKeyRecord(Base64.getDecoder().decode(b64))
                }
            }
        }
        return record ?: error("PreKey #$preKeyId not found")
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        val b64 = Base64.getEncoder().encodeToString(record.serialize())
        driver.executeUpdate(
            null,
            "INSERT OR REPLACE INTO signal_prekeys (tenant_id, prekey_id, record_bytes) VALUES (?, ?, ?)",
            3
        ) {
            bindString(0, workspaceId)
            bindLong(1, preKeyId.toLong())
            bindString(2, b64)
        }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        var exists = false
        driver.executeQuery(
            null,
            "SELECT 1 FROM signal_prekeys WHERE tenant_id = ? AND prekey_id = ? LIMIT 1",
            2
        ) {
            bindString(0, workspaceId)
            bindLong(1, preKeyId.toLong())
        }.use { c -> exists = c.next().value }
        return exists
    }

    override fun removePreKey(preKeyId: Int) {
        driver.executeUpdate(
            null,
            "DELETE FROM signal_prekeys WHERE tenant_id = ? AND prekey_id = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindLong(1, preKeyId.toLong())
        }
    }

    // -------------------------------------------------------------
    // SignedPreKeyStore
    // -------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        var record: SignedPreKeyRecord? = null
        driver.executeQuery(
            null,
            "SELECT record_bytes FROM signal_signed_prekeys WHERE tenant_id = ? AND prekey_id = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindLong(1, signedPreKeyId.toLong())
        }.use { c ->
            if (c.next().value) {
                c.getString(0)?.let { b64 ->
                    record = SignedPreKeyRecord(Base64.getDecoder().decode(b64))
                }
            }
        }
        return record ?: error("SignedPreKey #$signedPreKeyId not found")
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        val list = mutableListOf<SignedPreKeyRecord>()
        driver.executeQuery(
            null,
            "SELECT record_bytes FROM signal_signed_prekeys WHERE tenant_id = ?",
            1
        ) {
            bindString(0, workspaceId)
        }.use { c ->
            while (c.next().value) {
                c.getString(0)?.let { b64 ->
                    list.add(SignedPreKeyRecord(Base64.getDecoder().decode(b64)))
                }
            }
        }
        return list
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        val b64 = Base64.getEncoder().encodeToString(record.serialize())
        driver.executeUpdate(
            null,
            "INSERT OR REPLACE INTO signal_signed_prekeys (tenant_id, prekey_id, record_bytes) VALUES (?, ?, ?)",
            3
        ) {
            bindString(0, workspaceId)
            bindLong(1, signedPreKeyId.toLong())
            bindString(2, b64)
        }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        var exists = false
        driver.executeQuery(
            null,
            "SELECT 1 FROM signal_signed_prekeys WHERE tenant_id = ? AND prekey_id = ? LIMIT 1",
            2
        ) {
            bindString(0, workspaceId)
            bindLong(1, signedPreKeyId.toLong())
        }.use { c -> exists = c.next().value }
        return exists
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        driver.executeUpdate(
            null,
            "DELETE FROM signal_signed_prekeys WHERE tenant_id = ? AND prekey_id = ?",
            2
        ) {
            bindString(0, workspaceId)
            bindLong(1, signedPreKeyId.toLong())
        }
    }
}