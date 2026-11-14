package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Integrated Find in Chat Liquid Glass Top Bar.
 *
 * Features:
 * - Back button: Exits search mode and clears active query
 * - Search field: Frosted glass input with auto-focus and debounce
 * - Clear button: Clears active search text
 * - Match navigation: Up/Down arrow buttons and match counter (e.g. 1 of 5)
 * - Calendar button: Triggers date-based message navigation picker
 */
@Composable
fun FindInChatBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    matchCount: Int,
    currentMatchIndex: Int,
    isSearching: Boolean,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onDatePickerClicked: () -> Unit,
    onDismiss: () -> Unit,
    onToggleResultsList: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val surfaceColor = if (isDark) Color(0xDD121212) else Color(0xEEFFFFFF)
    val inputSurfaceColor = if (isDark) Color(0x33FFFFFF) else Color(0x18000000)
    val contentColor = if (isDark) Color.White else Color(0xFF111111)
    val hintColor = if (isDark) Color(0x88FFFFFF) else Color(0x88000000)
    val accentColor = NexaBadgeMonospace

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
        surfaceColor = surfaceColor,
        elevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Back button
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    onDismiss()
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_arrow_back),
                    contentDescription = "Exit search",
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 2. Search Input Container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(inputSurfaceColor)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_search),
                        contentDescription = null,
                        tint = hintColor,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChanged,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = contentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(accentColor),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    text = "Search messages...",
                                    color = hintColor,
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    )

                    // Spinner or Clear button
                    if (isSearching) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    } else if (query.isNotEmpty()) {
                        IconButton(
                            onClick = onClearQuery,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_close),
                                contentDescription = "Clear search",
                                tint = hintColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 3. Match Counter & Up/Down Steppers (if search has matches)
            if (query.isNotEmpty() && matchCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Match counter badge (clickable to show full list)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0x3300E5FF) else Color(0x2200BCD4))
                            .clickable { onToggleResultsList?.invoke() }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${currentMatchIndex + 1}/$matchCount",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }

                    // Up Arrow (Previous match)
                    IconButton(
                        onClick = onPreviousMatch,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Previous match",
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Down Arrow (Next match)
                    IconButton(
                        onClick = onNextMatch,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Next match",
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else if (query.isNotEmpty() && !isSearching && matchCount == 0) {
                // Zero results pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDark) Color(0x22FF5252) else Color(0x18F44336))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "0 matches",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 4. Calendar date-picker action button
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    onDatePickerClicked()
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = "Jump to date",
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
