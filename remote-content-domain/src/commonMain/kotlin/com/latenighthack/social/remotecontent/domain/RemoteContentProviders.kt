package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the remote-content feature. The consuming app supplies the connector's
 * [RpcClient], a ktor [HttpClient] (with a platform engine), and the [StoreDelegate] the durable
 * upload queue is created from; this binds the [RemoteContentClient] transport and the
 * [RemoteContentUploader] on top of it. The uploader rides the shared `Set<DomainLifecycle>` boot.
 */
interface RemoteContentProviders {
    @Provides
    fun remoteContentClient(rpcClient: RpcClient, httpClient: HttpClient): RemoteContentClient =
        RemoteContentClientImpl(rpcClient, httpClient)

    @Provides
    @SocialScope
    fun remoteContentUploaderImpl(client: RemoteContentClient, delegate: StoreDelegate): RemoteContentUploaderImpl =
        RemoteContentUploaderImpl(client, delegate)

    @Provides
    fun remoteContentUploader(impl: RemoteContentUploaderImpl): RemoteContentUploader = impl

    @Provides
    @IntoSet
    fun remoteContentUploaderLifecycle(impl: RemoteContentUploaderImpl): DomainLifecycle = impl
}
