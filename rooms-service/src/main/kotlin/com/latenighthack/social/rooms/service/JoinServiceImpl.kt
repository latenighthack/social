package com.latenighthack.social.rooms.service

import com.latenighthack.ktbuf.net.GrpcRequestContext
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.fromPrivateKey
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.social.common.domain.Sealing
import com.latenighthack.social.rooms.v1.CreateInviteCodeRequest
import com.latenighthack.social.rooms.v1.CreateInviteCodeResponse
import com.latenighthack.social.rooms.v1.Invite
import com.latenighthack.social.rooms.v1.InviteCode
import com.latenighthack.social.rooms.v1.JoinRequest
import com.latenighthack.social.rooms.v1.JoinResponse
import com.latenighthack.social.rooms.v1.JoinResult
import com.latenighthack.social.rooms.v1.JoinServer
import com.latenighthack.social.rooms.v1.RevokeInviteCodeRequest
import com.latenighthack.social.rooms.v1.RevokeInviteCodeResponse
import com.latenighthack.social.rooms.v1.RoomKind
import com.latenighthack.social.rooms.v1.toByteArray
import java.security.SecureRandom

/**
 * The Join gRPC service: the server-side approver for group-room join codes.
 *
 * A member creates a code by presenting the room's shared private key — whoever holds it is already
 * a member, and the server checks it really belongs to the room ([keyMatchesRoom]) before retaining
 * it. A joiner redeems a code for an [Invite] (kind=GROUP, room id, group key) that is ECIES-sealed
 * to the joiner's own profile key, so an intercepted code is useless to anyone else. Revoking a code
 * deletes its record, stopping future joins (already-joined members are unaffected — full eviction
 * would need group-key rotation, which is out of scope).
 */
class JoinServiceImpl(
    private val store: InviteCodeStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : JoinServer {

    override suspend fun createInviteCode(
        context: GrpcRequestContext,
        request: CreateInviteCodeRequest,
    ): CreateInviteCodeResponse {
        if (!keyMatchesRoom(request.groupPrivateKey, request.roomId)) {
            return CreateInviteCodeResponse { result = JoinResult.JOIN_RESULT_UNAUTHORIZED }
        }
        val policy = request.policy
        val code = ByteArray(CODE_BYTES).also(random::nextBytes)
        store.put(
            code,
            StoredInviteCode(
                roomId = request.roomId,
                groupPrivateKey = request.groupPrivateKey,
                expiryMillis = policy?.expiryMillis ?: 0L,
                maxUses = policy?.maxUses ?: 0L,
                allowedProfileId = policy?.allowedProfileId ?: ByteArray(0),
            ),
        )
        return CreateInviteCodeResponse {
            result = JoinResult.JOIN_RESULT_OK
            this.code = InviteCode { value = code }
        }
    }

    override suspend fun join(context: GrpcRequestContext, request: JoinRequest): JoinResponse {
        val code = request.code?.value ?: return JoinResponse { result = JoinResult.JOIN_RESULT_INVALID_CODE }
        val record = store.get(code) ?: return JoinResponse { result = JoinResult.JOIN_RESULT_INVALID_CODE }

        if (record.expiryMillis != 0L && clock() >= record.expiryMillis) {
            return JoinResponse { result = JoinResult.JOIN_RESULT_EXPIRED }
        }
        if (record.allowedProfileId.isNotEmpty() && !record.allowedProfileId.contentEquals(request.inviteeProfileId)) {
            return JoinResponse { result = JoinResult.JOIN_RESULT_NOT_ALLOWED }
        }

        // Seal before consuming a use: a malformed profile key must not burn a use, and sealing an
        // already-exhausted code is harmless (the grant is never returned).
        val invite = Invite {
            kind = RoomKind.ROOM_KIND_GROUP
            roomId = record.roomId
            groupPrivateKey = record.groupPrivateKey
        }
        val sealed = try {
            Sealing.seal(request.inviteeProfileId, invite.toByteArray())
        } catch (e: Exception) {
            return JoinResponse { result = JoinResult.JOIN_RESULT_NOT_ALLOWED }
        }

        if (record.maxUses != 0L && !store.consumeUse(code)) {
            return JoinResponse { result = JoinResult.JOIN_RESULT_EXHAUSTED }
        }
        return JoinResponse {
            result = JoinResult.JOIN_RESULT_OK
            sealedInvite = sealed
        }
    }

    override suspend fun revokeInviteCode(
        context: GrpcRequestContext,
        request: RevokeInviteCodeRequest,
    ): RevokeInviteCodeResponse {
        val code = request.code?.value
            ?: return RevokeInviteCodeResponse { result = JoinResult.JOIN_RESULT_INVALID_CODE }
        val record = store.get(code)
            ?: return RevokeInviteCodeResponse { result = JoinResult.JOIN_RESULT_INVALID_CODE }
        if (!keyMatchesRoom(request.groupPrivateKey, record.roomId)) {
            return RevokeInviteCodeResponse { result = JoinResult.JOIN_RESULT_UNAUTHORIZED }
        }
        store.delete(code)
        return RevokeInviteCodeResponse { result = JoinResult.JOIN_RESULT_OK }
    }

    /** Whether [privateKey] is the shared key of the public-keyed room [roomId] (i.e. a member's key). */
    private suspend fun keyMatchesRoom(privateKey: ByteArray, roomId: ByteArray): Boolean {
        val keyPair = Secp256r1KeyPair.fromPrivateKey(privateKey) ?: return false
        val derivedRoom = RoomKeying.publicKeyed(keyPair.publicKey.encode())
        return derivedRoom.rawValue.contentEquals(roomId)
    }

    private companion object {
        const val CODE_BYTES = 32
    }
}
