package com.wa2port.android.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.wa2port.android.MainActivity

/**
 * Transparent trampoline Activity that receives the Google OAuth 2.0 custom URI scheme callback
 * (`com.googleusercontent.apps.<client-prefix>:/oauth2redirect?...`), delivers it to
 * [OAuthRedirectCoordinator.SHARED], brings [MainActivity] back to the foreground when an active
 * sign-in consumed the callback, and immediately finishes without leaving a stuck blank screen.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(redirectIntent: Intent?) {
        val uriString = redirectIntent?.dataString
        val consumed = OAuthRedirectCoordinator.SHARED.deliverRedirectUri(uriString)
        if (consumed) {
            val returnToApp = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            startActivity(returnToApp)
        }
        finish()
    }
}
