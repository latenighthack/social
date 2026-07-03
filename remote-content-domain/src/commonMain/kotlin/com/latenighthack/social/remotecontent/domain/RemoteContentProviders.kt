package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktbuf.net.RpcClient
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the remote-content client. The consuming app supplies
 * the connector's [RpcClient] and a ktor [HttpClient] (with a platform engine) into
 * its graph; this binds the [RemoteContentClient] helper over them.
 */
interface RemoteContentProviders {
    @Provides
    fun remoteContentClient(rpcClient: RpcClient, httpClient: HttpClient): RemoteContentClient =
        RemoteContentClientImpl(rpcClient, httpClient)
}
