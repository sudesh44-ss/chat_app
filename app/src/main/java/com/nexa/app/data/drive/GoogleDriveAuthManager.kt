package com.nexa.app.data.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages Google Sign-In and OAuth 2.0 access token acquisition scoped exclusively to
 * `https://www.googleapis.com/auth/drive.appdata`.
 */
class GoogleDriveAuthManager(private val context: Context) {

    private val driveScope = Scope(SCOPE_DRIVE_APPDATA)

    private val signInOptions: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(driveScope)
        .build()

    val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, signInOptions)

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun getLastSignedInAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null && GoogleSignIn.hasPermissions(account, driveScope)) {
            account
        } else {
            null
        }
    }

    fun isAuthorized(): Boolean {
        return getLastSignedInAccount() != null
    }

    /**
     * Obtains a valid Bearer OAuth 2.0 access token for Google Drive AppData REST requests.
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val account = getLastSignedInAccount()
            ?: throw IllegalStateException("User is not signed into Google Drive with appdata permission")

        val androidAccount = account.account
            ?: throw IllegalStateException("GoogleSignInAccount does not contain an Android account")

        val oauthScopeString = "oauth2:$SCOPE_DRIVE_APPDATA"
        GoogleAuthUtil.getToken(context, androidAccount, oauthScopeString)
    }

    /**
     * Invalidates a revoked or expired token in the Play Services token cache.
     */
    suspend fun invalidateToken(token: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (ignored: Exception) {}
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        googleSignInClient.signOut()
    }

    companion object {
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}
