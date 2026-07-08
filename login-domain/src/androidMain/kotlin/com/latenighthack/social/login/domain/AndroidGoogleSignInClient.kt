package com.latenighthack.social.login.domain

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Native Google sign-in on Android via the Credential Manager and Google Identity. Returns the Google
 * OIDC id token for the login service to verify. [serverClientId] is the OAuth web client id the
 * token's audience is issued for — the same value configured as a Google audience server-side. The
 * app supplies an activity [context] and wires this as the [GoogleSignInClient] binding.
 */
class AndroidGoogleSignInClient(
    private val context: Context,
    private val serverClientId: String,
) : GoogleSignInClient {
    override suspend fun signIn(): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val response = CredentialManager.create(context).getCredential(context, request)
        return GoogleIdTokenCredential.createFrom(response.credential.data).idToken
    }
}
