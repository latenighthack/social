package com.latenighthack.social.login.apple.domain

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.AuthenticationServices.ASPresentationAnchor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSError
import platform.Foundation.NSPersonNameComponentsFormatter
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.darwin.NSObject

/**
 * Native Sign in with Apple on iOS via the system AuthenticationServices framework. Presents the
 * Apple flow anchored to [presentationAnchor] (the app's key window) and returns the OIDC identity
 * token for the login service to verify, plus the first-authorization name/email prefills (Apple
 * never repeats the name after the first grant). A supplied replay [nonce] is bound as its SHA-256
 * hex, which Apple echoes into the token's `nonce` claim. The app wires this as the
 * [AppleSignInClient] binding.
 */
class IosAppleSignInClient(
    private val presentationAnchor: ASPresentationAnchor,
) : AppleSignInClient {
    @OptIn(BetaInteropApi::class)
    override suspend fun signIn(nonce: String?): AppleSignInResult = suspendCancellableCoroutine { continuation ->
        val request = ASAuthorizationAppleIDProvider().createRequest().apply {
            requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
            nonce?.let { this.nonce = sha256Hex(it) }
        }
        val delegate = object :
            NSObject(),
            ASAuthorizationControllerDelegateProtocol,
            ASAuthorizationControllerPresentationContextProvidingProtocol {
            override fun authorizationController(
                controller: ASAuthorizationController,
                didCompleteWithAuthorization: ASAuthorization,
            ) {
                val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
                val token = credential?.identityToken?.let {
                    NSString.create(data = it, encoding = NSUTF8StringEncoding) as String?
                }
                if (token != null) {
                    // First authorization only: Apple returns the name here and NEVER again (nor in
                    // the token), so capture it now or lose it.
                    val displayName = credential.fullName?.let {
                        NSPersonNameComponentsFormatter().stringFromPersonNameComponents(it).ifBlank { null }
                    }
                    continuation.resume(AppleSignInResult(token, displayName, credential.email))
                } else {
                    continuation.resumeWithException(IllegalStateException("Apple sign-in returned no identity token"))
                }
            }

            override fun authorizationController(
                controller: ASAuthorizationController,
                didCompleteWithError: NSError,
            ) {
                continuation.resumeWithException(RuntimeException(didCompleteWithError.localizedDescription))
            }

            override fun presentationAnchorForAuthorizationController(
                controller: ASAuthorizationController,
            ): ASPresentationAnchor = presentationAnchor
        }
        val controller = ASAuthorizationController(authorizationRequests = listOf(request))
        controller.delegate = delegate
        controller.presentationContextProvider = delegate
        // The controller holds its delegate weakly; keep it alive until the flow settles.
        continuation.invokeOnCancellation { delegate.let { controller.delegate = null } }
        controller.performRequests()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun sha256Hex(value: String): String {
        val bytes = value.encodeToByteArray()
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        bytes.usePinned { input ->
            digest.usePinned { output ->
                CC_SHA256(
                    if (bytes.isEmpty()) null else input.addressOf(0),
                    bytes.size.toUInt(),
                    output.addressOf(0).reinterpret(),
                )
            }
        }
        return digest.joinToString("") { byte -> ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    }
}
