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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn

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
                _connectionState.value = EducationalBackendConnectionState.Unavailable(error.toUiMessage())
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

    fun login(username: String, password: String) {
        if (_isLoggingIn.value) return
        if (username.isBlank() || password.isBlank()) {
            _state.value = EducationalCorrectionState.Message("Ingresa alias y PIN.")
            return
        }
        _isLoggingIn.value = true
        _state.value = EducationalCorrectionState.Message("Iniciando sesión...")
        scope.launch {
            val result = EducationalBackendBaseUrls.firstSuccessful { baseUrl ->
                repository.login(
                    baseUrl = baseUrl,
                    username = username.trim(),
                    password = password,
                )
            }
            result.onSuccess { session ->
                persistSession(session)
                _state.value = EducationalCorrectionState.Message("Sesión iniciada.")
            }.onFailure { error ->
                _state.value = EducationalCorrectionState.Message(error.toUiMessage())
            }
            _isLoggingIn.value = false
        }
    }

    fun logout() {
        clearSession()
        _state.value = EducationalCorrectionState.Message("Sesión educativa cerrada.")
    }

    fun requestCorrection() {
        if (_state.value is EducationalCorrectionState.Processing) return

        val session = _session.value
        if (session == null) {
            _state.value = EducationalCorrectionState.Message("Inicia sesión para usar la corrección IA.")
            return
        }
        if (session.isExpired()) {
            clearSession()
            _state.value = EducationalCorrectionState.Message("La sesión venció. Inicia sesión nuevamente.")
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
                _state.value = EducationalCorrectionState.Message("La sesión venció. Inicia sesión nuevamente.")
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
                _state.value = if (error.isRetryable()) {
                    EducationalCorrectionState.Error(error.toUiMessage(), retryText = extractedText)
                } else {
                    EducationalCorrectionState.Error(error.toUiMessage(), retryText = null)
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
            _state.value = EducationalCorrectionState.Message("El texto cambió. Solicita una nueva corrección.")
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

    fun dismissMessage() {
        if (_state.value !is EducationalCorrectionState.Processing) {
            _state.value = EducationalCorrectionState.Idle
        }
    }

    private fun sendFeedbackAndClose(
        response: CorrectionSessionResponse,
        selectedSuggestion: String?,
        accepted: Boolean,
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
                )
            }
        }
    }

    /**
     * Errores ante los que vale la pena ofrecer reintento manual: la IA caida (502) o
     * fallos de red transitorios. No se reintenta automaticamente (cada llamada crea una sesion).
     */
    private fun Throwable.isRetryable(): Boolean {
        return when (this) {
            is EducationalHttpException -> status == 502
            is ConnectException,
            is NoRouteToHostException,
            is SocketTimeoutException,
            is UnknownHostException -> true
            else -> false
        }
    }

    private fun Throwable.toUiMessage(): String {
        return when (this) {
            is EducationalHttpException -> when (status) {
                400 -> "El backend rechazó el fragmento enviado."
                401 -> "La sesión venció. Inicia sesión nuevamente."
                403 -> "No tienes permiso para solicitar correcciones."
                404 -> "La sesión de corrección ya no está disponible."
                502 -> "La IA no está disponible temporalmente."
                else -> "Error del backend: HTTP $status."
            }
            is ConnectException -> "No se pudo conectar con el backend desplegado. Revisa tu conexión a internet o si Cloud Run está activo."
            is NoRouteToHostException -> "No hay ruta hacia el backend desplegado. Revisa la conexión a internet del dispositivo."
            is SocketTimeoutException -> "El backend desplegado no respondió a tiempo. Vuelve a intentar en unos segundos."
            is UnknownHostException -> "No se pudo resolver la dirección del backend desplegado."
            else -> message ?: "No se pudo completar la corrección."
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
