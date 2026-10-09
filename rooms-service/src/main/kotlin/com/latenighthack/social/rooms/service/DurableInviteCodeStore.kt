package com.latenighthack.social.rooms.service

import com.latenighthack.ktstore.*
import java.io.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.*

internal data class InviteRow(val id: ByteArray, val encrypted: ByteArray) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
        out.writeInt(1); out.writeInt(id.size); out.write(id); out.writeInt(encrypted.size); out.write(encrypted)
    } }.toByteArray()
    companion object {
        fun decode(bytes: ByteArray): InviteRow = DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readInt() == 1)
            fun field(): ByteArray { val size = it.readInt(); require(size in 1..8192); return ByteArray(size).also(it::readFully) }
            InviteRow(field(), field()).also { _ -> require(it.available() == 0) }
        }
    }
}
internal object InviteDefinition : StoreDefinition<InviteRow>(StoreName("social_invite_codes"), "invite-envelope-v1", InviteRow::decode, InviteRow::encode) {
    val id = bytesIndex(IndexName("id"), InviteRow::id, "sha256-v1").also { primaryKey(it) }
}
object RoomsStorage { val definitions: List<StoreDefinition<*>> = listOf(InviteDefinition) }

/** The entire invitation and remaining-use count are authenticated ciphertext.
 * The key is separated from login custody and AAD binds ciphertext to its code.
 */
class DurableInviteCodeStore(private val database: Database, masterKey: ByteArray, private val clock: () -> Long = System::currentTimeMillis) : InviteCodeStore {
    private class Rows(database: Database) : Store<InviteRow>(database, InviteDefinition) {
        suspend fun put(row: InviteRow) = save(row)
        suspend fun find(id: ByteArray) = get(InviteDefinition.id.eq(id))
        suspend fun remove(id: ByteArray) = delete(InviteDefinition.id.eq(id))
    }
    private val rows = Rows(database)
    private val key = SecretKeySpec(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(masterKey, "HmacSHA256")) }
        .doFinal("social.invitation.encryption.v1".toByteArray()), "AES")
    private val random = SecureRandom()
    init { require(masterKey.size == 32) }
    private fun id(code: ByteArray) = MessageDigest.getInstance("SHA-256").digest(code)
    private fun lock(id: ByteArray) = "social.invite." + java.util.Base64.getEncoder().encodeToString(id)
    private fun seal(id: ByteArray, record: StoredInviteCode, remaining: Long): InviteRow {
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            fun field(value: ByteArray) { require(value.size <= 4096); out.writeInt(value.size); out.write(value) }
            field(record.roomId); field(record.groupPrivateKey); field(record.allowedProfileId)
            out.writeLong(record.expiryMillis); out.writeLong(record.maxUses); out.writeLong(remaining)
        } }.toByteArray()
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); cipher.updateAAD(id)
        return InviteRow(id, nonce + cipher.doFinal(bytes))
    }
    private fun open(row: InviteRow): Pair<StoredInviteCode, Long> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, row.encrypted.copyOfRange(0, 12))); cipher.updateAAD(row.id)
        return DataInputStream(ByteArrayInputStream(cipher.doFinal(row.encrypted.copyOfRange(12, row.encrypted.size)))).use { input ->
            fun field(): ByteArray { val size = input.readInt(); require(size in 0..4096); return ByteArray(size).also(input::readFully) }
            val room = field(); val privateKey = field(); val profile = field()
            StoredInviteCode(room, privateKey, input.readLong(), input.readLong(), profile) to input.readLong()
        }
    }
    override suspend fun put(code: ByteArray, record: StoredInviteCode) {
        val id = id(code)
        database.transaction(lock(id)) { rows.put(seal(id, record, record.maxUses)) }
    }
    override suspend fun get(code: ByteArray): StoredInviteCode? {
        val row = rows.find(id(code)) ?: return null
        return open(row).first.takeIf { it.expiryMillis == 0L || it.expiryMillis > clock() }
    }
    override suspend fun delete(code: ByteArray) {
        val id = id(code)
        database.transaction(lock(id)) { rows.remove(id) }
    }
    override suspend fun consumeUse(code: ByteArray): Boolean {
        val id = id(code)
        return database.transaction(lock(id)) {
            val row = rows.find(id) ?: return@transaction false
            val (record, remaining) = open(row)
            if ((record.expiryMillis != 0L && record.expiryMillis <= clock()) || remaining <= 0) return@transaction false
            rows.put(seal(id, record, remaining - 1))
            true
        }
    }
}
