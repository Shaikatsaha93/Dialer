package com.example.ui.components

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class KeypadKey(
    val char: Char,
    val letters: String
)

val KeypadMatrix = listOf(
    listOf(KeypadKey('1', ""), KeypadKey('2', "ABC"), KeypadKey('3', "DEF")),
    listOf(KeypadKey('4', "GHI"), KeypadKey('5', "JKL"), KeypadKey('6', "MNO")),
    listOf(KeypadKey('7', "PQRS"), KeypadKey('8', "TUV"), KeypadKey('9', "WXYZ")),
    listOf(KeypadKey('*', ""), KeypadKey('0', "+"), KeypadKey('#', ""))
)

@Composable
fun DtmfKeypad(
    onKeyPressed: (Char) -> Unit,
    modifier: Modifier = Modifier,
    hapticFeedbackEnabled: Boolean = true,
    keySize: Dp = 72.dp,
    spacing: Dp = 16.dp,
    /** Long press, e.g. 0 for "+" on the dial pad; null = long press acts like a tap */
    onKeyLongPressed: ((Char) -> Boolean)? = null
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        KeypadMatrix.forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowKeys.forEach { key ->
                    KeypadButton(
                        key = key,
                        size = keySize,
                        onClick = {
                            if (hapticFeedbackEnabled) {
                                try {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } catch (_: Throwable) {}
                            }
                            onKeyPressed(key.char)
                        },
                        onLongClick = {
                            if (onKeyLongPressed?.invoke(key.char) == true) {
                                if (hapticFeedbackEnabled) {
                                    try {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } catch (_: Throwable) {}
                                }
                            } else {
                                onKeyPressed(key.char)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeypadButton(
    key: KeypadKey,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .size(size)
            .glass(CircleShape, LocalGlassColors.current)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("dialer_key_${key.char}"),
        shape = CircleShape,
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = key.char.toString(),
                fontSize = if (key.char in listOf('*', '#')) 28.sp else 25.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (key.letters.isNotEmpty()) {
                Text(
                    text = key.letters,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            } else if (key.char == '1') {
                Spacer(modifier = Modifier.height(11.dp))
            }
        }
    }
}
