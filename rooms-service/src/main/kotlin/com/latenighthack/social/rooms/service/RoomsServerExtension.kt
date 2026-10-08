package com.latenighthack.social.rooms.service

import com.latenighthack.ktbuf.net.ServerDescriptor
import com.latenighthack.lockers.server.ServerExtension
import com.latenighthack.lockers.server.ServerExtensionFactory
import com.latenighthack.lockers.server.tools.GrpcRouteProvider
import com.latenighthack.social.rooms.v1.JoinServer
import io.micrometer.core.instrument.MeterRegistry

/**
 * Attaches the [JoinServiceImpl] (group-room invite codes) to the locker server as a gRPC service,
 * backed by a single [InviteCodeStore].
 */
class RoomsServerExtension(
    private val store: InviteCodeStore,
) : ServerExtension {
    private val serviceImpl = JoinServiceImpl(store)

    override suspend fun start() { (store as? DurableInviteCodeStore)?.prepare() }

    override val services: List<GrpcRouteProvider<*>> = listOf(
        object : GrpcRouteProvider<JoinServer> {
            override val descriptor: ServerDescriptor = JoinServer.Descriptor
            override val server: JoinServer = serviceImpl
        },
    )
}

/**
 * Registered via META-INF/services so the locker server discovers and mounts the Join service when
 * this module is on its classpath.
 *
 * NOTE: the default [InMemoryInviteCodeStore] does not survive a restart and holds room keys in the
 * clear. A production deployment must supply a durable store that encrypts [StoredInviteCode.groupPrivateKey]
 * at rest under a service master key.
 */
class RoomsServerExtensionFactory : ServerExtensionFactory {
    override val storeDefinitions get() = RoomsServiceStorage.definitions
    override fun create(meterRegistry: MeterRegistry): ServerExtension {
        check(System.getenv("ROOMS_DEVELOPMENT_MODE").equals("true", ignoreCase = true)) {
            "production rooms service requires the host-owned database overload"
        }
        return RoomsServerExtension(InMemoryInviteCodeStore())
    }
    override fun create(meterRegistry: MeterRegistry, database: com.latenighthack.ktstore.Database): ServerExtension {
        val configured = System.getenv("ROOMS_MASTER_KEY")
        val key = configured?.let { java.util.Base64.getDecoder().decode(it) } ?: run {
            check(System.getenv("ROOMS_DEVELOPMENT_MODE").equals("true", ignoreCase = true)) { "ROOMS_MASTER_KEY is required" }
            ByteArray(32) { 0x52 }
        }
        return RoomsServerExtension(DurableInviteCodeStore(database, key))
    }
}
