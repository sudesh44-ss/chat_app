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
import com.nexa.app.presentation.verification.PhoneVerificationSelectionScreen
import com.nexa.app.presentation.verification.PhoneVerificationViewModel
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
import com.nexa.app.presentation.settings.sound.SoundSettingsScreen
import com.nexa.app.presentation.settings.sound.SoundSettingsViewModel
import com.nexa.app.presentation.settings.archive.ArchiveSettingsScreen
import com.nexa.app.presentation.settings.SettingsScreen
import com.nexa.app.presentation.settings.SettingsDestination
import com.nexa.app.presentation.settings.account.AccountScreen
import com.nexa.app.presentation.settings.account.ChangePhoneNumberScreen
import com.nexa.app.presentation.settings.account.SecurityScreen
import com.nexa.app.presentation.settings.account.AccountStatusScreen
import com.nexa.app.presentation.settings.account.deactivation.AccountDeactivatedScreen
import com.nexa.app.presentation.status.StatusScreen
import com.nexa.app.presentation.settings.account.deletion.AccountDeletionFlow
import com.nexa.app.presentation.settings.appearance.AppearanceSettingsScreen
import com.nexa.app.presentation.settings.accessibility.AccessibilityScreen
import com.nexa.app.presentation.settings.chats.ChatFontSizeScreen
import com.nexa.app.presentation.settings.chats.ChatsSettingsScreen
import com.nexa.app.presentation.settings.help.AboutScreen
import com.nexa.app.presentation.settings.help.HelpSupportScreen
import com.nexa.app.presentation.settings.help.ReportBugScreen
import com.nexa.app.presentation.settings.help.RequestSourceCodeScreen
import com.nexa.app.presentation.settings.language.LanguageScreen
import com.nexa.app.presentation.settings.network.NearbyMeshScreen
import com.nexa.app.presentation.settings.network.StorageDataScreen
import com.nexa.app.presentation.settings.privacy.PersonalPrivacyScreen
import com.nexa.app.presentation.settings.privacy.PrivacySafetyScreen
import com.nexa.app.presentation.settings.privacy.BlockedContactsScreen
import com.nexa.app.presentation.settings.security.AppLockScreen
import com.nexa.app.presentation.settings.security.ConnectedDevicesScreen
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
import com.nexa.app.presentation.welcome.WelcomeBackScreen
import com.nexa.app.presentation.backup.BackupDiscoveryScreen
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Welcome : Screen("welcome")

    data object WelcomeBack : Screen(
        "welcome_back?nxId={nxId}&name={name}&avatarUrl={avatarUrl}&maskedPhone={maskedPhone}&phone={phone}&activeDeviceName={activeDeviceName}&activeDeviceId={activeDeviceId}&isSameDevice={isSameDevice}&hasActiveDevice={hasActiveDevice}&inboundNumber={inboundNumber}&googleUid={googleUid}&email={email}"
    ) {
        fun createRoute(
            nxId: String,
            name: String,
            avatarUrl: String? = null,
            maskedPhone: String,
            phone: String,
            activeDeviceName: String? = null,
            activeDeviceId: String? = null,
            isSameDevice: Boolean = false,
            hasActiveDevice: Boolean = false,
            inboundNumber: String? = null,
            googleUid: String? = null,
            email: String? = null
        ): String {
            val nId = Uri.encode(nxId)
            val nm = Uri.encode(name)
            val av = Uri.encode(avatarUrl ?: "")
            val mp = Uri.encode(maskedPhone)
            val ph = Uri.encode(phone)
            val adn = Uri.encode(activeDeviceName ?: "")
            val adi = Uri.encode(activeDeviceId ?: "")
            val inNum = Uri.encode(inboundNumber ?: "")
            val gUid = Uri.encode(googleUid ?: "")
            val em = Uri.encode(email ?: "")
            return "welcome_back?nxId=$nId&name=$nm&avatarUrl=$av&maskedPhone=$mp&phone=$ph&activeDeviceName=$adn&activeDeviceId=$adi&isSameDevice=$isSameDevice&hasActiveDevice=$hasActiveDevice&inboundNumber=$inNum&googleUid=$gUid&email=$em"
        }
    }

    data object BackupDiscovery : Screen("backup_discovery")

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
        "verification_radar?nonce={nonce}&inboundNumber={inboundNumber}&smsPayload={smsPayload}&expiresIn={expiresIn}&phone={phone}&name={name}&avatarUrl={avatarUrl}&age={age}&gender={gender}&bio={bio}&isReverify={isReverify}&email={email}&sessionId={sessionId}&gatewayNumber={gatewayNumber}"
    ) {
        fun createRoute(
            nonce: String,
            inboundNumber: String,
            smsPayload: String,
            expiresIn: Int = 120,
            phone: String,
            name: String,
            avatarUrl: String? = null,
            age: Int? = null,
            gender: String? = null,
            bio: String? = null,
            isReverify: Boolean = false,
            email: String? = null,
            sessionId: String = "",
            gatewayNumber: String? = null
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
            val em = Uri.encode(email ?: "")
            val sid = Uri.encode(sessionId)
            val gw = Uri.encode(gatewayNumber ?: "")
            return "verification_radar?nonce=$n&inboundNumber=$inNum&smsPayload=$p&expiresIn=$expiresIn&phone=$ph&name=$nm&avatarUrl=$av&age=$ag&gender=$gn&bio=$bi&isReverify=$isReverify&email=$em&sessionId=$sid&gatewayNumber=$gw"
        }
    }

    data object PhoneVerificationSelection : Screen(
        "phone_verification_selection?phone={phone}&name={name}&avatarUrl={avatarUrl}&age={age}&gender={gender}&bio={bio}&googleToken={googleToken}&googleUid={googleUid}&email={email}&isReverify={isReverify}"
    ) {
        fun createRoute(
            phone: String,
            name: String,
            avatarUrl: String? = null,
            age: Int? = null,
            gender: String? = null,
            bio: String? = null,
            googleToken: String? = null,
            googleUid: String? = null,
            email: String? = null,
            isReverify: Boolean = false
        ): String {
            val ph = Uri.encode(phone)
            val nm = Uri.encode(name)
            val av = Uri.encode(avatarUrl ?: "")
            val ag = age?.toString() ?: ""
            val gn = Uri.encode(gender ?: "")
            val bi = Uri.encode(bio ?: "")
            val gt = Uri.encode(googleToken ?: "")
            val gu = Uri.encode(googleUid ?: "")
            val em = Uri.encode(email ?: "")
            return "phone_verification_selection?phone=$ph&name=$nm&avatarUrl=$av&age=$ag&gender=$gn&bio=$bi&googleToken=$gt&googleUid=$gu&email=$em&isReverify=$isReverify"
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
    data object Status : Screen("status")
    data object Chat : Screen("chat/{conversationId}/{participantNxId}/{participantName}") {
        fun createRoute(conversationId: String, participantNxId: String, participantName: String): String {
            val safeConversationId = conversationId.ifBlank { "invalid_conversation" }
            val safeParticipantNxId = participantNxId.ifBlank { "unknown_peer" }
            val safeParticipantName = participantName.ifBlank { "Unknown contact" }
            return "chat/${Uri.encode(safeConversationId)}/${Uri.encode(safeParticipantNxId)}/${Uri.encode(safeParticipantName)}"
        }
    }
    data object QrDisplay : Screen("qr_display")
    data object QrScanner : Screen("qr_scanner")
    data object Contacts : Screen("contacts")
    data object NotificationSettings : Screen("notification_settings")
    data object SoundSettings : Screen("sound_settings")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data object Account : Screen("account")
    data object ChangePhoneNumber : Screen("change_phone_number")
    data object Security : Screen("security")
    data object AccountStatus : Screen("account_status")
    data object AccountDeactivated : Screen("account_deactivated")
    data object AccountDeletion : Screen("account_deletion")
    data object Appearance : Screen("appearance")
    data object PersonalPrivacy : Screen("personal_privacy")
    data object PrivacySafety : Screen("privacy_safety")
    data object AppLock : Screen("app_lock")
    data object ChatsSettings : Screen("chats_settings")
    data object ArchiveSettings : Screen("archive_settings")
    data object ChatFontSize : Screen("chat_font_size")
    data object NearbyMesh : Screen("nearby_mesh")
    data object StorageData : Screen("storage_data")
    data object Language : Screen("language")
    data object Accessibility : Screen("accessibility")
    data object ConnectedDevices : Screen("connected_devices")
    data object HelpSupport : Screen("help_support")
    data object ReportBug : Screen("report_bug")
    data object RequestSourceCode : Screen("request_source_code")
    data object About : Screen("about")
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
                onNavigateToWelcomeBack = { resp, email ->
                    val resolvedNxId = resp.nxId?.takeIf { it.isNotBlank() } ?: ""
                    val resolvedName = (resp.fullName ?: resp.prefillName)?.takeIf { it.isNotBlank() } ?: ""
                    val resolvedMaskedPhone = (resp.maskedPhone ?: resp.registeredPhoneMasked ?: resp.phoneNumber)?.takeIf { it.isNotBlank() } ?: ""
                    navController.navigate(
                        Screen.WelcomeBack.createRoute(
                            nxId = resolvedNxId,
                            name = resolvedName,
                            avatarUrl = resp.prefillAvatar,
                            maskedPhone = resolvedMaskedPhone,
                            phone = resp.phoneNumber ?: "",
                            activeDeviceName = resp.activeDeviceName,
                            activeDeviceId = resp.activeDeviceId,
                            isSameDevice = resp.isSameDevice,
                            hasActiveDevice = resp.hasActiveDevice,
                            inboundNumber = resp.inboundReceiverNumber,
                            googleUid = resp.googleUid,
                            email = resp.email ?: email
                        )
                    )
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
                        Screen.PhoneVerificationSelection.createRoute(
                            phone = phone,
                            name = "NEXA User",
                            googleUid = googleUid,
                            isReverify = true
                        )
                    )
                }
            )
        }

        // 2b. Welcome Back Screen (Returning User Lifecycle)
        composable(
            route = Screen.WelcomeBack.route,
            arguments = listOf(
                navArgument("nxId") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("avatarUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("maskedPhone") { type = NavType.StringType; defaultValue = "" },
                navArgument("phone") { type = NavType.StringType; defaultValue = "" },
                navArgument("activeDeviceName") { type = NavType.StringType; defaultValue = "" },
                navArgument("activeDeviceId") { type = NavType.StringType; defaultValue = "" },
                navArgument("isSameDevice") { type = NavType.BoolType; defaultValue = false },
                navArgument("hasActiveDevice") { type = NavType.BoolType; defaultValue = false },
                navArgument("inboundNumber") { type = NavType.StringType; defaultValue = "" },
                navArgument("googleUid") { type = NavType.StringType; defaultValue = "" },
                navArgument("email") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val nxId = backStackEntry.arguments?.getString("nxId") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val avatarUrl = backStackEntry.arguments?.getString("avatarUrl").takeIf { !it.isNullOrBlank() }
            val maskedPhone = backStackEntry.arguments?.getString("maskedPhone") ?: ""
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val activeDeviceName = backStackEntry.arguments?.getString("activeDeviceName").takeIf { !it.isNullOrBlank() }
            val activeDeviceId = backStackEntry.arguments?.getString("activeDeviceId").takeIf { !it.isNullOrBlank() }
            val isSameDevice = backStackEntry.arguments?.getBoolean("isSameDevice") ?: false
            val hasActiveDevice = backStackEntry.arguments?.getBoolean("hasActiveDevice") ?: false
            val inboundNumber = backStackEntry.arguments?.getString("inboundNumber").takeIf { !it.isNullOrBlank() }
            val googleUid = backStackEntry.arguments?.getString("googleUid").takeIf { !it.isNullOrBlank() }
            val email = backStackEntry.arguments?.getString("email").takeIf { !it.isNullOrBlank() }

            WelcomeBackScreen(
                nxId = nxId,
                displayName = name,
                avatarUrl = avatarUrl,
                maskedPhone = maskedPhone,
                phoneNumber = phone,
                hasActiveDevice = hasActiveDevice,
                activeDeviceName = activeDeviceName,
                isSameDevice = isSameDevice,
                onProceedToVerification = { verifiedPhone, verifiedName, verifiedAvatar ->
                    navController.navigate(
                        Screen.PhoneVerificationSelection.createRoute(
                            phone = verifiedPhone,
                            name = verifiedName,
                            avatarUrl = verifiedAvatar,
                            googleUid = googleUid,
                            email = email,
                            isReverify = true
                        )
                    )
                },
                onCancel = {
                    navController.popBackStack()
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
                onProceedToReverseVerify = { nonce, inboundNumber, smsPayload, expiresInSeconds, name, phone, avatarUrl, age, gender, bio, emailParam ->
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
                            bio = bio,
                            email = emailParam ?: email,
                            gatewayNumber = inboundNumber
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
                },
                onProceedToVerificationSelection = { name, phone, avatarUrl, age, gender, bio, emailParam ->
                    navController.navigate(
                        Screen.PhoneVerificationSelection.createRoute(
                            phone = phone,
                            name = name,
                            avatarUrl = avatarUrl,
                            age = age,
                            gender = gender,
                            bio = bio,
                            googleToken = googleToken,
                            googleUid = googleUid,
                            email = emailParam ?: email,
                            isReverify = false
                        )
                    )
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
                navArgument("isReverify") { type = NavType.BoolType; defaultValue = false },
                navArgument("email") { type = NavType.StringType; defaultValue = "" },
                navArgument("sessionId") { type = NavType.StringType; defaultValue = "" },
                navArgument("gatewayNumber") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val nonce = backStackEntry.arguments?.getString("nonce") ?: ""
            val inboundNumber = backStackEntry.arguments?.getString("inboundNumber") ?: ""
            val smsPayload = backStackEntry.arguments?.getString("smsPayload") ?: ""
            val expiresIn = backStackEntry.arguments?.getInt("expiresIn") ?: 120
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val avatarUrl = backStackEntry.arguments?.getString("avatarUrl").takeIf { !it.isNullOrBlank() }
            val age = backStackEntry.arguments?.getString("age")?.toIntOrNull()
            val gender = backStackEntry.arguments?.getString("gender").takeIf { !it.isNullOrBlank() }
            val bio = backStackEntry.arguments?.getString("bio").takeIf { !it.isNullOrBlank() }
            val isReverify = backStackEntry.arguments?.getBoolean("isReverify") ?: false
            val email = backStackEntry.arguments?.getString("email").takeIf { !it.isNullOrBlank() }
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val gatewayNumber = backStackEntry.arguments?.getString("gatewayNumber").takeIf { !it.isNullOrBlank() } ?: inboundNumber

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
                email = email,
                expiresInSeconds = expiresIn,
                sessionId = sessionId,
                gatewayNumber = gatewayNumber,
                isReverify = isReverify,
                onBack = { navController.popBackStack() },
                onVerificationSuccess = { nxId, verifiedName, verifiedPhone, verifiedAvatar ->
                    if (isReverify) {
                        navController.navigate(Screen.BackupDiscovery.route) {
                            popUpTo(Screen.Welcome.route) { inclusive = true }
                        }
                    } else {
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
                }
            )
        }

        // 4a. Phone Verification Selection Screen (Truecaller, Firebase OTP, SIM Radar)
        composable(
            route = Screen.PhoneVerificationSelection.route,
            arguments = listOf(
                navArgument("phone") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("avatarUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("age") { type = NavType.StringType; defaultValue = "" },
                navArgument("gender") { type = NavType.StringType; defaultValue = "" },
                navArgument("bio") { type = NavType.StringType; defaultValue = "" },
                navArgument("googleToken") { type = NavType.StringType; defaultValue = "" },
                navArgument("googleUid") { type = NavType.StringType; defaultValue = "" },
                navArgument("email") { type = NavType.StringType; defaultValue = "" },
                navArgument("isReverify") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val avatarUrl = backStackEntry.arguments?.getString("avatarUrl").takeIf { !it.isNullOrBlank() }
            val age = backStackEntry.arguments?.getString("age")?.toIntOrNull()
            val gender = backStackEntry.arguments?.getString("gender").takeIf { !it.isNullOrBlank() }
            val bio = backStackEntry.arguments?.getString("bio").takeIf { !it.isNullOrBlank() }
            val googleToken = backStackEntry.arguments?.getString("googleToken").takeIf { !it.isNullOrBlank() }
            val googleUid = backStackEntry.arguments?.getString("googleUid").takeIf { !it.isNullOrBlank() }
            val email = backStackEntry.arguments?.getString("email").takeIf { !it.isNullOrBlank() }
            val isReverify = backStackEntry.arguments?.getBoolean("isReverify") ?: false

            val verificationViewModel = remember { PhoneVerificationViewModel(authRepository) }

            PhoneVerificationSelectionScreen(
                viewModel = verificationViewModel,
                phone = phone,
                name = name,
                avatarUrl = avatarUrl,
                age = age,
                gender = gender,
                bio = bio,
                googleToken = googleToken,
                googleUid = googleUid,
                email = email,
                isReverify = isReverify,
                onBack = { navController.popBackStack() },
                onVerificationSuccess = { profile ->
                    if (isReverify) {
                        navController.navigate(Screen.BackupDiscovery.route) {
                            popUpTo(Screen.Welcome.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(
                            Screen.ProfileBadge.createRoute(
                                nxId = profile.nxId,
                                name = profile.fullName,
                                phone = profile.phoneNumber,
                                avatarUrl = profile.avatarUrl
                            )
                        ) {
                            popUpTo(Screen.Welcome.route) { inclusive = true }
                        }
                    }
                },
                onProceedToReverseRadar = { nonce, inboundNumber, payload, expiresIn, sessionId, radarPhone, radarName ->
                    navController.navigate(
                        Screen.VerificationRadar.createRoute(
                            nonce = nonce,
                            inboundNumber = inboundNumber,
                            smsPayload = payload,
                            expiresIn = expiresIn,
                            phone = radarPhone,
                            name = radarName,
                            avatarUrl = avatarUrl,
                            age = age,
                            gender = gender,
                            bio = bio,
                            isReverify = isReverify,
                            email = email,
                            sessionId = sessionId,
                            gatewayNumber = inboundNumber
                        )
                    )
                }
            )
        }

        // 4b. Encrypted Backup Discovery Screen (Post Device Migration)
        composable(route = Screen.BackupDiscovery.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val backupViewModel = remember {
                BackupViewModel(
                    backupRepository = app.driveBackupRepository,
                    backupPrefs = app.backupPrefs,
                    authManager = app.googleDriveAuthManager,
                    context = app
                )
            }
            BackupDiscoveryScreen(
                viewModel = backupViewModel,
                onProceedToRestore = {
                    navController.navigate(Screen.RestoreBackup.route)
                },
                onSkip = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.BackupDiscovery.route) { inclusive = true }
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
            val app = LocalContext.current.applicationContext as NexaApplication

            // Process recreation & security guard: redirect to Welcome/Auth if session is not active
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (!authRepository.isLoggedIn()) {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }

            MainChatDashboardScreen(
                authRepository = authRepository,
                chatRepository = chatRepository,
                statusRepository = app.statusRepository,
                contactRepository = app.contactRepository,
                onOpenChat = { conversationId, participantNxId, participantName ->
                    navController.navigate(Screen.Chat.createRoute(conversationId, participantNxId, participantName))
                },
                onOpenSearch = {
                    navController.navigate(Screen.Search.route)
                },
                onOpenContactProfile = { nxId, conversationId ->
                    navController.navigate(Screen.ContactProfile.createRoute(nxId, conversationId))
                },
                onOpenQr = {
                    navController.navigate(Screen.QrDisplay.route)
                },
                onOpenContacts = {
                    navController.navigate(Screen.Contacts.route)
                },
                onOpenPendingRequests = {
                    navController.navigate(Screen.PendingRequests.route) {
                        launchSingleTop = true
                    }
                },
                onOpenNotificationSettings = {
                    navController.navigate(Screen.NotificationSettings.route)
                },
                onOpenAccount = {
                    navController.navigate(Screen.Account.route) {
                        launchSingleTop = true
                    }
                },
                onOpenSettings = {
                    navController.navigate(Screen.Settings.route) {
                        launchSingleTop = true
                    }
                },
                onOpenStatus = {
                    navController.navigate(Screen.Status.route) {
                        launchSingleTop = true
                    }
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

        // 9. Status / Stories screen
        composable(route = Screen.Status.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            StatusScreen(
                authRepository = authRepository,
                statusRepository = app.statusRepository,
                contactRepository = app.contactRepository,
                onNavigateHome = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Dashboard.route) { inclusive = true }
                    }
                },
                onNavigateProfile = {
                    navController.navigate(Screen.Account.route) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // 10. One-on-One Chat Screen
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
                    contactRepository = app.contactRepository,
                    scheduledMessageManager = app.scheduledMessageManager,
                    smsFallbackRepository = app.smsFallbackRepository
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

        // 14. Sound Settings Screen
        composable(route = Screen.SoundSettings.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val viewModel = remember {
                SoundSettingsViewModel(
                    soundPreferences = app.soundPreferences,
                    soundManager = app.soundManager
                )
            }
            SoundSettingsScreen(
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
                onNavigateProfile = {
                    navController.navigate(Screen.Account.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateDestination = { destination ->
                    when (destination) {
                        SettingsDestination.ACCOUNT -> navController.navigate(Screen.Account.route)
                        SettingsDestination.PERSONAL_PRIVACY -> navController.navigate(Screen.PersonalPrivacy.route)
                        SettingsDestination.PRIVACY_AND_SAFETY -> navController.navigate(Screen.PrivacySafety.route)
                        SettingsDestination.SECURITY -> navController.navigate(Screen.Security.route)
                        SettingsDestination.APP_LOCK -> navController.navigate(Screen.AppLock.route)
                        SettingsDestination.APPEARANCE -> navController.navigate(Screen.Appearance.route)
                        SettingsDestination.NOTIFICATIONS -> navController.navigate(Screen.NotificationSettings.route)
                        SettingsDestination.SOUND -> navController.navigate(Screen.SoundSettings.route)
                        SettingsDestination.CHATS -> navController.navigate(Screen.ChatsSettings.route)
                        SettingsDestination.ARCHIVE -> navController.navigate(Screen.ArchiveSettings.route)
                        SettingsDestination.NEARBY_AND_MESH -> navController.navigate(Screen.NearbyMesh.route)
                        SettingsDestination.STORAGE_AND_DATA -> navController.navigate(Screen.StorageData.route)
                        SettingsDestination.LANGUAGE -> navController.navigate(Screen.Language.route)
                        SettingsDestination.ACCESSIBILITY -> navController.navigate(Screen.Accessibility.route)
                        SettingsDestination.CONNECTED_DEVICES -> navController.navigate(Screen.ConnectedDevices.route)
                        SettingsDestination.BACKUP -> navController.navigate(Screen.BackupSettings.route)
                        SettingsDestination.HELP -> navController.navigate(Screen.HelpSupport.route)
                        SettingsDestination.ABOUT -> navController.navigate(Screen.About.route)
                    }
                }
            )
        }

        // 17. Account Management Screen (3 Sections)
        composable(route = Screen.Account.route) {
            AccountScreen(
                authRepository = authRepository,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = {
                    if (!navController.popBackStack(Screen.Dashboard.route, inclusive = false)) {
                        navController.navigate(Screen.Dashboard.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onNavigateProfile = {
                    navController.navigate(Screen.Account.route) {
                        launchSingleTop = true
                    }
                },
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

        // 23a. Personal Privacy (visibility controls, independent of Privacy & Safety)
        composable(route = Screen.PersonalPrivacy.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            PersonalPrivacyScreen(
                chatPrivacyPrefs = app.chatPrivacyPrefs,
                chatRepository = app.chatRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23b. Blocked Contacts (formerly Privacy & Safety)
        composable(route = Screen.PrivacySafety.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            val scope = rememberCoroutineScope()
            BlockedContactsScreen(
                contactRepository = app.contactRepository,
                chatRepository = app.chatRepository,
                onNavigateToChat = { nxId, displayName ->
                    scope.launch {
                        val conversation = app.chatRepository.createOrGetConversation(nxId, displayName)
                        navController.navigate(Screen.Chat.createRoute(conversation.conversationId, nxId, displayName))
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23c. App Lock (PIN + biometric)
        composable(route = Screen.AppLock.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            AppLockScreen(
                secureStorage = app.secureStorage,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23d. Chats settings and the single canonical font-size screen
        composable(route = Screen.ChatsSettings.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            ChatsSettingsScreen(
                chatsPreferences = app.chatsPrefs,
                onOpenFontSize = { navController.navigate(Screen.ChatFontSize.route) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(route = Screen.ArchiveSettings.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            ArchiveSettingsScreen(
                chatRepository = app.chatRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(route = Screen.ChatFontSize.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            ChatFontSizeScreen(
                chatsPreferences = app.chatsPrefs,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23e. Nearby & Mesh (discovery OFF by default)
        composable(route = Screen.NearbyMesh.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            NearbyMeshScreen(
                nearbyMeshPreferences = app.nearbyMeshPrefs,
                dtnRepository = app.dtnRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23f. Storage & Data (real on-device usage + cache clearing)
        composable(route = Screen.StorageData.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            StorageDataScreen(
                context = app,
                mediaAutoReceivePreferences = app.mediaAutoReceivePrefs,
                storageManager = app.nexaStorageManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23g. Language (persisted locale)
        composable(route = Screen.Language.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            LanguageScreen(
                languagePreferences = app.languagePrefs,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23h. Accessibility
        composable(route = Screen.Accessibility.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            AccessibilityScreen(
                chatsPreferences = app.chatsPrefs,
                accessibilityPreferences = app.accessibilityPrefs,
                onOpenFontSize = { navController.navigate(Screen.ChatFontSize.route) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23i. Connected Devices (real server device list + revocation)
        composable(route = Screen.ConnectedDevices.route) {
            val app = LocalContext.current.applicationContext as NexaApplication
            ConnectedDevicesScreen(
                authRepository = authRepository,
                currentDeviceId = app.secureStorage.getDeviceId() ?: "",
                hardwareSecurityLevel = app.secureStorage.getHardwareSecurityLevel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 23j. Help & Support, bug report, source request, About
        composable(route = Screen.HelpSupport.route) {
            HelpSupportScreen(
                onOpenReportBug = { navController.navigate(Screen.ReportBug.route) },
                onOpenSourceCode = { navController.navigate(Screen.RequestSourceCode.route) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(route = Screen.ReportBug.route) {
            ReportBugScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(route = Screen.RequestSourceCode.route) {
            RequestSourceCodeScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(route = Screen.About.route) {
            AboutScreen(onNavigateBack = { navController.popBackStack() })
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
                    conversationPreferences = app.conversationPrefs,
                    statusRepository = app.statusRepository
                )
            }

            ContactProfileScreen(
                viewModel = profileViewModel,
                onBack = { navController.popBackStack() },
                onOpenChat = { _, participantNxId, participantName ->
                    coroutineScope.launch {
                        val chat = chatRepository.createOrGetConversation(participantNxId, participantName)
                        navController.navigate(
                            Screen.Chat.createRoute(chat.conversationId, chat.participantNxId, chat.participantName)
                        ) {
                            launchSingleTop = true
                        }
                    }
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
                    authManager = app.googleDriveAuthManager,
                    context = app
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
                    authManager = app.googleDriveAuthManager,
                    context = app
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
