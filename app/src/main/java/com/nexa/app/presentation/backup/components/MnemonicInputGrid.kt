package com.nexa.app.presentation.backup.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.backup.Bip39PassphraseHelper

/**
 * 12-word mnemonic phrase input grid.
 * Displays 6 rows of 2 columns with automatic focus cycling and clipboard paste splitting.
 */
@Composable
fun MnemonicInputGrid(
    words: List<String>,
    onWordChange: (index: Int, newWord: String) -> Unit,
    onPasteFullMnemonic: (phrase: String) -> Unit,
    modifier: Modifier = Modifier,
    isReadOnly: Boolean = false
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in 0 until 6) {
            val idxLeft = row * 2
            val idxRight = row * 2 + 1

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MnemonicWordBox(
                    index = idxLeft,
                    word = words.getOrElse(idxLeft) { "" },
                    onWordChange = { text ->
                        if (text.contains(" ") || text.contains("\n")) {
                            onPasteFullMnemonic(text)
                        } else {
                            onWordChange(idxLeft, text.trim().lowercase())
                        }
                    },
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                    isReadOnly = isReadOnly,
                    modifier = Modifier.weight(1f)
                )

                MnemonicWordBox(
                    index = idxRight,
                    word = words.getOrElse(idxRight) { "" },
                    onWordChange = { text ->
                        if (text.contains(" ") || text.contains("\n")) {
                            onPasteFullMnemonic(text)
                        } else {
                            onWordChange(idxRight, text.trim().lowercase())
                        }
                    },
                    onNext = {
                        if (idxRight == 11) {
                            focusManager.clearFocus()
                        } else {
                            focusManager.moveFocus(FocusDirection.Next)
                        }
                    },
                    isReadOnly = isReadOnly,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MnemonicWordBox(
    index: Int,
    word: String,
    onWordChange: (String) -> Unit,
    onNext: () -> Unit,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier
) {
    val isValidBip39 = word.isBlank() || Bip39PassphraseHelper.WORD_LIST.contains(word)
    val borderColor = when {
        !isValidBip39 -> MaterialTheme.colorScheme.error
        word.isNotBlank() -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = modifier
            .height(52.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.width(26.dp)
            )

            if (isReadOnly) {
                Text(
                    text = word.ifBlank { "—" },
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            } else {
                BasicTextField(
                    value = word,
                    onValueChange = onWordChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isValidBip39) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (index == 11) ImeAction.Done else ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { onNext() },
                        onDone = { onNext() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
