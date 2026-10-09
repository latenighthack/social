package com.latenighthack.social.login.core.service

import com.latenighthack.social.observability.*

import com.latenighthack.ktbuf.net.GrpcRequestContext
import com.latenighthack.social.login.v1.AuthenticateResponse
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.BindRequest
import com.latenighthack.social.login.v1.BindResponse
import com.latenighthack.social.login.v1.ChallengeRecord
import com.latenighthack.social.login.v1.CompleteEmailLinkRequest
import com.latenighthack.social.login.v1.CredentialRecord
import com.latenighthack.social.login.v1.LoginResult
import com.latenighthack.social.login.v1.LoginServer
import com.latenighthack.social.login.v1.Provider
import com.latenighthack.social.login.v1.RequestNonceRequest
import com.latenighthack.social.login.v1.RequestNonceResponse
import com.latenighthack.social.login.v1.StartChallengeResponse
import com.latenighthack.social.login.v1.StartEmailLinkRequest
import com.latenighthack.social.login.v1.StartPhoneCodeRequest
import com.latenighthack.social.login.v1.VerifyPhoneCodeRequest
import com.latenighthack.social.login.v1.copy
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The custodial Login gRPC service. Implements every provider's RPCs but each provider handler is
 * OPTIONAL — the per-provider service modules contribute them via the [LoginProviderFactory] SPI, and
 * a request for a handler that isn't on the classpath returns [LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE].
 * The shared parts — the credential/challenge stores, the custodial crypto, and [bind] — are always
 * present.
 *
 * Authenticating a method (a verified Apple/Google id token, or a magic-link token / OTP code that
 * matches a live challenge) either recovers the account key bound to it — returned so the client can
 * `restoreAccount` — or, when nothing is bound yet, issues a single-use bind ticket. [bind] redeems a
 * ticket to store `(provider, subject) → account key`, encrypting the key at rest under the service
 * master key ([CustodyCrypto]). Challenge secrets are never stored in the clear ([Pbkdf2Hasher]); OTP
 * brute force is bounded by [maxAttempts] and [challengeTtlMillis].
 */
class LoginServiceImpl(
    private val credentials: CredentialStore,
    private val challenges: ChallengeStore,
    private val custody: CustodyCrypto,
    private val hasher: Pbkdf2Hasher,
    private val appleVerifier: SocialTokenVerifier?,
    private val googleVerifier: SocialTokenVerifier?,
    private val emailSender: EmailSender?,
    private val smsSender: SmsSender?,
    private val linkBaseUrl: String,
    private val nonces: NonceService = NonceService(),
    // When true, AuthenticateSocial rejects requests whose nonce is absent or fails the single-use
    // check. Off by default for rollout: legacy clients carry no nonce.
    private val requireNonce: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val challengeTtlMillis: Long = 15 * 60 * 1000L,
    private val ticketTtlMillis: Long = 10 * 60 * 1000L,
    private val maxAttempts: Int = 5,
    private val otpDigits: Int = 6,
    private val tokenBytes: Int = 32,
) : LoginServer, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry
        set(value) {
            field = value
            (appleVerifier as? SocialTelemetryOwner)?.socialTelemetry = value
            (googleVerifier as? SocialTelemetryOwner)?.socialTelemetry = value
        }


    override suspend fun requestNonce(
        context: GrpcRequestContext,
        request: RequestNonceRequest,
    ): RequestNonceResponse = socialTelemetry.measure("login", "requestNonce", "none") { RequestNonceResponse {
        result = LoginResult.LOGIN_RESULT_OK
        nonce = nonces.issue()
        expiresInSeconds = nonces.expiresInSeconds
    } }

    override suspend fun authenticateSocial(
        context: GrpcRequestContext,
        request: AuthenticateSocialRequest,
    ): AuthenticateResponse = socialTelemetry.measure("login", "authenticateSocial", socialProvider(request.provider.value)) {
        run operation@ {
        val verifier = when (request.provider) {
            Provider.PROVIDER_APPLE -> appleVerifier
            Provider.PROVIDER_GOOGLE -> googleVerifier
            else -> null
        } ?: return@operation authResult(LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE)
        val claims = socialTelemetry.measure("login", "verify", socialProvider(request.provider.value)) {
            verifier.verify(request.idToken).also { if (it == null) result("unauthorized") }
        }
            ?: return@operation authResult(LoginResult.LOGIN_RESULT_UNAUTHORIZED)
        // Replay defense: under enforcement, a nonce must be present, issued here, and match the
        // token's claim (verbatim or SHA-256 hex). Outside enforcement the check is best-effort — the
        // nonce is still spent, but a mismatch is non-fatal (a non-enforcing server already accepts
        // nonce-less requests, so failing here would only break verifiers that don't surface the
        // claim, e.g. the dev verifier, without adding protection).
        if (requireNonce) {
            if (request.nonce.isEmpty() || !nonces.consume(request.nonce, claims.nonce)) {
                return@operation authResult(LoginResult.LOGIN_RESULT_UNAUTHORIZED)
            }
        } else if (request.nonce.isNotEmpty()) {
            nonces.consume(request.nonce, claims.nonce)
        }
        return@operation recoverOrIssueTicket(request.provider.value, subjectBytes(claims.subject), claims)

        }.also { response -> result(socialResult(response.result.toString())) }
    }

    override suspend fun startEmailLink(
        context: GrpcRequestContext,
        request: StartEmailLinkRequest,
    ): StartChallengeResponse = socialTelemetry.measure("login", "startEmailLink", "email") {
        run operation@ {
        val sender = emailSender ?: return@operation StartChallengeResponse { result = LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE }
        val email = request.email.trim()
        if (email.isEmpty()) return@operation StartChallengeResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        val token = randomToken()
        storeChallenge(providerNumber(Provider.PROVIDER_EMAIL), subjectBytes(email), token)
        socialTelemetry.measure("login", "send", "email") { sender.sendMagicLink(email, buildLink(email, token)) }
        return@operation StartChallengeResponse { result = LoginResult.LOGIN_RESULT_OK }

        }.also { response -> result(socialResult(response.result.toString())) }
    }

    override suspend fun completeEmailLink(
        context: GrpcRequestContext,
        request: CompleteEmailLinkRequest,
    ): AuthenticateResponse = socialTelemetry.measure("login", "completeEmailLink", "email") {
        run operation@ {
        if (emailSender == null) return@operation authResult(LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE)
        return@operation verifyChallenge(providerNumber(Provider.PROVIDER_EMAIL), subjectBytes(request.email.trim()), request.token)

        }.also { response -> result(socialResult(response.result.toString())) }
    }

    override suspend fun startPhoneCode(
        context: GrpcRequestContext,
        request: StartPhoneCodeRequest,
    ): StartChallengeResponse = socialTelemetry.measure("login", "startPhoneCode", "phone") {
        run operation@ {
        val sender = smsSender ?: return@operation StartChallengeResponse { result = LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE }
        val phone = request.phoneNumber.trim()
        if (phone.isEmpty()) return@operation StartChallengeResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        val code = randomCode()
        storeChallenge(providerNumber(Provider.PROVIDER_PHONE), subjectBytes(phone), code)
        socialTelemetry.measure("login", "send", "phone") { sender.sendCode(phone, code) }
        return@operation StartChallengeResponse { result = LoginResult.LOGIN_RESULT_OK }

        }.also { response -> result(socialResult(response.result.toString())) }
    }

    override suspend fun verifyPhoneCode(
        context: GrpcRequestContext,
        request: VerifyPhoneCodeRequest,
    ): AuthenticateResponse = socialTelemetry.measure("login", "verifyPhoneCode", "phone") {
        run operation@ {
        if (smsSender == null) return@operation authResult(LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE)
        return@operation verifyChallenge(providerNumber(Provider.PROVIDER_PHONE), subjectBytes(request.phoneNumber.trim()), request.code)

        }.also { response -> result(socialResult(response.result.toString())) }
    }

    override suspend fun bind(context: GrpcRequestContext, request: BindRequest): BindResponse = socialTelemetry.measure("login", "bind", "none") {
        run operation@ {
        val ticketLookup = ticketKey(request.bindTicket)
        val ticket = challenges.getByLookup(ticketLookup)
            ?: return@operation BindResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        // Single-use: a ticket is spent whether or not the bind succeeds.
        challenges.deleteByLookup(ticketLookup)
        if (ticket.expiryMillis != 0L && clock() >= ticket.expiryMillis) {
            return@operation BindResponse { result = LoginResult.LOGIN_RESULT_EXPIRED }
        }

        val credentialLookup = credentialKey(ticket.provider, ticket.subject)
        val existing = credentials.getByLookup(credentialLookup)
        if (existing != null && !existing.accountId.contentEquals(request.accountId)) {
            return@operation BindResponse { result = LoginResult.LOGIN_RESULT_ALREADY_BOUND }
        }

        val sealed = custody.encrypt(request.accountPrivateKey, CustodyCrypto.Binding(ticket.provider, ticket.subject))
        val now = clock()
        credentials.put(
            CredentialRecord {
                lookupKey = credentialLookup
                provider = ticket.provider
                subject = ticket.subject
                accountId = request.accountId
                encPrivateKey = sealed.ciphertext
                encNonce = sealed.nonce
                kdfSalt = sealed.salt
                keyVersion = sealed.keyVersion
                createdAtMillis = existing?.createdAtMillis ?: now
                updatedAtMillis = now
            },
        )
        return@operation BindResponse { result = LoginResult.LOGIN_RESULT_OK }

        }.also { response -> result(socialResult(response.result.toString())) }
    }

    /** After a method is proven, recover its bound key or, if none, issue a single-use bind ticket. */
    private suspend fun recoverOrIssueTicket(
        provider: Int,
        subject: ByteArray,
        claims: VerifiedClaims? = null,
    ): AuthenticateResponse {
        val credential = credentials.getByLookup(credentialKey(provider, subject))
        if (credential != null) {
            val binding = CustodyCrypto.Binding(provider, subject)
            val privateKey = custody.decrypt(
                credential.encPrivateKey, credential.encNonce, credential.kdfSalt, credential.keyVersion, binding,
            )
            // Migrate legacy / previous-key-version records to the current envelope opportunistically.
            if (custody.needsRewrap(credential.kdfSalt, credential.keyVersion)) {
                val sealed = custody.encrypt(privateKey, binding)
                credentials.put(
                    credential.copy {
                        encPrivateKey = sealed.ciphertext
                        encNonce = sealed.nonce
                        kdfSalt = sealed.salt
                        keyVersion = sealed.keyVersion
                        updatedAtMillis = clock()
                    },
                )
            }
            return AuthenticateResponse {
                result = LoginResult.LOGIN_RESULT_OK
                identity {
                    accountId = credential.accountId
                    accountPrivateKey = privateKey
                }
                applyPrefill(claims)
            }
        }
        val ticket = randomBytes(tokenBytes)
        challenges.put(
            ChallengeRecord {
                lookupKey = ticketKey(ticket)
                this.provider = provider
                this.subject = subject
                expiryMillis = clock() + ticketTtlMillis
                attemptsRemaining = 1
            },
        )
        return AuthenticateResponse {
            result = LoginResult.LOGIN_RESULT_NEEDS_BINDING
            bindTicket = ticket
            applyPrefill(claims)
        }
    }

    private suspend fun storeChallenge(provider: Int, subject: ByteArray, secret: String) {
        val hashed = hasher.hash(secret)
        challenges.put(
            ChallengeRecord {
                lookupKey = challengeKey(provider, subject)
                this.provider = provider
                this.subject = subject
                secretHash = hashed.hash
                salt = hashed.salt
                kdfIterations = hasher.iterations
                expiryMillis = clock() + challengeTtlMillis
                attemptsRemaining = maxAttempts
            },
        )
    }

    private suspend fun verifyChallenge(provider: Int, subject: ByteArray, presented: String): AuthenticateResponse {
        val lookup = challengeKey(provider, subject)
        val record = challenges.getByLookup(lookup) ?: return authResult(LoginResult.LOGIN_RESULT_INVALID)
        if (record.expiryMillis != 0L && clock() >= record.expiryMillis) {
            challenges.deleteByLookup(lookup)
            return authResult(LoginResult.LOGIN_RESULT_EXPIRED)
        }
        if (record.attemptsRemaining <= 0) {
            challenges.deleteByLookup(lookup)
            return authResult(LoginResult.LOGIN_RESULT_EXHAUSTED)
        }
        if (!hasher.verify(presented, record.secretHash, record.salt, record.kdfIterations)) {
            val remaining = record.attemptsRemaining - 1
            if (remaining <= 0) {
                challenges.deleteByLookup(lookup)
                return authResult(LoginResult.LOGIN_RESULT_EXHAUSTED)
            }
            challenges.put(record.copy { attemptsRemaining = remaining })
            return authResult(LoginResult.LOGIN_RESULT_INVALID)
        }
        challenges.deleteByLookup(lookup)
        return recoverOrIssueTicket(provider, subject)
    }

    private fun authResult(result: LoginResult) = AuthenticateResponse { this.result = result }

    // Attach best-effort prefills from the verified token's claims (Google: name/picture/email;
    // Apple: email only — its name never appears in the token).
    private fun com.latenighthack.social.login.v1.AuthenticateResponseBuilder.applyPrefill(claims: VerifiedClaims?) {
        if (claims == null) return
        if (claims.displayName == null && claims.photoUrl == null && claims.email == null) return
        prefill {
            claims.displayName?.let { displayName = it }
            claims.photoUrl?.let { photoUrl = it }
            claims.email?.let { email = it }
        }
    }

    // The proto enum number. Taken via the base Provider type: `Provider.PROVIDER_X.value` would bind
    // PROVIDER_X to the nested classifier of the same name rather than the companion instance.
    private fun providerNumber(provider: Provider) = provider.value

    private fun subjectBytes(subject: String) = subject.encodeToByteArray()

    private fun credentialKey(provider: Int, subject: ByteArray) = byteArrayOf(provider.toByte()) + subject

    private fun challengeKey(provider: Int, subject: ByteArray) =
        byteArrayOf(CHALLENGE_PREFIX, provider.toByte()) + subject

    private fun ticketKey(ticket: ByteArray) = byteArrayOf(TICKET_PREFIX) + sha256(ticket)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

    private fun randomToken() = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(tokenBytes))

    private fun randomCode() = buildString { repeat(otpDigits) { append(random.nextInt(10)) } }

    private fun buildLink(email: String, token: String): String {
        val separator = if ('?' in linkBaseUrl) "&" else "?"
        val e = URLEncoder.encode(email, "UTF-8")
        val t = URLEncoder.encode(token, "UTF-8")
        return "$linkBaseUrl${separator}email=$e&token=$t"
    }

    private companion object {
        // lookup_key domain prefixes so challenges and tickets never collide in the shared store.
        const val CHALLENGE_PREFIX: Byte = 0x00
        const val TICKET_PREFIX: Byte = 0x01
    }
}
