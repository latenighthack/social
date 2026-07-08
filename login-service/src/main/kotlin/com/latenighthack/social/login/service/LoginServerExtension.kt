package com.latenighthack.social.login.service

import com.latenighthack.ktbuf.net.ServerDescriptor
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.server.ServerExtension
import com.latenighthack.lockers.server.ServerExtensionFactory
import com.latenighthack.lockers.server.tools.GrpcRouteProvider
import com.latenighthack.social.login.v1.LoginServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.micrometer.core.instrument.MeterRegistry

/**
 * Attaches the [LoginServiceImpl] to the locker server as a gRPC service, backed by two ktstore
 * stores on a shared [StoreDelegate]. The stores are prepared in [start] (mirroring the monolith's
 * own start), so a durable delegate is a drop-in replacement for the in-memory default.
 */
class LoginServerExtension(
    private val storeDelegate: StoreDelegate,
    custody: CustodyCrypto,
    hasher: Pbkdf2Hasher,
    emailSender: EmailSender,
    smsSender: SmsSender,
    appleVerifier: SocialTokenVerifier,
    googleVerifier: SocialTokenVerifier,
    linkBaseUrl: String,
) : ServerExtension {
    private val credentials = CredentialStore(storeDelegate)
    private val challenges = ChallengeStore(storeDelegate)
    private val serviceImpl = LoginServiceImpl(
        credentials = credentials,
        challenges = challenges,
        custody = custody,
        hasher = hasher,
        emailSender = emailSender,
        smsSender = smsSender,
        appleVerifier = appleVerifier,
        googleVerifier = googleVerifier,
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
 * Registered via META-INF/services so the locker server discovers and mounts the Login service when
 * this module is on its classpath. Reads all configuration (master key, email/SMS provider + their
 * credentials, Apple/Google audiences) from the environment via [LoginConfig].
 *
 * NOTE: the default [InMemoryStoreDelegate] does not survive a restart. A production deployment must
 * supply a durable delegate; the custodial private key is already encrypted at rest under the master
 * key, so persistence is the only missing piece.
 */
class LoginServerExtensionFactory : ServerExtensionFactory {
    override fun create(meterRegistry: MeterRegistry): ServerExtension {
        val config = LoginConfig.fromEnv()
        val httpClient = HttpClient(CIO)
        val emailSender: EmailSender = when (config.emailProvider) {
            "smtp" -> SmtpEmailSender(
                config.smtpHost,
                config.smtpPort,
                config.smtpUser,
                config.smtpPassword,
                config.emailFrom,
            )
            "sendgrid" -> SendGridEmailSender(config.sendGridApiKey, config.emailFrom, httpClient)
            else -> ConsoleEmailSender()
        }
        val smsSender: SmsSender = when (config.smsProvider) {
            "twilio" -> TwilioSmsSender(
                config.twilioAccountSid,
                config.twilioAuthToken,
                config.twilioFrom,
                httpClient,
            )
            else -> ConsoleSmsSender()
        }
        return LoginServerExtension(
            storeDelegate = InMemoryStoreDelegate(),
            custody = CustodyCrypto(config.masterKey),
            hasher = Pbkdf2Hasher(),
            emailSender = emailSender,
            smsSender = smsSender,
            appleVerifier = SocialTokenVerifiers.apple(config.appleAudiences),
            googleVerifier = SocialTokenVerifiers.google(config.googleAudiences),
            linkBaseUrl = config.linkBaseUrl,
        )
    }
}
