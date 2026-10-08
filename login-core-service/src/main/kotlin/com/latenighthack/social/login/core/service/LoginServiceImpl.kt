package com.latenighthack.social.login.core.service

import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.fromPrivateKey
import com.latenighthack.ktcrypto.encode

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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
    private val nonces: NonceService = NonceService(store = challenges),
    // When true, AuthenticateSocial rejects requests whose nonce is absent or fails the single-use
    // check. Enforced by default; disable only in an explicitly controlled development rollout.
    private val requireNonce: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val challengeTtlMillis: Long = 15 * 60 * 1000L,
    private val ticketTtlMillis: Long = 10 * 60 * 1000L,
    private val maxAttempts: Int = 5,
    private val otpDigits: Int = 6,
    private val tokenBytes: Int = 32,
    private val requestsPerMinute: Int = 240,
    private val startCooldownMillis: Long = 60_000,
) : LoginServer {

    private val hashSlots = kotlinx.coroutines.sync.Semaphore(4)

    init {
        require(requestsPerMinute > 0 && startCooldownMillis >= 0)
        require(credentials.database === challenges.database) { "login stores must share one database" }
    }

    override suspend fun requestNonce(context: GrpcRequestContext, request: RequestNonceRequest): RequestNonceResponse {
        if (!reserveBudget()) return RequestNonceResponse { result = LoginResult.LOGIN_RESULT_RATE_LIMITED }
        return RequestNonceResponse {
            result = LoginResult.LOGIN_RESULT_OK; nonce = nonces.issue(); expiresInSeconds = nonces.expiresInSeconds
        }
    }

    override suspend fun authenticateSocial(
        context: GrpcRequestContext,
        request: AuthenticateSocialRequest,
    ): AuthenticateResponse {
        if (request.idToken.length !in 1..16_384 || request.nonce.length > 128) return authResult(LoginResult.LOGIN_RESULT_INVALID)
        if (!reserveBudget()) return authResult(LoginResult.LOGIN_RESULT_RATE_LIMITED)
        val verifier = when (request.provider) {
            Provider.PROVIDER_APPLE -> appleVerifier
            Provider.PROVIDER_GOOGLE -> googleVerifier
            else -> null
        } ?: return authResult(LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE)
        val claims = verifier.verify(request.idToken)
            ?: return authResult(LoginResult.LOGIN_RESULT_UNAUTHORIZED)
        // Replay defense: under enforcement, a nonce must be present, issued here, and match the
        // token's claim (verbatim or SHA-256 hex). Outside enforcement the check is best-effort — the
        // nonce is still spent, but a mismatch is non-fatal (a non-enforcing server already accepts
        // nonce-less requests, so failing here would only break verifiers that don't surface the
        // claim, e.g. the dev verifier, without adding protection).
        if (requireNonce) {
            if (request.nonce.isEmpty() || !nonces.consume(request.nonce, claims.nonce)) {
                return authResult(LoginResult.LOGIN_RESULT_UNAUTHORIZED)
            }
        } else if (request.nonce.isNotEmpty()) {
            nonces.consume(request.nonce, claims.nonce)
        }
        return recoverOrIssueTicket(request.provider.value, subjectBytes(claims.subject), claims)
    }

    override suspend fun startEmailLink(
        context: GrpcRequestContext,
        request: StartEmailLinkRequest,
    ): StartChallengeResponse {
        val sender = emailSender ?: return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE }
        val email = request.email.trim()
        if (email.length !in 3..254 || !email.contains('@') || email.any { it.isWhitespace() || it.code < 32 })
            return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        if (!reserveBudget(providerNumber(Provider.PROVIDER_EMAIL), subjectBytes(email)))
            return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_RATE_LIMITED }
        val token = randomToken()
        storeChallenge(providerNumber(Provider.PROVIDER_EMAIL), subjectBytes(email), token)
        sender.sendMagicLink(email, buildLink(email, token))
        return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_OK }
    }

    override suspend fun completeEmailLink(
        context: GrpcRequestContext,
        request: CompleteEmailLinkRequest,
    ): AuthenticateResponse {
        if (emailSender == null) return authResult(LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE)
        return verifyChallenge(providerNumber(Provider.PROVIDER_EMAIL), subjectBytes(request.email.trim()), request.token)
    }

    override suspend fun startPhoneCode(
        context: GrpcRequestContext,
        request: StartPhoneCodeRequest,
    ): StartChallengeResponse {
        val sender = smsSender ?: return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE }
        val phone = request.phoneNumber.trim()
        if (phone.length !in 4..32 || phone.any { it !in "+0123456789" })
            return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        if (!reserveBudget(providerNumber(Provider.PROVIDER_PHONE), subjectBytes(phone)))
            return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_RATE_LIMITED }
        val code = randomCode()
        storeChallenge(providerNumber(Provider.PROVIDER_PHONE), subjectBytes(phone), code)
        sender.sendCode(phone, code)
        return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_OK }
    }

    override suspend fun verifyPhoneCode(
        context: GrpcRequestContext,
        request: VerifyPhoneCodeRequest,
    ): AuthenticateResponse {
        if (smsSender == null) return authResult(LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE)
        return verifyChallenge(providerNumber(Provider.PROVIDER_PHONE), subjectBytes(request.phoneNumber.trim()), request.code)
    }

    override suspend fun bind(context: GrpcRequestContext, request: BindRequest): BindResponse {
        if (request.bindTicket.size !in 16..128 || request.accountId.size != 33 || request.accountPrivateKey.size != 32)
            return BindResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        if (!reserveBudget(excludeLookup = ticketKey(request.bindTicket))) return BindResponse { result = LoginResult.LOGIN_RESULT_RATE_LIMITED }
        val identity = try { Secp256r1KeyPair.fromPrivateKey(request.accountPrivateKey) }
            catch (_: IllegalArgumentException) { null }
            catch (_: java.security.GeneralSecurityException) { null }
        if (identity == null || !identity.publicKey.encode().contentEquals(request.accountId)) {
            return BindResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        }
        return challenges.database.transaction("social.login.credentials") {
        val ticketLookup = ticketKey(request.bindTicket)
        val ticket = challenges.getByLookup(ticketLookup)
            ?: return@transaction BindResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        // Single-use: a ticket is spent whether or not the bind succeeds.
        challenges.deleteByLookup(ticketLookup)
        if (ticket.expiryMillis != 0L && clock() >= ticket.expiryMillis) {
            return@transaction BindResponse { result = LoginResult.LOGIN_RESULT_EXPIRED }
        }

        val credentialLookup = credentialKey(ticket.provider, ticket.subject)
        val existing = credentials.getByLookup(credentialLookup)
        if (existing != null && !existing.accountId.contentEquals(request.accountId)) {
            return@transaction BindResponse { result = LoginResult.LOGIN_RESULT_ALREADY_BOUND }
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
        BindResponse { result = LoginResult.LOGIN_RESULT_OK }
    }

    }

    /** After a method is proven, recover its bound key or, if none, issue a single-use bind ticket. */
    private suspend fun recoverOrIssueTicket(
        provider: Int,
        subject: ByteArray,
        claims: VerifiedClaims? = null,
    ): AuthenticateResponse = challenges.database.transaction("social.login.credentials") {
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
            return@transaction AuthenticateResponse {
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
        AuthenticateResponse {
            result = LoginResult.LOGIN_RESULT_NEEDS_BINDING
            bindTicket = ticket
            applyPrefill(claims)
        }
    }

    private suspend fun storeChallenge(provider: Int, subject: ByteArray, secret: String) {
        val hashed = hashSlots.withPermit { withContext(Dispatchers.Default) { hasher.hash(secret) } }
        challenges.database.transaction("social.login.credentials") {
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

    }

    private suspend fun verifyChallenge(provider: Int, subject: ByteArray, presented: String): AuthenticateResponse {
        if (subject.size !in 1..254 || presented.length !in 1..512) return authResult(LoginResult.LOGIN_RESULT_INVALID)
        val lookup = challengeKey(provider, subject)
        if (!reserveBudget(excludeLookup = lookup)) return authResult(LoginResult.LOGIN_RESULT_RATE_LIMITED)
        val snapshot = challenges.getByLookup(lookup) ?: return authResult(LoginResult.LOGIN_RESULT_INVALID)
        val valid = if (snapshot.attemptsRemaining <= 0 || clock() >= snapshot.expiryMillis) false else
            hashSlots.withPermit { withContext(Dispatchers.Default) {
                hasher.verify(presented, snapshot.secretHash, snapshot.salt, snapshot.kdfIterations)
            } }
        return challenges.database.transaction("social.login.credentials") {
            val record = challenges.getByLookup(lookup) ?: return@transaction authResult(LoginResult.LOGIN_RESULT_INVALID)
            if (record.expiryMillis != 0L && clock() >= record.expiryMillis) {
                challenges.deleteByLookup(lookup)
                return@transaction authResult(LoginResult.LOGIN_RESULT_EXPIRED)
            }
            if (!record.secretHash.contentEquals(snapshot.secretHash) || !record.salt.contentEquals(snapshot.salt) ||
                record.kdfIterations != snapshot.kdfIterations || record.expiryMillis != snapshot.expiryMillis)
                return@transaction authResult(LoginResult.LOGIN_RESULT_INVALID)
            if (record.attemptsRemaining <= 0) return@transaction authResult(LoginResult.LOGIN_RESULT_EXHAUSTED)
            if (!valid) {
                val remaining = record.attemptsRemaining - 1
                challenges.put(record.copy(attemptsRemaining = remaining))
                return@transaction authResult(if (remaining <= 0) LoginResult.LOGIN_RESULT_EXHAUSTED else LoginResult.LOGIN_RESULT_INVALID)
            }
            challenges.deleteByLookup(lookup)
            recoverOrIssueTicket(provider, subject)
        }
    }

    /** Database-backed global cost budget and per-subject delivery cooldown, shared by replicas. */
    private suspend fun reserveBudget(provider: Int? = null, subject: ByteArray? = null, excludeLookup: ByteArray? = null): Boolean {
        challenges.pruneExpired(clock(), excludeLookup)
        return challenges.database.transaction("social.login.credentials") {
            val now = clock()
            val globalKey = byteArrayOf(3, 0)
            val global = challenges.getByLookup(globalKey)
            val remaining = if (global == null || global.expiryMillis <= now) requestsPerMinute else global.attemptsRemaining
            if (remaining <= 0) return@transaction false
            val subjectKey = if (provider == null || subject == null) null else byteArrayOf(3, 1) + sha256(credentialKey(provider, subject))
            if (subjectKey != null && challenges.getByLookup(subjectKey)?.expiryMillis?.let { it > now } == true)
                return@transaction false
            challenges.put(ChallengeRecord(lookupKey = globalKey, expiryMillis = if (global != null && global.expiryMillis > now)
                global.expiryMillis else now + 60_000, attemptsRemaining = remaining - 1))
            if (subjectKey != null) challenges.put(ChallengeRecord(lookupKey = subjectKey, expiryMillis = now + startCooldownMillis))
            true
        }

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
