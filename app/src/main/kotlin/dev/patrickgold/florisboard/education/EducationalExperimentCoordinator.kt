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
import kotlinx.serialization.Serializable

/** Operaciones del experimento ya resueltas contra la URL del backend; se falsifica en tests. */
interface EducationalExperimentApi {
    suspend fun redeem(token: String, code: String): Result<ExperimentRunResponse>
    suspend fun start(token: String, runId: String): Result<ExperimentRunResponse>
    suspend fun active(token: String): Result<ExperimentRunResponse?>
    suspend fun complete(
        token: String,
        runId: String,
        finalText: String,
        durationMs: Long,
        completionKey: String,
        appVersion: String,
    ): Result<ExperimentRunResponse>
    suspend fun cancel(token: String, runId: String, reason: CancelReason): Result<Unit>
}

/** Adaptador real: [EducationalApiRepository] probando cada URL de [baseUrls] como el resto del manager. */
class RepositoryExperimentApi(
    private val repository: EducationalApiRepository,
    private val baseUrls: List<String>,
) : EducationalExperimentApi {
    override suspend fun redeem(token: String, code: String) =
        baseUrls.firstSuccessful { repository.redeemExperimentCode(it, token, code) }

    override suspend fun start(token: String, runId: String) =
        baseUrls.firstSuccessful { repository.startExperiment(it, token, runId) }

    override suspend fun active(token: String) =
        baseUrls.firstSuccessful { repository.activeExperiment(it, token) }

    override suspend fun complete(
        token: String,
        runId: String,
        finalText: String,
        durationMs: Long,
        completionKey: String,
        appVersion: String,
    ) = baseUrls.firstSuccessful {
        repository.completeExperiment(it, token, runId, finalText, durationMs, completionKey, appVersion)
    }

    override suspend fun cancel(token: String, runId: String, reason: CancelReason) =
        baseUrls.firstSuccessful { repository.cancelExperiment(it, token, runId, reason) }
}

/**
 * Lo mínimo que sobrevive a la muerte del proceso: qué ejecución había, en qué condición y
 * cuándo empezó a escribir el alumno (`elapsedRealtime`, válido solo dentro del mismo [bootId]).
 */
@Serializable
data class ExperimentMarker(
    val runId: String,
    val condition: ExperimentCondition,
    val status: String,
    val firstKeyAtMs: Long?,
    val bootId: String,
)

/** Persistencia del marcador; la implementación Android vive en [PrefsExperimentMarkerStore]. */
interface ExperimentMarkerStore {
    fun load(): ExperimentMarker?
    fun save(marker: ExperimentMarker)
    fun clear()
}

/** Finalización que falló y se conserva para "Reintentar" con la MISMA clave (idempotente en el backend). */
data class PendingCompletion(val text: String, val durationMs: Long, val completionKey: String)

sealed interface EducationalExperimentState {
    /** Sin ejecución: la corrección funciona exactamente como siempre. */
    data object Idle : EducationalExperimentState
    data object Redeeming : EducationalExperimentState
    /** Código canjeado (run `PENDING`), pendiente de que el alumno confirme e inicie. */
    data class Ready(val run: ExperimentRunResponse) : EducationalExperimentState
    data class Starting(val run: ExperimentRunResponse) : EducationalExperimentState
    /**
     * El alumno escribe; [firstKeyAtMs] es `elapsedRealtime` de la primera pulsación (null hasta
     * entonces). [timerLost]: la ejecución se restauró sin un cronómetro fiable (otro arranque del
     * teléfono o sin primera pulsación guardada); no se puede finalizar, solo cancelar.
     */
    data class Active(
        val run: ExperimentRunResponse,
        val firstKeyAtMs: Long?,
        val timerLost: Boolean = false,
    ) : EducationalExperimentState
    data class Completing(
        val run: ExperimentRunResponse,
        val text: String,
        val durationMs: Long,
        val completionKey: String,
    ) : EducationalExperimentState
    /**
     * Algo falló. [run] se conserva para poder reintentar/cancelar; [pendingCompletion] guarda el
     * texto, la duración y la clave de una finalización fallida para reenviarla tal cual.
     */
    data class Failed(
        val run: ExperimentRunResponse?,
        val message: String,
        val retryable: Boolean,
        val pendingCompletion: PendingCompletion? = null,
    ) : EducationalExperimentState
    data class Completed(val run: ExperimentRunResponse) : EducationalExperimentState
    data object Cancelled : EducationalExperimentState
}

/**
 * Máquina de estados de la escritura controlada, sin Android, para probarla en JVM. Canjea el
 * código, inicia la ejecución, mide la duración desde la primera pulsación con el reloj monótono
 * inyectado, envía la finalización con una clave idempotente que se reutiliza al reintentar, y
 * expone los dos gates que usa el manager: [correctionAllowed] (bloqueo local en `UNASSISTED`) y
 * [activeRunId] (`id_ejecucion` solo en ASSISTED + ACTIVE).
 *
 * Persistencia: cada transición relevante guarda un [ExperimentMarker] en [store] (ejecución,
 * condición, estado, primera pulsación y arranque del teléfono). Al restaurar una ejecución ACTIVE
 * dentro del mismo arranque se recupera `firstKeyAtMs` y la duración cubre toda la tarea; tras un
 * reinicio el cronómetro se pierde ([EducationalExperimentState.Active.timerLost]) y solo cabe
 * cancelar. Si `restore()` falla y hay marcador, la ejecución se reconstruye desde él para que el
 * bloqueo UNASSISTED no se abra por un fallo de red.
 *
 * Frontera de finalización: mientras una finalización está en vuelo o falló sin confirmación
 * (`Completing`, `Failed` con `pendingCompletion` y run ACTIVE) no se expone id ni se permite
 * corregir, en ninguna condición: el backend puede haberla guardado ya.
 *
 * Concurrencia: los llamadores deben usar el hilo principal. Las transiciones compuestas
 * (comprobar `busy`, escribir estado, lanzar el job) van bajo un `lock` privado que solo protege
 * frente a llamadas accidentales desde otro hilo; no convierte la clase en una API multihilo.
 */
class EducationalExperimentCoordinator(
    private val scope: CoroutineScope,
    private val api: EducationalExperimentApi,
    private val session: () -> EducationalSession?,
    private val elapsedRealtime: () -> Long,
    private val appVersion: String,
    private val store: ExperimentMarkerStore,
    private val bootId: () -> String,
    private val newCompletionKey: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(
        scope: CoroutineScope,
        repository: EducationalApiRepository,
        baseUrls: List<String>,
        session: () -> EducationalSession?,
        elapsedRealtime: () -> Long,
        appVersion: String,
        store: ExperimentMarkerStore,
        bootId: () -> String,
    ) : this(scope, RepositoryExperimentApi(repository, baseUrls), session, elapsedRealtime, appVersion, store, bootId)

    private val lock = Any()
    private val _state = MutableStateFlow<EducationalExperimentState>(EducationalExperimentState.Idle)
    val state: StateFlow<EducationalExperimentState> = _state

    private var job: Job? = null
    private val busy: Boolean get() = job?.isActive == true

    // --- Gates ---------------------------------------------------------------------------------

    /** Finalización enviada sin confirmación: el backend puede haberla guardado ya. */
    private fun atCompletionBoundary(current: EducationalExperimentState): Boolean = when (current) {
        is EducationalExperimentState.Completing -> true
        is EducationalExperimentState.Failed ->
            current.pendingCompletion != null && current.run?.status == StatusActive
        else -> false
    }

    /** Ejecución que sigue vigente en el backend (ACTIVE) y cuyo desenlace no está en duda. */
    private fun activeRun(): ExperimentRunResponse? {
        val current = _state.value
        if (atCompletionBoundary(current)) return null
        return when (current) {
            is EducationalExperimentState.Active -> current.run
            is EducationalExperimentState.Failed -> current.run
            else -> null
        }?.takeIf { it.status == StatusActive }
    }

    /** `false` mientras hay una ejecución ACTIVE sin asistencia o una finalización sin confirmar. */
    fun correctionAllowed(): Boolean {
        if (atCompletionBoundary(_state.value)) return false
        return activeRun()?.condition != ExperimentCondition.UNASSISTED
    }

    /** `id_ejecucion` a enviar en la corrección: solo en ASSISTED + ACTIVE, nunca en la frontera. */
    fun activeRunId(): String? = activeRun()?.takeIf { it.condition == ExperimentCondition.ASSISTED }?.id

    /** Motivo por el que no se puede finalizar (la UI ofrece "Cancelar (problema técnico)"), o null. */
    fun completionBlockedReason(): String? =
        (_state.value as? EducationalExperimentState.Active)?.takeIf { it.timerLost }?.let { EducationalMessages.TimerLost }

    // --- Transitions ---------------------------------------------------------------------------

    /**
     * Canjea un código de acceso. Normaliza y valida localmente antes de tocar el backend. Una
     * ejecución ya canjeada pero no iniciada (PENDING) se conserva en [Failed] si el canje falla.
     */
    fun redeem(rawCode: String): Unit = synchronized(lock) {
        if (busy) return
        val current = _state.value
        val previousRun = when (current) {
            is EducationalExperimentState.Idle,
            is EducationalExperimentState.Completed,
            is EducationalExperimentState.Cancelled -> null
            is EducationalExperimentState.Ready -> current.run
            is EducationalExperimentState.Failed -> when {
                current.run == null -> null
                current.run.status == StatusPending && current.pendingCompletion == null -> current.run
                else -> return
            }
            else -> return
        }
        val code = rawCode.trim().uppercase().replace(" ", "").replace("-", "")
        if (!AccessCodePattern.matches(code)) {
            _state.value = EducationalExperimentState.Failed(previousRun, EducationalMessages.InvalidAccessCode, retryable = false)
            return
        }
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(previousRun, EducationalMessages.NoSession, retryable = false)
            return
        }
        _state.value = EducationalExperimentState.Redeeming
        job = scope.launch {
            api.redeem(token, code)
                .onSuccess { run -> transition(EducationalExperimentState.Ready(run)) { saveMarker(run, null) } }
                .onFailure { error -> transition(failed(previousRun, error)) }
        }
    }

    /** Inicia la ejecución canjeada; espera la confirmación del backend antes de pasar a [Active]. */
    fun start(): Unit = synchronized(lock) {
        if (busy) return
        val run = when (val current = _state.value) {
            is EducationalExperimentState.Ready -> current.run
            is EducationalExperimentState.Failed ->
                current.run?.takeIf { it.status == StatusPending && current.pendingCompletion == null }
            else -> null
        } ?: return
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(run, EducationalMessages.SessionExpired, retryable = true)
            return
        }
        _state.value = EducationalExperimentState.Starting(run)
        job = scope.launch {
            api.start(token, run.id)
                .onSuccess { started ->
                    transition(EducationalExperimentState.Active(started, firstKeyAtMs = null)) { saveMarker(started, null) }
                }
                .onFailure { error -> transition(failed(run, error)) }
        }
    }

    /**
     * Recupera la ejecución vigente al abrir la app: ACTIVE → [Active] (con `firstKeyAtMs` del
     * marcador si es del mismo arranque, si no `timerLost`), PENDING → [Ready], nada → [Idle] y se
     * borra el marcador. Si falla y hay marcador, la ejecución se reconstruye desde él en
     * [Failed] para conservar el bloqueo; sin marcador → [Idle]. Nunca pisa una ejecución en curso.
     */
    fun restore(): Unit = synchronized(lock) {
        if (busy) return
        when (val current = _state.value) {
            is EducationalExperimentState.Active, is EducationalExperimentState.Completing -> return
            is EducationalExperimentState.Failed -> if (current.pendingCompletion != null) return
            else -> Unit
        }
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Idle
            return
        }
        val marker = store.load()
        job = scope.launch {
            api.active(token)
                .onSuccess { run ->
                    when (run?.status) {
                        StatusActive -> {
                            val firstKey = marker?.firstKeyAtMs
                                ?.takeIf { marker.runId == run.id && marker.bootId != UnknownBootId && marker.bootId == bootId() }
                            transition(EducationalExperimentState.Active(run, firstKey, timerLost = firstKey == null)) {
                                saveMarker(run, firstKey)
                            }
                        }
                        StatusPending -> transition(EducationalExperimentState.Ready(run)) { saveMarker(run, null) }
                        else -> transition(EducationalExperimentState.Idle) { store.clear() }
                    }
                }
                .onFailure { error ->
                    transition(if (marker == null) EducationalExperimentState.Idle else failed(marker.toRun(), error))
                }
        }
    }

    /** Primera pulsación del alumno; idempotente. La duración se mide desde aquí. */
    fun markFirstKey(): Unit = synchronized(lock) {
        val current = _state.value as? EducationalExperimentState.Active ?: return
        if (current.firstKeyAtMs != null || current.timerLost) return
        val firstKey = elapsedRealtime()
        _state.value = current.copy(firstKeyAtMs = firstKey)
        saveMarker(current.run, firstKey)
    }

    /**
     * Envía el texto final. Devuelve `false` si no se envió nada: texto en blanco, sin primera
     * pulsación o duración no positiva (no escribió / reloj inválido), cronómetro perdido, o sin
     * ejecución activa. Tras un fallo reenvía la finalización pendiente TAL CUAL (mismo texto,
     * duración y clave): el texto nuevo se ignora para que el backend deduplique un único payload.
     */
    fun complete(rawText: String): Boolean = synchronized(lock) {
        if (busy) return false
        val (run, pending) = when (val current = _state.value) {
            is EducationalExperimentState.Active -> {
                if (current.timerLost) return false
                val text = rawText.trim()
                if (text.isEmpty()) return false
                val firstKey = current.firstKeyAtMs ?: return false
                val duration = elapsedRealtime() - firstKey
                if (duration <= 0L) return false
                current.run to PendingCompletion(text, duration, newCompletionKey())
            }
            is EducationalExperimentState.Failed -> {
                if (!current.retryable) return false
                val run = current.run ?: return false
                val previous = current.pendingCompletion ?: return false
                run to previous
            }
            else -> return false
        }
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(run, EducationalMessages.SessionExpired, retryable = true, pending)
            return true
        }
        _state.value = EducationalExperimentState.Completing(run, pending.text, pending.durationMs, pending.completionKey)
        job = scope.launch {
            api.complete(token, run.id, pending.text, pending.durationMs, pending.completionKey, appVersion)
                .onSuccess { completed -> transition(EducationalExperimentState.Completed(completed)) { store.clear() } }
                .onFailure { error -> transition(completionFailed(run, error, pending)) }
        }
        return true
    }

    /**
     * "Reintentar" desde [Failed] (solo si `retryable`): reenvía la finalización pendiente tal
     * cual, reintenta el inicio de una ejecución PENDING, o vuelve a restaurar.
     */
    fun retry() {
        val current = _state.value as? EducationalExperimentState.Failed ?: return
        if (!current.retryable) return
        when {
            current.pendingCompletion != null -> complete(current.pendingCompletion.text)
            current.run?.status == StatusPending -> start()
            else -> restore()
        }
    }

    /**
     * Abandona la ejecución. El estado local se limpia aunque el backend responda con error: el
     * investigador ve el estado real de la ejecución en su panel.
     */
    fun cancel(reason: CancelReason): Unit = synchronized(lock) {
        if (busy) return
        val run = when (val current = _state.value) {
            is EducationalExperimentState.Ready -> current.run
            is EducationalExperimentState.Active -> current.run
            is EducationalExperimentState.Failed -> current.run
            else -> null
        } ?: return
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Cancelled
            store.clear()
            return
        }
        job = scope.launch {
            api.cancel(token, run.id, reason)
            transition(EducationalExperimentState.Cancelled) { store.clear() }
        }
    }

    /** Cierre de sesión: olvida todo, incluida una operación en vuelo y el marcador persistido. */
    fun clear(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
        _state.value = EducationalExperimentState.Idle
        store.clear()
    }

    // --- Helpers -------------------------------------------------------------------------------

    /**
     * Resultado final de un job, bajo el mismo lock que las transiciones síncronas. Publicar el
     * estado libera también el `busy`: así un observador que reacciona al nuevo estado desde otro
     * hilo nunca ve la operación todavía "en vuelo".
     */
    private inline fun transition(next: EducationalExperimentState, sideEffect: () -> Unit = {}) {
        synchronized(lock) {
            _state.value = next
            sideEffect()
            job = null
        }
    }

    private fun saveMarker(run: ExperimentRunResponse, firstKeyAtMs: Long?) {
        store.save(ExperimentMarker(run.id, run.condition, run.status, firstKeyAtMs, bootId()))
    }

    /** Reconstrucción mínima cuando el backend no responde: basta para los gates y para cancelar. */
    private fun ExperimentMarker.toRun() = ExperimentRunResponse(
        id = runId,
        participantCode = "",
        condition = condition,
        taskVariant = "",
        promptText = "",
        status = status,
    )

    private fun failed(
        run: ExperimentRunResponse?,
        error: Throwable,
        pending: PendingCompletion? = null,
    ) = EducationalExperimentState.Failed(
        run = run,
        message = EducationalMessages.experiment(error),
        retryable = error.isRetryable(),
        pendingCompletion = pending,
    )

    /**
     * Un 4xx terminal (400/404/409) significa que la ejecución ya no acepta finalización: se
     * conserva como `CLOSED` para que los gates se abran. Un 401 la deja ACTIVE (problema de sesión).
     */
    private fun completionFailed(run: ExperimentRunResponse, error: Throwable, pending: PendingCompletion) =
        if (error is EducationalHttpException && error.status in TerminalCompletionStatuses) {
            store.clear()
            failed(run.copy(status = StatusClosed), error, pending)
        } else {
            failed(run, error, pending)
        }

    /** Red, 5xx y sesión caducada (401: se reintenta tras volver a entrar); el resto de 4xx es terminal. */
    private fun Throwable.isRetryable(): Boolean =
        this !is EducationalHttpException || status >= 500 || status == 401

    companion object {
        /** `bootId` cuando el teléfono no expone `boot_count`: nunca cuenta como "el mismo arranque". */
        const val UnknownBootId = "unknown"
        /** Ocho caracteres no ambiguos: sin 0/O, 1/I. */
        private val AccessCodePattern = Regex("[A-HJ-NP-Z2-9]{8}")
        private const val StatusActive = "ACTIVE"
        private const val StatusPending = "PENDING"
        /** Estado local (no del contrato) para una ejecución cerrada por el backend con un 4xx terminal. */
        private const val StatusClosed = "CLOSED"
        private val TerminalCompletionStatuses = setOf(400, 404, 409)
    }
}
