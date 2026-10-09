package com.latenighthack.social.login.core.service

import com.latenighthack.social.observability.*
import com.latenighthack.social.observability.server.SocialServerTelemetry

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
    clock: () -> Long = System::currentTimeMillis,
    nonces: NonceService = NonceService(store = ChallengeStore(database), clock = clock),
    requireNonce: Boolean = true,
    private val ownsDatabase: Boolean = false,
    private val releaseResources: () -> Unit = {},
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
        clock = clock,
    )

    override val services: List<GrpcRouteProvider<*>> = listOf(
        object : GrpcRouteProvider<LoginServer> {
            override val descriptor: ServerDescriptor = LoginServer.Descriptor
            override val server: LoginServer = serviceImpl
        },
    )

    override fun stop() = releaseResources()

    override suspend fun start() {
        if (ownsDatabase) database.open()
        credentials.prepare()
        challenges.prepare()
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
    override fun create(meterRegistry: MeterRegistry): ServerExtension {
        check(System.getenv("LOGIN_DEVELOPMENT_MODE").equals("true", ignoreCase = true)) {
            "production login service requires the host-owned database overload"
        }
        return createConfigured(meterRegistry, LoginStorage.inMemory(), ownsDatabase = true)
    }
    override fun create(meterRegistry: MeterRegistry, database: Database): ServerExtension = createConfigured(meterRegistry, database)

    private fun createConfigured(meterRegistry: MeterRegistry, database: Database, ownsDatabase: Boolean = false): ServerExtension {
        val config = LoginConfig.fromEnv()
        val httpClient = HttpClient(CIO) { install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 3_000; socketTimeoutMillis = 5_000 } }
        val measurements = DependencyMetrics(meterRegistry)
        val context = LoginProviderContext(System::getenv, httpClient, measurements::record)
        val handlers = ServiceLoader.load(LoginProviderFactory::class.java).mapNotNull { it.create(context) }

        val social = handlers.filterIsInstance<LoginHandler.SocialVerifier>()
        return LoginServerExtension(
            database = database,
            custody = CustodyCrypto(config.masterKey, keyVersion = config.keyVersion, previousKeys = config.previousKeys),
            hasher = Pbkdf2Hasher(),
            appleVerifier = measurements.verifier("apple", social.firstOrNull { it.provider == Provider.PROVIDER_APPLE }?.verifier),
            googleVerifier = measurements.verifier("google", social.firstOrNull { it.provider == Provider.PROVIDER_GOOGLE }?.verifier),
            emailSender = measurements.email(handlers.filterIsInstance<LoginHandler.Email>().firstOrNull()?.sender),
            smsSender = measurements.sms(handlers.filterIsInstance<LoginHandler.Sms>().firstOrNull()?.sender),
            linkBaseUrl = config.linkBaseUrl,
            requireNonce = config.requireNonce,
            ownsDatabase = ownsDatabase,
            releaseResources = { httpClient.close() },
        ).observedBy(SocialServerTelemetry(meterRegistry))
    }
}
