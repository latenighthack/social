package com.latenighthack.social.login.core.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.social.login.v1.AuthenticateResponse
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.BindRequest
import com.latenighthack.social.login.v1.BindResponse
import com.latenighthack.social.login.v1.CompleteEmailLinkRequest
import com.latenighthack.social.login.v1.LoginServiceRpc
import com.latenighthack.social.login.v1.StartChallengeResponse
import com.latenighthack.social.login.v1.StartEmailLinkRequest
import com.latenighthack.social.login.v1.StartPhoneCodeRequest
import com.latenighthack.social.login.v1.VerifyPhoneCodeRequest

/**
 * Thin client for the custodial Login gRPC service. It hides the gRPC transport so the login use
 * cases deal only in request/response protos — and so tests can supply an in-process fake instead of
 * a running server. Mirrors rooms-domain's JoinClient.
 */
interface LoginClient {
    suspend fun authenticateSocial(request: AuthenticateSocialRequest): AuthenticateResponse

    suspend fun startEmailLink(request: StartEmailLinkRequest): StartChallengeResponse

    suspend fun completeEmailLink(request: CompleteEmailLinkRequest): AuthenticateResponse

    suspend fun startPhoneCode(request: StartPhoneCodeRequest): StartChallengeResponse

    suspend fun verifyPhoneCode(request: VerifyPhoneCodeRequest): AuthenticateResponse

    suspend fun bind(request: BindRequest): BindResponse
}

class LoginClientImpl(rpcClient: RpcClient) : LoginClient {
    private val rpc = LoginServiceRpc(rpcClient)

    override suspend fun authenticateSocial(request: AuthenticateSocialRequest): AuthenticateResponse =
        rpc.authenticateSocial(request)

    override suspend fun startEmailLink(request: StartEmailLinkRequest): StartChallengeResponse =
        rpc.startEmailLink(request)

    override suspend fun completeEmailLink(request: CompleteEmailLinkRequest): AuthenticateResponse =
        rpc.completeEmailLink(request)

    override suspend fun startPhoneCode(request: StartPhoneCodeRequest): StartChallengeResponse =
        rpc.startPhoneCode(request)

    override suspend fun verifyPhoneCode(request: VerifyPhoneCodeRequest): AuthenticateResponse =
        rpc.verifyPhoneCode(request)

    override suspend fun bind(request: BindRequest): BindResponse = rpc.bind(request)
}
