package com.persistor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ThemeMode(val key: String, val title: String) {
    LIGHT("light", "Светлая"),
    DARK("dark", "Тёмная"),
    AMOLED("amoled", "AMOLED");

    companion object {
        fun fromKey(k: String?): ThemeMode = entries.firstOrNull { it.key == k } ?: LIGHT
    }
}

/** В тёмной и AMOLED-темах у кнопок и надписей тонкий оранжевый контур. */
val LocalFramed = compositionLocalOf { false }
val FrameOrange = Color(0xFFFF9800)

@Composable
fun PersistorTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val scheme = when (mode) {
        ThemeMode.LIGHT -> lightColorScheme()
        ThemeMode.DARK -> darkColorScheme()
        ThemeMode.AMOLED -> darkColorScheme(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF121212),
        )
    }
    CompositionLocalProvider(LocalFramed provides (mode != ThemeMode.LIGHT)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Тонкая оранжевая рамка (только в тёмных темах). */
@Composable
fun Modifier.framed(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)): Modifier =
    if (LocalFramed.current) this.border(0.8.dp, FrameOrange, shape) else this

/** Надпись: в тёмных темах обводится тонкой оранжевой рамкой. */
@Composable
fun PText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val m = if (LocalFramed.current) {
        modifier.border(0.8.dp, FrameOrange, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    } else modifier
    Text(text = text, modifier = m, color = color, maxLines = maxLines, overflow = overflow, style = style, textAlign = textAlign)
}

@Composable
fun PButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val m = if (LocalFramed.current) {
        modifier.border(0.8.dp, if (enabled) FrameOrange else FrameOrange.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
    } else modifier
    TextButton(onClick = onClick, modifier = m, enabled = enabled, contentPadding = contentPadding, content = content)
}

@Composable
fun POutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalFramed.current) {
        OutlinedButton(
            onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding,
            border = BorderStroke(0.8.dp, if (enabled) FrameOrange else FrameOrange.copy(alpha = 0.35f)),
            content = content,
        )
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding, content = content)
    }
}

@Composable
fun PFilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val m = if (LocalFramed.current) {
        modifier.border(0.8.dp, if (enabled) FrameOrange else FrameOrange.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
    } else modifier
    Button(onClick = onClick, modifier = m, enabled = enabled, content = content)
}

@Composable
fun themedFieldColors(): TextFieldColors =
    if (LocalFramed.current) {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = FrameOrange,
            unfocusedBorderColor = FrameOrange.copy(alpha = 0.6f),
        )
    } else OutlinedTextFieldDefaults.colors()

/** Круглая рамка для кнопок-иконок. */
@Composable
fun Modifier.framedCircle(): Modifier =
    if (LocalFramed.current) this.border(0.8.dp, FrameOrange, CircleShape) else this
