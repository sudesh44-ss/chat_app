package com.nexa.app.presentation.backup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.backup.ChatRestoreInventoryItem
import com.nexa.app.core.backup.RestoreInventory
import com.nexa.app.core.backup.RestoreMode
import com.nexa.app.core.backup.RestorePlan
import com.nexa.app.core.backup.RestoreResultSummary
import com.nexa.app.core.util.DriveResource
import com.nexa.app.domain.model.BackupRestorePoint
import com.nexa.app.domain.model.RestorePointStatus
import com.nexa.app.presentation.backup.components.BackupProgressCard
import com.nexa.app.presentation.backup.components.MnemonicInputGrid
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Multi-step UI workflow stages for backup restoration.
 */
enum class RestoreUiStep {
    SELECT_POINT,          // Step 1: Select restore point from Drive
    UNLOCK,                // Step 2: 12-word mnemonic entry & decrypt
    INSPECTION_CATEGORIES, // Step 3: View inventory & select categories
    SELECT_CHATS,          // Step 4: Search & select individual chats/groups
    SELECT_MODE,           // Step 5: Merge vs Replace selection & confirmation
    PROGRESS,              // Step 6: Live restore progress
    SUMMARY                // Step 7: Execution results report
}

/**
 * Production 11-step Restore Screen supporting Cloud Discovery, Multi-Restore-Point Selection,
 * In-RAM Decryption, Staged Inventory Analysis, Category & Chat Selection, Merge vs Replace,
 * and Atomic Rollback Safety following the NEXA Liquid Glass Design System.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreBackupScreen(
    viewModel: BackupViewModel,
    onRestoreSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableStateOf(RestoreUiStep.SELECT_POINT) }

    val restorePointsState by viewModel.restorePoints.collectAsState()
    val stagedInventoryState by viewModel.stagedInventory.collectAsState()
    val restoreSummaryState by viewModel.restoreSummary.collectAsState()
    val prefsState by viewModel.preferencesState.collectAsState()
    val restoreCategoryConfig by viewModel.restoreCategoryConfig.collectAsState()

    var selectedPoint by remember { mutableStateOf<BackupRestorePoint?>(null) }
    var loadedInventory by remember { mutableStateOf<RestoreInventory?>(null) }

    val inputWords = remember { mutableStateListOf(*Array(12) { "" }) }
    val selectedChatIds = remember { mutableStateListOf<String>() }
    var restoreMode by remember { mutableStateOf(RestoreMode.MERGE) }
    var replaceConfirmed by remember { mutableStateOf(false) }

    // Prefill 12 words if already saved in Keystore and handle preselected point
    LaunchedEffect(Unit) {
        viewModel.fetchRestorePoints()
        val preselected = viewModel.selectedRestorePointForRestore.value
        if (preselected != null) {
            selectedPoint = preselected
            currentStep = RestoreUiStep.UNLOCK
            viewModel.selectRestorePointForRestore(null)
        }
        viewModel.recoveryPassphrase.value?.let { phrase ->
            val words = Bip39PassphraseHelper.getWords(phrase)
            for (i in 0 until minOf(12, words.size)) {
                inputWords[i] = words[i]
            }
        }
    }

    // Auto-advance to categories when staged inventory decrypts successfully
    LaunchedEffect(stagedInventoryState) {
        if (stagedInventoryState is DriveResource.Success) {
            val inv = (stagedInventoryState as DriveResource.Success<RestoreInventory?>).data
            if (inv != null) {
                loadedInventory = inv
                selectedChatIds.clear()
                selectedChatIds.addAll(inv.chatItems.map { it.conversationId })
                currentStep = RestoreUiStep.INSPECTION_CATEGORIES
            }
        }
    }

    // Auto-advance to summary when restore finishes successfully
    LaunchedEffect(restoreSummaryState) {
        if (restoreSummaryState is DriveResource.Success) {
            currentStep = RestoreUiStep.SUMMARY
        }
    }

    // Handle system back gesture with complete staging cleanup
    val handleBackNavigation: () -> Unit = {
        when (currentStep) {
            RestoreUiStep.SELECT_POINT -> {
                viewModel.cancelActiveRestore()
                onNavigateBack()
            }
            RestoreUiStep.UNLOCK -> {
                viewModel.resetStagedInventory()
                currentStep = RestoreUiStep.SELECT_POINT
            }
            RestoreUiStep.INSPECTION_CATEGORIES -> {
                viewModel.resetStagedInventory()
                currentStep = RestoreUiStep.UNLOCK
            }
            RestoreUiStep.SELECT_CHATS -> currentStep = RestoreUiStep.INSPECTION_CATEGORIES
            RestoreUiStep.SELECT_MODE -> {
                if (restoreCategoryConfig.includeChats) {
                    currentStep = RestoreUiStep.SELECT_CHATS
                } else {
                    currentStep = RestoreUiStep.INSPECTION_CATEGORIES
                }
            }
            RestoreUiStep.PROGRESS -> viewModel.cancelActiveRestore()
            RestoreUiStep.SUMMARY -> onRestoreSuccess()
        }
    }

    BackHandler(onBack = handleBackNavigation)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentStep) {
                            RestoreUiStep.SELECT_POINT -> "Select Restore Point"
                            RestoreUiStep.UNLOCK -> "Unlock Encrypted Vault"
                            RestoreUiStep.INSPECTION_CATEGORIES -> "Select Categories"
                            RestoreUiStep.SELECT_CHATS -> "Select Conversations"
                            RestoreUiStep.SELECT_MODE -> "Restore Mode & Safety"
                            RestoreUiStep.PROGRESS -> "Restoring Data"
                            RestoreUiStep.SUMMARY -> "Restore Complete"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleBackNavigation) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (currentStep) {
                // =========================================================================
                // STEP 1: RESTORE POINT SELECTION
                // =========================================================================
                RestoreUiStep.SELECT_POINT -> {
                    RestorePointSelectionView(
                        state = restorePointsState,
                        onRefresh = { viewModel.fetchRestorePoints() },
                        onSelectPoint = { point ->
                            selectedPoint = point
                            currentStep = RestoreUiStep.UNLOCK
                        }
                    )
                }

                // =========================================================================
                // STEP 2: CREDENTIAL UNLOCK (PASSWORD OR 12-WORD MNEMONIC)
                // =========================================================================
                RestoreUiStep.UNLOCK -> {
                    selectedPoint?.let { point ->
                        UnlockVaultView(
                            point = point,
                            words = inputWords,
                            stagedInventoryState = stagedInventoryState,
                            onWordChange = { idx, w -> inputWords[idx] = w },
                            onPastePhrase = { phrase ->
                                val words = Bip39PassphraseHelper.getWords(phrase)
                                for (i in 0 until minOf(12, words.size)) {
                                    inputWords[i] = words[i]
                                }
                            },
                            onUnlock = { credential ->
                                viewModel.inspectBackup(point.driveFileId, credential)
                            }
                        )
                    }
                }

                // =========================================================================
                // STEP 3: BACKUP INVENTORY & CATEGORY SELECTION
                // =========================================================================
                RestoreUiStep.INSPECTION_CATEGORIES -> {
                    loadedInventory?.let { inv ->
                        CategorySelectionView(
                            inventory = inv,
                            config = restoreCategoryConfig,
                            onConfigChange = { viewModel.updateRestoreCategory { it } },
                            onToggleCategory = { update ->
                                val oldConfig = restoreCategoryConfig
                                val newConfig = update(oldConfig)
                                viewModel.updateRestoreCategory(update)
                                if (oldConfig.includeGroups != newConfig.includeGroups) {
                                    val groupIds = inv.chatItems.filter { it.isGroup }.map { it.conversationId }
                                    if (!newConfig.includeGroups) {
                                        selectedChatIds.removeAll(groupIds)
                                    } else {
                                        selectedChatIds.addAll(groupIds.filter { !selectedChatIds.contains(it) })
                                    }
                                }
                                if (oldConfig.includeChats != newConfig.includeChats) {
                                    if (!newConfig.includeChats) {
                                        selectedChatIds.clear()
                                    } else {
                                        val candidateChats = inv.chatItems
                                            .filter { if (!newConfig.includeGroups) !it.isGroup else true }
                                            .map { it.conversationId }
                                        selectedChatIds.addAll(candidateChats.filter { !selectedChatIds.contains(it) })
                                    }
                                }
                            },
                            onSelectAll = {
                                viewModel.setAllRestoreCategories(true)
                                selectedChatIds.clear()
                                selectedChatIds.addAll(inv.chatItems.map { it.conversationId })
                            },
                            onDeselectAll = {
                                viewModel.setAllRestoreCategories(false)
                                selectedChatIds.clear()
                            },
                            onProceed = {
                                if (restoreCategoryConfig.includeChats && inv.chatItems.isNotEmpty()) {
                                    currentStep = RestoreUiStep.SELECT_CHATS
                                } else {
                                    currentStep = RestoreUiStep.SELECT_MODE
                                }
                            }
                        )
                    }
                }

                // =========================================================================
                // STEP 4: CHAT & GROUP SELECTION
                // =========================================================================
                RestoreUiStep.SELECT_CHATS -> {
                    loadedInventory?.let { inv ->
                        ChatSelectionView(
                            allChats = inv.chatItems,
                            selectedIds = selectedChatIds,
                            onToggleChat = { id ->
                                if (selectedChatIds.contains(id)) {
                                    selectedChatIds.remove(id)
                                } else {
                                    selectedChatIds.add(id)
                                }
                            },
                            onSelectAll = {
                                selectedChatIds.clear()
                                selectedChatIds.addAll(inv.chatItems.map { it.conversationId })
                            },
                            onDeselectAll = {
                                selectedChatIds.clear()
                            },
                            onProceed = {
                                currentStep = RestoreUiStep.SELECT_MODE
                            }
                        )
                    }
                }

                // =========================================================================
                // STEP 5: RESTORE MODE & CONFIRMATION
                // =========================================================================
                RestoreUiStep.SELECT_MODE -> {
                    loadedInventory?.let { inv ->
                        RestoreModeSelectionView(
                            inventory = inv,
                            selectedMode = restoreMode,
                            onSelectMode = { restoreMode = it },
                            replaceConfirmed = replaceConfirmed,
                            onToggleReplaceConfirm = { replaceConfirmed = it },
                            selectedChatCount = selectedChatIds.size,
                            categoryConfig = restoreCategoryConfig,
                            onStartRestore = {
                                val plan = RestorePlan(
                                    mode = restoreMode,
                                    selectedCategories = restoreCategoryConfig,
                                    selectedConversationIds = selectedChatIds.toSet()
                                )
                                viewModel.executeRestorePlan(inv, plan)
                                currentStep = RestoreUiStep.PROGRESS
                            }
                        )
                    }
                }

                // =========================================================================
                // STEP 6: PROGRESS DISPLAY
                // =========================================================================
                RestoreUiStep.PROGRESS -> {
                    val summaryState = restoreSummaryState
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (summaryState) {
                            is DriveResource.Progress -> {
                                BackupProgressCard(
                                    phase = summaryState.phase,
                                    percent = summaryState.percent,
                                    onCancel = {
                                        viewModel.cancelActiveRestore()
                                        currentStep = RestoreUiStep.SELECT_MODE
                                    }
                                )
                            }
                            is DriveResource.Error -> {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Restoration Error", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                        Text(summaryState.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                        OutlinedButton(
                                            onClick = { currentStep = RestoreUiStep.SELECT_MODE },
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Text("Try Again")
                                        }
                                    }
                                }
                            }
                            else -> {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }

                // =========================================================================
                // STEP 7: RESTORE SUMMARY REPORT
                // =========================================================================
                RestoreUiStep.SUMMARY -> {
                    val summary = (restoreSummaryState as? DriveResource.Success<RestoreResultSummary?>)?.data
                    if (summary != null) {
                        RestoreSummaryView(
                            summary = summary,
                            onDone = onRestoreSuccess
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// SUB-VIEWS FOR EACH RESTORE WORKFLOW STEP
// =============================================================================

@Composable
private fun RestorePointSelectionView(
    state: DriveResource<List<BackupRestorePoint>>,
    onRefresh: () -> Unit,
    onSelectPoint: (BackupRestorePoint) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Discovered Cloud Restore Points",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "Select a verified backup restore point from your Google Drive account. Backups are end-to-end encrypted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (state) {
            is DriveResource.Progress -> {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text("Scanning Google Drive appDataFolder...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            is DriveResource.Success -> {
                val points = state.data
                if (points.isNullOrEmpty()) {
                    NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                            Text("No Backups Found", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text("No encrypted backups were discovered in your Google Drive AppData folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Re-scan")
                            }
                        }
                    }
                } else {
                    Text(
                        text = "${points.size} Restore Point(s) Available",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    points.forEach { point ->
                        RestorePointCard(point = point, onSelect = { onSelectPoint(point) })
                    }
                }
            }

            is DriveResource.Error -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun RestorePointCard(
    point: BackupRestorePoint,
    onSelect: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault()).format(Date(point.createdAt))

    NexaGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (point.isLegacy) Icons.Default.FolderZip else Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (point.isLegacy) "Legacy Single Vault" else "Multi-Pillar Restore Point",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(text = dateStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (point.isLegacy) "Format v1" else "Format v2",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricColumn("Size", formatFileSize(point.sizeBytes))
                MetricColumn("Conversations", "${point.totalConversations}")
                MetricColumn("Messages", "${point.totalMessages}")
                MetricColumn("Media Files", "${point.mediaFilesCount}")
            }

            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select for Restoration", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun UnlockVaultView(
    point: BackupRestorePoint,
    words: List<String>,
    stagedInventoryState: DriveResource<RestoreInventory?>,
    onWordChange: (Int, String) -> Unit,
    onPastePhrase: (String) -> Unit,
    onUnlock: (String) -> Unit
) {
    var isMnemonicMode by remember { mutableStateOf(point.isLegacy) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            if (isMnemonicMode) "Recovery Phrase Verification" else "Backup Password Verification",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = if (isMnemonicMode) {
                "Enter your 12-word account recovery phrase to decrypt and inspect this backup vault."
            } else {
                "Enter your backup password to decrypt and inspect this backup vault."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!isMnemonicMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup Password", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        placeholder = { Text("Enter your backup password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { onUnlock(passwordInput) },
                        enabled = passwordInput.isNotBlank() && stagedInventoryState !is DriveResource.Progress,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Decrypt & Inspect Vault", fontWeight = FontWeight.Bold)
                    }

                    TextButton(
                        onClick = { isMnemonicMode = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Use 12-Word Recovery Phrase Instead", fontSize = 13.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("12-Word Recovery Phrase", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }

                    MnemonicInputGrid(
                        words = words,
                        onWordChange = onWordChange,
                        onPasteFullMnemonic = onPastePhrase
                    )

                    val isComplete = words.all { it.isNotBlank() }
                    Button(
                        onClick = { onUnlock(words.joinToString(" ")) },
                        enabled = isComplete && stagedInventoryState !is DriveResource.Progress,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Decrypt & Inspect Vault", fontWeight = FontWeight.Bold)
                    }

                    if (!point.isLegacy) {
                        TextButton(
                            onClick = { isMnemonicMode = false },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Use Backup Password Instead", fontSize = 13.sp)
                        }
                    }
                }

                if (stagedInventoryState is DriveResource.Progress) {
                    BackupProgressCard(
                        phase = stagedInventoryState.phase,
                        percent = stagedInventoryState.percent,
                        onCancel = {}
                    )
                }

                if (stagedInventoryState is DriveResource.Error) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stagedInventoryState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySelectionView(
    inventory: RestoreInventory,
    config: BackupCategoryConfig,
    onConfigChange: () -> Unit,
    onToggleCategory: ((BackupCategoryConfig) -> BackupCategoryConfig) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onProceed: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Backup Inventory & Categories", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(
            text = "The vault was successfully authenticated and inspected. Choose which data categories to restore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Inventory Header Summary Card
        NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn("Discovered Chats", "${inventory.totalConversations}")
                MetricColumn("Messages", "${inventory.totalMessages}")
                MetricColumn("Media Files", "${inventory.mediaFilesCount}")
                MetricColumn("Vault Format", "v${inventory.formatVersion}")
            }
        }

        // Category Selection Surface
        NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Categories", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Row {
                        TextButton(onClick = onSelectAll) { Text("All", fontSize = 12.sp) }
                        TextButton(onClick = onDeselectAll) { Text("None", fontSize = 12.sp) }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                CategoryToggleRow("Chats & Conversations", "${inventory.totalMessages} messages, contacts & metadata", config.includeChats) {
                    onToggleCategory {
                        val next = !it.includeChats
                        it.copy(includeChats = next, includeGroups = next, includeContacts = next)
                    }
                }
                CategoryToggleRow("Images & Photos", "Photos, camera images & screenshots", config.includeImages) {
                    onToggleCategory { it.copy(includeImages = !it.includeImages) }
                }
                CategoryToggleRow("Videos", "Video messages & clips", config.includeVideos) {
                    onToggleCategory { it.copy(includeVideos = !it.includeVideos) }
                }
                CategoryToggleRow("Documents", "PDFs, spreadsheets, text documents", config.includeDocuments) {
                    onToggleCategory { it.copy(includeDocuments = !it.includeDocuments) }
                }
                CategoryToggleRow("Audio & Voice Notes", "Voice recordings & sound clips", config.includeAudio) {
                    onToggleCategory { it.copy(includeAudio = !it.includeAudio) }
                }
                CategoryToggleRow("Other Files", "Archives, downloads, misc attachments", config.includeOtherFiles) {
                    onToggleCategory { it.copy(includeOtherFiles = !it.includeOtherFiles) }
                }
                CategoryToggleRow("Settings & App Data", "Themes, preferences, chat layouts (zero secrets)", config.includeSettings) {
                    onToggleCategory {
                        val next = !it.includeSettings
                        it.copy(
                            includeSettings = next,
                            includeAppSettings = next,
                            includeUserPrefs = next,
                            includeBookmarks = next
                        )
                    }
                }
            }
        }

        Button(
            onClick = onProceed,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = if (config.includeChats) "Next: Select Conversations" else "Next: Select Restore Mode",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ChatSelectionView(
    allChats: List<ChatRestoreInventoryItem>,
    selectedIds: List<String>,
    onToggleChat: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onProceed: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf("ALL") } // ALL, DIRECT, GROUP

    val filteredChats = remember(allChats, searchQuery, filterType) {
        allChats.filter { chat ->
            val matchesSearch = searchQuery.isBlank() || chat.title.contains(searchQuery, ignoreCase = true) || chat.conversationId.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (filterType) {
                "DIRECT" -> !chat.isGroup
                "GROUP" -> chat.isGroup
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Select Conversations", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search by name or ID...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        // Filter Chips & Selection Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = filterType == "ALL",
                    onClick = { filterType = "ALL" },
                    label = { Text("All (${allChats.size})", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filterType == "DIRECT",
                    onClick = { filterType = "DIRECT" },
                    label = { Text("Direct", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filterType == "GROUP",
                    onClick = { filterType = "GROUP" },
                    label = { Text("Groups", fontSize = 11.sp) }
                )
            }

            Row {
                TextButton(onClick = onSelectAll) { Text("All", fontSize = 12.sp) }
                TextButton(onClick = onDeselectAll) { Text("None", fontSize = 12.sp) }
            }
        }

        // Chat List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredChats, key = { it.conversationId }) { chat ->
                val isSelected = selectedIds.contains(chat.conversationId)
                NexaGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleChat(chat.conversationId) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleChat(chat.conversationId) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (chat.isGroup) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (chat.isGroup) Icons.Default.Group else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (chat.isGroup) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = chat.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (chat.existsLocally) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text("Local", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                            Text(
                                text = "${chat.messageCount} msg · ${chat.attachmentCount} media" + if (chat.existsLocally) " (${chat.localMessageCount} local)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onProceed,
            enabled = selectedIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Next: Restore Mode (${selectedIds.size} selected)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RestoreModeSelectionView(
    inventory: RestoreInventory,
    selectedMode: RestoreMode,
    onSelectMode: (RestoreMode) -> Unit,
    replaceConfirmed: Boolean,
    onToggleReplaceConfirm: (Boolean) -> Unit,
    selectedChatCount: Int,
    categoryConfig: BackupCategoryConfig,
    onStartRestore: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Choose Restoration Mode", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(
            text = "Select how backup data should interact with your current device data.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // MERGE MODE CARD
        NexaGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (selectedMode == RestoreMode.MERGE) 2.dp else 0.dp,
                    color = if (selectedMode == RestoreMode.MERGE) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onSelectMode(RestoreMode.MERGE) }
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedMode == RestoreMode.MERGE,
                        onClick = { onSelectMode(RestoreMode.MERGE) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MERGE Mode", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Recommended", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text("Non-destructive · Deduplicates messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    text = "Preserves existing local conversations and messages. Missing chats and messages are imported. Duplicate messages are automatically detected and skipped. Updated snippets are applied if backup has newer messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // REPLACE MODE CARD
        NexaGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (selectedMode == RestoreMode.REPLACE) 2.dp else 0.dp,
                    color = if (selectedMode == RestoreMode.REPLACE) MaterialTheme.colorScheme.error else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onSelectMode(RestoreMode.REPLACE) }
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedMode == RestoreMode.REPLACE,
                        onClick = { onSelectMode(RestoreMode.REPLACE) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("REPLACE Mode", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Destructive", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text("Scoped replacement · Preserves identity & unselected chats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    text = "Deletes current messages for selected conversations and replaces them entirely with backup data. Unselected chats, local user credentials, and device encryption keys are strictly preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (selectedMode == RestoreMode.REPLACE) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = replaceConfirmed,
                            onCheckedChange = { onToggleReplaceConfirm(it) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "I understand that local data for $selectedChatCount selected conversation(s) will be replaced.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Conflict Preview Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Transactional Safety & Rollback", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
                Text(
                    text = "Restoration runs in a single atomic database transaction. If any verification fails, all operations roll back to preserve your local data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val canStart = selectedMode == RestoreMode.MERGE || (selectedMode == RestoreMode.REPLACE && replaceConfirmed)
        Button(
            onClick = onStartRestore,
            enabled = canStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedMode == RestoreMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (selectedMode == RestoreMode.REPLACE) "Execute Scoped Replacement" else "Start Non-Destructive Merge",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RestoreSummaryView(
    summary: RestoreResultSummary,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Success Header
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }

        Text(
            text = "Restoration Successful",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Text(
            text = "Your selected backup data has been verified and committed to the live database.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        // Metrics Grid Card
        NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Restore Metrics", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricColumn("Mode", summary.mode.name)
                    MetricColumn("Chats Restored", "${summary.totalChatsRestored}")
                    MetricColumn("Messages Added", "${summary.totalMessagesRestored}")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricColumn("Duplicates Skipped", "${summary.duplicateMessagesSkipped}")
                    MetricColumn("Media Files", "${summary.mediaFilesRestored}")
                    MetricColumn("Groups Restored", "${summary.groupsRestored}")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricColumn("Contacts Restored", "${summary.contactsRestored}")
                    MetricColumn("Settings Restored", if (summary.settingsRestored) "Yes" else "Skipped")
                    MetricColumn("Elapsed Time", "${summary.elapsedMillis / 1000}s")
                }
            }
        }

        if (summary.warnings.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Warnings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    summary.warnings.forEach { w ->
                        Text("• $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Complete & Return to Chats", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun CategoryToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
