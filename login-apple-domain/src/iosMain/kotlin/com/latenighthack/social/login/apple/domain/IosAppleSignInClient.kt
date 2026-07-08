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
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.darwin.NSObject

/**
 * Native Sign in with Apple on iOS via the system AuthenticationServices framework. Presents the
 * Apple flow anchored to [presentationAnchor] (the app's key window) and returns the OIDC identity
 * token for the login service to verify. The app wires this as the [AppleSignInClient] binding.
 */
class IosAppleSignInClient(
    private val presentationAnchor: ASPresentationAnchor,
) : AppleSignInClient {
    @OptIn(BetaInteropApi::class)
    override suspend fun signIn(): String = suspendCancellableCoroutine { continuation ->
        val request = ASAuthorizationAppleIDProvider().createRequest().apply {
            requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
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
                    continuation.resume(token)
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
}
