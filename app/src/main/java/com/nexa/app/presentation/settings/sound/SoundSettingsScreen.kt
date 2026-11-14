package com.nexa.app.presentation.settings.sound

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.sound.AudioValidationResult
import com.nexa.app.data.local.preferences.SoundCategory
import com.nexa.app.data.local.preferences.SoundConfig
import com.nexa.app.data.local.preferences.SoundMode
import com.nexa.app.presentation.settings.components.SettingsHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Section descriptor: title, icon, accent colour, categories contained
// ---------------------------------------------------------------------------
private data class SoundSection(
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val categories: List<SoundCategory>
)

@Composable
fun SoundSettingsScreen(
    viewModel: SoundSettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val state by viewModel.preferencesState.collectAsState()
    val previewingCategory by viewModel.previewingCategory.collectAsState()
    val sections = buildSoundSections()

    // Stop any preview when this screen leaves composition
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }

    Scaffold(
        topBar = {
            SettingsHeader(title = "Sound Settings", onBack = onBack)
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Build sections inline so LazyColumn keeps items lightweight
            sections.forEach { section ->
                item(key = section.title) {
                    SoundSectionCard(
                        section = section,
                        state = state,
                        previewingCategory = previewingCategory,
                        onPreview = { cat ->
                            if (previewingCategory == cat) viewModel.stopPreview()
                            else viewModel.previewSound(cat)
                        },
                        onSetMode = { cat, mode -> viewModel.setMode(cat, mode) },
                        onReset = { cat -> viewModel.resetToDefault(cat) },
                        onAudioSelected = { cat, uri ->
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    viewModel.processSelectedAudioUri(context, uri, cat)
                                }
                                when (result) {
                                    is AudioValidationResult.Success -> {
                                        snackbarHostState.showSnackbar(
                                            "✓ \"${result.displayName}\" set for ${cat.title}"
                                        )
                                    }
                                    is AudioValidationResult.Error -> {
                                        snackbarHostState.showSnackbar("Error: ${result.reason}")
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Bottom spacer so FABs / nav bars don't obscure the last card
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Section definitions
// ---------------------------------------------------------------------------
@Composable
private fun buildSoundSections(): List<SoundSection> {
    return listOf(
        SoundSection(
            title = "Messages",
            icon = Icons.Default.Message,
            accent = MaterialTheme.colorScheme.primary,
            categories = listOf(SoundCategory.MESSAGE, SoundCategory.GROUP_MESSAGE)
        ),
        SoundSection(
            title = "Calls",
            icon = Icons.Default.Call,
            accent = Color(0xFFFF6B35),
            categories = listOf(SoundCategory.CALL_RINGTONE)
        ),
        SoundSection(
            title = "Connection Requests",
            icon = Icons.Default.Link,
            accent = Color(0xFF7C4DFF),
            categories = listOf(SoundCategory.CONNECTION_REQUEST)
        ),
        SoundSection(
            title = "File Transfers",
            icon = Icons.Default.SwapVert,
            accent = MaterialTheme.colorScheme.tertiary,
            categories = listOf(
                SoundCategory.TRANSFER_STARTED,
                SoundCategory.TRANSFER_COMPLETED,
                SoundCategory.TRANSFER_FAILED
            )
        ),
        SoundSection(
            title = "Nearby & Mesh",
            icon = Icons.Default.CellTower,
            accent = Color(0xFF00BCD4),
            categories = listOf(
                SoundCategory.NEARBY_DEVICE,
                SoundCategory.MESH_MESSAGE,
                SoundCategory.MESH_TRANSFER_COMPLETED
            )
        ),
        SoundSection(
            title = "Status",
            icon = Icons.Default.GraphicEq,
            accent = Color(0xFF4CAF50),
            categories = listOf(SoundCategory.NEW_STATUS, SoundCategory.STATUS_INTERACTION)
        )
    )
}

// ---------------------------------------------------------------------------
// Section card
// ---------------------------------------------------------------------------
@Composable
private fun SoundSectionCard(
    section: SoundSection,
    state: Map<SoundCategory, SoundConfig>,
    previewingCategory: SoundCategory?,
    onPreview: (SoundCategory) -> Unit,
    onSetMode: (SoundCategory, SoundMode) -> Unit,
    onReset: (SoundCategory) -> Unit,
    onAudioSelected: (SoundCategory, android.net.Uri) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Section header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(section.accent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = section.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))

            // One row per category, with dividers between them
            section.categories.forEachIndexed { index, category ->
                val config = state[category] ?: SoundConfig(category = category)
                val isPreviewing = previewingCategory == category

                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )
                }

                SoundCategoryRow(
                    config = config,
                    isPreviewing = isPreviewing,
                    sectionAccent = section.accent,
                    onPreviewToggle = { onPreview(category) },
                    onSetMode = { mode -> onSetMode(category, mode) },
                    onReset = { onReset(category) },
                    onAudioSelected = { uri -> onAudioSelected(category, uri) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Individual category row (expandable inline)
// ---------------------------------------------------------------------------
@Composable
private fun SoundCategoryRow(
    config: SoundConfig,
    isPreviewing: Boolean,
    sectionAccent: Color,
    onPreviewToggle: () -> Unit,
    onSetMode: (SoundMode) -> Unit,
    onReset: () -> Unit,
    onAudioSelected: (android.net.Uri) -> Unit
) {
    var expanded by rememberSaveable(config.category.name) { mutableStateOf(false) }
    val context = LocalContext.current

    // File picker launcher
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onAudioSelected(uri)
        } else {
            Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ---- Collapsed / always-visible row ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category icon (music note with accent tint)
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = config.category.title,
                tint = sectionAccent.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )

            Spacer(Modifier.width(10.dp))

            // Title + subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.category.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = config.category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Mode badge
            ModeBadge(mode = config.mode)

            Spacer(Modifier.width(4.dp))

            // Play / Stop preview button
            IconButton(
                onClick = onPreviewToggle,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPreviewing) "Stop preview for ${config.category.title}"
                    else "Preview ${config.category.title}",
                    tint = if (isPreviewing) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ---- Expanded inline panel ----
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp, end = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Three radio buttons: DEFAULT, LOCAL, SILENT
                SoundMode.values().forEach { mode ->
                    val isSelected = config.mode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetMode(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked
                            else Icons.Default.RadioButtonUnchecked,
                            contentDescription = mode.label,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (mode == SoundMode.LOCAL) {
                                val displayedName = when {
                                    config.mode == SoundMode.LOCAL && config.displayName.isNotBlank()
                                            && config.displayName != config.category.defaultDisplayName ->
                                        config.displayName
                                    else -> null
                                }
                                if (displayedName != null) {
                                    Text(
                                        text = displayedName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // "Choose File…" button — always available so the user can update an existing selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            fileLauncher.launch(
                                arrayOf(
                                    "audio/mpeg",
                                    "audio/wav",
                                    "audio/x-wav",
                                    "audio/mp4",
                                    "audio/ogg",
                                    "audio/*"
                                )
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Choose audio file",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Choose File…")
                    }

                    // "Reset to Default" text button
                    TextButton(onClick = onReset) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset to default sound",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Reset to Default",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mode badge chip
// ---------------------------------------------------------------------------
@Composable
private fun ModeBadge(mode: SoundMode) {
    val (label, color) = when (mode) {
        SoundMode.DEFAULT -> Pair("DEFAULT", MaterialTheme.colorScheme.primary)
        SoundMode.LOCAL -> Pair("LOCAL", MaterialTheme.colorScheme.tertiary)
        SoundMode.SILENT -> Pair("SILENT", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Badge(
        containerColor = color.copy(alpha = 0.15f),
        contentColor = color
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
