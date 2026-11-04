package com.nexa.app.presentation.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nexa.app.NexaApplication
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.P2PTransferRepository
import com.nexa.app.presentation.badge.ProfileBadgeScreen
import com.nexa.app.presentation.chat.ChatScreen
import com.nexa.app.presentation.chat.ChatViewModel
import com.nexa.app.presentation.contacts.ContactListScreen
import com.nexa.app.presentation.contacts.ContactViewModel
import com.nexa.app.presentation.dashboard.MainChatDashboardScreen
import com.nexa.app.presentation.otp.OtpVerificationScreen
import com.nexa.app.presentation.otp.OtpViewModel
import com.nexa.app.presentation.permissions.PermissionGatewayScreen
import com.nexa.app.presentation.permissions.PermissionViewModel
import com.nexa.app.presentation.qr.QrDisplayScreen
import com.nexa.app.presentation.qr.QrScannerScreen
import com.nexa.app.presentation.qr.QrViewModel
import com.nexa.app.presentation.setup.ProfileSetupScreen
import com.nexa.app.presentation.setup.SetupViewModel
import com.nexa.app.presentation.splash.SplashScreen
import com.nexa.app.presentation.splash.SplashViewModel
import com.nexa.app.presentation.transfer.TransferViewModel
import com.nexa.app.presentation.welcome.WelcomeScreen
import com.nexa.app.presentation.welcome.WelcomeViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Welcome : Screen("welcome")
    data object ProfileSetup : Screen("profile_setup?googleToken={googleToken}") {
        fun createRoute(googleToken: String?): String {
            return if (!googleToken.isNullOrBlank()) {
                "profile_setup?googleToken=${Uri.encode(googleToken)}"
            } else {
                "profile_setup"
            }
        }
    }
    data object OtpVerification : Screen("otp_verification?phone={phone}&name={name}&age={age}&gender={gender}&about={about}") {
        fun createRoute(
            phone: String,
            name: String,
            age: Int?,
            gender: String?,
            about: String?
        ): String {
            val encodedPhone = Uri.encode(phone)
            val encodedName = Uri.encode(name)
            val ageParam = age?.toString() ?: ""
            val genderParam = Uri.encode(gender ?: "")
            val aboutParam = Uri.encode(about ?: "")
            return "otp_verification?phone=$encodedPhone&name=$encodedName&age=$ageParam&gender=$genderParam&about=$aboutParam"
        }
    }
    data object ProfileBadge : Screen("profile_badge/{nxId}/{name}/{phone}") {
        fun createRoute(nxId: String, name: String, phone: String): String {
            return "profile_badge/${Uri.encode(nxId)}/${Uri.encode(name)}/${Uri.encode(phone)}"
        }
    }
    data object PermissionGateway : Screen("permission_gateway")
    data object Dashboard : Screen("dashboard")
    data object Chat : Screen("chat/{conversationId}/{participantNxId}/{participantName}") {
        fun createRoute(conversationId: String, participantNxId: String, participantName: String): String {
            return "chat/${Uri.encode(conversationId)}/${Uri.encode(participantNxId)}/${Uri.encode(participantName)}"
        }
    }
    data object QrDisplay : Screen("qr_display")
    data object QrScanner : Screen("qr_scanner")
    data object Contacts : Screen("contacts")
}

@Composable
fun AuthNavGraph(
    navController: NavHostController,
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
    p2pTransferRepository: P2PTransferRepository
) {
    val coroutineScope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        // 1. Splash Screen
        composable(route = Screen.Splash.route) {
            val splashViewModel = remember { SplashViewModel(authRepository) }
            SplashScreen(
                viewModel = splashViewModel,
                onNavigateToDashboard = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToWelcome = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // 2. Welcome Screen
        composable(route = Screen.Welcome.route) {
            val welcomeViewModel: WelcomeViewModel = viewModel()
            WelcomeScreen(
                viewModel = welcomeViewModel,
                onNavigateToProfileSetup = { googleToken ->
                    navController.navigate(Screen.ProfileSetup.createRoute(googleToken))
                }
            )
        }

        // 3. Profile Setup Screen
        composable(
            route = Screen.ProfileSetup.route,
            arguments = listOf(
                navArgument("googleToken") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val googleToken = backStackEntry.arguments?.getString("googleToken")
            val setupViewModel = remember { SetupViewModel(authRepository) }

            ProfileSetupScreen(
                viewModel = setupViewModel,
                googleToken = googleToken,
                onBack = { navController.popBackStack() },
                onProceedToOtp = { name, phone, age, gender, about ->
                    navController.navigate(
                        Screen.OtpVerification.createRoute(
                            phone = phone,
                            name = name,
                            age = age,
                            gender = gender,
                            about = about
                        )
                    )
                }
            )
        }

        // 4. OTP Verification Screen
        composable(
            route = Screen.OtpVerification.route,
            arguments = listOf(
                navArgument("phone") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("age") { type = NavType.StringType; defaultValue = "" },
                navArgument("gender") { type = NavType.StringType; defaultValue = "" },
                navArgument("about") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val age = backStackEntry.arguments?.getString("age")?.toIntOrNull()
            val gender = backStackEntry.arguments?.getString("gender").takeIf { !it.isNullOrBlank() }
            val about = backStackEntry.arguments?.getString("about").takeIf { !it.isNullOrBlank() }

            val otpViewModel = remember { OtpViewModel(authRepository) }

            OtpVerificationScreen(
                viewModel = otpViewModel,
                phone = phone,
                name = name,
                age = age,
                gender = gender,
                about = about,
                onBack = { navController.popBackStack() },
                onProceedToBadge = { nxId, verifiedName, verifiedPhone ->
                    navController.navigate(
                        Screen.ProfileBadge.createRoute(
                            nxId = nxId,
                            name = verifiedName,
                            phone = verifiedPhone
                        )
                    ) {
                        popUpTo(Screen.ProfileSetup.route) { inclusive = true }
                    }
                }
            )
        }

        // 5. Profile Badge Screen
        composable(
            route = Screen.ProfileBadge.route,
            arguments = listOf(
                navArgument("nxId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("phone") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val nxId = backStackEntry.arguments?.getString("nxId") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val phone = backStackEntry.arguments?.getString("phone") ?: ""

            ProfileBadgeScreen(
                name = name,
                phone = phone,
                nxId = nxId,
                onContinueToPermissions = {
                    navController.navigate(Screen.PermissionGateway.route) {
                        popUpTo(Screen.ProfileBadge.route) { inclusive = true }
                    }
                }
            )
        }

        // 6. Permission Gateway Screen
        composable(route = Screen.PermissionGateway.route) {
            val permissionViewModel: PermissionViewModel = viewModel()
            PermissionGatewayScreen(
                viewModel = permissionViewModel,
                onAllPermissionsProcessed = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        // 7. Main Dashboard Screen
        composable(route = Screen.Dashboard.route) {
            MainChatDashboardScreen(
                authRepository = authRepository,
                chatRepository = chatRepository,
                onOpenChat = { conversationId, participantNxId, participantName ->
                    navController.navigate(Screen.Chat.createRoute(conversationId, participantNxId, participantName))
                },
                onOpenQr = {
                    navController.navigate(Screen.QrDisplay.route)
                },
                onOpenContacts = {
                    navController.navigate(Screen.Contacts.route)
                },
                onLogout = {
                    coroutineScope.launch {
                        authRepository.clearSession()
                        chatRepository.stopConnection()
                        navController.navigate(Screen.Welcome.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        // 8. One-on-One Chat Screen
        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("participantNxId") { type = NavType.StringType },
                navArgument("participantName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            val participantNxId = backStackEntry.arguments?.getString("participantNxId") ?: ""
            val participantName = backStackEntry.arguments?.getString("participantName") ?: ""

            val app = LocalContext.current.applicationContext as NexaApplication
            val chatViewModel = remember {
                ChatViewModel(
                    chatRepository = chatRepository,
                    mediaProcessingRepository = app.mediaProcessingRepository,
                    mediaPrefs = app.mediaPrefs
                )
            }
            val transferViewModel = remember { TransferViewModel(p2pTransferRepository) }

            ChatScreen(
                viewModel = chatViewModel,
                transferViewModel = transferViewModel,
                conversationId = conversationId,
                participantNxId = participantNxId,
                participantName = participantName,
                onBack = { navController.popBackStack() }
            )
        }

        // 9. Instant Cryptographic QR Identity Handshake Screen
        composable(route = Screen.QrDisplay.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val qrViewModel = remember { QrViewModel(app.qrHandshakeRepository, app.googleCodeScannerEngine) }

            QrDisplayScreen(
                viewModel = qrViewModel,
                onNavigateToCameraXScanner = {
                    navController.navigate(Screen.QrScanner.route)
                },
                onBack = { navController.popBackStack() },
                onStartConversation = { verifiedPeer ->
                    coroutineScope.launch {
                        val chat = chatRepository.createOrGetConversation(
                            participantNxId = verifiedPeer.nxId,
                            participantName = verifiedPeer.displayName
                        )
                        navController.navigate(
                            Screen.Chat.createRoute(
                                chat.conversationId,
                                chat.participantNxId,
                                chat.participantName
                            )
                        )
                    }
                }
            )
        }

        // 10. CameraX Scanner Fallback Screen (for de-Googled ROMs)
        composable(route = Screen.QrScanner.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val qrViewModel = remember { QrViewModel(app.qrHandshakeRepository, app.googleCodeScannerEngine) }

            QrScannerScreen(
                viewModel = qrViewModel,
                onBack = { navController.popBackStack() },
                onScannedSuccessfully = {
                    navController.popBackStack()
                }
            )
        }

        // 11. Dedicated Contacts Screen (Address Book + NEXA Discovery)
        composable(route = Screen.Contacts.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val contactViewModel = remember { ContactViewModel(app.contactRepository) }

            ContactListScreen(
                viewModel = contactViewModel,
                onBack = { navController.popBackStack() },
                onStartChat = { participantNxId, participantName ->
                    coroutineScope.launch {
                        val chat = chatRepository.createOrGetConversation(
                            participantNxId = participantNxId,
                            participantName = participantName
                        )
                        navController.navigate(
                            Screen.Chat.createRoute(
                                chat.conversationId,
                                chat.participantNxId,
                                chat.participantName
                            )
                        )
                    }
                },
                onNavigateToPending = {
                    // Navigate to pending invitations if needed
                }
            )
        }
    }
}
