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

package dev.patrickgold.florisboard.app.settings.tests

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.education.AttemptCancelReason
import dev.patrickgold.florisboard.education.EducationalCorrectionState
import dev.patrickgold.florisboard.education.EducationalMessages
import dev.patrickgold.florisboard.education.SentenceAssistance
import dev.patrickgold.florisboard.education.SentenceTestState
import dev.patrickgold.florisboard.educationalCorrectionManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen

/**
 * La oración en curso de una prueba: "Oración N de M", el chip Con/Sin ayuda, el campo donde
 * el alumno escribe (el IME es este mismo FlorisBoard, así que los globos de la IA funcionan
 * como en cualquier app), Comenzar antes de escribir y Terminar después. Nada muestra tiempo.
 * Solo refleja [SentenceTestState]; atrás no cancela (la prueba sigue en curso y se retoma
 * desde Pruebas o desde el inicio). Los fallos que no cambian de estado llegan por `lastError`.
 */
@Composable
fun TestSentenceScreen() = FlorisScreen {
    title = EducationalMessages.TestsTitle
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current

    content {
        val manager by context.educationalCorrectionManager()
        val tests = manager.tests
        val state by tests.state.collectAsState()
        val correctionState by manager.state.collectAsState()
        val finishing by manager.finishing.collectAsState()
        val lastError by tests.lastError.collectAsState()
        var askEmpty by rememberSaveable { mutableStateOf(false) }
        var askCancel by rememberSaveable { mutableStateOf(false) }
        var left by remember { mutableStateOf(false) }
        val correcting = correctionState is EducationalCorrectionState.Processing

        // Una sola salida: volver al inicio deja el coordinador en Idle y esta pantalla no vuelve
        // a hacer pop al recomponerse con ese Idle.
        val leave: () -> Unit = {
            if (!left) {
                left = true
                tests.leaveToHome()
                navController.popBackStack()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (val current = state) {
                is SentenceTestState.AtSentence -> SentenceCard(
                    position = current.position,
                    total = current.attempt.sentenceCount,
                    assistance = current.assistance,
                    text = "",
                    enabled = false,
                    onTextChanged = {},
                    primaryLabel = EducationalMessages.SentenceStart,
                    primaryEnabled = true,
                    onPrimary = { tests.pressStart() },
                    onCancel = { askCancel = true },
                )
                is SentenceTestState.Writing -> SentenceCard(
                    position = current.position,
                    total = current.attempt.sentenceCount,
                    assistance = current.assistance,
                    text = current.text,
                    enabled = true,
                    onTextChanged = { tests.onTextChanged(it) },
                    // Terminar se deshabilita con una corrección en vuelo ("Corrigiendo…") y
                    // mientras se envía el feedback pendiente antes del PUT ("Guardando…").
                    primaryLabel = when {
                        correcting -> EducationalMessages.SentenceCorrecting
                        finishing -> EducationalMessages.Saving
                        else -> EducationalMessages.SentenceFinish
                    },
                    primaryEnabled = !correcting && !finishing,
                    onPrimary = { if (current.text.isBlank()) askEmpty = true else manager.finishSentence() },
                    onCancel = { askCancel = true },
                )
                is SentenceTestState.Finishing -> ProgressCard(EducationalMessages.Saving)
                is SentenceTestState.FinishFailed -> ErrorCard(
                    message = "${EducationalMessages.SentenceSaveFailed}. ${current.message}",
                    onRetry = if (current.retryable) ({ tests.retry() }) else null,
                    onCancel = { tests.cancel(AttemptCancelReason.TECHNICAL_PROBLEM) },
                    cancelLabel = EducationalMessages.CancelTechnical,
                )
                is SentenceTestState.ClockLost -> ErrorCard(
                    message = EducationalMessages.ClockLost,
                    onRetry = null,
                    onCancel = { tests.cancel(AttemptCancelReason.TECHNICAL_PROBLEM) },
                    cancelLabel = EducationalMessages.CancelTechnical,
                )
                is SentenceTestState.Cancelling -> ProgressCard(EducationalMessages.Cancelling)
                is SentenceTestState.Completed -> DoneCard(EducationalMessages.TestCompleted, onBack = leave)
                SentenceTestState.Cancelled -> DoneCard(EducationalMessages.TestCancelled, onBack = leave)
                is SentenceTestState.Failed -> DoneCard(current.message, onBack = leave)
                // Sin oración que mostrar (Idle, lista, elección): esta pantalla no pinta nada.
                else -> LaunchedEffect(Unit) { leave() }
            }
            lastError?.let { message -> TransientErrorCard(message, onDismiss = tests::dismissError) }
        }

        if (askEmpty) {
            ConfirmDialog(
                question = EducationalMessages.SentenceEmptyQuestion,
                onYes = {
                    askEmpty = false
                    manager.finishSentence()
                },
                onNo = { askEmpty = false },
            )
        }
        if (askCancel) {
            CancelDialog(
                onConfirm = { reason ->
                    askCancel = false
                    tests.cancel(reason)
                },
                onDismiss = { askCancel = false },
            )
        }
    }
}

/**
 * La oración: progreso grande, chip de condición, el campo (deshabilitado hasta Comenzar; pide
 * el foco al habilitarse para que el teclado aparezca solo), la acción principal y cancelar.
 */
@Composable
private fun SentenceCard(
    position: Int,
    total: Int,
    assistance: SentenceAssistance,
    text: String,
    enabled: Boolean,
    onTextChanged: (String) -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (enabled) focusRequester.requestFocus()
    }
    Text(
        EducationalMessages.sentenceProgress(position, total),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            EducationalMessages.assistanceLabel(assistance),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = onTextChanged,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        enabled = enabled,
        minLines = 3,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default,
        ),
    )
    Spacer(Modifier.height(16.dp))
    PrimaryAction(text = primaryLabel, onClick = onPrimary, enabled = primaryEnabled)
    Spacer(Modifier.height(4.dp))
    TextButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(EducationalMessages.CancelTest)
    }
}

/** Estado terminal (completada, cancelada, fallo): solo cabe volver al inicio. */
@Composable
private fun DoneCard(message: String, onBack: () -> Unit) {
    Text(
        message,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(16.dp))
    PrimaryAction(text = EducationalMessages.BackHome, onClick = onBack)
}

@Composable
private fun ConfirmDialog(question: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        text = { Text(question) },
        confirmButton = {
            TextButton(onClick = onYes, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EducationalMessages.Yes)
            }
        },
        dismissButton = {
            TextButton(onClick = onNo, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EducationalMessages.No)
            }
        },
    )
}

/** Motivo de cancelación desde la oración: abandono o interrupción (el problema técnico tiene su propio botón). */
@Composable
private fun CancelDialog(onConfirm: (AttemptCancelReason) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(AttemptCancelReason.ABANDONED.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(EducationalMessages.CancelTestTitle) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(EducationalMessages.CancelTestIntro)
                Spacer(Modifier.height(8.dp))
                CancelReasons.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = reason.name == selected,
                                onClick = { selected = reason.name },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason.name == selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(EducationalMessages.cancelReasonLabel(reason))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(AttemptCancelReason.valueOf(selected)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(EducationalMessages.CancelTest)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EducationalMessages.KeepGoing)
            }
        },
    )
}

private val CancelReasons = listOf(AttemptCancelReason.ABANDONED, AttemptCancelReason.INTERRUPTED)
