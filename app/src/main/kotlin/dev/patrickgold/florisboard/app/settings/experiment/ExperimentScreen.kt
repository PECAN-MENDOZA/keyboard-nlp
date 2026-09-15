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

package dev.patrickgold.florisboard.app.settings.experiment

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.education.CancelReason
import dev.patrickgold.florisboard.education.EducationalExperimentState
import dev.patrickgold.florisboard.education.EducationalMessages
import dev.patrickgold.florisboard.education.ExperimentCodeLength
import dev.patrickgold.florisboard.education.ExperimentMaxTextLength
import dev.patrickgold.florisboard.education.ExperimentRunResponse
import dev.patrickgold.florisboard.education.experimentCodeHint
import dev.patrickgold.florisboard.education.experimentCodeReady
import dev.patrickgold.florisboard.education.experimentDurationMs
import dev.patrickgold.florisboard.education.experimentFailedActions
import dev.patrickgold.florisboard.education.experimentFinishEnabled
import dev.patrickgold.florisboard.education.filterExperimentCodeInput
import dev.patrickgold.florisboard.education.formatExperimentElapsed
import dev.patrickgold.florisboard.education.limitExperimentText
import dev.patrickgold.florisboard.educationalCorrectionManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.delay

/**
 * "Participar en una prueba": escritura controlada supervisada por el investigador. La pantalla
 * solo refleja [EducationalExperimentState] del coordinador; condición y consigna llegan del
 * backend y nunca se eligen aquí. Fases: sin sesión → código → prueba lista → tarea (texto,
 * cronómetro, finalizar/cancelar) → guardando → fallo (reintentar/cancelar/cerrar) → guardada o
 * cancelada. Toda la lógica decidible sin Android vive en `education/ExperimentScreenRules.kt`.
 */
@Composable
fun ExperimentScreen() = FlorisScreen {
    title = EducationalMessages.ExperimentTitle
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current

    content {
        val manager by context.educationalCorrectionManager()
        val session by manager.session.collectAsState()
        val hasSession = session?.takeUnless { it.isExpired() } != null
        val experiment = manager.experiment
        val state by experiment.state.collectAsState()

        // Una sola restauración por instancia de la pantalla (la rotación no la repite): una
        // ejecución PENDING/ACTIVE vuelve a aparecer. Una prueba recién guardada o cancelada se
        // deja ver antes de que "Volver" la limpie.
        var restored by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(hasSession) {
            if (!hasSession || restored) return@LaunchedEffect
            restored = true
            val current = experiment.state.value
            if (current !is EducationalExperimentState.Completed && current !is EducationalExperimentState.Cancelled) {
                experiment.restore()
            }
        }

        if (!hasSession) {
            NoSessionPhase(onBack = { navController.popBackStack() })
            return@content
        }

        val runId = state.runOrNull()?.id
        var code by rememberSaveable { mutableStateOf("") }
        // El texto sobrevive a la rotación (rememberSaveable) y a salir/volver a la pantalla dentro
        // del mismo proceso (borrador en memoria por ejecución).
        var text by rememberSaveable(runId) { mutableStateOf(runId?.let(ExperimentDraft::load) ?: "") }
        var cancelDialogOpen by rememberSaveable { mutableStateOf(false) }
        var cancelReason by rememberSaveable { mutableStateOf(CancelReason.ABANDONED.name) }
        var completionRejected by rememberSaveable { mutableStateOf(false) }

        val blockedReason = if (state is EducationalExperimentState.Active) experiment.completionBlockedReason() else null
        val openCancelDialog = {
            if (blockedReason != null) cancelReason = CancelReason.TECHNICAL_PROBLEM.name
            cancelDialogOpen = true
        }
        val leave: () -> Unit = {
            ExperimentDraft.clear()
            experiment.clear()
            navController.popBackStack()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (val current = state) {
                is EducationalExperimentState.Idle,
                is EducationalExperimentState.Redeeming -> CodeEntryPhase(
                    code = code,
                    onCodeChange = { code = filterExperimentCodeInput(it) },
                    redeeming = current is EducationalExperimentState.Redeeming,
                    message = null,
                    onValidate = { experiment.redeem(code) },
                )
                is EducationalExperimentState.Ready -> ReadyPhase(
                    run = current.run,
                    starting = false,
                    message = null,
                    onStart = experiment::start,
                    onRetry = null,
                    onCancel = openCancelDialog,
                )
                is EducationalExperimentState.Starting -> ReadyPhase(
                    run = current.run,
                    starting = true,
                    message = null,
                    onStart = {},
                    onRetry = null,
                    onCancel = {},
                )
                is EducationalExperimentState.Active -> ActivePhase(
                    run = current.run,
                    firstKeyAtMs = current.firstKeyAtMs,
                    text = text,
                    onTextChange = { changed ->
                        val limited = limitExperimentText(changed)
                        if (limited.isNotEmpty()) experiment.markFirstKey()
                        text = limited
                        completionRejected = false
                        ExperimentDraft.save(current.run.id, limited)
                    },
                    blockedReason = blockedReason,
                    completionRejected = completionRejected,
                    onFinish = { if (!experiment.complete(text)) completionRejected = true },
                    onCancel = openCancelDialog,
                )
                is EducationalExperimentState.Completing -> CompletingPhase(run = current.run, text = current.text)
                is EducationalExperimentState.Failed -> FailedPhase(
                    state = current,
                    code = code,
                    onCodeChange = { code = filterExperimentCodeInput(it) },
                    onValidate = { experiment.redeem(code) },
                    keptText = current.pendingCompletion?.text,
                    onRetry = if (experimentFailedActions(current).retryByRestore) experiment::restore else experiment::retry,
                    onCancel = openCancelDialog,
                    onClose = experiment::clear,
                )
                is EducationalExperimentState.Completed -> OutcomePhase(
                    title = EducationalMessages.ExperimentCompleted,
                    detail = EducationalMessages.ExperimentCompletedDetail,
                    onBack = leave,
                )
                is EducationalExperimentState.Cancelled -> OutcomePhase(
                    title = EducationalMessages.ExperimentCancelled,
                    detail = EducationalMessages.ExperimentCancelledDetail,
                    onBack = leave,
                )
            }
        }

        if (cancelDialogOpen) {
            CancelDialog(
                selected = CancelReason.valueOf(cancelReason),
                onSelect = { cancelReason = it.name },
                onConfirm = {
                    cancelDialogOpen = false
                    experiment.cancel(CancelReason.valueOf(cancelReason))
                },
                onDismiss = { cancelDialogOpen = false },
            )
        }
    }
}

// --- Phases ------------------------------------------------------------------------------------

@Composable
private fun NoSessionPhase(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(EducationalMessages.ExperimentNoSession)
        Spacer(Modifier.height(16.dp))
        PrimaryAction(text = EducationalMessages.ExperimentBackHome, onClick = onBack)
    }
}

@Composable
private fun CodeEntryPhase(
    code: String,
    onCodeChange: (String) -> Unit,
    redeeming: Boolean,
    message: String?,
    onValidate: () -> Unit,
) {
    val ready = experimentCodeReady(code)
    val hint = experimentCodeHint(code)
    Text(EducationalMessages.ExperimentCodeIntro)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !redeeming,
        isError = hint != null,
        label = { Text(EducationalMessages.ExperimentCodeLabel) },
        supportingText = { Text(hint ?: EducationalMessages.textCounter(code.length, ExperimentCodeLength)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (ready && !redeeming) onValidate() }),
    )
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        ErrorCard(message)
    }
    Spacer(Modifier.height(12.dp))
    if (redeeming) {
        ProgressRow(EducationalMessages.ExperimentValidating)
        Spacer(Modifier.height(12.dp))
    }
    PrimaryAction(
        text = EducationalMessages.ExperimentValidateCode,
        onClick = onValidate,
        enabled = ready && !redeeming,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        EducationalMessages.ExperimentPrivacy,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadyPhase(
    run: ExperimentRunResponse,
    starting: Boolean,
    message: String?,
    onStart: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    Text(
        EducationalMessages.ExperimentReadyTitle,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(EducationalMessages.ExperimentReadyIntro)
    Spacer(Modifier.height(12.dp))
    RunCard(run)
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        ErrorCard(message)
    }
    Spacer(Modifier.height(12.dp))
    if (starting) {
        ProgressRow(EducationalMessages.ExperimentStarting)
        Spacer(Modifier.height(12.dp))
    }
    if (onRetry != null) {
        PrimaryAction(text = EducationalMessages.ExperimentRetry, onClick = onRetry)
        Spacer(Modifier.height(8.dp))
    } else if (onStart != null) {
        PrimaryAction(text = EducationalMessages.ExperimentStart, onClick = onStart, enabled = !starting)
        Spacer(Modifier.height(8.dp))
    }
    SecondaryAction(text = EducationalMessages.ExperimentCancel, onClick = onCancel, enabled = !starting)
}

@Composable
private fun ActivePhase(
    run: ExperimentRunResponse,
    firstKeyAtMs: Long?,
    text: String,
    onTextChange: (String) -> Unit,
    blockedReason: String?,
    completionRejected: Boolean,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    // El cronómetro avanza cada segundo desde la primera pulsación; antes muestra "—".
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(firstKeyAtMs) {
        if (firstKeyAtMs == null) return@LaunchedEffect
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    val elapsed = formatExperimentElapsed(firstKeyAtMs?.let { experimentDurationMs(it, nowMs) })

    PromptCard(run)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            EducationalMessages.ExperimentElapsedLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            elapsed,
            modifier = Modifier.semantics { contentDescription = EducationalMessages.elapsedDescription(elapsed) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 8,
        enabled = blockedReason == null,
        label = { Text(EducationalMessages.ExperimentTextLabel) },
        supportingText = { Text(EducationalMessages.textCounter(text.length, ExperimentMaxTextLength)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default,
        ),
    )
    if (blockedReason != null) {
        Spacer(Modifier.height(8.dp))
        ErrorCard(blockedReason)
    } else if (completionRejected) {
        Spacer(Modifier.height(8.dp))
        ErrorCard(EducationalMessages.ExperimentCompletionRejected)
    }
    Spacer(Modifier.height(12.dp))
    if (blockedReason == null) {
        PrimaryAction(
            text = EducationalMessages.ExperimentFinish,
            onClick = onFinish,
            enabled = experimentFinishEnabled(text, completing = false, blockedReason = null),
        )
        Spacer(Modifier.height(8.dp))
    }
    SecondaryAction(text = EducationalMessages.ExperimentCancel, onClick = onCancel)
    Spacer(Modifier.height(12.dp))
    Text(
        EducationalMessages.ExperimentPrivacy,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CompletingPhase(run: ExperimentRunResponse, text: String) {
    PromptCard(run)
    Spacer(Modifier.height(12.dp))
    KeptTextField(text)
    Spacer(Modifier.height(12.dp))
    ProgressRow(EducationalMessages.ExperimentSaving)
    Text(EducationalMessages.ExperimentSavingDetail, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    PrimaryAction(text = EducationalMessages.ExperimentFinish, onClick = {}, enabled = false)
}

@Composable
private fun FailedPhase(
    state: EducationalExperimentState.Failed,
    code: String,
    onCodeChange: (String) -> Unit,
    onValidate: () -> Unit,
    keptText: String?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val actions = experimentFailedActions(state)
    val run = state.run
    when {
        actions.codeField -> {
            CodeEntryPhase(
                code = code,
                onCodeChange = onCodeChange,
                redeeming = false,
                message = state.message,
                onValidate = onValidate,
            )
        }
        actions.runCard && run != null -> {
            ReadyPhase(
                run = run,
                starting = false,
                message = state.message,
                onStart = null,
                onRetry = onRetry.takeIf { actions.retry },
                onCancel = onCancel,
            )
        }
        else -> {
            if (run != null && run.promptText.isNotBlank()) {
                PromptCard(run)
                Spacer(Modifier.height(12.dp))
            }
            if (keptText != null) {
                KeptTextField(keptText)
                Spacer(Modifier.height(12.dp))
            }
            ErrorCard(state.message)
            Spacer(Modifier.height(12.dp))
            if (actions.retry) {
                PrimaryAction(text = EducationalMessages.ExperimentRetry, onClick = onRetry)
                Spacer(Modifier.height(8.dp))
            }
            if (actions.cancel) {
                SecondaryAction(text = EducationalMessages.ExperimentCancel, onClick = onCancel)
            }
        }
    }
    if (actions.close) {
        Spacer(Modifier.height(8.dp))
        SecondaryAction(text = EducationalMessages.ExperimentCloseAction, onClick = onClose)
    }
}

@Composable
private fun OutcomePhase(title: String, detail: String, onBack: () -> Unit) {
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(detail)
    Spacer(Modifier.height(16.dp))
    PrimaryAction(text = EducationalMessages.ExperimentBack, onClick = onBack)
}

// --- Pieces ------------------------------------------------------------------------------------

/** Participante, consigna y modo (solo lectura: nunca hay un selector de condición). */
@Composable
private fun RunCard(run: ExperimentRunResponse) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            LabeledValue(EducationalMessages.ExperimentParticipantLabel, run.participantCode)
            Spacer(Modifier.height(8.dp))
            LabeledValue(EducationalMessages.ExperimentPromptLabel, run.promptText)
            Spacer(Modifier.height(8.dp))
            LabeledValue(EducationalMessages.ExperimentConditionLabel, EducationalMessages.conditionLabel(run.condition))
        }
    }
}

/** Consigna inmutable durante la tarea, con el modo asignado. */
@Composable
private fun PromptCard(run: ExperimentRunResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            LabeledValue(EducationalMessages.ExperimentPromptLabel, run.promptText)
            Spacer(Modifier.height(8.dp))
            Text(
                "${run.participantCode} · ${EducationalMessages.conditionLabel(run.condition)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyLarge)
}

/** El texto que se conserva mientras se guarda o tras un fallo: se ve, no se edita. */
@Composable
private fun KeptTextField(text: String) {
    OutlinedTextField(
        value = text,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        readOnly = true,
        minLines = 4,
        label = { Text(EducationalMessages.ExperimentTextLabel) },
        supportingText = { Text(EducationalMessages.textCounter(text.length, ExperimentMaxTextLength)) },
    )
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ProgressRow(text: String) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .visibleFocus(ButtonDefaults.shape),
    ) {
        Text(text)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .visibleFocus(ButtonDefaults.outlinedShape),
    ) {
        Text(text)
    }
}

/** Borde visible al navegar con teclado físico o lector de pantalla. */
@Composable
private fun Modifier.visibleFocus(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}

@Composable
private fun CancelDialog(
    selected: CancelReason,
    onSelect: (CancelReason) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(EducationalMessages.ExperimentCancelTitle) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(EducationalMessages.ExperimentCancelIntro)
                Spacer(Modifier.height(8.dp))
                CancelReason.entries.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = reason == selected,
                                onClick = { onSelect(reason) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(EducationalMessages.cancelReasonLabel(reason))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EducationalMessages.ExperimentCancel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EducationalMessages.ExperimentKeepGoing)
            }
        },
    )
}

// --- Helpers -----------------------------------------------------------------------------------

private fun EducationalExperimentState.runOrNull(): ExperimentRunResponse? = when (this) {
    is EducationalExperimentState.Ready -> run
    is EducationalExperimentState.Starting -> run
    is EducationalExperimentState.Active -> run
    is EducationalExperimentState.Completing -> run
    is EducationalExperimentState.Failed -> run
    is EducationalExperimentState.Completed -> run
    EducationalExperimentState.Idle,
    EducationalExperimentState.Redeeming,
    EducationalExperimentState.Cancelled -> null
}

/**
 * Borrador en memoria del texto de la tarea, por ejecución: si el alumno sale de la pantalla
 * (por ejemplo para volver a iniciar sesión) y regresa dentro del mismo proceso, no pierde lo escrito.
 */
private object ExperimentDraft {
    private var runId: String? = null
    private var text: String = ""

    fun load(runId: String): String = if (this.runId == runId) text else ""

    fun save(runId: String, text: String) {
        this.runId = runId
        this.text = text
    }

    fun clear() {
        runId = null
        text = ""
    }
}
