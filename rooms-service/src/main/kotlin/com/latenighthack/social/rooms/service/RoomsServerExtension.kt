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
    store: InviteCodeStore,
) : ServerExtension {
    private val serviceImpl = JoinServiceImpl(store)

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
    override fun create(meterRegistry: MeterRegistry): ServerExtension =
        RoomsServerExtension(InMemoryInviteCodeStore())
}
