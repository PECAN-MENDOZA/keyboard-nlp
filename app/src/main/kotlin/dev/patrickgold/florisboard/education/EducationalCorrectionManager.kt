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

import android.content.Context
import android.os.SystemClock
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EducationalCorrectionManager(context: Context) {
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val repository = EducationalApiRepository()
    private val sessionStore = SecureEducationalSessionStore(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by FlorisPreferenceStore
    private var lastPrewarmAtMs: Long = 0L

    private val _onboardingHint = MutableStateFlow(false)
    val onboardingHint: StateFlow<Boolean> = _onboardingHint

    private val _state = MutableStateFlow<EducationalCorrectionState>(EducationalCorrectionState.Idle)
    val state: StateFlow<EducationalCorrectionState> = _state

    private val _connectionState = MutableStateFlow<EducationalBackendConnectionState>(
        EducationalBackendConnectionState.Unknown,
    )
    val connectionState: StateFlow<EducationalBackendConnectionState> = _connectionState

    // Sesion cacheada en memoria. El descifrado con Keystore + lectura de disco solo ocurre una
    // vez (al construir el manager), no en cada recomposicion ni en cada peticion de correccion.
    // Una sesion guardada que ya vencio se descarta y borra de entrada (no cuenta como activa).
    private val _session = MutableStateFlow(loadValidSession())
    val session: StateFlow<EducationalSession?> = _session

    private val loginFlow = LoginFlow(
        scope = scope,
        authenticate = { username, pin ->
            EducationalBackendBaseUrls.firstSuccessful { baseUrl ->
                repository.login(baseUrl = baseUrl, username = username, password = pin)
            }
        },
        onSuccess = ::persistSession,
    )
    val loginState: StateFlow<LoginState> = loginFlow.state

    private fun loadValidSession(): EducationalSession? {
        val stored = sessionStore.load() ?: return null
        if (stored.isExpired()) {
            sessionStore.clear()
            return null
        }
        return stored
    }

    /** Sesion utilizable: no nula y no vencida. Null significa "hay que iniciar sesion". */
    fun currentSession(): EducationalSession? = _session.value?.takeUnless { it.isExpired() }

    private fun persistSession(newSession: EducationalSession) {
        sessionStore.save(newSession)
        _session.value = newSession
    }

    private fun clearSession() {
        sessionStore.clear()
        _session.value = null
    }

    fun checkBackendConnection() {
        _connectionState.value = EducationalBackendConnectionState.Checking
        scope.launch {
            val result = EducationalBackendBaseUrls.firstSuccessfulBaseUrl { baseUrl ->
                repository.checkHealth(baseUrl = baseUrl)
            }
            result.onSuccess { baseUrl ->
                _connectionState.value = EducationalBackendConnectionState.Connected(baseUrl)
            }.onFailure { error ->
                _connectionState.value = EducationalBackendConnectionState.Unavailable(EducationalMessages.login(error))
            }
        }
    }

    /**
     * Lanza un health-check best-effort para despertar el backend (cold start de Cloud Run) antes
     * de que el alumno toque IA. Debounced y silencioso: no hace nada sin sesion activa, si la
     * sesion vencio, si ya hay un chequeo en curso, o si se llamo hace poco.
     */
    fun prewarm() {
        val session = _session.value ?: return
        if (session.isExpired()) return
        if (_connectionState.value is EducationalBackendConnectionState.Checking) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrewarmAtMs < PREWARM_DEBOUNCE_MS) return
        lastPrewarmAtMs = now
        checkBackendConnection()
    }

    /** Muestra una sola vez la pista de uso, tras el primer login. */
    fun maybeShowOnboarding() {
        if (_session.value == null) return
        if (prefs.accessibility.onboardingHintShown.get()) return
        _onboardingHint.value = true
    }

    fun dismissOnboarding() {
        _onboardingHint.value = false
        scope.launch { prefs.accessibility.onboardingHintShown.set(true) }
    }

    fun login(username: String, pin: String) = loginFlow.submit(username, pin)

    fun logout() {
        clearSession()
        loginFlow.reset()
    }

    fun requestCorrection() {
        if (_state.value is EducationalCorrectionState.Processing) return

        val session = _session.value
        if (session == null) {
            _state.value = EducationalCorrectionState.Message(EducationalMessages.NoSession)
            return
        }
        if (session.isExpired()) {
            clearSession()
            _state.value = EducationalCorrectionState.Message(EducationalMessages.SessionExpired)
            return
        }

        EducationalPrivacyPolicy.evaluate(
            editorInfo = editorInstance.activeInfo,
            keyboardState = keyboardManager.activeState.snapshot(),
            session = session,
        )?.let { reason ->
            _state.value = EducationalCorrectionState.Message(reason)
            return
        }

        val extraction = EditorTextExtractor.extract(content = editorInstance.activeContent)
        val extractedText = when (extraction) {
            is EducationalExtractionResult.Blocked -> {
                _state.value = EducationalCorrectionState.Message(extraction.reason)
                return
            }
            is EducationalExtractionResult.Ready -> extraction.value
        }

        runCorrection(extractedText)
    }

    /** Reenvia manualmente el mismo texto tras un error recuperable (sin reintento automatico). */
    fun retryCorrection() {
        val current = _state.value as? EducationalCorrectionState.Error ?: return
        val retryText = current.retryText ?: return
        runCorrection(retryText)
    }

    private fun runCorrection(extractedText: ExtractedEducationalText) {
        _state.value = EducationalCorrectionState.Processing(extractedText)
        scope.launch {
            val activeSession = _session.value
            if (activeSession == null || activeSession.isExpired()) {
                clearSession()
                _state.value = EducationalCorrectionState.Message(EducationalMessages.SessionExpired)
                return@launch
            }
            val result = EducationalBackendBaseUrls.firstSuccessful { baseUrl ->
                repository.processCorrection(
                    baseUrl = baseUrl,
                    token = activeSession.token,
                    text = extractedText.text,
                )
            }
            result.onSuccess { response ->
                _state.value = EducationalCorrectionState.ShowingSuggestions(extractedText, response)
            }.onFailure { error ->
                if (error is EducationalHttpException && error.status == 401) {
                    clearSession()
                }
                _state.value = if (EducationalMessages.isRetryable(error)) {
                    EducationalCorrectionState.Error(EducationalMessages.correction(error), retryText = extractedText)
                } else {
                    EducationalCorrectionState.Error(EducationalMessages.correction(error), retryText = null)
                }
            }
        }
    }

    fun acceptSuggestion(suggestion: CorrectionSuggestionOption) {
        val current = _state.value as? EducationalCorrectionState.ShowingSuggestions ?: return
        val replaced = editorInstance.replaceRangeIfUnchanged(
            range = current.extractedText.range,
            expectedText = current.extractedText.text,
            replacement = suggestion.text,
        )
        if (!replaced) {
            _state.value = EducationalCorrectionState.Message(EducationalMessages.TextChanged)
            return
        }
        sendFeedbackAndClose(
            response = current.response,
            selectedSuggestion = suggestion.text,
            accepted = true,
        )
    }

    fun ignoreSuggestion() {
        val current = _state.value as? EducationalCorrectionState.ShowingSuggestions ?: run {
            dismissMessage()
            return
        }
        sendFeedbackAndClose(
            response = current.response,
            selectedSuggestion = null,
            accepted = false,
        )
    }

    /**
     * "Editar": la sugerencia se vuelve texto editable dentro del panel (buffer interno). No toca
     * el campo real todavía; las teclas se enrutan al buffer vía [isEditingBuffer]/[bufferInsert].
     */
    fun editSuggestion(suggestion: CorrectionSuggestionOption) {
        val current = _state.value as? EducationalCorrectionState.ShowingSuggestions ?: return
        _state.value = EducationalCorrectionState.Editing(
            extractedText = current.extractedText,
            response = current.response,
            baseSuggestion = suggestion.text,
            buffer = suggestion.text,
            cursor = suggestion.text.length,
        )
    }

    /** True cuando hay un buffer de edición activo: el [KeyboardManager] enruta las teclas aquí. */
    fun isEditingBuffer(): Boolean = _state.value is EducationalCorrectionState.Editing

    /** Inserta [text] en la posición del cursor del buffer. */
    fun bufferInsert(text: String) {
        val s = _state.value as? EducationalCorrectionState.Editing ?: return
        val c = s.cursor.coerceIn(0, s.buffer.length)
        _state.value = s.copy(
            buffer = s.buffer.substring(0, c) + text + s.buffer.substring(c),
            cursor = c + text.length,
        )
    }

    /** Borra el carácter anterior al cursor del buffer. */
    fun bufferBackspace() {
        val s = _state.value as? EducationalCorrectionState.Editing ?: return
        val c = s.cursor.coerceIn(0, s.buffer.length)
        if (c == 0) return
        _state.value = s.copy(
            buffer = s.buffer.substring(0, c - 1) + s.buffer.substring(c),
            cursor = c - 1,
        )
    }

    /** Mueve el cursor del buffer [delta] posiciones (p. ej. flechas ← →). */
    fun bufferMoveCursor(delta: Int) {
        val s = _state.value as? EducationalCorrectionState.Editing ?: return
        _state.value = s.copy(cursor = (s.cursor + delta).coerceIn(0, s.buffer.length))
    }

    /** Fija el cursor del buffer en [index] (p. ej. al tocar sobre el texto del panel). */
    fun bufferSetCursor(index: Int) {
        val s = _state.value as? EducationalCorrectionState.Editing ?: return
        _state.value = s.copy(cursor = index.coerceIn(0, s.buffer.length))
    }

    /** "Guardar y enviar": vuelca el buffer al campo real y envía el feedback (aceptado/editado). */
    fun confirmEdit() {
        val current = _state.value as? EducationalCorrectionState.Editing ?: return
        val buffer = current.buffer
        if (buffer.isBlank()) {
            _state.value = EducationalCorrectionState.ShowingSuggestions(current.extractedText, current.response)
            return
        }
        val replaced = editorInstance.replaceRangeIfUnchanged(
            range = current.extractedText.range,
            expectedText = current.extractedText.text,
            replacement = buffer,
        )
        if (!replaced) {
            _state.value = EducationalCorrectionState.Message(EducationalMessages.TextChanged)
            return
        }
        val edited = buffer != current.baseSuggestion
        sendFeedbackAndClose(
            response = current.response,
            selectedSuggestion = current.baseSuggestion,
            accepted = true,
            finalText = if (edited) buffer else null,
        )
    }

    /** Cancela la edición: descarta el buffer y vuelve a la lista (el campo real no se tocó). */
    fun cancelEdit() {
        val current = _state.value as? EducationalCorrectionState.Editing ?: return
        _state.value = EducationalCorrectionState.ShowingSuggestions(
            extractedText = current.extractedText,
            response = current.response,
        )
    }

    fun dismissMessage() {
        if (_state.value !is EducationalCorrectionState.Processing) {
            _state.value = EducationalCorrectionState.Idle
        }
    }

    private fun sendFeedbackAndClose(
        response: CorrectionSessionResponse,
        selectedSuggestion: String?,
        accepted: Boolean,
        finalText: String? = null,
    ) {
        _state.value = EducationalCorrectionState.Idle
        scope.launch {
            val session = _session.value ?: return@launch
            EducationalBackendBaseUrls.firstSuccessful { baseUrl ->
                repository.sendFeedback(
                    baseUrl = baseUrl,
                    token = session.token,
                    sessionId = response.sessionId,
                    selectedSuggestion = selectedSuggestion,
                    accepted = accepted,
                    finalText = finalText,
                )
            }
        }
    }

}

private const val PREWARM_DEBOUNCE_MS = 2 * 60 * 1000L // 2 minutos

// URL del backend desplegado. Se define en build.gradle.kts (buildConfigField
// EDUCATION_BACKEND_BASE_URL) para poder cambiar el despliegue sin tocar el codigo.
val EducationalBackendBaseUrls = listOf(
    BuildConfig.EDUCATION_BACKEND_BASE_URL,
)

private suspend fun <T> List<String>.firstSuccessful(
    block: suspend (String) -> Result<T>,
): Result<T> {
    var lastFailure: Throwable? = null
    for (baseUrl in this) {
        val result = block(baseUrl)
        result.onSuccess { return result }
        val error = result.exceptionOrNull()
        lastFailure = error
        if (error is EducationalHttpException) {
            return result
        }
    }
    return Result.failure(lastFailure ?: IllegalStateException("No backend URL configured."))
}

private suspend fun List<String>.firstSuccessfulBaseUrl(
    block: suspend (String) -> Result<Unit>,
): Result<String> {
    var lastFailure: Throwable? = null
    for (baseUrl in this) {
        val result = block(baseUrl)
        result.onSuccess { return Result.success(baseUrl) }
        val error = result.exceptionOrNull()
        lastFailure = error
        if (error is EducationalHttpException) {
            return Result.failure(error)
        }
    }
    return Result.failure(lastFailure ?: IllegalStateException("No backend URL configured."))
}
