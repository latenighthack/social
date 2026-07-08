package com.latenighthack.social.login.service

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
 * The custodial Login gRPC service.
 *
 * Authenticating a method (a verified Apple/Google id token, or a magic-link token / OTP code that
 * matches a live challenge) either recovers the account key bound to it — returned so the client can
 * `restoreAccount` — or, when nothing is bound yet, issues a single-use bind ticket. [bind] redeems a
 * ticket to store `(provider, subject) → account key`, encrypting the key at rest under the service
 * master key ([CustodyCrypto]). Challenge secrets are never stored in the clear ([Pbkdf2Hasher]); OTP
 * brute force is bounded by [maxAttempts] and [challengeTtlMillis]. Email/SMS delivery and social
 * token verification are pluggable handlers chosen by configuration.
 */
class LoginServiceImpl(
    private val credentials: CredentialStore,
    private val challenges: ChallengeStore,
    private val custody: CustodyCrypto,
    private val hasher: Pbkdf2Hasher,
    private val emailSender: EmailSender,
    private val smsSender: SmsSender,
    private val appleVerifier: SocialTokenVerifier,
    private val googleVerifier: SocialTokenVerifier,
    private val linkBaseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val challengeTtlMillis: Long = 15 * 60 * 1000L,
    private val ticketTtlMillis: Long = 10 * 60 * 1000L,
    private val maxAttempts: Int = 5,
    private val otpDigits: Int = 6,
    private val tokenBytes: Int = 32,
) : LoginServer {

    override suspend fun authenticateSocial(
        context: GrpcRequestContext,
        request: AuthenticateSocialRequest,
    ): AuthenticateResponse {
        val verifier = when (request.provider) {
            Provider.PROVIDER_APPLE -> appleVerifier
            Provider.PROVIDER_GOOGLE -> googleVerifier
            else -> return authResult(LoginResult.LOGIN_RESULT_INVALID)
        }
        val subject = verifier.verify(request.idToken)
            ?: return authResult(LoginResult.LOGIN_RESULT_UNAUTHORIZED)
        return recoverOrIssueTicket(request.provider.value, subjectBytes(subject))
    }

    override suspend fun startEmailLink(
        context: GrpcRequestContext,
        request: StartEmailLinkRequest,
    ): StartChallengeResponse {
        val email = request.email.trim()
        if (email.isEmpty()) return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        val token = randomToken()
        storeChallenge(providerNumber(Provider.PROVIDER_EMAIL), subjectBytes(email), token)
        emailSender.sendMagicLink(email, buildLink(email, token))
        return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_OK }
    }

    override suspend fun completeEmailLink(
        context: GrpcRequestContext,
        request: CompleteEmailLinkRequest,
    ): AuthenticateResponse =
        verifyChallenge(providerNumber(Provider.PROVIDER_EMAIL), subjectBytes(request.email.trim()), request.token)

    override suspend fun startPhoneCode(
        context: GrpcRequestContext,
        request: StartPhoneCodeRequest,
    ): StartChallengeResponse {
        val phone = request.phoneNumber.trim()
        if (phone.isEmpty()) return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        val code = randomCode()
        storeChallenge(providerNumber(Provider.PROVIDER_PHONE), subjectBytes(phone), code)
        smsSender.sendCode(phone, code)
        return StartChallengeResponse { result = LoginResult.LOGIN_RESULT_OK }
    }

    override suspend fun verifyPhoneCode(
        context: GrpcRequestContext,
        request: VerifyPhoneCodeRequest,
    ): AuthenticateResponse =
        verifyChallenge(providerNumber(Provider.PROVIDER_PHONE), subjectBytes(request.phoneNumber.trim()), request.code)

    override suspend fun bind(context: GrpcRequestContext, request: BindRequest): BindResponse {
        val ticketLookup = ticketKey(request.bindTicket)
        val ticket = challenges.getByLookup(ticketLookup)
            ?: return BindResponse { result = LoginResult.LOGIN_RESULT_INVALID }
        // Single-use: a ticket is spent whether or not the bind succeeds.
        challenges.deleteByLookup(ticketLookup)
        if (ticket.expiryMillis != 0L && clock() >= ticket.expiryMillis) {
            return BindResponse { result = LoginResult.LOGIN_RESULT_EXPIRED }
        }

        val credentialLookup = credentialKey(ticket.provider, ticket.subject)
        val existing = credentials.getByLookup(credentialLookup)
        if (existing != null && !existing.accountId.contentEquals(request.accountId)) {
            return BindResponse { result = LoginResult.LOGIN_RESULT_ALREADY_BOUND }
        }

        val sealed = custody.encrypt(request.accountPrivateKey)
        val now = clock()
        credentials.put(
            CredentialRecord {
                lookupKey = credentialLookup
                provider = ticket.provider
                subject = ticket.subject
                accountId = request.accountId
                encPrivateKey = sealed.ciphertext
                encNonce = sealed.nonce
                createdAtMillis = existing?.createdAtMillis ?: now
                updatedAtMillis = now
            },
        )
        return BindResponse { result = LoginResult.LOGIN_RESULT_OK }
    }

    /** After a method is proven, recover its bound key or, if none, issue a single-use bind ticket. */
    private suspend fun recoverOrIssueTicket(provider: Int, subject: ByteArray): AuthenticateResponse {
        val credential = credentials.getByLookup(credentialKey(provider, subject))
        if (credential != null) {
            val privateKey = custody.decrypt(credential.encPrivateKey, credential.encNonce)
            return AuthenticateResponse {
                result = LoginResult.LOGIN_RESULT_OK
                identity {
                    accountId = credential.accountId
                    accountPrivateKey = privateKey
                }
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
