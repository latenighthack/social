package com.latenighthack.social.login.core.service

import com.latenighthack.ktbuf.net.ServerDescriptor
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.server.ServerExtension
import com.latenighthack.lockers.server.ServerExtensionFactory
import com.latenighthack.lockers.server.tools.GrpcRouteProvider
import com.latenighthack.social.login.v1.LoginServer
import com.latenighthack.social.login.v1.Provider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.micrometer.core.instrument.MeterRegistry
import java.util.ServiceLoader

/**
 * Attaches the [LoginServiceImpl] to the locker server as a gRPC service, backed by two ktstore
 * stores on a shared [StoreDelegate]. The stores are prepared in [start] (mirroring the monolith's
 * own start), so a durable delegate is a drop-in replacement for the in-memory default.
 */
class LoginServerExtension(
    private val storeDelegate: StoreDelegate,
    custody: CustodyCrypto,
    hasher: Pbkdf2Hasher,
    appleVerifier: SocialTokenVerifier?,
    googleVerifier: SocialTokenVerifier?,
    emailSender: EmailSender?,
    smsSender: SmsSender?,
    linkBaseUrl: String,
) : ServerExtension {
    private val credentials = CredentialStore(storeDelegate)
    private val challenges = ChallengeStore(storeDelegate)
    private val serviceImpl = LoginServiceImpl(
        credentials = credentials,
        challenges = challenges,
        custody = custody,
        hasher = hasher,
        appleVerifier = appleVerifier,
        googleVerifier = googleVerifier,
        emailSender = emailSender,
        smsSender = smsSender,
        linkBaseUrl = linkBaseUrl,
    )

    override val services: List<GrpcRouteProvider<*>> = listOf(
        object : GrpcRouteProvider<LoginServer> {
            override val descriptor: ServerDescriptor = LoginServer.Descriptor
            override val server: LoginServer = serviceImpl
        },
    )

    override suspend fun start() {
        credentials.prepare()
        challenges.prepare()
        storeDelegate.createStores()
    }
}

/**
 * Registered via META-INF/services so the locker server discovers and mounts the Login service. The
 * provider handlers (Apple/Google verifiers, email/SMS senders) are contributed by whatever
 * [LoginProviderFactory] SPI implementations are on the classpath — i.e. which login-<provider>-service
 * modules the deployment includes. Shared configuration (master key, link base url) comes from
 * [LoginConfig]; each provider reads its own configuration from the environment.
 *
 * NOTE: the default [InMemoryStoreDelegate] does not survive a restart. A production deployment must
 * supply a durable delegate; the custodial private key is already encrypted at rest under the master
 * key, so persistence is the only missing piece.
 */
class LoginServerExtensionFactory : ServerExtensionFactory {
    override fun create(meterRegistry: MeterRegistry): ServerExtension {
        val config = LoginConfig.fromEnv()
        val httpClient = HttpClient(CIO)
        val context = LoginProviderContext(System::getenv, httpClient)
        val handlers = ServiceLoader.load(LoginProviderFactory::class.java).mapNotNull { it.create(context) }

        val social = handlers.filterIsInstance<LoginHandler.SocialVerifier>()
        return LoginServerExtension(
            storeDelegate = InMemoryStoreDelegate(),
            custody = CustodyCrypto(config.masterKey),
            hasher = Pbkdf2Hasher(),
            appleVerifier = social.firstOrNull { it.provider == Provider.PROVIDER_APPLE }?.verifier,
            googleVerifier = social.firstOrNull { it.provider == Provider.PROVIDER_GOOGLE }?.verifier,
            emailSender = handlers.filterIsInstance<LoginHandler.Email>().firstOrNull()?.sender,
            smsSender = handlers.filterIsInstance<LoginHandler.Sms>().firstOrNull()?.sender,
            linkBaseUrl = config.linkBaseUrl,
        )
    }
}
