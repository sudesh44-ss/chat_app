package com.nexa.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.nexa.app.core.theme.NexaTheme
import com.nexa.app.presentation.navigation.AuthNavGraph
import com.nexa.app.presentation.security.AppLockGate
import com.truecaller.android.sdk.oAuth.TcSdk

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NexaApplication
        val authRepository = app.authRepository
        val chatRepository = app.chatRepository
        val p2pTransferRepository = app.p2pTransferRepository

        setContent {
            val appearanceMode by app.appearancePrefs.appearanceMode.collectAsState()
            val reduceAnimations by app.appearancePrefs.reduceAnimations.collectAsState()
            val fontStyle by app.appearancePrefs.fontStyle.collectAsState()
            val appTextSize by app.appearancePrefs.appTextSize.collectAsState()
            val chatTextSize by app.appearancePrefs.chatTextSize.collectAsState()

            NexaTheme(
                appearanceMode = appearanceMode,
                reduceAnimations = reduceAnimations,
                fontStyle = fontStyle,
                appTextSize = appTextSize,
                chatTextSize = chatTextSize
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppLockGate(secureStorage = app.secureStorage) {
                        val navController = rememberNavController()
                        AuthNavGraph(
                            navController = navController,
                            authRepository = authRepository,
                            chatRepository = chatRepository,
                            p2pTransferRepository = p2pTransferRepository
                        )
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (TcSdk.getInstance().isOAuthFlowUsable) {
                TcSdk.getInstance().onActivityResultObtained(this, requestCode, resultCode, data)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "TcSdk onActivityResult error: ${e.message}")
        }
    }
}
