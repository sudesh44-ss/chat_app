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
import com.nexa.app.presentation.backup.BackupSettingsScreen
import com.nexa.app.presentation.backup.BackupViewModel
import com.nexa.app.presentation.backup.RestoreBackupScreen
import com.nexa.app.presentation.common.viewer.ProfileImageViewerScreen
import com.nexa.app.presentation.chat.ChatScreen
import com.nexa.app.presentation.chat.ChatViewModel
import com.nexa.app.presentation.contacts.ContactListScreen
import com.nexa.app.presentation.contacts.ContactViewModel
import com.nexa.app.presentation.contacts.PendingRequestsScreen
import com.nexa.app.presentation.dashboard.MainChatDashboardScreen
import com.nexa.app.presentation.otp.OtpVerificationScreen
import com.nexa.app.presentation.otp.OtpViewModel
import com.nexa.app.presentation.permissions.PermissionGatewayScreen
import com.nexa.app.presentation.permissions.PermissionViewModel
import com.nexa.app.presentation.qr.QrDisplayScreen
import com.nexa.app.presentation.qr.QrScannerScreen
import com.nexa.app.presentation.qr.QrViewModel
import com.nexa.app.presentation.radar.VerificationRadarScreen
import com.nexa.app.presentation.radar.VerificationRadarViewModel
import com.nexa.app.presentation.search.NexaSearchScreen
import com.nexa.app.presentation.settings.notifications.NotificationSettingsScreen
import com.nexa.app.presentation.settings.notifications.NotificationSettingsViewModel
import com.nexa.app.presentation.settings.SettingsScreen
import com.nexa.app.presentation.settings.SettingsDestination
import com.nexa.app.presentation.settings.account.AccountScreen
import com.nexa.app.presentation.settings.account.ChangePhoneNumberScreen
import com.nexa.app.presentation.settings.account.SecurityScreen
import com.nexa.app.presentation.settings.account.AccountStatusScreen
import com.nexa.app.presentation.settings.account.deactivation.AccountDeactivatedScreen
import com.nexa.app.presentation.settings.account.deletion.AccountDeletionFlow
import com.nexa.app.presentation.settings.appearance.AppearanceSettingsScreen
import com.nexa.app.presentation.profile.ContactProfileScreen
import com.nexa.app.presentation.profile.ContactProfileViewModel
import com.nexa.app.presentation.contacts.details.ContactDetailsScreen
import com.nexa.app.presentation.media_explorer.MediaExplorerScreen
import com.nexa.app.presentation.media_explorer.MediaExplorerViewModel
import com.nexa.app.presentation.chat.settings.ChatWallpaperScreen
import com.nexa.app.presentation.chat.settings.PinnedMessagesScreen
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

    data object ProfileSetup : Screen("profile_setup?googleToken={googleToken}&googleUid={googleUid}&prefillName={prefillName}&prefillAvatar={prefillAvatar}&email={email}") {
        fun createRoute(
            googleToken: String? = null,
            googleUid: String? = null,
            prefillName: String? = null,
            prefillAvatar: String? = null,
            email: String? = null
        ): String {
            val t = Uri.encode(googleToken ?: "")
            val u = Uri.encode(googleUid ?: "")
            val n = Uri.encode(prefillName ?: "")
            val a = Uri.encode(prefillAvatar ?: "")
            val e = Uri.encode(email ?: "")
            return "profile_setup?googleToken=$t&googleUid=$u&prefillName=$n&prefillAvatar=$a&email=$e"
        }
    }

    data object VerificationRadar : Screen(
        "verification_radar?nonce={nonce}&inboundNumber={inboundNumber}&smsPayload={smsPayload}&expiresIn={expiresIn}&phone={phone}&name={name}&avatarUrl={avatarUrl}&age={age}&gender={gender}&bio={bio}&isReverify={isReverify}"
    ) {
        fun createRoute(
            nonce: String,
            inboundNumber: String,
            smsPayload: String,
            expiresIn: Int = 90,
            phone: String,
            name: String,
            avatarUrl: String? = null,
            age: Int? = null,
            gender: String? = null,
            bio: String? = null,
            isReverify: Boolean = false
        ): String {
            val n = Uri.encode(nonce)
            val inNum = Uri.encode(inboundNumber)
            val p = Uri.encode(smsPayload)
            val ph = Uri.encode(phone)
            val nm = Uri.encode(name)
            val av = Uri.encode(avatarUrl ?: "")
            val ag = age?.toString() ?: ""
            val gn = Uri.encode(gender ?: "")
            val bi = Uri.encode(bio ?: "")
            return "verification_radar?nonce=$n&inboundNumber=$inNum&smsPayload=$p&expiresIn=$expiresIn&phone=$ph&name=$nm&avatarUrl=$av&age=$ag&gender=$gn&bio=$bi&isReverify=$isReverify"
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

    data object ProfileBadge : Screen("profile_badge/{nxId}/{name}/{phone}?avatarUrl={avatarUrl}") {
        fun createRoute(nxId: String, name: String, phone: String, avatarUrl: String? = null): String {
            val av = Uri.encode(avatarUrl ?: "")
            return "profile_badge/${Uri.encode(nxId)}/${Uri.encode(name)}/${Uri.encode(phone)}?avatarUrl=$av"
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
    data object NotificationSettings : Screen("notification_settings")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data object Account : Screen("account")
    data object ChangePhoneNumber : Screen("change_phone_number")
    data object Security : Screen("security")
    data object AccountStatus : Screen("account_status")
    data object AccountDeactivated : Screen("account_deactivated")
    data object AccountDeletion : Screen("account_deletion")
    data object Appearance : Screen("appearance")
    data object ContactProfile : Screen("contact_profile/{participantNxId}/{conversationId}") {
        fun createRoute(participantNxId: String, conversationId: String): String {
            return "contact_profile/${Uri.encode(participantNxId)}/${Uri.encode(conversationId)}"
        }
    }
    data object ContactDetails : Screen("contact_details/{participantNxId}/{conversationId}") {
        fun createRoute(participantNxId: String, conversationId: String): String {
            return "contact_details/${Uri.encode(participantNxId)}/${Uri.encode(conversationId)}"
        }
    }
    data object MediaExplorer : Screen("media_explorer/{conversationId}/{participantName}") {
        fun createRoute(conversationId: String, participantName: String): String {
            return "media_explorer/${Uri.encode(conversationId)}/${Uri.encode(participantName)}"
        }
    }
    data object ChatWallpaper : Screen("chat_wallpaper/{conversationId}") {
        fun createRoute(conversationId: String): String {
            return "chat_wallpaper/${Uri.encode(conversationId)}"
        }
    }
    data object PinnedMessages : Screen("pinned_messages/{conversationId}") {
        fun createRoute(conversationId: String): String {
            return "pinned_messages/${Uri.encode(conversationId)}"
        }
    }
    data object ProfileImageViewer : Screen("profile_image_viewer?imageUri={imageUri}") {
        fun createRoute(imageUri: String): String {
            return "profile_image_viewer?imageUri=${Uri.encode(imageUri)}"
        }
    }
    data object BackupSettings : Screen("backup_settings")
    data object RestoreBackup : Screen("restore_backup")
    data object PendingRequests : Screen("pending_requests")
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
                    if (navController.currentDestination?.route == Screen.Splash.route) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                },
                onNavigateToWelcome = {
                    if (navController.currentDestination?.route == Screen.Splash.route) {
                        navController.navigate(Screen.Welcome.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        // 2. Welcome Screen
        composable(route = Screen.Welcome.route) {
            val welcomeViewModel = remember { WelcomeViewModel(authRepository) }
            WelcomeScreen(
                viewModel = welcomeViewModel,
                onNavigateToDashboard = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToProfileSetup = { googleToken, googleUid, prefillName, prefillAvatar, email ->
                    navController.navigate(
                        Screen.ProfileSetup.createRoute(
                            googleToken = googleToken,
                            googleUid = googleUid,
                            prefillName = prefillName,
                            prefillAvatar = prefillAvatar,
                            email = email
                        )
                    )
                },
                onNavigateToSimVerify = { phone, googleUid, inboundNumber ->
                    navController.navigate(
                        Screen.VerificationRadar.createRoute(
                            nonce = "NX-INIT",
                            inboundNumber = inboundNumber ?: "+917887711223",
                            smsPayload = "NX-VERIFY",
                            phone = phone,
                            name = "NEXA User",
                            isReverify = true
                        )
                    )
                }
            )
        }

        // 3. Profile Setup Screen
        composable(
            route = Screen.ProfileSetup.route,
            arguments = listOf(
                navArgument("googleToken") { type = NavType.StringType; defaultValue = "" },
                navArgument("googleUid") { type = NavType.StringType; defaultValue = "" },
                navArgument("prefillName") { type = NavType.StringType; defaultValue = "" },
                navArgument("prefillAvatar") { type = NavType.StringType; defaultValue = "" },
                navArgument("email") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val googleToken = backStackEntry.arguments?.getString("googleToken").takeIf { !it.isNullOrBlank() }
            val googleUid = backStackEntry.arguments?.getString("googleUid").takeIf { !it.isNullOrBlank() }
            val prefillName = backStackEntry.arguments?.getString("prefillName").takeIf { !it.isNullOrBlank() }
            val prefillAvatar = backStackEntry.arguments?.getString("prefillAvatar").takeIf { !it.isNullOrBlank() }
            val email = backStackEntry.arguments?.getString("email").takeIf { !it.isNullOrBlank() }

            val setupViewModel = remember { SetupViewModel(authRepository) }

            ProfileSetupScreen(
                viewModel = setupViewModel,
                googleToken = googleToken,
                googleUid = googleUid,
                prefillName = prefillName,
                prefillAvatar = prefillAvatar,
                email = email,
                onBack = { navController.popBackStack() },
                onProceedToReverseVerify = { nonce, inboundNumber, smsPayload, expiresInSeconds, name, phone, avatarUrl, age, gender, bio ->
                    navController.navigate(
                        Screen.VerificationRadar.createRoute(
                            nonce = nonce,
                            inboundNumber = inboundNumber,
                            smsPayload = smsPayload,
                            expiresIn = expiresInSeconds,
                            phone = phone,
                            name = name,
                            avatarUrl = avatarUrl,
                            age = age,
                            gender = gender,
                            bio = bio
                        )
                    )
                },
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
                },
                onProceedToBadge = { nxId, name, phone, avatarUrl ->
                    navController.navigate(
                        Screen.ProfileBadge.createRoute(
                            nxId = nxId,
                            name = name,
                            phone = phone,
                            avatarUrl = avatarUrl
                        )
                    ) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        // 4. Verification Radar Screen (Zero-Cost UPI Reverse SMS)
        composable(
            route = Screen.VerificationRadar.route,
            arguments = listOf(
                navArgument("nonce") { type = NavType.StringType; defaultValue = "" },
                navArgument("inboundNumber") { type = NavType.StringType; defaultValue = "" },
                navArgument("smsPayload") { type = NavType.StringType; defaultValue = "" },
                navArgument("expiresIn") { type = NavType.IntType; defaultValue = 90 },
                navArgument("phone") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("avatarUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("age") { type = NavType.StringType; defaultValue = "" },
                navArgument("gender") { type = NavType.StringType; defaultValue = "" },
                navArgument("bio") { type = NavType.StringType; defaultValue = "" },
                navArgument("isReverify") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val nonce = backStackEntry.arguments?.getString("nonce") ?: ""
            val inboundNumber = backStackEntry.arguments?.getString("inboundNumber") ?: ""
            val smsPayload = backStackEntry.arguments?.getString("smsPayload") ?: ""
            val expiresIn = backStackEntry.arguments?.getInt("expiresIn") ?: 90
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val avatarUrl = backStackEntry.arguments?.getString("avatarUrl").takeIf { !it.isNullOrBlank() }
            val age = backStackEntry.arguments?.getString("age")?.toIntOrNull()
            val gender = backStackEntry.arguments?.getString("gender").takeIf { !it.isNullOrBlank() }
            val bio = backStackEntry.arguments?.getString("bio").takeIf { !it.isNullOrBlank() }
            val isReverify = backStackEntry.arguments?.getBoolean("isReverify") ?: false

            val radarViewModel = remember { VerificationRadarViewModel(authRepository) }

            VerificationRadarScreen(
                viewModel = radarViewModel,
                nonce = nonce,
                inboundNumber = inboundNumber,
                smsPayload = smsPayload,
                phone = phone,
                name = name,
                avatarUrl = avatarUrl,
                age = age,
                gender = gender,
                bio = bio,
                expiresInSeconds = expiresIn,
                isReverify = isReverify,
                onBack = { navController.popBackStack() },
                onVerificationSuccess = { nxId, verifiedName, verifiedPhone, verifiedAvatar ->
                    navController.navigate(
                        Screen.ProfileBadge.createRoute(
                            nxId = nxId,
                            name = verifiedName,
                            phone = verifiedPhone,
                            avatarUrl = verifiedAvatar
                        )
                    ) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        // 5. Legacy OTP Verification Screen (Fallback)
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

        // 6. Profile Badge Screen (Identity Reveal)
        composable(
            route = Screen.ProfileBadge.route,
            arguments = listOf(
                navArgument("nxId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("phone") { type = NavType.StringType },
                navArgument("avatarUrl") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val nxId = backStackEntry.arguments?.getString("nxId") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val avatarUrl = backStackEntry.arguments?.getString("avatarUrl").takeIf { !it.isNullOrBlank() }

            ProfileBadgeScreen(
                name = name,
                phone = phone,
                nxId = nxId,
                avatarUrl = avatarUrl,
                onContinueToPermissions = {
                    navController.navigate(Screen.PermissionGateway.route) {
                        popUpTo(Screen.ProfileBadge.route) { inclusive = true }
                    }
                }
            )
        }

        // 7. Permission Gateway Screen
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

        // 8. Main Dashboard Screen
        composable(route = Screen.Dashboard.route) {
            MainChatDashboardScreen(
                authRepository = authRepository,
                chatRepository = chatRepository,
                onOpenChat = { conversationId, participantNxId, participantName ->
                    navController.navigate(Screen.Chat.createRoute(conversationId, participantNxId, participantName))
                },
                onOpenSearch = {
                    navController.navigate(Screen.Search.route)
                },
                onOpenQr = {
                    navController.navigate(Screen.QrDisplay.route)
                },
                onOpenContacts = {
                    navController.navigate(Screen.Contacts.route)
                },
                onOpenPendingRequests = {
                    navController.navigate(Screen.PendingRequests.route)
                },
                onOpenNotificationSettings = {
                    navController.navigate(Screen.NotificationSettings.route)
                },
                onOpenProfile = {
                    navController.navigate(Screen.Settings.route)
                },
                onLogout = {
                    coroutineScope.launch {
                        try {
                            authRepository.clearSession()
                            chatRepository.stopConnection()
                        } catch (e: Exception) {
                            android.util.Log.e("AuthNavGraph", "Error clearing session during logout", e)
                        }
                        navController.navigate(Screen.Welcome.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        // 9. One-on-One Chat Screen
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
            val chatViewModel = remember(conversationId) {
                ChatViewModel(
                    chatRepository = chatRepository,
                    mediaProcessingRepository = app.mediaProcessingRepository,
                    mediaPrefs = app.mediaPrefs,
                    chatPrivacyPrefs = app.chatPrivacyPrefs,
                    contactRepository = app.contactRepository
                )
            }
            val transferViewModel = remember(conversationId) { TransferViewModel(p2pTransferRepository) }

            ChatScreen(
                viewModel = chatViewModel,
                transferViewModel = transferViewModel,
                conversationId = conversationId,
                participantNxId = participantNxId,
                participantName = participantName,
                onBack = { navController.popBackStack() },
                onNavigateToProfile = { nxId, convId ->
                    navController.navigate(Screen.ContactProfile.createRoute(nxId, convId))
                },
                onNavigateToMediaExplorer = { convId, name ->
                    navController.navigate(Screen.MediaExplorer.createRoute(convId, name))
                },
                onNavigateToWallpaper = { convId ->
                    navController.navigate(Screen.ChatWallpaper.createRoute(convId))
                },
                onNavigateToPinnedMessages = { convId ->
                    navController.navigate(Screen.PinnedMessages.createRoute(convId))
                }
            )
        }

        // 10. Instant Cryptographic QR Identity Handshake Screen
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

        // 11. CameraX Scanner Fallback Screen (for de-Googled ROMs)
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

        // 12. Dedicated Contacts Screen (Address Book + NEXA Discovery)
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
                    navController.navigate(Screen.PendingRequests.route)
                }
            )
        }

        // 13. Dedicated Notification Settings Screen
        composable(route = Screen.NotificationSettings.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val viewModel = remember {
                NotificationSettingsViewModel(app.notificationPrefs)
            }
            NotificationSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // 14. Full-Screen 1:1 Profile Photo Viewer
        composable(
            route = Screen.ProfileImageViewer.route,
            arguments = listOf(
                navArgument("imageUri") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val imageUri = backStackEntry.arguments?.getString("imageUri") ?: ""
            ProfileImageViewerScreen(
                imageModel = imageUri,
                onCloseClick = { navController.popBackStack() }
            )
        }

        // 15. Dedicated Advanced Search Screen
        composable(route = Screen.Search.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            NexaSearchScreen(
                chatRepository = chatRepository,
                contactRepository = app.contactRepository,
                onNavigateBack = { navController.popBackStack() },
                onOpenChat = { conversationId, participantNxId, participantName ->
                    navController.navigate(Screen.Chat.createRoute(conversationId, participantNxId, participantName))
                }
            )
        }

        // 16. Main Settings Screen (13 Options + Fixed Bottom Nav)
        composable(route = Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Dashboard.route) { inclusive = true }
                    }
                },
                onNavigateDestination = { destination ->
                    when (destination) {
                        SettingsDestination.ACCOUNT -> navController.navigate(Screen.Account.route)
                        SettingsDestination.PRIVACY_AND_SAFETY -> {
                            navController.navigate(Screen.Security.route)
                        }
                        SettingsDestination.SECURITY -> navController.navigate(Screen.Security.route)
                        SettingsDestination.APPEARANCE -> navController.navigate(Screen.Appearance.route)
                        SettingsDestination.NOTIFICATIONS -> navController.navigate(Screen.NotificationSettings.route)
                        SettingsDestination.LANGUAGE -> {}
                        SettingsDestination.ARCHIVED_CHATS -> {}
                        SettingsDestination.NETWORK -> {}
                        SettingsDestination.STORAGE_AND_DATA -> {}
                        SettingsDestination.DEVICES -> navController.navigate(Screen.Security.route)
                        SettingsDestination.BACKUP -> navController.navigate(Screen.BackupSettings.route)
                        SettingsDestination.HELP -> {}
                        SettingsDestination.ABOUT -> navController.navigate(Screen.Security.route)
                    }
                }
            )
        }

        // 17. Account Management Screen (3 Sections)
        composable(route = Screen.Account.route) {
            AccountScreen(
                authRepository = authRepository,
                onNavigateBack = { navController.popBackStack() },
                onNavigateChangePhone = { navController.navigate(Screen.ChangePhoneNumber.route) },
                onNavigateSecurity = { navController.navigate(Screen.Security.route) },
                onNavigateAccountStatus = { navController.navigate(Screen.AccountStatus.route) }
            )
        }

        // 18. Change Phone Number Screen
        composable(route = Screen.ChangePhoneNumber.route) {
            ChangePhoneNumberScreen(
                authRepository = authRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 19. Security & Encryption Screen
        composable(route = Screen.Security.route) {
            SecurityScreen(
                authRepository = authRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 20. Account Status Screen (Deactivation vs Deletion)
        composable(route = Screen.AccountStatus.route) {
            AccountStatusScreen(
                onNavigateBack = { navController.popBackStack() },
                onStartDeactivation = { navController.navigate(Screen.AccountDeactivated.route) },
                onStartDeletion = { navController.navigate(Screen.AccountDeletion.route) }
            )
        }

        // 21. Deactivated Account Screen (Reversible)
        composable(route = Screen.AccountDeactivated.route) {
            AccountDeactivatedScreen(
                authRepository = authRepository,
                onKeepDeactivated = { navController.popBackStack() },
                onReactivatedSuccessfully = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Dashboard.route) { inclusive = true }
                    }
                }
            )
        }

        // 22. Irreversible Permanent Account Deletion Flow (7 Steps)
        composable(route = Screen.AccountDeletion.route) {
            AccountDeletionFlow(
                authRepository = authRepository,
                onNavigateBack = { navController.popBackStack() },
                onAccountDeletedDone = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // 23. Appearance Settings Screen
        composable(route = Screen.Appearance.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            AppearanceSettingsScreen(
                appearancePrefs = app.appearancePrefs,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 24. Contact Profile Screen
        composable(
            route = Screen.ContactProfile.route,
            arguments = listOf(
                navArgument("participantNxId") { type = NavType.StringType },
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val participantNxId = backStackEntry.arguments?.getString("participantNxId") ?: ""
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            val app = LocalContext.current.applicationContext as NexaApplication

            val profileViewModel = remember(participantNxId, conversationId) {
                ContactProfileViewModel(
                    participantNxId = participantNxId,
                    initialConversationId = conversationId,
                    contactRepository = app.contactRepository,
                    chatRepository = chatRepository,
                    contactDao = app.database.contactDao(),
                    chatDao = app.database.chatDao(),
                    messageDao = app.database.messageDao(),
                    fileTransferDao = app.database.fileTransferDao(),
                    userDao = app.database.userDao(),
                    chatPrivacyPrefs = app.chatPrivacyPrefs,
                    conversationPreferences = app.conversationPrefs
                )
            }

            ContactProfileScreen(
                viewModel = profileViewModel,
                onBack = { navController.popBackStack() },
                onOpenChat = { _, _, _ ->
                    navController.popBackStack()
                },
                onOpenContactDetails = { nxId, convId ->
                    navController.navigate(Screen.ContactDetails.createRoute(nxId, convId))
                },
                onOpenMediaExplorer = { convId, name ->
                    navController.navigate(Screen.MediaExplorer.createRoute(convId, name))
                },
                onOpenWallpaper = { convId ->
                    navController.navigate(Screen.ChatWallpaper.createRoute(convId))
                },
                onOpenPinnedMessages = { convId ->
                    navController.navigate(Screen.PinnedMessages.createRoute(convId))
                }
            )
        }

        // 25. Contact Details Screen
        composable(
            route = Screen.ContactDetails.route,
            arguments = listOf(
                navArgument("participantNxId") { type = NavType.StringType },
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val participantNxId = backStackEntry.arguments?.getString("participantNxId") ?: ""
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            val app = LocalContext.current.applicationContext as NexaApplication

            ContactDetailsScreen(
                participantNxId = participantNxId,
                conversationId = conversationId,
                contactDao = app.database.contactDao(),
                userDao = app.database.userDao(),
                contactRepository = app.contactRepository,
                onBack = { navController.popBackStack() },
                onContactRemoved = {
                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                }
            )
        }

        // 26. Media Explorer Screen
        composable(
            route = Screen.MediaExplorer.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("participantName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            val participantName = backStackEntry.arguments?.getString("participantName") ?: ""
            val app = LocalContext.current.applicationContext as NexaApplication

            val explorerViewModel = remember(conversationId) {
                MediaExplorerViewModel(
                    conversationId = conversationId,
                    participantName = participantName,
                    fileTransferDao = app.database.fileTransferDao()
                )
            }

            MediaExplorerScreen(
                viewModel = explorerViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // 27. Chat Wallpaper Screen
        composable(
            route = Screen.ChatWallpaper.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            val app = LocalContext.current.applicationContext as NexaApplication

            ChatWallpaperScreen(
                conversationId = conversationId,
                conversationPreferences = app.conversationPrefs,
                onBack = { navController.popBackStack() }
            )
        }

        // 28. Pinned Messages Screen
        composable(
            route = Screen.PinnedMessages.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            val app = LocalContext.current.applicationContext as NexaApplication

            PinnedMessagesScreen(
                conversationId = conversationId,
                messageDao = app.database.messageDao(),
                chatRepository = chatRepository,
                onBack = { navController.popBackStack() },
                onNavigateToMessage = {
                    navController.popBackStack()
                }
            )
        }

        // 29. Zero-Knowledge Google Drive Backup Settings Screen
        composable(route = Screen.BackupSettings.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val backupViewModel = remember {
                BackupViewModel(
                    backupRepository = app.driveBackupRepository,
                    backupPrefs = app.backupPrefs,
                    authManager = app.googleDriveAuthManager
                )
            }
            BackupSettingsScreen(
                viewModel = backupViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateRestore = { navController.navigate(Screen.RestoreBackup.route) }
            )
        }

        // 30. Zero-Knowledge Backup Restoration Screen (BIP-39 Mnemonic)
        composable(route = Screen.RestoreBackup.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val backupViewModel = remember {
                BackupViewModel(
                    backupRepository = app.driveBackupRepository,
                    backupPrefs = app.backupPrefs,
                    authManager = app.googleDriveAuthManager
                )
            }
            RestoreBackupScreen(
                viewModel = backupViewModel,
                onRestoreSuccess = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Dashboard.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 31. Pending Connection Requests Screen
        composable(route = Screen.PendingRequests.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val contactViewModel = remember { ContactViewModel(app.contactRepository) }

            PendingRequestsScreen(
                viewModel = contactViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
