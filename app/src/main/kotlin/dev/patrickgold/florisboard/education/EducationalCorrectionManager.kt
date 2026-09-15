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
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EducationalCorrectionManager(context: Context) {
    private val appContext: Context = context.applicationContext
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val repository = EducationalApiRepository()
    private val sessionStore = SecureEducationalSessionStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by FlorisPreferenceStore
    private var lastPrewarmAtMs: Long = 0L
    private var autoDismissJob: Job? = null
    private val feedbackMutex = Mutex()

    private val _state = MutableStateFlow<EducationalCorrectionState>(EducationalCorrectionState.Idle)
    val state: StateFlow<EducationalCorrectionState> = _state

    private val _connectionState = MutableStateFlow<EducationalBackendConnectionState>(
        EducationalBackendConnectionState.Unknown,
    )
    val connectionState: StateFlow<EducationalBackendConnectionState> = _connectionState

    // Sesion cacheada en memoria. El descifrado con Keystore + lectura de disco solo ocurre una
    // vez (al construir el manager). Una sesion guardada que ya vencio se descarta de entrada.
    private val _session = MutableStateFlow(loadValidSession())
    val session: StateFlow<EducationalSession?> = _session

    private val loginFlow = LoginFlow(
        scope = scope,
        authenticate = { username, pin ->
            EducationalBackendBaseUrls.firstSuccessful { baseUrl ->
                repository.login(baseUrl = baseUrl, username = username, password = pin)
            }
        },
        onSuccess = { newSession ->
            persistSession(newSession)
            // Una ejecución pendiente (o una finalización que falló por sesión vencida) sigue ahí.
            experiment.restore()
        },
    )
    val loginState: StateFlow<LoginState> = loginFlow.state

    /**
     * Escritura controlada (experimento). Sin ejecución activa la corrección funciona como siempre;
     * en `UNASSISTED` [requestCorrection] avisa y no llama al backend; en `ASSISTED` la corrección
     * lleva `id_ejecucion`. Sobrevive a una sesión vencida (la finalización pendiente se reenvía
     * tras volver a entrar); solo [logout] lo borra.
     */
    val experiment = EducationalExperimentCoordinator(
        scope = scope,
        api = RepositoryExperimentApi(repository, EducationalBackendBaseUrls),
        session = { currentSession() },
        elapsedRealtime = SystemClock::elapsedRealtime,
        appVersion = BuildConfig.VERSION_NAME,
        store = PrefsExperimentMarkerStore(appContext),
        bootId = { PrefsExperimentMarkerStore.bootId(appContext) },
    )

    init {
        // Mientras hay algo aplicado o en edición, cada cambio de contenido se contrasta con el ancla.
        scope.launch {
            editorInstance.activeContentFlow.collect { content -> onContentChanged(content) }
        }
        // Tras una muerte del proceso, el gate y las pantallas deben reflejar la ejecución vigente.
        if (currentSession() != null) experiment.restore()
    }

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

    fun login(username: String, pin: String) = loginFlow.submit(username, pin)

    fun logout() {
        clearSession()
        loginFlow.reset()
        experiment.clear()
        PrefsExperimentDraftStore(appContext).clear()
        reset()
        _connectionState.value = EducationalBackendConnectionState.Unknown
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
     * Health-check best-effort para despertar el backend antes de que el alumno toque IA.
     * Debounced y silencioso.
     */
    fun prewarm() {
        val session = currentSession() ?: return
        if (_connectionState.value is EducationalBackendConnectionState.Checking) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrewarmAtMs < PREWARM_DEBOUNCE_MS) return
        lastPrewarmAtMs = now
        checkBackendConnection()
    }

    /** Globo de bienvenida una sola vez tras el primer login. */
    fun maybeShowOnboarding() {
        if (currentSession() == null) return
        if (prefs.accessibility.onboardingHintShown.get()) return
        if (_state.value !is EducationalCorrectionState.Idle) return
        scope.launch { prefs.accessibility.onboardingHintShown.set(true) }
        show(EducationalCorrectionState.Notice(EducationalMessages.SelectFirst, NoticeKind.INFO), ONBOARDING_MS)
    }

    /** Android ocultó el teclado: las burbujas se van con él; el estado se conserva para reaparecer. */
    fun onKeyboardHidden() {
        when (_state.value) {
            is EducationalCorrectionState.Applied -> reset()
            is EducationalCorrectionState.EditingInPlace -> finishEdit()
            else -> Unit
        }
    }

    /**
     * Android inició la entrada en un campo NUEVO (otro campo u otra app): cualquier corrección
     * en curso deja de tener sentido. Un restart del mismo campo (restarting = true) y volver a
     * mostrar el teclado no pasan por aquí, así que los globos reaparecen en el mismo campo.
     */
    fun onInputFieldChanged() {
        if (_state.value is EducationalCorrectionState.EditingInPlace) finishEdit() else reset()
    }

    /**
     * El alumno pulsó una tecla que escribe (carácter, espacio, borrar…). Solo importa como primera
     * pulsación de una ejecución activa: arranca el cronómetro; el resto de pulsaciones se ignoran.
     */
    fun onUserKeyPress() {
        if (experiment.state.value is EducationalExperimentState.Active) experiment.markFirstKey()
    }

    // ---- Solicitud ------------------------------------------------------------------------

    fun requestCorrection() {
        if (_state.value is EducationalCorrectionState.Processing) return
        if (_state.value is EducationalCorrectionState.EditingInPlace) finishEdit()

        // Escritura controlada sin asistencia: se avisa y no se toca el backend (que lo revalida).
        if (!experiment.correctionAllowed()) {
            show(EducationalCorrectionState.Notice(EducationalMessages.CorrectionDisabledInTask, NoticeKind.INFO), NOTICE_MS)
            return
        }

        val session = _session.value
        if (session == null) {
            show(EducationalCorrectionState.Notice(EducationalMessages.NoSession, NoticeKind.SESSION))
            return
        }
        if (session.isExpired()) {
            clearSession()
            show(EducationalCorrectionState.Notice(EducationalMessages.SessionExpired, NoticeKind.SESSION))
            return
        }

        EducationalPrivacyPolicy.evaluate(
            editorInfo = editorInstance.activeInfo,
            keyboardState = keyboardManager.activeState.snapshot(),
            session = session,
        )?.let { reason ->
            show(EducationalCorrectionState.Notice(reason, NoticeKind.INFO), NOTICE_MS)
            return
        }

        when (val extraction = EditorTextExtractor.extract(content = editorInstance.activeContent)) {
            is EducationalExtractionResult.Blocked ->
                show(EducationalCorrectionState.Notice(extraction.reason, NoticeKind.INFO), NOTICE_MS)
            is EducationalExtractionResult.Ready -> runCorrection(extraction.value)
        }
    }

    /** Reenvia manualmente el mismo texto tras un error recuperable (sin reintento automatico). */
    fun retryCorrection() {
        val current = _state.value as? EducationalCorrectionState.Error ?: return
        val retryText = current.retryText ?: return
        runCorrection(retryText)
    }

    private fun runCorrection(extractedText: ExtractedEducationalText) {
        // Se repite aquí (además de en requestCorrection) para cubrir retryCorrection: un
        // reintento tras un error no debe poder llegar al backend si mientras tanto la
        // ejecución pasó a UNASSISTED o cruzó el límite. Ver docs/ux-smoke-test.md, Task 5:
        // "IA en UNASSISTED y Reintentar tras error: ningún request llega al backend".
        if (!experiment.correctionAllowed()) {
            show(EducationalCorrectionState.Notice(EducationalMessages.CorrectionDisabledInTask, NoticeKind.INFO), NOTICE_MS)
            return
        }
        val processing = EducationalCorrectionState.Processing(extractedText, SystemClock.elapsedRealtime())
        show(processing)
        scope.launch {
            val activeSession = currentSession()
            if (activeSession == null) {
                clearSession()
                show(EducationalCorrectionState.Notice(EducationalMessages.SessionExpired, NoticeKind.SESSION))
                return@launch
            }
            val result = EducationalBackendBaseUrls.firstSuccessful { baseUrl ->
                repository.processCorrection(
                    baseUrl = baseUrl,
                    token = activeSession.token,
                    text = extractedText.text,
                    // Solo en una ejecución ASSISTED activa; null en uso normal.
                    experimentRunId = experiment.activeRunId(),
                )
            }
            // Si mientras tanto cambió el campo o el alumno cerró, la respuesta ya no interesa.
            if (_state.value != processing) return@launch
            result.onSuccess { response ->
                val options = response.displayOptions().filter { it.text != extractedText.text }
                if (options.isEmpty()) {
                    show(EducationalCorrectionState.Notice(EducationalMessages.AlreadyCorrect, NoticeKind.SUCCESS), NOTICE_SHORT_MS)
                    sendFeedback(response, selectedSuggestion = null, accepted = false)
                } else {
                    show(EducationalCorrectionState.ShowingSuggestions(extractedText, response))
                }
            }.onFailure { error ->
                if (error is EducationalHttpException && error.status == 401) {
                    clearSession()
                }
                val retry = if (EducationalMessages.isRetryable(error)) extractedText else null
                show(EducationalCorrectionState.Error(EducationalMessages.correction(error), retryText = retry))
            }
        }
    }

    // ---- Elegir ---------------------------------------------------------------------------

    /** Toque corto en un globo: aplica y ofrece Deshacer 3 s. */
    fun applySuggestion(option: CorrectionSuggestionOption) {
        val current = _state.value as? EducationalCorrectionState.ShowingSuggestions ?: return
        val anchor = replaceSelection(current.extractedText, option.text) ?: return
        sendFeedback(current.response, selectedSuggestion = option.text, accepted = true)
        show(EducationalCorrectionState.Applied(current.extractedText, current.response, option.text, anchor), UNDO_MS)
    }

    /** "✎ Editar" o pulsación larga: aplica y deja al alumno retocar en el campo real. */
    fun editSuggestion(option: CorrectionSuggestionOption) {
        val current = _state.value as? EducationalCorrectionState.ShowingSuggestions ?: return
        val anchor = replaceSelection(current.extractedText, option.text) ?: return
        val start = EditorRange(anchor.start, anchor.start + option.text.length)
        show(
            EducationalCorrectionState.EditingInPlace(
                extractedText = current.extractedText,
                response = current.response,
                baseSuggestion = option.text,
                anchor = anchor,
                lastKnown = TrackedRange.Inside(option.text, start),
            ),
        )
    }

    /** "Dejar como está": cierra los globos y registra el rechazo. */
    fun ignoreSuggestion() {
        val current = _state.value as? EducationalCorrectionState.ShowingSuggestions ?: run { dismiss(); return }
        sendFeedback(current.response, selectedSuggestion = null, accepted = false)
        reset()
    }

    /** "✓ Listo" (o Listo implícito): envía el texto final tal como quedó en la app. */
    fun finishEdit() {
        val current = _state.value as? EducationalCorrectionState.EditingInPlace ?: return
        val finalText = current.lastKnown.text
        sendFeedback(
            response = current.response,
            selectedSuggestion = current.baseSuggestion,
            accepted = true,
            finalText = finalText.takeIf { it != current.baseSuggestion },
        )
        reset()
    }

    /** Deshacer desde Applied o EditingInPlace: restaura el texto original. */
    fun undo() {
        when (val current = _state.value) {
            is EducationalCorrectionState.Applied -> {
                val range = EditorRange(current.anchor.start, current.anchor.start + current.appliedText.length)
                if (!editorInstance.replaceRangeIfUnchanged(range, current.appliedText, current.extractedText.text)) {
                    show(EducationalCorrectionState.Notice(EducationalMessages.TextChanged, NoticeKind.INFO), NOTICE_MS)
                    return
                }
                sendFeedback(current.response, selectedSuggestion = null, accepted = false, reason = REASON_UNDO)
                reset()
            }
            is EducationalCorrectionState.EditingInPlace -> {
                val known = current.lastKnown
                if (!editorInstance.replaceRangeIfUnchanged(known.range, known.text, current.extractedText.text)) {
                    // El texto ya no coincide: se cierra la edición con su feedback y se avisa.
                    finishEdit()
                    show(EducationalCorrectionState.Notice(EducationalMessages.TextChanged, NoticeKind.INFO), NOTICE_MS)
                    return
                }
                sendFeedback(current.response, selectedSuggestion = null, accepted = false, reason = REASON_UNDO)
                reset()
            }
            else -> Unit
        }
    }

    /** Cierra avisos y errores; no interrumpe una petición en curso. */
    fun dismiss() {
        when (_state.value) {
            is EducationalCorrectionState.Processing -> Unit
            is EducationalCorrectionState.EditingInPlace -> finishEdit()
            else -> reset()
        }
    }

    // ---- Internos -------------------------------------------------------------------------

    /**
     * Reemplaza la selección original por [replacement] y devuelve el ancla para seguirla.
     * Si la app ya no tiene el texto esperado, avisa y devuelve null. Un fallo por conexión
     * de entrada perdida se reintenta una vez tras pedir al sistema que muestre el teclado.
     */
    private fun replaceSelection(extracted: ExtractedEducationalText, replacement: String): EditAnchor? {
        val anchor = InPlaceEditTracker.anchor(editorInstance.activeContent, extracted.range)
        var replaced = editorInstance.replaceRangeIfUnchanged(extracted.range, extracted.text, replacement)
        if (!replaced) {
            dev.patrickgold.florisboard.FlorisImeService.showUi()
            replaced = editorInstance.replaceRangeIfUnchanged(extracted.range, extracted.text, replacement)
        }
        if (!replaced || anchor == null) {
            show(EducationalCorrectionState.Notice(EducationalMessages.TextChanged, NoticeKind.INFO), NOTICE_MS)
            return null
        }
        return anchor
    }

    private fun onContentChanged(content: EditorContent) {
        if (content.offset < 0) return
        when (val current = _state.value) {
            is EducationalCorrectionState.Applied -> {
                // Cualquier tecla tras aplicar retira la tira de Deshacer sin más. Justo después de
                // reemplazar, el editor puede emitir un estado intermedio con el texto original
                // todavía presente (setSelection antes de commitText): no cuenta como tecla.
                val tracked = InPlaceEditTracker.resolve(current.anchor, content)
                val untouched = tracked is TrackedRange.Inside &&
                    (tracked.text == current.appliedText || tracked.text == current.extractedText.text)
                if (!untouched) reset()
            }
            is EducationalCorrectionState.EditingInPlace -> {
                when (val tracked = InPlaceEditTracker.resolve(current.anchor, content)) {
                    is TrackedRange.Inside -> {
                        // Eco intermedio del editor (texto original con la selección original) antes
                        // de que confirme el commit: no es una edición del alumno, se ignora.
                        val preCommitEcho = tracked.text == current.extractedText.text &&
                            current.lastKnown.text == current.baseSuggestion
                        if (!preCommitEcho) _state.value = current.copy(lastKnown = tracked)
                    }
                    TrackedRange.Lost -> finishEdit()
                }
            }
            else -> Unit
        }
    }

    private fun show(newState: EducationalCorrectionState, autoDismissMs: Long? = null) {
        autoDismissJob?.cancel()
        _state.value = newState
        if (autoDismissMs != null) {
            autoDismissJob = scope.launch {
                delay(autoDismissMs)
                if (_state.value == newState) reset()
            }
        }
    }

    private fun reset() {
        autoDismissJob?.cancel()
        _state.value = EducationalCorrectionState.Idle
    }

    private fun sendFeedback(
        response: CorrectionSessionResponse,
        selectedSuggestion: String?,
        accepted: Boolean,
        finalText: String? = null,
        reason: String? = null,
    ) {
        // Mutex FIFO: los envíos se completan en el orden en que se lanzaron (p. ej. aceptar
        // antes que UNDO), aunque la primera petición sea lenta.
        scope.launch {
            feedbackMutex.withLock {
                val session = currentSession() ?: return@launch
                EducationalBackendBaseUrls.firstSuccessful { baseUrl ->
                    repository.sendFeedback(
                        baseUrl = baseUrl,
                        token = session.token,
                        sessionId = response.sessionId,
                        selectedSuggestion = selectedSuggestion,
                        accepted = accepted,
                        finalText = finalText,
                        reason = reason,
                    )
                }
            }
        }
    }

    companion object {
        const val UNDO_MS = 3_000L
        const val NOTICE_MS = 4_000L
        const val NOTICE_SHORT_MS = 3_000L
        const val ONBOARDING_MS = 6_000L
        const val PROCESSING_SLOW_AFTER_MS = 5_000L
        const val REASON_UNDO = "UNDO"
    }
}

private const val PREWARM_DEBOUNCE_MS = 2 * 60 * 1000L // 2 minutos

/**
 * Gate puro del botón IA según la condición de la ejecución activa: solo `UNASSISTED` bloquea,
 * con el aviso que ve el alumno; sin ejecución (null) o `ASSISTED` no hay bloqueo.
 */
internal fun correctionBlockMessage(condition: ExperimentCondition?): String? =
    if (condition == ExperimentCondition.UNASSISTED) EducationalMessages.CorrectionDisabledInTask else null

// URL del backend. Se define en build.gradle.kts (buildConfigField EDUCATION_BACKEND_BASE_URL).
val EducationalBackendBaseUrls = listOf(
    BuildConfig.EDUCATION_BACKEND_BASE_URL,
)

internal suspend fun <T> List<String>.firstSuccessful(
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
