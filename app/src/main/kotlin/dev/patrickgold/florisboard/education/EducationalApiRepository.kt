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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EducationalApiRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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
            )
        }
    }

    suspend fun processCorrection(
        baseUrl: String,
        token: String,
        text: String,
    ): Result<CorrectionSessionResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                baseUrl = baseUrl,
                path = "/corrections/process",
                method = "POST",
                token = token,
                body = json.encodeToString(ProcessCorrectionRequest(text)),
            )
            json.decodeFromString<CorrectionSessionResponse>(response)
        }
    }

    suspend fun sendFeedback(
        baseUrl: String,
        token: String,
        sessionId: String,
        selectedSuggestion: String?,
        accepted: Boolean,
        finalText: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            request(
                baseUrl = baseUrl,
                path = "/corrections/sessions/$sessionId/feedback",
                method = "PATCH",
                token = token,
                body = json.encodeToString(
                    CorrectionFeedbackRequest(selectedSuggestion, accepted, finalText),
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
    response: String,
) : Exception("HTTP $status: ${response.take(160)}")
