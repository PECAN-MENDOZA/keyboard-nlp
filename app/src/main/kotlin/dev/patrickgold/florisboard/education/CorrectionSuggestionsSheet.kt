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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.educationalCorrectionManager
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlin.getValue
import kotlin.math.roundToInt
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggText

// Elementos del tema que SI tienen fondo (verificado en los stylesheets):
//   - SmartbarActionsEditor              -> background: var(--background)  (panel opaco)
//   - SmartbarActionsEditorHeader        -> background: var(--surface)     (tarjeta)
//   - SmartbarActionsOverflowCustomizeButton -> background: var(--primary) (boton relleno)
private val PanelElement = FlorisImeUi.SmartbarActionsEditor.elementName
private val CardElement = FlorisImeUi.SmartbarActionsEditorHeader.elementName
private val ButtonElement = FlorisImeUi.SmartbarActionsOverflowCustomizeButton.elementName

// Area tactil comoda para ninos: botones altos y bien espaciados.
private val ButtonMinHeight = 52.dp
private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)

/**
 * Panel de corrección apilado *encima* del teclado (no lo tapa). Se coloca en el Column de
 * [dev.patrickgold.florisboard.ime.text.TextInputLayout], entre la Smartbar y las teclas, de modo
 * que la ventana del IME crece y el teclado sigue vivo y tocable debajo (necesario para editar).
 * No renderiza nada cuando no hay corrección activa (estado Idle).
 */
@Composable
fun EducationalCorrectionPanel() {
    val context = LocalContext.current
    val manager by context.educationalCorrectionManager()
    val state by manager.state.collectAsState()
    if (state is EducationalCorrectionState.Idle) return
    CorrectionSuggestionsSheet(
        state = state,
        onAccept = manager::acceptSuggestion,
        onEdit = manager::editSuggestion,
        onIgnore = manager::ignoreSuggestion,
        onRetry = manager::retryCorrection,
        onCursorChange = manager::bufferSetCursor,
        onConfirmEdit = manager::confirmEdit,
        onCancelEdit = manager::cancelEdit,
    )
}

@Composable
fun CorrectionSuggestionsSheet(
    state: EducationalCorrectionState,
    onAccept: (CorrectionSuggestionOption) -> Unit,
    onEdit: (CorrectionSuggestionOption) -> Unit,
    onIgnore: () -> Unit,
    onRetry: () -> Unit,
    onCursorChange: (Int) -> Unit,
    onConfirmEdit: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    // Panel opaco apilado sobre el teclado (no lo tapa: crece la ventana del IME).
    SnyggColumn(
        elementName = PanelElement,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is EducationalCorrectionState.Processing -> ProcessingContent()
                is EducationalCorrectionState.ShowingSuggestions ->
                    SuggestionsContent(state, onAccept, onEdit, onIgnore)
                is EducationalCorrectionState.Editing ->
                    EditingContent(state, onCursorChange, onConfirmEdit, onCancelEdit)
                is EducationalCorrectionState.Message -> MessageContent(state.text, onIgnore)
                is EducationalCorrectionState.Error -> ErrorContent(state, onRetry, onIgnore)
                EducationalCorrectionState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    SnyggText(
        modifier = Modifier.semantics { heading() },
        text = text,
    )
}

@Composable
private fun PrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SnyggButton(
        elementName = ButtonElement,
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonMinHeight),
        contentPadding = ButtonPadding,
    ) {
        // Icono antes de la palabra, como pide la interfaz.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIcon(
                modifier = Modifier.size(20.dp),
                imageVector = icon,
                contentDescription = null,
            )
            SnyggText(text = text)
        }
    }
}

@Composable
private fun ProcessingContent() {
    Heading("Corrección IA")
    SnyggText(text = "Estamos revisando tu texto...")
    SnyggText(text = "La primera vez puede tardar hasta un minuto.")
}

@Composable
private fun MessageContent(
    text: String,
    onDismiss: () -> Unit,
) {
    Heading("Corrección IA")
    SnyggText(maxLines = Int.MAX_VALUE, text = text)
    PrimaryButton(text = "Cerrar", icon = Icons.Default.Close, onClick = onDismiss)
}

@Composable
private fun ErrorContent(
    state: EducationalCorrectionState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Heading("No se pudo corregir")
    SnyggText(maxLines = Int.MAX_VALUE, text = state.text)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.retryText != null) {
            PrimaryButton(
                text = "Reintentar",
                icon = Icons.Default.Refresh,
                onClick = onRetry,
                modifier = Modifier.weight(1f),
            )
        }
        PrimaryButton(
            text = "Cerrar",
            icon = Icons.Default.Close,
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SuggestionsContent(
    state: EducationalCorrectionState.ShowingSuggestions,
    onAccept: (CorrectionSuggestionOption) -> Unit,
    onEdit: (CorrectionSuggestionOption) -> Unit,
    onIgnore: () -> Unit,
) {
    val options = state.response.displayOptions()
    Heading("Elige cómo corregir")
    SnyggText(text = "Texto que escribiste:")
    SnyggText(maxLines = Int.MAX_VALUE, text = state.extractedText.text)
    if (options.isEmpty()) {
        SnyggText(text = "No encontramos una mejor forma de escribirlo.")
    } else {
        options.forEachIndexed { index, option ->
            SuggestionOptionContent(
                index = index,
                option = option,
                onAccept = onAccept,
                onEdit = onEdit,
            )
        }
    }
    PrimaryButton(
        text = "Dejar como está",
        icon = Icons.Default.Close,
        onClick = onIgnore,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SuggestionOptionContent(
    index: Int,
    option: CorrectionSuggestionOption,
    onAccept: (CorrectionSuggestionOption) -> Unit,
    onEdit: (CorrectionSuggestionOption) -> Unit,
) {
    val isRecommended = option.recommended || index == 0
    // Tarjeta con fondo var(--surface) para diferenciar cada sugerencia del panel.
    SnyggBox(
        elementName = CardElement,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SnyggText(text = if (isRecommended) "Recomendada" else "Otra opción")
            SnyggText(maxLines = Int.MAX_VALUE, text = option.text)
            // "Usar" la inserta tal cual; "Editar" la vuelve texto editable en el panel para
            // retocarla antes de guardar (el teclado escribe en el buffer, no en la app).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryButton(
                    text = "Usar",
                    icon = Icons.Default.Check,
                    onClick = { onAccept(option) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "Editar",
                    icon = Icons.Default.Edit,
                    onClick = { onEdit(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EditingContent(
    state: EducationalCorrectionState.Editing,
    onCursorChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Heading("Edita y envía")
    SnyggText(text = "Toca el texto para colocar el cursor y corrige con el teclado.")
    // Campo editable propio: el texto vive en el buffer del panel, no en la app.
    SnyggBox(
        elementName = CardElement,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            EditableBuffer(
                buffer = state.buffer,
                cursor = state.cursor,
                onCursorChange = onCursorChange,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimaryButton(
            text = "Guardar y enviar",
            icon = Icons.Default.Send,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = "Cancelar",
            icon = Icons.Default.Close,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Texto editable del buffer con un cursor dibujado a mano. No abre una sesión de entrada (lo que en
 * un IME sería problemático): las teclas llegan por el enrutado del [KeyboardManager] y el toque solo
 * reposiciona el cursor vía [onCursorChange]. El color se toma de `LocalContentColor` (tema Snygg).
 */
@Composable
private fun EditableBuffer(
    buffer: String,
    cursor: Int,
    onCursorChange: (Int) -> Unit,
) {
    val contentColor = LocalContentColor.current
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    layout?.let { onCursorChange(it.getOffsetForPosition(pos)) }
                }
            },
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            // Un espacio como mínimo para que el layout tenga altura y el cursor sea visible.
            text = buffer.ifEmpty { " " },
            color = contentColor,
            fontSize = 16.sp,
            onTextLayout = { layout = it },
        )
        layout?.let { textLayout ->
            val safeCursor = cursor.coerceIn(0, buffer.length)
            val cursorRect = textLayout.getCursorRect(safeCursor)
            Box(
                modifier = Modifier
                    .offset { IntOffset(cursorRect.left.roundToInt(), cursorRect.top.roundToInt()) }
                    .size(width = 2.dp, height = with(density) { cursorRect.height.toDp() })
                    .background(contentColor),
            )
        }
    }
}

@Composable
fun OnboardingHintSheet(onDismiss: () -> Unit) {
    SnyggColumn(
        elementName = PanelElement,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Cómo corregir")
            SnyggText(text = "Sombrea el texto con el dedo y toca el botón IA para corregir.")
            PrimaryButton(text = "Entendido", icon = Icons.Default.Check, onClick = onDismiss)
        }
    }
}
