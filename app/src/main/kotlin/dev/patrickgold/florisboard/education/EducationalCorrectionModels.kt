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

import dev.patrickgold.florisboard.ime.editor.EditorRange
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val EDUCATIONAL_BACKEND_MAX_CHARS = 5000

enum class EducationalTextSource {
    SELECTION,
}

data class ExtractedEducationalText(
    val text: String,
    val range: EditorRange,
    val source: EducationalTextSource,
)

sealed class EducationalExtractionResult {
    data class Ready(val value: ExtractedEducationalText) : EducationalExtractionResult()
    data class Blocked(val reason: String) : EducationalExtractionResult()
}

@Serializable
data class EducationalSession(
    val userId: String,
    val token: String,
    val expiresAt: String,
    /** Alias con el que inicio sesion el alumno; vacio en sesiones persistidas antes de existir. */
    val username: String = "",
) {
    /**
     * Indica si el JWT ya vencio segun [expiresAt] (ISO-8601). Permite detectar la
     * expiracion antes de llamar al backend, en vez de esperar un 401 tras el round-trip.
     * Si la marca no se puede interpretar, se asume valido (no se bloquea al alumno).
     */
    fun isExpired(now: Instant = Instant.now()): Boolean {
        val expiry = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return false
        return !now.isBefore(expiry)
    }
}

@Serializable
data class StudentLoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class StudentLoginResponse(
    val userId: String,
    val token: String,
    val expiresAt: String,
    val role: String,
)

@Serializable
data class ProcessCorrectionRequest(
    @SerialName("texto_original")
    val originalText: String,
    // Solo se envía en una ejecución experimental ASSISTED y ACTIVE.
    @SerialName("id_ejecucion")
    val experimentRunId: String? = null,
)

@Serializable
data class CorrectionSuggestionOption(
    val text: String,
    val recommended: Boolean = false,
)

@Serializable
data class CorrectionWordResponse(
    val id: String? = null,
    @SerialName("palabra_original")
    val originalWord: String,
    @SerialName("palabra_corregida")
    val correctedWord: String,
    @SerialName("posicion_inicio")
    val start: Int? = null,
    @SerialName("posicion_fin")
    val end: Int? = null,
)

@Serializable
data class CorrectionSessionResponse(
    @SerialName("id_sesion")
    val sessionId: String,
    @SerialName("texto_original")
    val originalText: String,
    @SerialName("texto_corregido")
    val correctedText: String,
    val suggestions: List<String> = emptyList(),
    val suggestionOptions: List<CorrectionSuggestionOption> = emptyList(),
    @SerialName("palabras_corregidas")
    val correctedWords: List<CorrectionWordResponse> = emptyList(),
    // Devueltos por el backend tras el feedback (contrato con texto_final). Opcionales: en la
    // respuesta inicial de /process aún no vienen, y con ignoreUnknownKeys no romperían igual.
    @SerialName("texto_final")
    val finalText: String? = null,
    @SerialName("fue_editada")
    val wasEdited: Boolean = false,
) {
    fun displayOptions(): List<CorrectionSuggestionOption> {
        val richOptions = suggestionOptions
            .filter { it.text.isNotBlank() }
            .distinctBy { it.text }
            .take(3)
        if (richOptions.isNotEmpty()) {
            return richOptions
        }
        return suggestions
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(correctedText) }
            .distinct()
            .take(3)
            .mapIndexed { index, text ->
                CorrectionSuggestionOption(
                    text = text,
                    recommended = index == 0,
                )
            }
    }
}

@Serializable
data class CorrectionFeedbackRequest(
    @SerialName("sugerencia_elegida")
    val selectedSuggestion: String?,
    @SerialName("acepto_correccion")
    val acceptedCorrection: Boolean,
    // Texto realmente insertado tras la edición manual del alumno. Opcional: null cuando aceptó
    // la sugerencia tal cual. El backend clasifica como "editada" si difiere de la sugerencia base.
    @SerialName("texto_final")
    val finalText: String? = null,
    // Motivo opcional de un segundo feedback sobre la misma sesión ("UNDO"). El backend actual
    // lo ignora; el plan del modo experimental lo registra como evento.
    @SerialName("motivo")
    val reason: String? = null,
)

enum class NoticeKind { INFO, SUCCESS, SESSION }

sealed class EducationalCorrectionState {
    object Idle : EducationalCorrectionState()

    data class Processing(
        val extractedText: ExtractedEducationalText,
        val startedAtMs: Long,
    ) : EducationalCorrectionState()

    /** Hay opciones que elegir: el teclado entra en modo burbujas. */
    data class ShowingSuggestions(
        val extractedText: ExtractedEducationalText,
        val response: CorrectionSessionResponse,
    ) : EducationalCorrectionState()

    /** Sugerencia aplicada; se ofrece Deshacer unos segundos. */
    data class Applied(
        val extractedText: ExtractedEducationalText,
        val response: CorrectionSessionResponse,
        val appliedText: String,
        val anchor: EditAnchor,
    ) : EducationalCorrectionState()

    /** El alumno edita la sugerencia aplicada directamente en el campo de la app. */
    data class EditingInPlace(
        val extractedText: ExtractedEducationalText,
        val response: CorrectionSessionResponse,
        val baseSuggestion: String,
        val anchor: EditAnchor,
        val lastKnown: TrackedRange.Inside,
    ) : EducationalCorrectionState()

    /** Aviso informativo (ámbar), éxito (verde) o sesión (ámbar, manual). */
    data class Notice(val text: String, val kind: NoticeKind) : EducationalCorrectionState()

    /** Error; con [retryText] se ofrece Reintentar. */
    data class Error(
        val text: String,
        val retryText: ExtractedEducationalText?,
    ) : EducationalCorrectionState()
}

sealed class EducationalBackendConnectionState {
    object Unknown : EducationalBackendConnectionState()
    object Checking : EducationalBackendConnectionState()
    data class Connected(val baseUrl: String) : EducationalBackendConnectionState()
    data class Unavailable(val message: String) : EducationalBackendConnectionState()
}

/** Estado exclusivo del formulario de login de la app. Nunca se mezcla con [EducationalCorrectionState]. */
sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Failed(val message: String) : LoginState()
}
