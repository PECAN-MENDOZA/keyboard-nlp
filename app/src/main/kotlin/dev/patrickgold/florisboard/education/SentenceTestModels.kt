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

import kotlinx.serialization.Serializable

/** Condición de la oración dentro de una prueba: con o sin asistencia de la IA. */
@Serializable
enum class SentenceAssistance { ASSISTED, UNASSISTED }

/** Prueba asignada al alumno, tal como la lista `GET /tests/assigned` (`PENDING`, `IN_PROGRESS` o `COMPLETED`). */
@Serializable
data class AssignedTest(
    val testId: String,
    val code: String,
    val title: String,
    val sentenceCount: Int,
    val status: String,
) {
    val isPending: Boolean get() = status == StatusPending
    val isInProgress: Boolean get() = status == StatusInProgress

    companion object {
        const val StatusPending = "PENDING"
        const val StatusInProgress = "IN_PROGRESS"
        const val StatusCompleted = "COMPLETED"
    }
}

@Serializable
data class StartAttemptRequest(val appVersion: String)

/** Tope del backend para el texto final de una oración (`finalText @Size(max = 5000)`). */
const val MaxSentenceLength = 5000

/**
 * Recorta el texto de la oración al tope del backend (un `PUT` más largo sería un 400 no
 * reintentable). Un corte que caiga dentro de un par sustituto (emoji) descarta el par entero.
 */
fun clampSentence(text: String): String {
    if (text.length <= MaxSentenceLength) return text
    val cut = text.take(MaxSentenceLength)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** Posición de una oración dentro del intento y su condición asignada. */
@Serializable
data class SentenceSlot(val position: Int, val assistance: SentenceAssistance)

@Serializable
data class AttemptResponse(
    val attemptId: String,
    val testId: String,
    val code: String,
    val title: String,
    // Ausente cuando el intento ya terminó (no queda oración siguiente).
    val nextPosition: Int? = null,
    val status: String,
    val sentences: List<SentenceSlot>,
) {
    val sentenceCount: Int get() = sentences.size

    /** Condición de la oración en [position]; sin asistencia si no está en la lista. */
    fun assistanceOf(position: Int): SentenceAssistance =
        sentences.firstOrNull { it.position == position }?.assistance ?: SentenceAssistance.UNASSISTED
}

@Serializable
data class StartSentenceResponse(
    val responseId: String,
    val position: Int,
    val assistance: SentenceAssistance,
    val alreadyStarted: Boolean,
)

@Serializable
data class FinishSentenceRequest(
    val finalText: String,
    // Momento de la primera pulsación desde que se mostró la oración; null si el alumno nunca escribió.
    val firstKeyOffsetMs: Long?,
    val finishedOffsetMs: Long,
    val suggestionsOffered: Int,
    val suggestionsAccepted: Int,
    val suggestionsRejected: Int,
    val suggestionsUndone: Int,
    val skipped: Boolean,
    val completionKey: String,
)

@Serializable
data class FinishSentenceResponse(
    val responseId: String,
    // Ausente cuando esa fue la última oración del intento.
    val nextPosition: Int? = null,
    val attemptStatus: String,
)

@Serializable
enum class AttemptCancelReason { ABANDONED, TECHNICAL_PROBLEM, INTERRUPTED }

@Serializable
data class CancelAttemptRequest(val reason: AttemptCancelReason)

/** Contadores de sugerencias de la oración en curso (se reinician en cada oración). */
data class SuggestionCounters(val offered: Int = 0, val accepted: Int = 0, val rejected: Int = 0, val undone: Int = 0) {
    fun offered() = copy(offered = offered + 1)
    fun accepted() = copy(accepted = accepted + 1)
    fun rejected() = copy(rejected = rejected + 1)
    fun undone() = copy(undone = undone + 1)
}
