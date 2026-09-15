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

/** Finalización que falló y se conserva para "Reintentar" con la MISMA clave (idempotente en el backend). */
data class PendingCompletion(val text: String, val durationMs: Long, val completionKey: String)

/** [PendingCompletion] tal como se persiste dentro del marcador. */
@Serializable
data class PendingCompletionMarker(val text: String, val durationMs: Long, val completionKey: String)

/**
 * Lo mínimo que sobrevive a la muerte del proceso: qué ejecución había, en qué condición, de qué
 * alumno ([ownerUserId] = `EducationalSession.userId`), cuándo empezó a escribir (`elapsedRealtime`,
 * válido solo dentro del mismo [bootId]) y, si una finalización quedó sin confirmar, su payload
 * exacto ([pendingCompletion], con `status = "COMPLETING"`) para reenviarlo con la misma clave.
 * Los marcadores anteriores a estos campos se leen con sus valores por defecto.
 */
@Serializable
data class ExperimentMarker(
    val runId: String,
    val condition: ExperimentCondition,
    val status: String,
    val firstKeyAtMs: Long?,
    val bootId: String,
    val ownerUserId: String = "",
    val pendingCompletion: PendingCompletionMarker? = null,
)

/** Persistencia del marcador; la implementación Android vive en [PrefsExperimentMarkerStore]. */
interface ExperimentMarkerStore {
    fun load(): ExperimentMarker?
    fun save(marker: ExperimentMarker)
    fun clear()
}

sealed interface EducationalExperimentState {
    /** Sin ejecución: la corrección funciona exactamente como siempre. */
    data object Idle : EducationalExperimentState
    /**
     * `GET /runs/active` en vuelo. [known] es la ejecución reconstruida desde el marcador (null si no
     * había): mientras el backend no responde, los gates siguen su condición y estado. Sin marcador
     * no hay nada que bloquear y el uso normal sigue permitido (constraint: sin ejecución activa la
     * corrección funciona como siempre). Compromiso asumido: si el marcador no existe (reinstalación
     * con borrado de datos, versión anterior del teclado) y el backend aún tiene una ejecución
     * UNASSISTED ACTIVE, la IA queda disponible hasta que llega la respuesta.
     */
    data class Restoring(val known: ExperimentRunResponse?) : EducationalExperimentState
    data object Redeeming : EducationalExperimentState
    /** Código canjeado (run `PENDING`), pendiente de que el alumno confirme e inicie. */
    data class Ready(val run: ExperimentRunResponse) : EducationalExperimentState
    data class Starting(val run: ExperimentRunResponse) : EducationalExperimentState
    /**
     * El alumno escribe; [firstKeyAtMs] es `elapsedRealtime` de la primera pulsación (null hasta
     * entonces). [timerLost]: la ejecución se restauró sin un cronómetro fiable (una primera
     * pulsación de otro arranque del teléfono, un marcador de otra ejecución o ninguno); no se
     * puede finalizar, solo cancelar.
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
     * `POST …/cancel` en vuelo: nada más se acepta hasta que el backend confirme. [pendingCompletion]
     * se arrastra si se cancela desde una finalización fallida, para no abrir esa frontera.
     */
    data class Cancelling(
        val run: ExperimentRunResponse,
        val pendingCompletion: PendingCompletion? = null,
    ) : EducationalExperimentState
    /**
     * Algo falló. [run] se conserva para poder reintentar/cancelar; [pendingCompletion] guarda el
     * texto, la duración y la clave de una finalización fallida para reenviarla tal cual;
     * [pendingCancel] guarda el motivo de una cancelación que el backend no confirmó.
     */
    data class Failed(
        val run: ExperimentRunResponse?,
        val message: String,
        val retryable: Boolean,
        val pendingCompletion: PendingCompletion? = null,
        val pendingCancel: CancelReason? = null,
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
 * condición, estado, alumno, primera pulsación, arranque del teléfono y finalización pendiente).
 * [restore] publica [EducationalExperimentState.Restoring] de forma síncrona con la ejecución del
 * marcador, así los gates quedan cerrados desde antes de preguntar al backend. Al restaurar una
 * ejecución ACTIVE dentro del mismo arranque se recupera `firstKeyAtMs` y la duración cubre toda la
 * tarea; una primera pulsación de otro arranque pierde el cronómetro
 * ([EducationalExperimentState.Active.timerLost]) y solo cabe cancelar. Si `restore()` falla y hay
 * marcador, la ejecución se reconstruye desde él para que el bloqueo UNASSISTED no se abra por un
 * fallo de red. Un marcador de otra cuenta (`ownerUserId` distinto del alumno con sesión) se
 * descarta antes de tocar el backend: la ejecución anterior sigue tal cual en el backend.
 *
 * Frontera de finalización: mientras una finalización está en vuelo o falló sin confirmación
 * (`Completing`, `Failed` con `pendingCompletion` y run ACTIVE) no se expone id ni se permite
 * corregir, en ninguna condición: el backend puede haberla guardado ya. El payload pendiente se
 * persiste ANTES de enviarlo, así tras una muerte del proceso se reenvían exactamente los mismos
 * bytes (mismo `completion_key`) sin volver a preguntar por la ejecución.
 *
 * Un 401 en cualquier llamada se comunica por [onSessionRejected] (el manager cierra la sesión
 * local sin tocar este estado, y al volver a entrar se reanuda con el mismo payload).
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
    private val onSessionRejected: () -> Unit = {},
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
        onSessionRejected: () -> Unit = {},
    ) : this(
        scope, RepositoryExperimentApi(repository, baseUrls), session, elapsedRealtime, appVersion, store, bootId,
        onSessionRejected = onSessionRejected,
    )

    private val lock = Any()
    private val _state = MutableStateFlow<EducationalExperimentState>(EducationalExperimentState.Idle)
    val state: StateFlow<EducationalExperimentState> = _state

    private var job: Job? = null
    private val busy: Boolean get() = job?.isActive == true

    /** Último alumno conocido: permite guardar el marcador aunque la sesión ya haya vencido. */
    private var lastOwnerUserId: String = ""

    // --- Gates ---------------------------------------------------------------------------------

    /** Finalización enviada sin confirmación: el backend puede haberla guardado ya. */
    private fun atCompletionBoundary(current: EducationalExperimentState): Boolean = when (current) {
        is EducationalExperimentState.Completing -> true
        is EducationalExperimentState.Failed ->
            current.pendingCompletion != null && current.run?.status == StatusActive
        is EducationalExperimentState.Cancelling ->
            current.pendingCompletion != null && current.run.status == StatusActive
        else -> false
    }

    /** Ejecución que sigue vigente en el backend (ACTIVE) y cuyo desenlace no está en duda. */
    private fun activeRun(): ExperimentRunResponse? {
        val current = _state.value
        if (atCompletionBoundary(current)) return null
        return when (current) {
            is EducationalExperimentState.Active -> current.run
            is EducationalExperimentState.Failed -> current.run
            is EducationalExperimentState.Restoring -> current.known
            is EducationalExperimentState.Cancelling -> current.run
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
                current.run.status == StatusPending && current.pendingCompletion == null && current.pendingCancel == null ->
                    current.run
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
                .onFailure { error -> transition(failed(previousRun, error, ExperimentOp.REDEEM)) }
        }
    }

    /** Inicia la ejecución canjeada; espera la confirmación del backend antes de pasar a [Active]. */
    fun start(): Unit = synchronized(lock) {
        if (busy) return
        val run = when (val current = _state.value) {
            is EducationalExperimentState.Ready -> current.run
            is EducationalExperimentState.Failed ->
                current.run?.takeIf {
                    it.status == StatusPending && current.pendingCompletion == null && current.pendingCancel == null
                }
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
                .onFailure { error -> transition(failed(run, error, ExperimentOp.START)) }
        }
    }

    /**
     * Recupera la ejecución vigente al abrir la app. Primero, síncronamente: un marcador de otra
     * cuenta se descarta; una finalización pendiente persistida vuelve como [Failed] reintentable
     * sin tocar la red (la frontera sigue cerrada); si no, se publica [Restoring] con la ejecución
     * del marcador y se consulta el backend: ACTIVE → [Active] (con `firstKeyAtMs` del marcador si
     * es del mismo arranque; sin primera pulsación en la misma ejecución no hay nada perdido; con
     * una de otro arranque, otra ejecución o sin marcador → `timerLost`), PENDING → [Ready], nada
     * → [Idle] y se borra el marcador. Si falla y hay marcador, la ejecución se reconstruye desde
     * él en [Failed] (reintentable) para conservar el bloqueo; sin marcador → [Idle]. Nunca pisa
     * una ejecución en curso ni una operación pendiente de confirmación.
     */
    fun restore(): Unit = synchronized(lock) {
        if (busy) return
        val current = session()
        if (current == null) {
            _state.value = EducationalExperimentState.Idle
            return
        }
        lastOwnerUserId = current.userId
        val stored = store.load()
        val marker = stored?.takeIf { it.ownerUserId == current.userId }
        if (stored != null && marker == null) {
            // Otra cuenta en el mismo teléfono: nada de lo retenido es de este alumno.
            store.clear()
            _state.value = EducationalExperimentState.Idle
        }
        when (val state = _state.value) {
            is EducationalExperimentState.Active,
            is EducationalExperimentState.Completing,
            is EducationalExperimentState.Cancelling -> return
            is EducationalExperimentState.Failed ->
                if (state.pendingCompletion != null || state.pendingCancel != null) return
            else -> Unit
        }
        val pending = marker?.pendingCompletion
        if (marker != null && pending != null) {
            _state.value = EducationalExperimentState.Failed(
                run = marker.toRun().copy(status = StatusActive),
                message = EducationalMessages.ExperimentCompletionPending,
                retryable = true,
                pendingCompletion = PendingCompletion(pending.text, pending.durationMs, pending.completionKey),
            )
            return
        }
        val known = marker?.toRun()
        _state.value = EducationalExperimentState.Restoring(known)
        val token = current.token
        job = scope.launch {
            api.active(token)
                .onSuccess { run ->
                    when (run?.status) {
                        StatusActive -> {
                            val sameRun = marker?.runId == run.id
                            val firstKey = marker?.firstKeyAtMs
                                ?.takeIf { sameRun && marker.bootId != UnknownBootId && marker.bootId == bootId() }
                            // Misma ejecución sin primera pulsación: todavía no se midió nada.
                            val nothingMeasured = sameRun && marker?.firstKeyAtMs == null
                            transition(EducationalExperimentState.Active(run, firstKey, timerLost = firstKey == null && !nothingMeasured)) {
                                saveMarker(run, firstKey)
                            }
                        }
                        StatusPending -> transition(EducationalExperimentState.Ready(run)) { saveMarker(run, null) }
                        else -> transition(EducationalExperimentState.Idle) { store.clear() }
                    }
                }
                .onFailure { error ->
                    transition(
                        if (marker == null) {
                            EducationalExperimentState.Idle
                        } else {
                            failed(marker.toRun(), error, ExperimentOp.RESTORE).copy(retryable = true)
                        },
                    )
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
     * El payload se persiste en el marcador antes de lanzar la petición.
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
        saveCompletingMarker(run, pending)
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(
                run, EducationalMessages.SessionExpired, retryable = true, pendingCompletion = pending,
            )
            return true
        }
        _state.value = EducationalExperimentState.Completing(run, pending.text, pending.durationMs, pending.completionKey)
        job = scope.launch {
            api.complete(token, run.id, pending.text, pending.durationMs, pending.completionKey, appVersion)
                .onSuccess { completed -> transition(EducationalExperimentState.Completed(completed)) { store.clear() } }
                .onFailure { error ->
                    val next = completionFailed(run, error, pending)
                    transition(next) {
                        val kept = next.pendingCompletion
                        when {
                            next.run?.status == StatusClosed -> store.clear()
                            kept != null && kept.completionKey != pending.completionKey -> saveCompletingMarker(run, kept)
                        }
                    }
                }
        }
        return true
    }

    /**
     * "Reintentar" desde [Failed] (solo si `retryable`): reenvía la cancelación pendiente con el
     * mismo motivo, si no la finalización pendiente tal cual, si no reintenta el inicio de una
     * ejecución PENDING, o vuelve a restaurar.
     */
    fun retry() {
        val current = _state.value as? EducationalExperimentState.Failed ?: return
        if (!current.retryable) return
        when {
            current.pendingCancel != null -> cancel(current.pendingCancel)
            current.pendingCompletion != null -> complete(current.pendingCompletion.text)
            current.run?.status == StatusPending -> start()
            else -> restore()
        }
    }

    /**
     * Abandona la ejecución. Solo pasa a [Cancelled] (y borra el marcador) cuando el backend lo
     * confirma (204) o responde que la ejecución ya estaba cerrada (400/404). Sin red, con 401 o
     * 5xx —o sin sesión— la ejecución, el marcador y el bloqueo se conservan en [Failed] con el
     * motivo pendiente para "Reintentar".
     */
    fun cancel(reason: CancelReason): Unit = synchronized(lock) {
        if (busy) return
        val (run, pendingCompletion) = when (val current = _state.value) {
            is EducationalExperimentState.Ready -> current.run to null
            is EducationalExperimentState.Active -> current.run to null
            is EducationalExperimentState.Failed -> (current.run ?: return) to current.pendingCompletion
            else -> return
        }
        val token = session()?.token
        if (token == null) {
            _state.value = EducationalExperimentState.Failed(
                run, EducationalMessages.SessionExpired, retryable = true,
                pendingCompletion = pendingCompletion, pendingCancel = reason,
            )
            return
        }
        _state.value = EducationalExperimentState.Cancelling(run, pendingCompletion)
        job = scope.launch {
            api.cancel(token, run.id, reason)
                .onSuccess { transition(EducationalExperimentState.Cancelled) { store.clear() } }
                .onFailure { error ->
                    if (error is EducationalHttpException && error.status in TerminalCancelStatuses) {
                        transition(EducationalExperimentState.Cancelled) { store.clear() }
                    } else {
                        transition(failed(run, error, ExperimentOp.CANCEL, pendingCompletion, pendingCancel = reason))
                    }
                }
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

    private fun ownerUserId(): String {
        session()?.userId?.let { lastOwnerUserId = it }
        return lastOwnerUserId
    }

    private fun saveMarker(run: ExperimentRunResponse, firstKeyAtMs: Long?) {
        store.save(ExperimentMarker(run.id, run.condition, run.status, firstKeyAtMs, bootId(), ownerUserId()))
    }

    /** Payload de finalización en disco ANTES de enviarlo: tras morir el proceso se reenvía idéntico. */
    private fun saveCompletingMarker(run: ExperimentRunResponse, pending: PendingCompletion) {
        store.save(
            ExperimentMarker(
                runId = run.id,
                condition = run.condition,
                status = StatusCompleting,
                firstKeyAtMs = null,
                bootId = bootId(),
                ownerUserId = ownerUserId(),
                pendingCompletion = PendingCompletionMarker(pending.text, pending.durationMs, pending.completionKey),
            ),
        )
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
        op: ExperimentOp,
        pending: PendingCompletion? = null,
        pendingCancel: CancelReason? = null,
    ): EducationalExperimentState.Failed {
        if (error is EducationalHttpException && error.status == 401) onSessionRejected()
        return EducationalExperimentState.Failed(
            run = run,
            message = EducationalMessages.experiment(error, op),
            retryable = error.isRetryable(),
            pendingCompletion = pending,
            pendingCancel = pendingCancel,
        )
    }

    /**
     * Un 4xx terminal (400/404) significa que la ejecución ya no acepta finalización: se conserva
     * como `CLOSED` para que los gates se abran. Un 409 es una colisión de `completion_key` (otra
     * ejecución la usó), no una ejecución cerrada: se conserva todo y solo se renueva la clave.
     * Un 401 la deja ACTIVE (problema de sesión).
     */
    private fun completionFailed(
        run: ExperimentRunResponse,
        error: Throwable,
        pending: PendingCompletion,
    ): EducationalExperimentState.Failed = when {
        error is EducationalHttpException && error.status in TerminalCompletionStatuses ->
            failed(run.copy(status = StatusClosed), error, ExperimentOp.COMPLETE, pending)
        error is EducationalHttpException && error.status == 409 ->
            EducationalExperimentState.Failed(
                run = run,
                message = EducationalMessages.ExperimentCompletionConflict,
                retryable = true,
                pendingCompletion = pending.copy(completionKey = newCompletionKey()),
            )
        else -> failed(run, error, ExperimentOp.COMPLETE, pending)
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
        /** Estado local del marcador mientras una finalización espera confirmación. */
        private const val StatusCompleting = "COMPLETING"
        private val TerminalCompletionStatuses = setOf(400, 404)
        private val TerminalCancelStatuses = setOf(400, 404)
    }
}
