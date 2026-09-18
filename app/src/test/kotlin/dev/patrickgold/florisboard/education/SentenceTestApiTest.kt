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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

/**
 * Servidor HTTP real en loopback que hace de backend falso: evita reimplementar
 * `HttpURLConnection`, que es lo que usa de verdad [EducationalApiRepository].
 */
private class FakeBackend {
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    var method: String? = null
        private set
    var path: String? = null
        private set
    var authorization: String? = null
        private set
    var requestBody: String = ""
        private set

    var responseStatus = 200
    var responseBody = ""

    val baseUrl: String get() = "http://localhost:${server.address.port}"

    init {
        server.createContext("/") { exchange: HttpExchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.rawPath
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val bytes = responseBody.encodeToByteArray()
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(responseStatus, -1)
            } else {
                exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
    }

    fun stop() = server.stop(0)
}

private fun withFakeBackend(block: suspend (FakeBackend) -> Unit) {
    val backend = FakeBackend()
    try {
        kotlinx.coroutines.runBlocking { block(backend) }
    } finally {
        backend.stop()
    }
}

class SentenceTestApiTest : FunSpec({
    val repository = EducationalApiRepository()

    test("assignedTests hits GET /tests/assigned with the bearer token") {
        withFakeBackend { backend ->
            backend.responseBody = """[{"testId":"t1","code":"ABC123","title":"Prueba 1","sentenceCount":3,"status":"PENDING"}]"""

            val result = repository.assignedTests(backend.baseUrl, "tok-1")

            backend.method shouldBe "GET"
            backend.path shouldBe "/tests/assigned"
            backend.authorization shouldBe "Bearer tok-1"
            result.getOrThrow() shouldBe listOf(AssignedTest("t1", "ABC123", "Prueba 1", 3, "PENDING"))
        }
    }

    test("startAttempt posts the app version to /tests/{testId}/attempts") {
        withFakeBackend { backend ->
            backend.responseBody = """
                {"attemptId":"attempt-1","testId":"t1","code":"ABC123","title":"Prueba 1",
                 "nextPosition":1,"status":"ACTIVE","sentences":[{"position":1,"assistance":"ASSISTED"}]}
            """.trimIndent()

            val result = repository.startAttempt(backend.baseUrl, "tok-1", "t1", "1.2.3")

            backend.method shouldBe "POST"
            backend.path shouldBe "/tests/t1/attempts"
            backend.requestBody shouldBe """{"appVersion":"1.2.3"}"""
            result.getOrThrow().attemptId shouldBe "attempt-1"
        }
    }

    test("startSentence posts to /attempts/{id}/responses/{position}/start without a body") {
        withFakeBackend { backend ->
            backend.responseBody = """{"responseId":"resp-1","position":2,"assistance":"UNASSISTED","alreadyStarted":false}"""

            val result = repository.startSentence(backend.baseUrl, "tok-1", "attempt-1", 2)

            backend.method shouldBe "POST"
            backend.path shouldBe "/attempts/attempt-1/responses/2/start"
            backend.requestBody shouldBe ""
            result.getOrThrow() shouldBe StartSentenceResponse("resp-1", 2, SentenceAssistance.UNASSISTED, false)
        }
    }

    test("finishSentence puts the metrics to /attempts/{id}/responses/{position}") {
        withFakeBackend { backend ->
            backend.responseBody = """{"responseId":"resp-1","nextPosition":3,"attemptStatus":"ACTIVE"}"""
            val body = FinishSentenceRequest(
                finalText = "Texto final",
                firstKeyOffsetMs = null,
                finishedOffsetMs = 5_000,
                suggestionsOffered = 1,
                suggestionsAccepted = 1,
                suggestionsRejected = 0,
                suggestionsUndone = 0,
                skipped = false,
                completionKey = "key-1",
            )

            val result = repository.finishSentence(backend.baseUrl, "tok-1", "attempt-1", 2, body)

            backend.method shouldBe "PUT"
            backend.path shouldBe "/attempts/attempt-1/responses/2"
            backend.requestBody shouldBe
                "{\"finalText\":\"Texto final\",\"firstKeyOffsetMs\":null,\"finishedOffsetMs\":5000," +
                "\"suggestionsOffered\":1,\"suggestionsAccepted\":1,\"suggestionsRejected\":0," +
                "\"suggestionsUndone\":0,\"skipped\":false,\"completionKey\":\"key-1\"}"
            result.getOrThrow() shouldBe FinishSentenceResponse("resp-1", 3, "ACTIVE")
        }
    }

    test("cancelAttempt posts the reason to /attempts/{id}/cancel and returns Unit") {
        withFakeBackend { backend ->
            backend.responseBody = ""

            val result = repository.cancelAttempt(backend.baseUrl, "tok-1", "attempt-1", AttemptCancelReason.ABANDONED)

            backend.method shouldBe "POST"
            backend.path shouldBe "/attempts/attempt-1/cancel"
            backend.requestBody shouldBe """{"reason":"ABANDONED"}"""
            result.getOrThrow() shouldBe Unit
        }
    }

    test("processCorrection with a testResponseId sends id_respuesta") {
        withFakeBackend { backend ->
            backend.responseBody = """{"id_sesion":"s1","texto_original":"a","texto_corregido":"a"}"""

            repository.processCorrection(backend.baseUrl, "tok-1", "a", testResponseId = "resp-1")

            backend.requestBody shouldBe """{"texto_original":"a","id_respuesta":"resp-1"}"""
        }
    }

    test("processCorrection without a testResponseId sends an explicit null") {
        withFakeBackend { backend ->
            backend.responseBody = """{"id_sesion":"s1","texto_original":"a","texto_corregido":"a"}"""

            repository.processCorrection(backend.baseUrl, "tok-1", "a")

            backend.requestBody shouldBe """{"texto_original":"a","id_respuesta":null}"""
        }
    }

    test("RepositorySentenceTestApi wires assigned() through the repository") {
        withFakeBackend { backend ->
            backend.responseBody = """[{"testId":"t1","code":"ABC123","title":"Prueba 1","sentenceCount":3,"status":"PENDING"}]"""
            val api = RepositorySentenceTestApi(repository, listOf(backend.baseUrl))

            val result = api.assigned("tok-1")

            backend.path shouldBe "/tests/assigned"
            result.getOrThrow() shouldBe listOf(AssignedTest("t1", "ABC123", "Prueba 1", 3, "PENDING"))
        }
    }
})
