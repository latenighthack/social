package com.latenighthack.social.login.domain

import com.latenighthack.ktbuf.net.RpcClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the login feature. Requires an `RpcClient` binding (shared with
 * remote-content and rooms) for the Login service. The native [AppleSignInClient] / [GoogleSignInClient]
 * are NOT bound here — they need platform context (an Android Activity, an iOS presentation anchor),
 * so the app supplies them from its platform-specific graph.
 */
interface LoginProviders {
    @Provides
    fun loginClient(rpcClient: RpcClient): LoginClient = LoginClientImpl(rpcClient)
}
