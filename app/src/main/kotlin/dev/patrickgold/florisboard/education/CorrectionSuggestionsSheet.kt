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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggColumn
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

@Composable
fun CorrectionSuggestionsSheet(
    state: EducationalCorrectionState,
    onAccept: (CorrectionSuggestionOption) -> Unit,
    onIgnore: () -> Unit,
    onRetry: () -> Unit,
) {
    // Panel opaco que cubre el teclado para que el contenido sea legible.
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
                is EducationalCorrectionState.ShowingSuggestions -> SuggestionsContent(state, onAccept, onIgnore)
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SnyggButton(
        elementName = ButtonElement,
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonMinHeight),
        contentPadding = ButtonPadding,
    ) {
        SnyggText(text = text)
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
    SnyggText(text = text)
    PrimaryButton(text = "Cerrar", onClick = onDismiss)
}

@Composable
private fun ErrorContent(
    state: EducationalCorrectionState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Heading("No se pudo corregir")
    SnyggText(text = state.text)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.retryText != null) {
            PrimaryButton(text = "Reintentar", onClick = onRetry)
        }
        PrimaryButton(text = "Cerrar", onClick = onDismiss)
    }
}

@Composable
private fun SuggestionsContent(
    state: EducationalCorrectionState.ShowingSuggestions,
    onAccept: (CorrectionSuggestionOption) -> Unit,
    onIgnore: () -> Unit,
) {
    val options = state.response.displayOptions()
    Heading("Elige cómo corregir")
    SnyggText(text = "Texto que escribiste:")
    SnyggText(text = state.extractedText.text)
    if (options.isEmpty()) {
        SnyggText(text = "No encontramos una mejor forma de escribirlo.")
    } else {
        options.forEachIndexed { index, option ->
            SuggestionOptionContent(
                index = index,
                option = option,
                onAccept = onAccept,
            )
        }
    }
    PrimaryButton(
        text = "Dejar como está",
        onClick = onIgnore,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SuggestionOptionContent(
    index: Int,
    option: CorrectionSuggestionOption,
    onAccept: (CorrectionSuggestionOption) -> Unit,
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
            SnyggText(text = option.text)
            PrimaryButton(
                text = "Usar",
                onClick = { onAccept(option) },
                modifier = Modifier.fillMaxWidth(),
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
            PrimaryButton(text = "Entendido", onClick = onDismiss)
        }
    }
}
