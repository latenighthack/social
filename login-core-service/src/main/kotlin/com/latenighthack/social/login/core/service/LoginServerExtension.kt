package com.latenighthack.social.login.core.service

import com.latenighthack.ktbuf.net.ServerDescriptor
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.Database
import com.latenighthack.lockers.server.ServerExtension
import com.latenighthack.lockers.server.ServerExtensionFactory
import com.latenighthack.lockers.server.tools.GrpcRouteProvider
import com.latenighthack.social.login.v1.LoginServer
import com.latenighthack.social.login.v1.Provider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.micrometer.core.instrument.MeterRegistry
import com.latenighthack.social.observability.*
import com.latenighthack.social.observability.server.SocialServerTelemetry
import java.util.ServiceLoader

/**
 * Attaches the [LoginServiceImpl] to the locker server as a gRPC service, backed by two ktstore
 * stores on a shared [Database]. The stores are prepared in [start] (mirroring the monolith's
 * own start), so a durable database is a drop-in replacement for the in-memory default.
 */
class LoginServerExtension(
    private val database: Database,
    custody: CustodyCrypto,
    hasher: Pbkdf2Hasher,
    private val appleVerifier: SocialTokenVerifier?,
    private val googleVerifier: SocialTokenVerifier?,
    private val emailSender: EmailSender?,
    private val smsSender: SmsSender?,
    linkBaseUrl: String,
    nonces: NonceService = NonceService(),
    requireNonce: Boolean = false,
) : ServerExtension, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry
        set(value) {
            field = value
            serviceImpl.socialTelemetry = value
            value.feature("login")
            if (value is SocialServerTelemetry) {
                value.provider("apple", appleVerifier != null); value.provider("google", googleVerifier != null)
                value.provider("email", emailSender != null); value.provider("phone", smsSender != null)
            }
        }
    private val credentials = CredentialStore(database)
    private val challenges = ChallengeStore(database)
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
        nonces = nonces,
        requireNonce = requireNonce,
    )

    override val services: List<GrpcRouteProvider<*>> = listOf(
        object : GrpcRouteProvider<LoginServer> {
            override val descriptor: ServerDescriptor = LoginServer.Descriptor
            override val server: LoginServer = serviceImpl
        },
    )

    override suspend fun start(): Unit = socialTelemetry.measure("login", "start") {
        database.open()
        credentials.prepare()
        challenges.prepare()
        database.open()
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
 * supply a durable database; the custodial private key is already encrypted at rest under the master
 * key, so persistence is the only missing piece.
 */
class LoginServerExtensionFactory : ServerExtensionFactory {
    override val storeDefinitions get() = LoginStorage.definitions
    override fun create(meterRegistry: MeterRegistry): ServerExtension = create(meterRegistry, LoginStorage.inMemory())
    override fun create(meterRegistry: MeterRegistry, database: Database): ServerExtension {
        val config = LoginConfig.fromEnv()
        val httpClient = HttpClient(CIO)
        val context = LoginProviderContext(System::getenv, httpClient)
        val handlers = ServiceLoader.load(LoginProviderFactory::class.java).mapNotNull { it.create(context) }

        val social = handlers.filterIsInstance<LoginHandler.SocialVerifier>()
        return LoginServerExtension(
            database = database,
            custody = CustodyCrypto(config.masterKey),
            hasher = Pbkdf2Hasher(),
            appleVerifier = social.firstOrNull { it.provider == Provider.PROVIDER_APPLE }?.verifier,
            googleVerifier = social.firstOrNull { it.provider == Provider.PROVIDER_GOOGLE }?.verifier,
            emailSender = handlers.filterIsInstance<LoginHandler.Email>().firstOrNull()?.sender,
            smsSender = handlers.filterIsInstance<LoginHandler.Sms>().firstOrNull()?.sender,
            linkBaseUrl = config.linkBaseUrl,
            requireNonce = config.requireNonce,
        ).observedBy(SocialServerTelemetry(meterRegistry))
    }
}
