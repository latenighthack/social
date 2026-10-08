package com.latenighthack.social.rooms.service

import com.latenighthack.ktstore.*
import com.latenighthack.social.rooms.v1.*
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object InviteCodeDefinitionV1 : StoreDefinition<InviteCodeRecord>(StoreName("social_invite_codes"),
    "InviteCodeRecord-protobuf-v1", InviteCodeRecord.Companion::fromByteArray, InviteCodeRecord::toByteArray) {
    val lookup = bytesIndex(IndexName("lookupKey"), InviteCodeRecord::lookupKey, "sha256-code-v1").also { primaryKey(it) }
}
object RoomsServiceStorage {
    val definitions: List<StoreDefinition<*>> = listOf(InviteCodeDefinitionV1)
    fun configuration(identity: String) = definitionDatabaseConfiguration(identity, definitions)
    /** Explicit host migration for a database that previously omitted the invite-code table. */
    fun upgrade(previous: DatabaseConfiguration): DatabaseConfiguration {
        require(previous.stores.none { it.name == InviteCodeDefinitionV1.storeName })
        val target = previous.stores + InviteCodeDefinitionV1.declaration
        val step = DatabaseMigration.configured(previous.version, previous.version + 1, previous.stores, target) {
            createStore(InviteCodeDefinitionV1.declaration)
        }
        return previous.copy(version = previous.version + 1, stores = target, migrations = previous.migrations + step)
    }
}

/** Host-owned shared database; the ciphertext includes policy and remaining uses as well as the key. */
class DurableInviteCodeStore(private val database: Database, masterKey: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis) : InviteCodeStore {
    private val key = SecretKeySpec(masterKey.copyOf().also { require(it.size == 32) }, "AES")
    private val rows = Rows(database)
    private val random = SecureRandom()
    private class Rows(database: Database) : Store<InviteCodeRecord>(database, InviteCodeDefinitionV1) {
        suspend fun lookup(id: ByteArray) = get(InviteCodeDefinitionV1.lookup.eq(id))
        suspend fun put(record: InviteCodeRecord) = save(record)
        suspend fun remove(id: ByteArray) = delete(InviteCodeDefinitionV1.lookup.eq(id))
    }
    suspend fun prepare() = rows.prepare()
    private fun lookup(code: ByteArray) = MessageDigest.getInstance("SHA-256").digest(code)
    private fun decode(row: InviteCodeRecord): InviteCodeSecret {
        require(row.ciphertext.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, row.ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD("social/invite-code/v1".encodeToByteArray() + row.lookupKey)
        return InviteCodeSecret.fromByteArray(cipher.doFinal(row.ciphertext.copyOfRange(12, row.ciphertext.size)))
    }
    private fun encode(id: ByteArray, secret: InviteCodeSecret): InviteCodeRecord {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD("social/invite-code/v1".encodeToByteArray() + id)
        return InviteCodeRecord(lookupKey = id, ciphertext = nonce + cipher.doFinal(secret.toByteArray()))
    }
    override suspend fun put(code: ByteArray, record: StoredInviteCode) = database.transaction("social.invite.codes") {
        val id = lookup(code)
        check(rows.lookup(id) == null) { "invite codes are immutable; mint a fresh code" }
        rows.put(encode(id, InviteCodeSecret(roomId = record.roomId, groupPrivateKey = record.groupPrivateKey,
            expiryMillis = record.expiryMillis, maxUses = record.maxUses, allowedProfileId = record.allowedProfileId,
            remainingUses = record.maxUses)))
    }
    override suspend fun get(code: ByteArray): StoredInviteCode? {
        val secret = rows.lookup(lookup(code))?.let(::decode) ?: return null
        if (secret.expiryMillis != 0L && secret.expiryMillis <= clock()) return null
        return StoredInviteCode(secret.roomId, secret.groupPrivateKey, secret.expiryMillis, secret.maxUses, secret.allowedProfileId)
    }
    override suspend fun delete(code: ByteArray) = database.transaction("social.invite.codes") { rows.remove(lookup(code)) }
    override suspend fun consumeUse(code: ByteArray): Boolean = database.transaction("social.invite.codes") {
        val id = lookup(code)
        val secret = rows.lookup(id)?.let(::decode) ?: return@transaction false
        if (secret.remainingUses <= 0 || (secret.expiryMillis != 0L && secret.expiryMillis <= clock())) return@transaction false
        rows.put(encode(id, secret.copy(remainingUses = secret.remainingUses - 1)))
        true
    }
}
