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

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EducationalApiRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // El backend espera `firstKeyOffsetMs` explícito como `null` (no ausente) cuando el
        // alumno nunca escribió; kotlinx.serialization ya lo hace por defecto, se deja explícito.
        explicitNulls = true
    }

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<EducationalSession> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/auth/students/login",
                method = "POST",
                token = null,
                body = json.encodeToString(StudentLoginRequest(username, password)),
            )
            val login = json.decodeFromString<StudentLoginResponse>(response)
            require(login.role == "STUDENT") { "La cuenta no pertenece a un estudiante." }
            EducationalSession(
                userId = login.userId,
                token = login.token,
                expiresAt = login.expiresAt,
                username = username,
            )
        }
    }

    suspend fun processCorrection(
        baseUrl: String,
        token: String,
        text: String,
        testResponseId: String? = null,
    ): Result<CorrectionSessionResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/corrections/process",
                method = "POST",
                token = token,
                body = json.encodeToString(ProcessCorrectionRequest(text, testResponseId)),
            )
            json.decodeFromString<CorrectionSessionResponse>(response)
        }
    }

    /** `GET /tests/assigned`: pruebas de oraciones asignadas al alumno. */
    suspend fun assignedTests(
        baseUrl: String,
        token: String,
    ): Result<List<AssignedTest>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/tests/assigned",
                method = "GET",
                token = token,
                body = null,
            )
            json.decodeFromString<List<AssignedTest>>(response)
        }
    }

    /** `POST /tests/{testId}/attempts`: crea (o retoma) el intento del alumno para esa prueba. */
    suspend fun startAttempt(
        baseUrl: String,
        token: String,
        testId: String,
        appVersion: String,
    ): Result<AttemptResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/tests/$testId/attempts",
                method = "POST",
                token = token,
                body = json.encodeToString(StartAttemptRequest(appVersion)),
            )
            json.decodeFromString<AttemptResponse>(response)
        }
    }

    /** `POST /attempts/{id}/responses/{position}/start`: marca el inicio de la oración (sin cuerpo). */
    suspend fun startSentence(
        baseUrl: String,
        token: String,
        attemptId: String,
        position: Int,
    ): Result<StartSentenceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/attempts/$attemptId/responses/$position/start",
                method = "POST",
                token = token,
                body = null,
            )
            json.decodeFromString<StartSentenceResponse>(response)
        }
    }

    /** `PUT /attempts/{id}/responses/{position}`: cierra la oración con sus métricas. */
    suspend fun finishSentence(
        baseUrl: String,
        token: String,
        attemptId: String,
        position: Int,
        body: FinishSentenceRequest,
    ): Result<FinishSentenceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/attempts/$attemptId/responses/$position",
                method = "PUT",
                token = token,
                body = json.encodeToString(body),
            )
            json.decodeFromString<FinishSentenceResponse>(response)
        }
    }

    /** `POST /attempts/{id}/cancel`: cancela el intento en curso; el backend responde sin cuerpo. */
    suspend fun cancelAttempt(
        baseUrl: String,
        token: String,
        attemptId: String,
        reason: AttemptCancelReason,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            request(
                baseUrl = baseUrl,
                path = "/attempts/$attemptId/cancel",
                method = "POST",
                token = token,
                body = json.encodeToString(CancelAttemptRequest(reason)),
            )
            Unit
        }
    }

    suspend fun redeemExperimentCode(
        baseUrl: String,
        token: String,
        code: String,
    ): Result<ExperimentRunResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/experiments/access-code/redeem",
                method = "POST",
                token = token,
                body = json.encodeToString(RedeemAccessCodeRequest(code)),
            )
            json.decodeFromString<ExperimentRunResponse>(response)
        }
    }

    suspend fun startExperiment(
        baseUrl: String,
        token: String,
        runId: String,
    ): Result<ExperimentRunResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/experiments/runs/$runId/start",
                method = "POST",
                token = token,
                body = null,
            )
            json.decodeFromString<ExperimentRunResponse>(response)
        }
    }

    /**
     * Restaura una ejecución vigente (ACTIVE o PENDING) tras reabrir la app. Solo el 404 del
     * contrato ("No experiment run to restore") significa que no hay nada: `null`. Cualquier otro
     * 404 (URL mal enrutada, portal cautivo…) es un fallo, para que el marcador conserve el bloqueo.
     */
    suspend fun activeExperiment(
        baseUrl: String,
        token: String,
    ): Result<ExperimentRunResponse?> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                val response = request(
                    baseUrl = baseUrl,
                    path = "/experiments/runs/active",
                    method = "GET",
                    token = token,
                    body = null,
                )
                json.decodeFromString<ExperimentRunResponse>(response)
            } catch (error: EducationalHttpException) {
                if (isNoRunToRestore(error)) null else throw error
            }
        }
    }

    suspend fun completeExperiment(
        baseUrl: String,
        token: String,
        runId: String,
        finalText: String,
        durationMs: Long,
        completionKey: String,
        appVersion: String,
    ): Result<ExperimentRunResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/experiments/runs/$runId/complete",
                method = "PATCH",
                token = token,
                body = json.encodeToString(
                    CompleteExperimentRequest(finalText, durationMs, completionKey, appVersion),
                ),
            )
            json.decodeFromString<ExperimentRunResponse>(response)
        }
    }

    suspend fun cancelExperiment(
        baseUrl: String,
        token: String,
        runId: String,
        reason: CancelReason,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            request(
                baseUrl = baseUrl,
                path = "/experiments/runs/$runId/cancel",
                method = "POST",
                token = token,
                body = json.encodeToString(CancelExperimentRequest(reason)),
            )
            Unit
        }
    }

    suspend fun sendFeedback(
        baseUrl: String,
        token: String,
        sessionId: String,
        selectedSuggestion: String?,
        accepted: Boolean,
        finalText: String? = null,
        reason: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            request(
                baseUrl = baseUrl,
                path = "/corrections/sessions/$sessionId/feedback",
                method = "PATCH",
                token = token,
                body = json.encodeToString(
                    CorrectionFeedbackRequest(selectedSuggestion, accepted, finalText, reason),
                ),
            )
            Unit
        }
    }

    suspend fun checkHealth(baseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                request(
                    baseUrl = baseUrl.healthBaseUrl(),
                    path = "/actuator/health",
                    method = "GET",
                    token = null,
                    body = null,
                )
            } catch (error: EducationalHttpException) {
                if (error.status !in ReachableUnauthorizedStatuses) {
                    throw error
                }
            }
            Unit
        }
    }

    private fun request(
        baseUrl: String,
        path: String,
        method: String,
        token: String?,
        body: String?,
    ): String {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val connection = URL("$normalizedBase$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = ConnectTimeoutMs
            connection.readTimeout = ReadTimeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { stream ->
                    stream.write(body.encodeToByteArray())
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                throw EducationalHttpException(status, response)
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun String.healthBaseUrl(): String {
        val normalized = trim().trimEnd('/')
        return if (normalized.endsWith(ApiVersionPath)) {
            normalized.removeSuffix(ApiVersionPath)
        } else {
            normalized
        }
    }

    companion object {
        // El servicio de IA tiene un cold start de ~70 s al cargar el modelo BETO tras inactividad
        // (ver docs/pendientes-ia.md). El backend espera hasta 90 s a la IA, asi que el read timeout
        // del cliente debe superar los ~70 s; con la IA caliente la respuesta llega en ~1 s.
        // Si fuera menor, la primera correccion tras inactividad fallaria con SocketTimeout.
        private const val ConnectTimeoutMs = 15_000
        private const val ReadTimeoutMs = 90_000
        private const val ApiVersionPath = "/api/v1"
        private val ReachableUnauthorizedStatuses = setOf(401, 403)
    }
}

class EducationalHttpException(
    val status: Int,
    val body: String,
) : Exception("HTTP $status: ${body.take(160)}")

/** Cuerpo de error del backend: `{"message":"…"}` (otros campos se ignoran). */
@Serializable
private data class ApiErrorBody(val message: String? = null)

private val errorBodyJson = Json { ignoreUnknownKeys = true }

/** `GET /runs/active` → 404 con el mensaje exacto del contrato (§3.3): no hay ejecución que restaurar. */
internal fun isNoRunToRestore(error: EducationalHttpException): Boolean {
    if (error.status != 404) return false
    val message = runCatching { errorBodyJson.decodeFromString<ApiErrorBody>(error.body).message }.getOrNull()
    return message == NoRunToRestoreMessage
}

private const val NoRunToRestoreMessage = "No experiment run to restore"
