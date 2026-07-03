package com.latenighthack.social.remotecontent.service

import com.latenighthack.ktbuf.net.ServerDescriptor
import com.latenighthack.lockers.server.ServerExtension
import com.latenighthack.lockers.server.ServerExtensionFactory
import com.latenighthack.lockers.server.tools.GrpcRouteProvider
import com.latenighthack.social.remotecontent.v1.RemoteContentServer
import io.ktor.server.routing.Routing
import io.micrometer.core.instrument.MeterRegistry
import java.io.File

/**
 * Attaches the remote-content upload/hosting service to the locker server: it
 * contributes the RemoteContent gRPC service plus the /content HTTP routes, both
 * backed by a single [ContentStore].
 */
class RemoteContentExtension(
    private val store: ContentStore,
    urls: ContentUrls,
) : ServerExtension {
    private val serviceImpl = RemoteContentServiceImpl(store, urls)

    override val services: List<GrpcRouteProvider<*>> = listOf(
        object : GrpcRouteProvider<RemoteContentServer> {
            override val descriptor: ServerDescriptor = RemoteContentServer.Descriptor
            override val server: RemoteContentServer = serviceImpl
        },
    )

    override fun install(routing: Routing) {
        routing.remoteContent(store)
    }
}

/**
 * Registered via META-INF/services so the locker server discovers and mounts the
 * remote-content service when this module is on its classpath. Configuration is
 * read from the environment:
 *   REMOTE_CONTENT_PUBLIC_URL  base URL the upload/download URLs are built from
 *                              (unset/empty => root-relative paths)
 *   REMOTE_CONTENT_PATH        directory the bytes are stored under (default ./content)
 */
class RemoteContentExtensionFactory : ServerExtensionFactory {
    override fun create(meterRegistry: MeterRegistry): ServerExtension {
        val publicBaseUrl = System.getenv(ENV_PUBLIC_URL).orEmpty()
        val storagePath = System.getenv(ENV_PATH)?.takeIf { it.isNotBlank() } ?: DEFAULT_PATH
        return RemoteContentExtension(
            store = FileContentStore(File(storagePath)),
            urls = ContentUrls(publicBaseUrl),
        )
    }

    private companion object {
        const val ENV_PUBLIC_URL = "REMOTE_CONTENT_PUBLIC_URL"
        const val ENV_PATH = "REMOTE_CONTENT_PATH"
        const val DEFAULT_PATH = "./content"
    }
}
