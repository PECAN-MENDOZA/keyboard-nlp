/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.education

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.educationalCorrectionManager
import kotlinx.coroutines.delay
import org.florisboard.lib.snygg.ui.LocalSnyggForcedFontFamily

/** Paleta de los globos; se elige según el tema del sistema para leerse en claro y oscuro. */
data class BubblePalette(
    val surface: Color, val onSurface: Color, val muted: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
    val success: Color, val successSoft: Color,
    val warn: Color, val warnSoft: Color,
    val error: Color, val errorSoft: Color,
    val dark: Color, val onDark: Color,
) {
    companion object {
        val Light = BubblePalette(
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF222222), muted = Color(0xFF666666),
            accent = Color(0xFF2B5FB3), accentSoft = Color(0xFFEAF1FD), onAccent = Color(0xFFFFFFFF),
            success = Color(0xFF2F6B3A), successSoft = Color(0xFFDFF3E2),
            warn = Color(0xFF8A6300), warnSoft = Color(0xFFFDF8EA),
            error = Color(0xFFA33333), errorSoft = Color(0xFFFDF0F0),
            dark = Color(0xFF2F3640), onDark = Color(0xFFFFFFFF),
        )
        val Dark = BubblePalette(
            surface = Color(0xFF2A2D33), onSurface = Color(0xFFF2F2F2), muted = Color(0xFFB0B4BA),
            accent = Color(0xFF8FB3F0), accentSoft = Color(0xFF1E2C45), onAccent = Color(0xFF0B1730),
            success = Color(0xFF9BDBA6), successSoft = Color(0xFF1F3A26),
            warn = Color(0xFFF0D080), warnSoft = Color(0xFF3B3320),
            error = Color(0xFFF09A9A), errorSoft = Color(0xFF3F2424),
            dark = Color(0xFFE6E8EC), onDark = Color(0xFF1B1D22),
        )
    }
}

@Composable
fun bubblePalette(): BubblePalette = if (isSystemInDarkTheme()) BubblePalette.Dark else BubblePalette.Light

val BubbleTextSize = 15.sp
val BubbleTouchMin = 48.dp
val BubbleShape = RoundedCornerShape(14.dp)

/** Fuente forzada por accesibilidad (OpenDyslexic) que el tema solo aplica a textos Snygg. */
@Composable
fun bubbleFontFamily(): FontFamily? = LocalSnyggForcedFontFamily.current

/** Avatar circular "IA" que acompaña a todos los globos. */
@Composable
fun IaAvatar(palette: BubblePalette, size: androidx.compose.ui.unit.Dp = 32.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(palette.accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("IA", color = palette.onAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = bubbleFontFamily())
    }
}

@Composable
private fun BubbleAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = BubbleTouchMin)
            .widthIn(min = BubbleTouchMin)
            .semantics { role = Role.Button; contentDescription = if (label == EducationalMessages.Close) EducationalMessages.CloseDescription else label }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = BubbleTextSize, fontFamily = bubbleFontFamily())
    }
}

/**
 * Globo único para procesando / aviso / éxito / error, sobre la Smartbar y sin ocultar el
 * teclado. No renderiza nada en Idle, ShowingSuggestions, Applied ni EditingInPlace
 * (esos estados tienen sus propios composables).
 */
@Composable
fun CorrectionBubble() {
    val context = LocalContext.current
    val manager by context.educationalCorrectionManager()
    val state by manager.state.collectAsState()
    val palette = bubblePalette()
    val font = bubbleFontFamily()

    val (text, detail, fill, ink, actions) = when (val s = state) {
        is EducationalCorrectionState.Processing -> {
            var slow by remember(s) { mutableStateOf(false) }
            LaunchedEffect(s) {
                val elapsed = SystemClock.elapsedRealtime() - s.startedAtMs
                delay((EducationalCorrectionManager.PROCESSING_SLOW_AFTER_MS - elapsed).coerceAtLeast(0))
                slow = true
            }
            BubbleSpec("⏳ ${EducationalMessages.Processing}", if (slow) EducationalMessages.ProcessingSlow else null, palette.surface, palette.muted, emptyList())
        }
        is EducationalCorrectionState.Notice -> when (s.kind) {
            NoticeKind.SUCCESS -> BubbleSpec("✓ ${s.text}", null, palette.successSoft, palette.success, emptyList())
            NoticeKind.INFO -> BubbleSpec("ⓘ ${s.text}", null, palette.warnSoft, palette.warn, listOf(EducationalMessages.Close to manager::dismiss))
            NoticeKind.SESSION -> BubbleSpec("ⓘ ${s.text}", null, palette.warnSoft, palette.warn, listOf(EducationalMessages.Close to manager::dismiss))
        }
        is EducationalCorrectionState.Error -> BubbleSpec(
            "! ${s.text}", null, palette.errorSoft, palette.error,
            buildList {
                if (s.retryText != null) add(EducationalMessages.Retry to manager::retryCorrection)
                add(EducationalMessages.Close to manager::dismiss)
            },
        )
        else -> return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IaAvatar(palette)
        Row(
            modifier = Modifier
                .weight(1f)
                .background(fill, BubbleShape)
                .border(1.dp, ink.copy(alpha = 0.5f), BubbleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text, color = palette.onSurface, fontSize = BubbleTextSize, fontFamily = font)
                if (detail != null) Text(detail, color = palette.muted, fontSize = 13.sp, fontFamily = font)
            }
            actions.forEach { (label, onClick) -> BubbleAction(label, ink, onClick) }
        }
    }
}

private data class BubbleSpec(
    val text: String,
    val detail: String?,
    val fill: Color,
    val ink: Color,
    val actions: List<Pair<String, () -> Unit>>,
)

/**
 * Tira oscura de una línea sobre la Smartbar: "✓ Corregido · ↶ Deshacer" (Applied) o
 * "Editando · ✓ Listo · ↶ Deshacer" (EditingInPlace). Nada en otros estados.
 */
@Composable
fun ActionStrip() {
    val context = LocalContext.current
    val manager by context.educationalCorrectionManager()
    val state by manager.state.collectAsState()
    val palette = bubblePalette()
    val font = bubbleFontFamily()

    val (label, actions) = when (state) {
        is EducationalCorrectionState.Applied ->
            "✓ ${EducationalMessages.Corrected}" to listOf(EducationalMessages.Undo to manager::undo)
        is EducationalCorrectionState.EditingInPlace ->
            "✎ ${EducationalMessages.Editing}" to listOf(EducationalMessages.Done to manager::finishEdit, EducationalMessages.Undo to manager::undo)
        else -> return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .background(palette.dark, BubbleShape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = palette.onDark, fontSize = BubbleTextSize, fontFamily = font, modifier = Modifier.weight(1f))
        actions.forEach { (text, onClick) -> BubbleAction(text, palette.onDark, onClick) }
    }
}
