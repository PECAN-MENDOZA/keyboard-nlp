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

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType

/*
 * Reglas puras de la pantalla "Participar en una prueba" (sin Android): qué acepta el campo del
 * código, cuándo se habilita "Finalizar y guardar", cómo se muestra el cronómetro, qué acciones
 * ofrece cada estado [EducationalExperimentState.Failed], cuándo "Cerrar sesión" pide confirmar y
 * qué tecla cuenta como primera pulsación. La pantalla Compose solo las compone.
 */

/** Longitud fija del código de acceso que entrega el investigador. */
const val ExperimentCodeLength = 8

/** Tope del texto de la tarea; el campo muestra un contador `n / 10000`. */
const val ExperimentMaxTextLength = 10_000

/** Ocho caracteres no ambiguos (sin 0/O ni 1/I); misma regla que el coordinador. */
private val ExperimentCodePattern = Regex("[A-HJ-NP-Z2-9]{8}")

private const val ExperimentRunStatusPending = "PENDING"

/** Estado local (no del contrato) con el que el coordinador marca una ejecución cerrada por el backend. */
private const val ExperimentRunStatusClosed = "CLOSED"

/** Lo que el campo acepta mientras se escribe: mayúsculas, solo letras y dígitos ASCII, máximo 8. */
fun filterExperimentCodeInput(raw: String): String =
    raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(ExperimentCodeLength)

/** Código listo para canjear (espacios y guiones fuera, mayúsculas), o `null` si no es válido. */
fun normalizeExperimentCode(raw: String): String? =
    raw.trim().uppercase().replace(" ", "").replace("-", "").takeIf { ExperimentCodePattern.matches(it) }

/** "Validar código" se habilita solo con un código completo y aceptable. */
fun experimentCodeReady(code: String): Boolean = normalizeExperimentCode(code) != null

/** Aviso bajo el campo: solo cuando ya tiene 8 caracteres pero contiene alguno ambiguo. */
fun experimentCodeHint(code: String): String? =
    if (code.length == ExperimentCodeLength && !experimentCodeReady(code)) EducationalMessages.InvalidAccessCode else null

/** Duración desde la primera pulsación; `null` si el reloj monótono no avanzó (reinicio, etc.). */
fun experimentDurationMs(firstKeyAtMs: Long, nowMs: Long): Long? =
    (nowMs - firstKeyAtMs).takeIf { it > 0L }

/** Cronómetro en pantalla: "—" antes de la primera pulsación, `m:ss` o `h:mm:ss` después. */
fun formatExperimentElapsed(elapsedMs: Long?): String {
    if (elapsedMs == null) return EducationalMessages.ExperimentElapsedNone
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * "Finalizar y guardar": con texto, sin envío en vuelo, sin motivo de bloqueo (cronómetro perdido)
 * y sin una corrección IA en curso ([correcting]: su feedback debe llegar antes que la finalización).
 */
fun experimentFinishEnabled(
    text: String,
    completing: Boolean,
    blockedReason: String?,
    correcting: Boolean = false,
): Boolean = text.isNotBlank() && !completing && blockedReason == null && !correcting

fun limitExperimentText(text: String): String = text.take(ExperimentMaxTextLength)

/**
 * El borrador en disco ([ExperimentDraftStore]) se limpia solo cuando la ejecución terminó de
 * verdad (guardada o cancelada). Nunca en `Idle` ni `Restoring`: son el estado transitorio mientras
 * `GET /runs/active` está en vuelo tras una muerte del proceso, justo cuando el borrador hace
 * falta. Salir de la pantalla y cerrar sesión limpian explícitamente por su cuenta.
 */
fun experimentDraftShouldClear(state: EducationalExperimentState): Boolean = when (state) {
    is EducationalExperimentState.Completed,
    EducationalExperimentState.Cancelled -> true
    else -> false
}

/**
 * "Cerrar sesión" pide confirmación cuando hay texto o una operación que se perdería: ejecución
 * activa, finalización o cancelación en vuelo, o una fallida pendiente de reintentar.
 */
fun logoutNeedsConfirmation(state: EducationalExperimentState): Boolean = when (state) {
    is EducationalExperimentState.Active,
    is EducationalExperimentState.Completing,
    is EducationalExperimentState.Cancelling -> true
    is EducationalExperimentState.Failed -> state.pendingCompletion != null || state.pendingCancel != null
    else -> false
}

/**
 * Tecla que escribe en el campo (carácter, número, espacio, Enter, borrar): cuenta como primera
 * pulsación de una escritura controlada. Las teclas de sistema (cambiar vista, ajustes…) no.
 */
fun experimentTypingKey(code: Int, type: KeyType): Boolean = when (code) {
    KeyCode.DELETE, KeyCode.DELETE_WORD, KeyCode.FORWARD_DELETE, KeyCode.FORWARD_DELETE_WORD, KeyCode.ENTER -> true
    else -> code >= KeyCode.Spec.CHARACTERS_MIN && (type == KeyType.CHARACTER || type == KeyType.NUMERIC)
}

/**
 * Qué muestra la pantalla en [EducationalExperimentState.Failed]:
 * - [codeField]: el campo del código otra vez (canje fallido: no hay ejecución).
 * - [runCard]: participante, consigna y condición (inicio fallido de una ejecución PENDING).
 * - [retry]: "Reintentar" (`retryable`: reenvía la finalización, reintenta el inicio o restaura).
 * - [retryByRestore]: "Reintentar" vuelve a restaurar en lugar de `retry()`: la ejecución PENDING
 *   se reconstruyó desde el marcador (sin consigna) y no debe iniciarse sin que el alumno la vea.
 * - [cancel]: "Cancelar prueba" mientras exista una ejecución que el backend aún puede cerrar.
 * - [close]: "Cerrar" (`clear()`) cuando no hay ejecución o el backend ya la cerró.
 */
data class ExperimentFailedActions(
    val codeField: Boolean,
    val runCard: Boolean,
    val retry: Boolean,
    val cancel: Boolean,
    val close: Boolean,
    val retryByRestore: Boolean = false,
)

fun experimentFailedActions(state: EducationalExperimentState.Failed): ExperimentFailedActions {
    val run = state.run
        ?: return ExperimentFailedActions(codeField = true, runCard = false, retry = false, cancel = false, close = true)
    if (run.status == ExperimentRunStatusClosed) {
        return ExperimentFailedActions(codeField = false, runCard = false, retry = false, cancel = false, close = true)
    }
    // Una ejecución PENDING con consigna es un inicio fallido; sin consigna viene del marcador
    // (restauración fallida) y no hay nada que mostrar salvo el mensaje y las acciones.
    val pendingRun = state.pendingCompletion == null && run.status == ExperimentRunStatusPending
    val startFailed = pendingRun && run.promptText.isNotBlank()
    return ExperimentFailedActions(
        codeField = false,
        runCard = startFailed && state.pendingCancel == null,
        retry = state.retryable,
        cancel = true,
        close = false,
        // Una cancelación pendiente se reintenta con retry(), nunca restaurando.
        retryByRestore = state.retryable && pendingRun && !startFailed && state.pendingCancel == null,
    )
}
