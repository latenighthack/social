package com.latenighthack.social.rooms.service

import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.ECDH
import com.latenighthack.ktcrypto.Secp256r1
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.generate
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.common.domain.Sealing
import com.latenighthack.social.rooms.v1.CodePolicy
import com.latenighthack.social.rooms.v1.CreateInviteCodeRequest
import com.latenighthack.social.rooms.v1.Invite
import com.latenighthack.social.rooms.v1.JoinRequest
import com.latenighthack.social.rooms.v1.JoinResult
import com.latenighthack.social.rooms.v1.JoinServer
import com.latenighthack.social.rooms.v1.JoinServiceRpc
import com.latenighthack.social.rooms.v1.RevokeInviteCodeRequest
import com.latenighthack.social.rooms.v1.RoomKind
import com.latenighthack.social.rooms.v1.fromByteArray
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Boots just the Join gRPC service over a fresh in-memory invite-code store.
suspend fun Application.attachJoin() {
    val service = JoinServiceImpl(InMemoryInviteCodeStore())
    routing {
        serveAll(service, JoinServer.Descriptor)
    }
}

class JoinServiceIntegrationTest {

    @Test
    fun `create then join returns a group grant sealed to the joiner`() =
        runTestWithServer(Application::attachJoin) { server, _ ->
            val rpc = JoinServiceRpc(server.rpcClient)
            val group = Secp256r1KeyPair.generate()
            val roomId = RoomKeying.publicKeyed(group.publicKey.encode()).rawValue
            val profile = Secp256r1KeyPair.generate()
            val profileId = profile.publicKey.encode()

            val created = rpc.createInviteCode(CreateInviteCodeRequest {
                this.roomId = roomId
                groupPrivateKey = group.privateKey.encode()
            })
            assertEquals(JoinResult.JOIN_RESULT_OK, created.result)
            val code = assertNotNull(created.code)
            assertTrue(code.value.isNotEmpty())

            val joined = rpc.join(JoinRequest {
                this.code = code
                inviteeProfileId = profileId
            })
            assertEquals(JoinResult.JOIN_RESULT_OK, joined.result)
            val sealed = assertNotNull(joined.sealedInvite)

            // Only the joiner's profile key can unseal the grant.
            val ephemeral = Secp256r1PublicKey.decode(sealed.ephemeralPublicKey)
            val shared = Secp256r1.ECDH.sharedSecret(profile.privateKey, ephemeral)
            val invite = Invite.fromByteArray(assertNotNull(Sealing.unsealWith(shared, sealed)))
            assertEquals(RoomKind.ROOM_KIND_GROUP, invite.kind)
            assertTrue(invite.roomId.contentEquals(roomId))
            assertTrue(invite.groupPrivateKey.contentEquals(group.privateKey.encode()))
        }

    @Test
    fun `creating a code with a key that is not the room's is unauthorized`() =
        runTestWithServer(Application::attachJoin) { server, _ ->
            val rpc = JoinServiceRpc(server.rpcClient)
            val group = Secp256r1KeyPair.generate()
            val roomId = RoomKeying.publicKeyed(group.publicKey.encode()).rawValue
            val impostor = Secp256r1KeyPair.generate()

            val created = rpc.createInviteCode(CreateInviteCodeRequest {
                this.roomId = roomId
                groupPrivateKey = impostor.privateKey.encode()
            })
            assertEquals(JoinResult.JOIN_RESULT_UNAUTHORIZED, created.result)
        }

    @Test
    fun `a revoked code can no longer be joined`() =
        runTestWithServer(Application::attachJoin) { server, _ ->
            val rpc = JoinServiceRpc(server.rpcClient)
            val group = Secp256r1KeyPair.generate()
            val roomId = RoomKeying.publicKeyed(group.publicKey.encode()).rawValue
            val profileId = Secp256r1KeyPair.generate().publicKey.encode()

            val created = rpc.createInviteCode(CreateInviteCodeRequest {
                this.roomId = roomId
                groupPrivateKey = group.privateKey.encode()
            })
            val revoked = rpc.revokeInviteCode(RevokeInviteCodeRequest {
                code = created.code
                groupPrivateKey = group.privateKey.encode()
            })
            assertEquals(JoinResult.JOIN_RESULT_OK, revoked.result)

            val joined = rpc.join(JoinRequest {
                code = created.code
                inviteeProfileId = profileId
            })
            assertEquals(JoinResult.JOIN_RESULT_INVALID_CODE, joined.result)
        }

    @Test
    fun `a use-limited code is exhausted after its uses are spent`() =
        runTestWithServer(Application::attachJoin) { server, _ ->
            val rpc = JoinServiceRpc(server.rpcClient)
            val group = Secp256r1KeyPair.generate()
            val roomId = RoomKeying.publicKeyed(group.publicKey.encode()).rawValue
            val profileId = Secp256r1KeyPair.generate().publicKey.encode()

            val created = rpc.createInviteCode(CreateInviteCodeRequest {
                this.roomId = roomId
                groupPrivateKey = group.privateKey.encode()
                policy = CodePolicy { maxUses = 1 }
            })
            val first = rpc.join(JoinRequest { code = created.code; inviteeProfileId = profileId })
            assertEquals(JoinResult.JOIN_RESULT_OK, first.result)
            val second = rpc.join(JoinRequest { code = created.code; inviteeProfileId = profileId })
            assertEquals(JoinResult.JOIN_RESULT_EXHAUSTED, second.result)
        }

    @Test
    fun `an expired code is rejected`() =
        runTestWithServer(Application::attachJoin) { server, _ ->
            val rpc = JoinServiceRpc(server.rpcClient)
            val group = Secp256r1KeyPair.generate()
            val roomId = RoomKeying.publicKeyed(group.publicKey.encode()).rawValue
            val profileId = Secp256r1KeyPair.generate().publicKey.encode()

            val created = rpc.createInviteCode(CreateInviteCodeRequest {
                this.roomId = roomId
                groupPrivateKey = group.privateKey.encode()
                policy = CodePolicy { expiryMillis = 1L } // epoch 1ms — far in the past
            })
            val joined = rpc.join(JoinRequest { code = created.code; inviteeProfileId = profileId })
            assertEquals(JoinResult.JOIN_RESULT_EXPIRED, joined.result)
        }

    @Test
    fun `a profile-restricted code rejects any other profile`() =
        runTestWithServer(Application::attachJoin) { server, _ ->
            val rpc = JoinServiceRpc(server.rpcClient)
            val group = Secp256r1KeyPair.generate()
            val roomId = RoomKeying.publicKeyed(group.publicKey.encode()).rawValue
            val allowed = Secp256r1KeyPair.generate().publicKey.encode()
            val other = Secp256r1KeyPair.generate().publicKey.encode()

            val created = rpc.createInviteCode(CreateInviteCodeRequest {
                this.roomId = roomId
                groupPrivateKey = group.privateKey.encode()
                policy = CodePolicy { allowedProfileId = allowed }
            })
            val rejected = rpc.join(JoinRequest { code = created.code; inviteeProfileId = other })
            assertEquals(JoinResult.JOIN_RESULT_NOT_ALLOWED, rejected.result)
            val admitted = rpc.join(JoinRequest { code = created.code; inviteeProfileId = allowed })
            assertEquals(JoinResult.JOIN_RESULT_OK, admitted.result)
        }
}
