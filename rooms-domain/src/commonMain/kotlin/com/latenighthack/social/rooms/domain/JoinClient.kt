package com.latenighthack.social.rooms.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.social.rooms.v1.CreateInviteCodeRequest
import com.latenighthack.social.rooms.v1.CreateInviteCodeResponse
import com.latenighthack.social.rooms.v1.JoinRequest
import com.latenighthack.social.rooms.v1.JoinResponse
import com.latenighthack.social.rooms.v1.JoinServiceRpc
import com.latenighthack.social.rooms.v1.RevokeInviteCodeRequest
import com.latenighthack.social.rooms.v1.RevokeInviteCodeResponse

/**
 * Thin client for the server-side Join service (group-room invite codes). It hides the gRPC
 * transport so [RoomsManagerImpl] deals only in request/response protos — and so tests can supply an
 * in-process fake instead of a running server.
 */
interface JoinClient {
    suspend fun createInviteCode(request: CreateInviteCodeRequest): CreateInviteCodeResponse

    suspend fun join(request: JoinRequest): JoinResponse

    suspend fun revokeInviteCode(request: RevokeInviteCodeRequest): RevokeInviteCodeResponse
}

class JoinClientImpl(rpcClient: RpcClient) : JoinClient {
    private val rpc = JoinServiceRpc(rpcClient)

    override suspend fun createInviteCode(request: CreateInviteCodeRequest): CreateInviteCodeResponse =
        rpc.createInviteCode(request)

    override suspend fun join(request: JoinRequest): JoinResponse = rpc.join(request)

    override suspend fun revokeInviteCode(request: RevokeInviteCodeRequest): RevokeInviteCodeResponse =
        rpc.revokeInviteCode(request)
}
