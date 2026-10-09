package com.latenighthack.social.login.core.domain

import com.latenighthack.social.observability.*
import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.social.login.v1.AuthenticateResponse
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.BindRequest
import com.latenighthack.social.login.v1.BindResponse
import com.latenighthack.social.login.v1.CompleteEmailLinkRequest
import com.latenighthack.social.login.v1.LoginServiceRpc
import com.latenighthack.social.login.v1.RequestNonceRequest
import com.latenighthack.social.login.v1.RequestNonceResponse
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
    suspend fun requestNonce(): RequestNonceResponse

    suspend fun authenticateSocial(request: AuthenticateSocialRequest): AuthenticateResponse

    suspend fun startEmailLink(request: StartEmailLinkRequest): StartChallengeResponse

    suspend fun completeEmailLink(request: CompleteEmailLinkRequest): AuthenticateResponse

    suspend fun startPhoneCode(request: StartPhoneCodeRequest): StartChallengeResponse

    suspend fun verifyPhoneCode(request: VerifyPhoneCodeRequest): AuthenticateResponse

    suspend fun bind(request: BindRequest): BindResponse
}

class LoginClientImpl(rpcClient: RpcClient) : LoginClient, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry
    private val rpc = LoginServiceRpc(rpcClient)

    override suspend fun requestNonce(): RequestNonceResponse = socialTelemetry.measure("login", "requestNonce", "none") {
        rpc.requestNonce(RequestNonceRequest {}).also { result(socialResult(it.result.toString())) }
    }

    override suspend fun authenticateSocial(request: AuthenticateSocialRequest): AuthenticateResponse = socialTelemetry.measure("login", "authenticateSocial", socialProvider(request.provider.value)) {
        rpc.authenticateSocial(request).also { result(socialResult(it.result.toString())) }
    }

    override suspend fun startEmailLink(request: StartEmailLinkRequest): StartChallengeResponse = socialTelemetry.measure("login", "startEmailLink", "email") {
        rpc.startEmailLink(request).also { result(socialResult(it.result.toString())) }
    }

    override suspend fun completeEmailLink(request: CompleteEmailLinkRequest): AuthenticateResponse = socialTelemetry.measure("login", "completeEmailLink", "email") {
        rpc.completeEmailLink(request).also { result(socialResult(it.result.toString())) }
    }

    override suspend fun startPhoneCode(request: StartPhoneCodeRequest): StartChallengeResponse = socialTelemetry.measure("login", "startPhoneCode", "phone") {
        rpc.startPhoneCode(request).also { result(socialResult(it.result.toString())) }
    }

    override suspend fun verifyPhoneCode(request: VerifyPhoneCodeRequest): AuthenticateResponse = socialTelemetry.measure("login", "verifyPhoneCode", "phone") {
        rpc.verifyPhoneCode(request).also { result(socialResult(it.result.toString())) }
    }

    override suspend fun bind(request: BindRequest): BindResponse = socialTelemetry.measure("login", "bind", "none") {
        rpc.bind(request).also { result(socialResult(it.result.toString())) }
    }
}
