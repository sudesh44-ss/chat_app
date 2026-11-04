package com.nexa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.nexa.app.core.theme.NexaTheme
import com.nexa.app.presentation.navigation.AuthNavGraph

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NexaApplication
        val authRepository = app.authRepository
        val chatRepository = app.chatRepository
        val p2pTransferRepository = app.p2pTransferRepository

        setContent {
            NexaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
