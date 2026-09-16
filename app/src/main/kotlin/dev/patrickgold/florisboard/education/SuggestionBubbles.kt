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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.roundToIntRect
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.educationalCorrectionManager
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val AvatarSize = 48.dp
private val BubbleMaxWidth = 300.dp
private val SpeechShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp)

/**
 * Superposición a pantalla completa con el avatar IA y hasta tres globos, uno por opción.
 * Se compone en [dev.patrickgold.florisboard.ime.window.ImeRootWindow] en lugar del teclado
 * cuando el estado es [EducationalCorrectionState.ShowingSuggestions]. Reporta al window controller los
 * rectángulos tocables; todo lo demás pasa a la app.
 */
@Composable
fun SuggestionBubblesOverlay() {
    val context = LocalContext.current
    val manager by context.educationalCorrectionManager()
    val windowController = LocalWindowController.current
    val state by manager.state.collectAsState()
    val current = state as? EducationalCorrectionState.ShowingSuggestions ?: return
    val palette = bubblePalette()
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()

    // Posición: la guardada, o el tercio inferior alineado a la izquierda.
    val savedX = prefs.accessibility.bubbleOffsetXDp.get()
    val savedY = prefs.accessibility.bubbleOffsetYDp.get()
    val defaultY = (configuration.screenHeightDp * 0.55f).roundToInt()
    var offsetX by remember { mutableStateOf(if (savedX >= 0) savedX.toFloat() else 12f) }
    var offsetY by remember { mutableStateOf(if (savedY >= 0) savedY.toFloat() else defaultY.toFloat()) }
    // Tamaño medido del grupo (en dp) para que nunca quede fuera de la pantalla.
    var groupSizeDp by remember { mutableStateOf(IntSize.Zero) }
    val maxX = (configuration.screenWidthDp - groupSizeDp.width).coerceAtLeast(0).toFloat()
    val maxY = (configuration.screenHeightDp - groupSizeDp.height).coerceAtLeast(0).toFloat()

    // Rectángulos tocables reportados al window controller (uno por elemento interactivo).
    val touchable = remember { mutableStateOf<Map<String, IntRect>>(emptyMap()) }
    fun report(key: String, rect: IntRect) {
        touchable.value = touchable.value + (key to rect)
        windowController.setOverlayTouchableRects(touchable.value.values.toList())
    }
    DisposableEffect(Unit) {
        onDispose { windowController.setOverlayTouchableRects(null) }
    }

    // Nunca se ofrece como globo una opción idéntica al texto original, y nunca más de tres globos.
    val options = current.response.displayOptions()
        .filter { it.text != current.extractedText.text }
        .take(MaxDisplayOptions)

    LaunchedEffect(maxX, maxY) {
        offsetX = offsetX.coerceIn(0f, maxX)
        offsetY = offsetY.coerceIn(0f, maxY)
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .offset { with(density) { IntOffset(offsetX.dp.roundToPx(), offsetY.dp.roundToPx()) } }
                .onGloballyPositioned {
                    groupSizeDp = with(density) {
                        IntSize(it.size.width.toDp().value.roundToInt(), it.size.height.toDp().value.roundToInt())
                    }
                }
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // El avatar es el asa de arrastre del grupo.
            Box(
                modifier = Modifier
                    .onGloballyPositioned { report("avatar", it.boundsInRoot().roundToIntRect()) }
                    .semantics { contentDescription = EducationalMessages.AvatarDescription }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                offsetX = (offsetX + with(density) { drag.x.toDp() }.value).coerceIn(0f, maxX)
                                offsetY = (offsetY + with(density) { drag.y.toDp() }.value).coerceIn(0f, maxY)
                            },
                            onDragEnd = {
                                // set() es suspend en esta versión de jetpref.
                                scope.launch {
                                    prefs.accessibility.bubbleOffsetXDp.set(offsetX.roundToInt())
                                    prefs.accessibility.bubbleOffsetYDp.set(offsetY.roundToInt())
                                }
                            },
                        )
                    },
            ) {
                IaAvatar(palette, size = AvatarSize)
            }

            Column(
                modifier = Modifier
                    .heightIn(max = (configuration.screenHeightDp * 0.6f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEachIndexed { index, option ->
                    val recommended = index == 0
                    SpeechBubble(
                        original = current.extractedText.text,
                        option = option,
                        recommended = recommended,
                        palette = palette,
                        modifier = Modifier.onGloballyPositioned { report("opt$index", it.boundsInRoot().roundToIntRect()) },
                        onTap = { manager.applySuggestion(option) },
                        onLongPress = { manager.editSuggestion(option) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(EducationalMessages.EditChip, palette, Modifier.onGloballyPositioned { report("edit", it.boundsInRoot().roundToIntRect()) }) {
                        options.firstOrNull()?.let { manager.editSuggestion(it) }
                    }
                    Chip(EducationalMessages.IgnoreChip, palette, Modifier.onGloballyPositioned { report("ignore", it.boundsInRoot().roundToIntRect()) }) {
                        manager.ignoreSuggestion()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeechBubble(
    original: String,
    option: CorrectionSuggestionOption,
    recommended: Boolean,
    palette: BubblePalette,
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val segments = remember(original, option.text) { SuggestionDiff.compute(original, option.text) }
    val changes = remember(original, option.text) { SuggestionDiff.countChanges(original, option.text) }
    val fill = if (recommended) palette.accentSoft else palette.surface
    val ink = if (recommended) palette.accent else palette.muted
    val font = bubbleFontFamily()

    Column(
        modifier = modifier
            .widthIn(max = BubbleMaxWidth)
            .heightIn(min = BubbleTouchMin)
            .background(fill, SpeechShape)
            .border(if (recommended) 1.5.dp else 1.dp, ink, SpeechShape)
            .semantics { role = Role.Button }
            .combinedClickable(onClick = onTap, onLongClick = onLongPress, onLongClickLabel = "Editar")
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (recommended) EducationalMessages.recommendedLabel(changes) else EducationalMessages.OtherOption,
            color = ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = font,
        )
        Text(
            text = buildAnnotatedString {
                segments.forEach { seg ->
                    if (seg.changed) {
                        withStyle(SpanStyle(background = palette.successSoft, color = palette.success, fontWeight = FontWeight.SemiBold)) {
                            append(seg.text)
                        }
                    } else {
                        append(seg.text)
                    }
                }
            },
            color = palette.onSurface,
            fontSize = BubbleTextSize,
            fontFamily = font,
        )
    }
}

@Composable
private fun Chip(label: String, palette: BubblePalette, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = BubbleTouchMin)
            .background(palette.surface, RoundedCornerShape(20.dp))
            .border(1.dp, palette.muted.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.onSurface, fontSize = BubbleTextSize, fontFamily = bubbleFontFamily())
    }
}
