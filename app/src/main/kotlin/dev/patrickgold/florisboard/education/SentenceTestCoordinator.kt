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

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SentenceTestState {
    /** Sin prueba: la corrección funciona exactamente como siempre. */
    data object Idle : SentenceTestState
    /** `GET /tests/assigned` en vuelo (lista o reanudación). */
    data object LoadingTests : SentenceTestState
    data class Choosing(val tests: List<AssignedTest>) : SentenceTestState
    data class Starting(val test: AssignedTest) : SentenceTestState
    /** Pantalla de oración antes de tocar Comenzar. */
    data class AtSentence(val attempt: AttemptResponse, val position: Int) : SentenceTestState {
        val assistance get() = attempt.assistanceOf(position)
    }
    data class Writing(
        val attempt: AttemptResponse,
        val position: Int,
        val responseId: String,
        val pressedAtElapsedMs: Long,
        val firstKeyAtElapsedMs: Long?,
        val counters: SuggestionCounters,
        val text: String,
    ) : SentenceTestState {
        val assistance get() = attempt.assistanceOf(position)
    }
    /** `PUT …/responses/{position}` en vuelo con el payload congelado en [pending]. */
    data class Finishing(val attempt: AttemptResponse, val position: Int, val pending: FinishSentenceRequest) : SentenceTestState
    /** La finalización no se confirmó; [pending] se reenvía TAL CUAL (misma `completionKey`) con Reintentar. */
    data class FinishFailed(
        val attempt: AttemptResponse,
        val position: Int,
        val pending: FinishSentenceRequest,
        val message: String,
        val retryable: Boolean,
    ) : SentenceTestState
    data class Completed(val attempt: AttemptResponse) : SentenceTestState
    data class Cancelling(val attempt: AttemptResponse) : SentenceTestState
    data object Cancelled : SentenceTestState
    /** Reinicio del teléfono con una oración a medias: solo cabe cancelar (problema técnico). */
    data class ClockLost(val attempt: AttemptResponse, val position: Int) : SentenceTestState
    data class Failed(val message: String) : SentenceTestState
}

/** Oración por resolver (posición y total de la prueba) mientras hay una en curso; null si no. */
fun SentenceTestState.currentSentence(): Pair<Int, Int>? = when (this) {
    is SentenceTestState.AtSentence -> position to attempt.sentenceCount
    is SentenceTestState.Writing -> position to attempt.sentenceCount
    is SentenceTestState.Finishing -> position to attempt.sentenceCount
    is SentenceTestState.FinishFailed -> position to attempt.sentenceCount
    is SentenceTestState.ClockLost -> position to attempt.sentenceCount
    else -> null
}

/**
 * Máquina de estados de las pruebas de oraciones, sin Android, para probarla en JVM. Lista las
 * pruebas asignadas, inicia (o retoma) el intento, y por cada oración: Comenzar (`POST …/start`,
 * cronómetro `elapsedRealtime` desde la pulsación), escritura con borrador en disco, Terminar
 * (`PUT` con offsets, contadores y clave idempotente) y paso a la siguiente oración o al final.
 *
 * Gates que consulta `EducationalCorrectionManager`: [correctionAllowed] (con una prueba en curso
 * solo se corrige escribiendo una oración ASSISTED; sin prueba, como siempre) y
 * [activeResponseId] (`id_respuesta` solo en `Writing` ASSISTED).
 *
 * Persistencia: [SentenceDraft] en [drafts] desde que la oración se comienza; cada tecla y cada
 * contador lo actualizan; Terminar guarda la `completionKey` ANTES de enviar, así un reintento
 * (incluso tras morir el proceso: [resume] restaura `Writing` y Terminar reutiliza la clave)
 * envía la misma clave y el backend deduplica. Al pasar a la siguiente oración, al completar o
 * al cancelar se borra. Un borrador de otro arranque del teléfono ([bootId]) no tiene cronómetro
 * comparable: [SentenceTestState.ClockLost], solo cabe cancelar.
 *
 * Errores: los fallos de operaciones "de pantalla" (Comenzar, cancelar, iniciar una prueba) no
 * cambian de estado; se publican en [lastError] (transitorio, la pantalla lo muestra y llama a
 * [dismissError]). Una finalización fallida sí tiene estado propio ([SentenceTestState.FinishFailed])
 * porque conserva el payload a reenviar. Un 401 en cualquier llamada se comunica por
 * [onSessionRejected] (el manager cierra la sesión local sin tocar este estado).
 *
 * Concurrencia: los llamadores deben usar el hilo principal. Las transiciones compuestas
 * (comprobar `busy`, escribir estado, lanzar el job) van bajo un `lock` privado que solo protege
 * frente a llamadas accidentales desde otro hilo; no convierte la clase en una API multihilo.
 */
class SentenceTestCoordinator(
    private val scope: CoroutineScope,
    private val api: SentenceTestApi,
    private val session: () -> EducationalSession?,
    private val elapsedRealtime: () -> Long,
    private val appVersion: String,
    private val drafts: SentenceTestDraftStore,
    private val bootId: () -> String,
    private val newCompletionKey: () -> String = { UUID.randomUUID().toString() },
    private val onSessionRejected: () -> Unit = {},
) {
    private val lock = Any()
    private val _state = MutableStateFlow<SentenceTestState>(SentenceTestState.Idle)
    val state: StateFlow<SentenceTestState> = _state

    /** Último fallo de una operación que no cambia de estado (Comenzar, cancelar, iniciar); null sin fallo. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var job: Job? = null
    private val busy: Boolean get() = job?.isActive == true

    /**
     * Se pidió la lista mientras `GET assigned` ya volaba (p. ej. abrir Pruebas justo tras iniciar
     * sesión, con [resume] en curso): al terminar se publica [SentenceTestState.Choosing] (o
     * [SentenceTestState.Failed]) en vez de `Idle`, que la pantalla pintaría como carga sin fin.
     */
    private var listRequested = false

    /**
     * Último alumno conocido: permite guardar el borrador aunque la sesión ya haya vencido y
     * detectar que entró otra cuenta en el mismo teléfono ([adoptSessionOwner]).
     */
    private var lastOwnerUserId: String = ""

    /** Última lista recibida: a ella se vuelve si iniciar una prueba falla. */
    private var lastTests: List<AssignedTest> = emptyList()

    // --- Gates ---------------------------------------------------------------------------------

    private fun writingAssisted(): SentenceTestState.Writing? =
        (_state.value as? SentenceTestState.Writing)?.takeIf { it.assistance == SentenceAssistance.ASSISTED }

    /** Sin prueba en curso, como siempre; con una, solo escribiendo una oración con ayuda. */
    fun correctionAllowed(): Boolean = !inProgress() || writingAssisted() != null

    /** `id_respuesta` a enviar en la corrección: solo escribiendo una oración con ayuda. */
    fun activeResponseId(): String? = writingAssisted()?.responseId

    /** Hay una oración por resolver: antes, durante o después (sin confirmar) de escribirla. */
    fun inProgress(): Boolean = _state.value.currentSentence() != null

    // --- Counters ------------------------------------------------------------------------------

    fun onSuggestionsOffered() = count { it.offered() }
    fun onSuggestionAccepted() = count { it.accepted() }
    fun onSuggestionRejected() = count { it.rejected() }
    fun onSuggestionUndone() = count { it.undone() }

    /** Los eventos de sugerencias solo cuentan mientras se escribe una oración. */
    private fun count(update: (SuggestionCounters) -> SuggestionCounters): Unit = synchronized(lock) {
        val current = _state.value as? SentenceTestState.Writing ?: return
        val next = current.copy(counters = update(current.counters))
        _state.value = next
        saveDraft(next)
    }

    // --- Screen actions ------------------------------------------------------------------------

    /**
     * Lista las pruebas asignadas. Si alguna está `IN_PROGRESS` no hay nada que elegir: se retoma
     * directamente (el backend devuelve el intento existente con su `nextPosition`). Con una
     * consulta ya en vuelo ([resume] o esta misma) no se repite: su resultado se publica como lista.
     */
    fun loadTests(): Unit = synchronized(lock) {
        val current = adoptSessionOwner()
        if (inProgress() || _state.value is SentenceTestState.Cancelling) return
        if (busy) {
            if (_state.value is SentenceTestState.LoadingTests) listRequested = true
            return
        }
        val token = current?.token
        if (token == null) {
            _state.value = SentenceTestState.Failed(EducationalMessages.NoSession)
            return
        }
        _state.value = SentenceTestState.LoadingTests
        job = scope.launch {
            api.assigned(token)
                .onSuccess { tests ->
                    lastTests = tests
                    val inProgress = tests.firstOrNull { it.isInProgress }
                    if (inProgress == null) transition(SentenceTestState.Choosing(tests)) else resumeAttempt(token, inProgress)
                }
                .onFailure { error -> transition(failed(error)) }
        }
    }

    /** Inicia la prueba elegida; si falla se vuelve a la lista con el motivo en [lastError]. */
    fun start(test: AssignedTest): Unit = synchronized(lock) {
        if (busy) return
        val choosing = _state.value as? SentenceTestState.Choosing ?: return
        val token = session()?.token
        if (token == null) {
            _lastError.value = EducationalMessages.SessionExpired
            return
        }
        _state.value = SentenceTestState.Starting(test)
        job = scope.launch {
            api.startAttempt(token, test.testId, appVersion)
                .onSuccess { attempt -> enterAttempt(token, attempt) }
                .onFailure { error -> transition(choosing) { reportError(error) } }
        }
    }

    /**
     * Retoma la prueba en curso al abrir la app o al iniciar sesión. Sin sesión no se consulta
     * nada; una oración en curso (o una operación sin confirmar) nunca se pisa. Con sesión:
     * `GET assigned`; si hay `IN_PROGRESS` → `startAttempt` (devuelve el intento existente) →
     * `Writing` desde el borrador si coincide alumno, intento, posición y arranque del teléfono
     * (`responseId` se recupera con `startSentence`, idempotente); borrador de otro arranque →
     * [SentenceTestState.ClockLost]; sin borrador → [SentenceTestState.AtSentence]. Sin prueba en
     * curso → [SentenceTestState.Idle]. Si la consulta falla se queda en `Idle` con [lastError];
     * el borrador se conserva para el siguiente intento. Si mientras tanto alguien pidió la lista
     * ([loadTests]), el resultado se publica como en `loadTests` (`Choosing` o `Failed`).
     *
     * Otra cuenta en el mismo teléfono (el último alumno conocido no es el de la sesión): nada
     * del anterior sobrevive, ni la oración en curso, ni la operación en vuelo ni el borrador.
     */
    fun resume(): Unit = synchronized(lock) {
        val current = adoptSessionOwner() ?: return
        if (busy || inProgress() || _state.value is SentenceTestState.Cancelling) return
        // Un borrador de otra cuenta (p. ej. de antes de este arranque) no es de este alumno.
        drafts.load()?.takeIf { it.ownerUserId != current.userId }?.let { drafts.clear() }
        val token = current.token
        _state.value = SentenceTestState.LoadingTests
        job = scope.launch {
            api.assigned(token)
                .onSuccess { tests ->
                    lastTests = tests
                    val inProgress = tests.firstOrNull { it.isInProgress }
                    when {
                        inProgress != null -> resumeAttempt(token, inProgress)
                        listRequested -> transition(SentenceTestState.Choosing(tests)) { drafts.clear() }
                        else -> transition(SentenceTestState.Idle) { drafts.clear() }
                    }
                }
                .onFailure { error ->
                    if (listRequested) transition(failed(error)) else transition(SentenceTestState.Idle) { reportError(error) }
                }
        }
    }

    /** Comenzar: `POST …/start`; el cronómetro arranca en la pulsación. Si falla, se queda en la oración. */
    fun pressStart(): Unit = synchronized(lock) {
        if (busy) return
        val current = _state.value as? SentenceTestState.AtSentence ?: return
        val token = session()?.token
        if (token == null) {
            _lastError.value = EducationalMessages.SessionExpired
            return
        }
        val pressedAt = elapsedRealtime()
        job = scope.launch {
            api.startSentence(token, current.attempt.attemptId, current.position)
                .onSuccess { started ->
                    val writing = SentenceTestState.Writing(
                        attempt = current.attempt,
                        position = current.position,
                        responseId = started.responseId,
                        pressedAtElapsedMs = pressedAt,
                        firstKeyAtElapsedMs = null,
                        counters = SuggestionCounters(),
                        text = "",
                    )
                    transition(writing) { saveDraft(writing) }
                }
                .onFailure { error -> transition(current) { reportError(error) } }
        }
    }

    /** Cada cambio del campo: fija la primera tecla (una sola vez, con texto) y guarda el borrador. */
    fun onTextChanged(text: String): Unit = synchronized(lock) {
        val current = _state.value as? SentenceTestState.Writing ?: return
        val firstKey = current.firstKeyAtElapsedMs ?: elapsedRealtime().takeIf { text.isNotEmpty() }
        val next = current.copy(text = text, firstKeyAtElapsedMs = firstKey)
        _state.value = next
        saveDraft(next)
    }

    /**
     * Terminar: congela el payload (offsets desde Comenzar, contadores, `skipped` con texto en
     * blanco) con la clave del borrador si ya había una (reintento tras reinicio) o una nueva,
     * la guarda en disco y envía. Texto no vacío sin primera tecla (no debería pasar) se mide
     * desde Comenzar. [tappedAtElapsedMs] es el instante (`elapsedRealtime`) del toque en
     * Terminar: quien llame más tarde (p. ej. tras vaciar la cola de feedback) lo pasa para que
     * la duración de la oración no incluya esa espera.
     */
    fun finish(tappedAtElapsedMs: Long = elapsedRealtime()): Unit = synchronized(lock) {
        if (busy) return
        val current = _state.value as? SentenceTestState.Writing ?: return
        val now = tappedAtElapsedMs
        val text = current.text
        val firstKey = current.firstKeyAtElapsedMs ?: current.pressedAtElapsedMs.takeIf { text.isNotEmpty() }
        val key = drafts.load()?.takeIf { it.matches(current) }?.completionKey ?: newCompletionKey()
        val pending = FinishSentenceRequest(
            finalText = text,
            firstKeyOffsetMs = firstKey?.let { it - current.pressedAtElapsedMs },
            finishedOffsetMs = now - current.pressedAtElapsedMs,
            suggestionsOffered = current.counters.offered,
            suggestionsAccepted = current.counters.accepted,
            suggestionsRejected = current.counters.rejected,
            suggestionsUndone = current.counters.undone,
            skipped = text.isBlank(),
            completionKey = key,
        )
        saveDraft(current, completionKey = key)
        launchFinish(current.attempt, current.position, pending)
    }

    /** Reintentar desde [SentenceTestState.FinishFailed]: reenvía el MISMO payload (misma clave). */
    fun retry(): Unit = synchronized(lock) {
        if (busy) return
        val current = _state.value as? SentenceTestState.FinishFailed ?: return
        if (!current.retryable) return
        launchFinish(current.attempt, current.position, current.pending)
    }

    /**
     * `PUT …/responses/{position}`. Éxito → siguiente oración o [SentenceTestState.Completed]
     * (borrador fuera). Un `409 "Sentence already finished"` (el backend ya la tiene con otra
     * clave) es éxito lógico: se pide el intento para saber la siguiente posición; si esa era la
     * última oración el backend responde `409 "Test already completed"`, que también es éxito
     * ([SentenceTestState.Completed]). Cualquier otro fallo → [SentenceTestState.FinishFailed],
     * reintentable con red, 5xx o 401 (tras volver a entrar se reenvía igual).
     */
    private fun launchFinish(attempt: AttemptResponse, position: Int, pending: FinishSentenceRequest) {
        val token = session()?.token
        if (token == null) {
            _state.value = SentenceTestState.FinishFailed(
                attempt, position, pending, EducationalMessages.SessionExpired, retryable = true,
            )
            return
        }
        _state.value = SentenceTestState.Finishing(attempt, position, pending)
        job = scope.launch {
            api.finishSentence(token, attempt.attemptId, position, pending)
                .onSuccess { response -> advance(attempt, response.nextPosition) }
                .onFailure { error ->
                    if (error.isAlreadyFinished()) {
                        api.startAttempt(token, attempt.testId, appVersion)
                            .onSuccess { refreshed -> advance(refreshed, refreshed.nextPosition) }
                            .onFailure { e ->
                                if (e.isAlreadyCompleted()) advance(attempt, null) else transition(finishFailed(attempt, position, pending, e))
                            }
                    } else {
                        transition(finishFailed(attempt, position, pending, error))
                    }
                }
        }
    }

    /**
     * Abandona el intento (desde la oración, escribiendo, tras un fallo o con el reloj perdido).
     * Solo pasa a [SentenceTestState.Cancelled] (y borra el borrador) cuando el backend lo
     * confirma o responde que el intento ya estaba cerrado (400/404/409). Si falla, se vuelve al
     * estado anterior con el motivo en [lastError].
     */
    fun cancel(reason: AttemptCancelReason): Unit = synchronized(lock) {
        if (busy) return
        val previous = _state.value
        val attempt = when (previous) {
            is SentenceTestState.AtSentence -> previous.attempt
            is SentenceTestState.Writing -> previous.attempt
            is SentenceTestState.FinishFailed -> previous.attempt
            is SentenceTestState.ClockLost -> previous.attempt
            else -> return
        }
        val token = session()?.token
        if (token == null) {
            _lastError.value = EducationalMessages.SessionExpired
            return
        }
        _state.value = SentenceTestState.Cancelling(attempt)
        job = scope.launch {
            api.cancel(token, attempt.attemptId, reason)
                .onSuccess { transition(SentenceTestState.Cancelled) { drafts.clear() } }
                .onFailure { error ->
                    if (error is EducationalHttpException && error.status in TerminalCancelStatuses) {
                        transition(SentenceTestState.Cancelled) { drafts.clear() }
                    } else {
                        transition(previous) { reportError(error) }
                    }
                }
        }
    }

    /** Volver al inicio desde un estado terminal (completada, cancelada o fallo). */
    fun leaveToHome(): Unit = synchronized(lock) {
        when (_state.value) {
            is SentenceTestState.Completed,
            SentenceTestState.Cancelled,
            is SentenceTestState.Failed -> _state.value = SentenceTestState.Idle
            else -> Unit
        }
    }

    /** La pantalla ya mostró [lastError]. */
    fun dismissError() {
        _lastError.value = null
    }

    /** Cierre de sesión: olvida todo, incluida una operación en vuelo y el borrador en disco. */
    fun clear(): Unit = synchronized(lock) {
        forgetPreviousOwner()
    }

    private fun forgetPreviousOwner() {
        job?.cancel()
        job = null
        listRequested = false
        lastTests = emptyList()
        _lastError.value = null
        _state.value = SentenceTestState.Idle
        drafts.clear()
    }

    // --- Helpers -------------------------------------------------------------------------------

    /**
     * Resultado final de un job, bajo el mismo lock que las transiciones síncronas. Publicar el
     * estado libera también el `busy`: así un observador que reacciona al nuevo estado desde otro
     * hilo nunca ve la operación todavía "en vuelo".
     */
    private inline fun transition(next: SentenceTestState, sideEffect: () -> Unit = {}) {
        synchronized(lock) {
            _state.value = next
            sideEffect()
            job = null
            listRequested = false
        }
    }

    /** `startAttempt` del intento en curso (devuelve el existente) y entrada en su siguiente oración. */
    private suspend fun resumeAttempt(token: String, test: AssignedTest) {
        api.startAttempt(token, test.testId, appVersion)
            .onSuccess { attempt -> enterAttempt(token, attempt) }
            .onFailure { error -> transition(failed(error)) }
    }

    /**
     * Sitúa al alumno en `attempt.nextPosition`: con un borrador de esa misma oración y del mismo
     * arranque → `Writing` restaurado (`responseId` vía `startSentence`, idempotente); de otro
     * arranque → `ClockLost`; sin borrador (o de otra oración/intento) → `AtSentence`.
     */
    private suspend fun enterAttempt(token: String, attempt: AttemptResponse) {
        val next = attempt.nextPosition
        if (next == null) {
            transition(SentenceTestState.Completed(attempt)) { drafts.clear() }
            return
        }
        val draft = drafts.load()?.takeIf {
            it.ownerUserId == ownerUserId() && it.attemptId == attempt.attemptId && it.position == next
        }
        when {
            draft == null -> transition(SentenceTestState.AtSentence(attempt, next)) { drafts.clear() }
            draft.bootId == UnknownBootId || draft.bootId != bootId() -> transition(SentenceTestState.ClockLost(attempt, next))
            else -> api.startSentence(token, attempt.attemptId, next)
                .onSuccess { started ->
                    transition(
                        SentenceTestState.Writing(
                            attempt = attempt,
                            position = next,
                            responseId = started.responseId,
                            pressedAtElapsedMs = draft.pressedAtElapsedMs,
                            firstKeyAtElapsedMs = draft.firstKeyAtElapsedMs,
                            counters = SuggestionCounters(draft.offered, draft.accepted, draft.rejected, draft.undone),
                            text = draft.text,
                        ),
                    )
                }
                .onFailure { error -> transition(failed(error)) }
        }
    }

    /**
     * Oración confirmada: siguiente oración o prueba completada; el borrador ya no hace falta.
     * El intento que llevan los estados refleja la posición actual (no la de cuando se pidió).
     */
    private fun advance(attempt: AttemptResponse, next: Int?) {
        val refreshed = attempt.copy(nextPosition = next)
        val state = if (next == null) SentenceTestState.Completed(refreshed) else SentenceTestState.AtSentence(refreshed, next)
        transition(state) { drafts.clear() }
    }

    /**
     * Sesión actual (null si no hay) tomando nota de su alumno. Si es otro alumno que el último
     * conocido (otra cuenta en el mismo teléfono) se olvida todo lo del anterior antes de seguir.
     */
    private fun adoptSessionOwner(): EducationalSession? {
        val current = session() ?: return null
        if (lastOwnerUserId.isNotEmpty() && lastOwnerUserId != current.userId) forgetPreviousOwner()
        lastOwnerUserId = current.userId
        return current
    }

    private fun ownerUserId(): String {
        session()?.userId?.let { lastOwnerUserId = it }
        return lastOwnerUserId
    }

    private fun SentenceDraft.matches(writing: SentenceTestState.Writing): Boolean =
        attemptId == writing.attempt.attemptId && position == writing.position && ownerUserId == ownerUserId()

    private fun saveDraft(writing: SentenceTestState.Writing, completionKey: String? = null) {
        val stored = drafts.load()?.takeIf { it.matches(writing) }
        drafts.save(
            SentenceDraft(
                ownerUserId = ownerUserId(),
                attemptId = writing.attempt.attemptId,
                position = writing.position,
                bootId = stored?.bootId ?: bootId(),
                pressedAtElapsedMs = writing.pressedAtElapsedMs,
                firstKeyAtElapsedMs = writing.firstKeyAtElapsedMs,
                text = writing.text,
                offered = writing.counters.offered,
                accepted = writing.counters.accepted,
                rejected = writing.counters.rejected,
                undone = writing.counters.undone,
                completionKey = completionKey ?: stored?.completionKey,
            ),
        )
    }

    private fun reportError(error: Throwable) {
        if (error is EducationalHttpException && error.status == 401) onSessionRejected()
        _lastError.value = EducationalMessages.sentenceTest(error)
    }

    private fun failed(error: Throwable): SentenceTestState.Failed {
        if (error is EducationalHttpException && error.status == 401) onSessionRejected()
        return SentenceTestState.Failed(EducationalMessages.sentenceTest(error))
    }

    private fun finishFailed(
        attempt: AttemptResponse,
        position: Int,
        pending: FinishSentenceRequest,
        error: Throwable,
    ): SentenceTestState.FinishFailed {
        if (error is EducationalHttpException && error.status == 401) onSessionRejected()
        return SentenceTestState.FinishFailed(
            attempt, position, pending, EducationalMessages.sentenceTest(error), retryable = error.isRetryable(),
        )
    }

    /** Red, 5xx y sesión caducada (401: se reintenta tras volver a entrar); el resto de 4xx es terminal. */
    private fun Throwable.isRetryable(): Boolean =
        this !is EducationalHttpException || status >= 500 || status == 401

    /** El backend ya tiene esta oración terminada con otra clave: no hay nada que reenviar. */
    private fun Throwable.isAlreadyFinished(): Boolean =
        this is EducationalHttpException && status == 409 && body.contains(AlreadyFinished, ignoreCase = true)

    /** Tras la última oración el intento ya está cerrado: `startAttempt` no puede devolverlo. */
    private fun Throwable.isAlreadyCompleted(): Boolean =
        this is EducationalHttpException && status == 409 && body.contains(AlreadyCompleted, ignoreCase = true)

    companion object {
        /** `bootId` cuando el teléfono no expone `boot_count`: nunca cuenta como "el mismo arranque". */
        const val UnknownBootId = "unknown"
        private const val AlreadyFinished = "already finished"
        private const val AlreadyCompleted = "already completed"
        private val TerminalCancelStatuses = setOf(400, 404, 409)
    }
}
