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

/** Finalización que falló y se conserva para "Reintentar" con la MISMA clave (idempotente en el backend). */
data class PendingCompletion(val text: String, val durationMs: Long, val completionKey: String)

sealed interface EducationalExperimentState {
    /** Sin ejecución: la corrección funciona exactamente como siempre. */
    data object Idle : EducationalExperimentState
    data object Redeeming : EducationalExperimentState
    /** Código canjeado (run `PENDING`), pendiente de que el alumno confirme e inicie. */
    data class Ready(val run: ExperimentRunResponse) : EducationalExperimentState
    data class Starting(val run: ExperimentRunResponse) : EducationalExperimentState
    /** El alumno escribe; [firstKeyAtMs] es `elapsedRealtime` de la primera pulsación (null hasta entonces). */
    data class Active(val run: ExperimentRunResponse, val firstKeyAtMs: Long?) : EducationalExperimentState
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
 * Una ejecución ACTIVE restaurada tras cerrar la app vuelve con `firstKeyAtMs = null`: el reloj
 * monótono no sobrevive a la muerte del proceso, así que la duración se mide desde la siguiente
 * primera pulsación. El backend guarda además el tiempo transcurrido en servidor y marca la
 * incidencia si no cuadran; no rechaza la finalización.
 */
class EducationalExperimentCoordinator(
    private val scope: CoroutineScope,
    private val api: EducationalExperimentApi,
    private val session: () -> EducationalSession?,
    private val elapsedRealtime: () -> Long,
    private val appVersion: String,
    private val newCompletionKey: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(
        scope: CoroutineScope,
        repository: EducationalApiRepository,
        baseUrls: List<String>,
        session: () -> EducationalSession?,
        elapsedRealtime: () -> Long,
        appVersion: String,
    ) : this(scope, RepositoryExperimentApi(repository, baseUrls), session, elapsedRealtime, appVersion)

    private val _state = MutableStateFlow<EducationalExperimentState>(EducationalExperimentState.Idle)
    val state: StateFlow<EducationalExperimentState> = _state

    private var job: Job? = null
    private val busy: Boolean get() = job?.isActive == true

    // --- Gates ---------------------------------------------------------------------------------

    /** Ejecución que sigue vigente en el backend (ACTIVE), en cualquier estado que la conserve. */
    private fun activeRun(): ExperimentRunResponse? = when (val current = _state.value) {
        is EducationalExperimentState.Active -> current.run
        is EducationalExperimentState.Completing -> current.run
        is EducationalExperimentState.Failed -> current.run
        else -> null
    }?.takeIf { it.status == StatusActive }

    /** `false` solo mientras hay una ejecución ACTIVE sin asistencia; el backend lo vuelve a validar. */
    fun correctionAllowed(): Boolean = activeRun()?.condition != ExperimentCondition.UNASSISTED

    /** `id_ejecucion` a enviar en la corrección: solo en ASSISTED + ACTIVE. */
    fun activeRunId(): String? = activeRun()?.takeIf { it.condition == ExperimentCondition.ASSISTED }?.id

    // --- Transitions ---------------------------------------------------------------------------

    /** Canjea un código de acceso. Normaliza y valida localmente antes de tocar el backend. */
    fun redeem(rawCode: String) {
        if (busy) return
        val current = _state.value
        val canRedeem = when (current) {
            is EducationalExperimentState.Idle,
            is EducationalExperimentState.Ready,
            is EducationalExperimentState.Completed,
            is EducationalExperimentState.Cancelled -> true
            is EducationalExperimentState.Failed -> current.run == null
            else -> false
        }
        if (!canRedeem) return
        val code = rawCode.trim().uppercase().replace(" ", "").replace("-", "")
        if (!AccessCodePattern.matches(code)) {
            _state.value = EducationalExperimentState.Failed(null, EducationalMessages.InvalidAccessCode, retryable = false)
            return
        }
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(null, EducationalMessages.NoSession, retryable = false)
            return
        }
        _state.value = EducationalExperimentState.Redeeming
        job = scope.launch {
            api.redeem(token, code)
                .onSuccess { run -> _state.value = EducationalExperimentState.Ready(run) }
                .onFailure { error -> _state.value = failed(null, error) }
        }
    }

    /** Inicia la ejecución canjeada; espera la confirmación del backend antes de pasar a [Active]. */
    fun start() {
        if (busy) return
        val run = when (val current = _state.value) {
            is EducationalExperimentState.Ready -> current.run
            is EducationalExperimentState.Failed ->
                current.run?.takeIf { it.status == StatusPending && current.pendingCompletion == null }
            else -> null
        } ?: return
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(run, EducationalMessages.SessionExpired, retryable = false)
            return
        }
        _state.value = EducationalExperimentState.Starting(run)
        job = scope.launch {
            api.start(token, run.id)
                .onSuccess { started -> _state.value = EducationalExperimentState.Active(started, firstKeyAtMs = null) }
                .onFailure { error -> _state.value = failed(run, error) }
        }
    }

    /**
     * Recupera la ejecución vigente al abrir la app: ACTIVE → [Active] sin primera pulsación,
     * PENDING → [Ready], nada → [Idle]. Nunca pisa una ejecución en curso en memoria.
     */
    fun restore() {
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
        job = scope.launch {
            api.active(token)
                .onSuccess { run ->
                    _state.value = when (run?.status) {
                        StatusActive -> EducationalExperimentState.Active(run, firstKeyAtMs = null)
                        StatusPending -> EducationalExperimentState.Ready(run)
                        else -> EducationalExperimentState.Idle
                    }
                }
                .onFailure { error -> _state.value = failed(null, error) }
        }
    }

    /** Primera pulsación del alumno; idempotente. La duración se mide desde aquí. */
    fun markFirstKey() {
        val current = _state.value as? EducationalExperimentState.Active ?: return
        if (current.firstKeyAtMs != null) return
        _state.value = current.copy(firstKeyAtMs = elapsedRealtime())
    }

    /**
     * Envía el texto final. Devuelve `false` si no se envió nada: texto en blanco, sin primera
     * pulsación (duración 0 = no escribió), o sin ejecución activa. Tras un fallo, el siguiente
     * `complete` reutiliza la misma clave y duración, así el backend deduplica.
     */
    fun complete(rawText: String): Boolean {
        if (busy) return false
        val text = rawText.trim()
        if (text.isEmpty()) return false
        val (run, pending) = when (val current = _state.value) {
            is EducationalExperimentState.Active -> {
                val firstKey = current.firstKeyAtMs ?: return false
                val duration = (elapsedRealtime() - firstKey).coerceAtLeast(0L)
                current.run to PendingCompletion(text, duration, newCompletionKey())
            }
            is EducationalExperimentState.Failed -> {
                val run = current.run ?: return false
                val previous = current.pendingCompletion ?: return false
                run to previous.copy(text = text)
            }
            else -> return false
        }
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(run, EducationalMessages.SessionExpired, retryable = false, pending)
            return true
        }
        _state.value = EducationalExperimentState.Completing(run, pending.text, pending.durationMs, pending.completionKey)
        job = scope.launch {
            api.complete(token, run.id, pending.text, pending.durationMs, pending.completionKey, appVersion)
                .onSuccess { completed -> _state.value = EducationalExperimentState.Completed(completed) }
                .onFailure { error -> _state.value = failed(run, error, pending) }
        }
        return true
    }

    /** "Reintentar" desde [Failed]: reenvía la finalización pendiente, reintenta el inicio o la restauración. */
    fun retry() {
        val current = _state.value as? EducationalExperimentState.Failed ?: return
        when {
            current.pendingCompletion != null -> complete(current.pendingCompletion.text)
            current.run != null -> start()
            else -> restore()
        }
    }

    /**
     * Abandona la ejecución. El estado local se limpia aunque el backend responda con error: el
     * investigador ve el estado real de la ejecución en su panel.
     */
    fun cancel(reason: CancelReason) {
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
            return
        }
        job = scope.launch {
            api.cancel(token, run.id, reason)
            _state.value = EducationalExperimentState.Cancelled
        }
    }

    /** Cierre de sesión: olvida todo, incluida una operación en vuelo. */
    fun clear() {
        job?.cancel()
        job = null
        _state.value = EducationalExperimentState.Idle
    }

    private fun failed(
        run: ExperimentRunResponse?,
        error: Throwable,
        pending: PendingCompletion? = null,
    ) = EducationalExperimentState.Failed(
        run = run,
        message = EducationalMessages.experiment(error),
        retryable = error !is EducationalHttpException || error.status >= 500,
        pendingCompletion = pending,
    )

    companion object {
        /** Ocho caracteres no ambiguos: sin 0/O, 1/I. */
        private val AccessCodePattern = Regex("[A-HJ-NP-Z2-9]{8}")
        private const val StatusActive = "ACTIVE"
        private const val StatusPending = "PENDING"
    }
}
