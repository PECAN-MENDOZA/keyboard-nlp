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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class EducationalMessagesTest : FunSpec({
    val networkErrors = listOf(
        ConnectException("x"), NoRouteToHostException("x"),
        SocketTimeoutException("x"), UnknownHostException("x"),
    )

    context("login") {
        test("401 and 403 mean wrong credentials, never an expired session") {
            EducationalMessages.login(EducationalHttpException(401, "")) shouldBe
                "Alias o PIN incorrectos. Revisa los datos que te dio tu docente."
            EducationalMessages.login(EducationalHttpException(403, "")) shouldBe
                "Alias o PIN incorrectos. Revisa los datos que te dio tu docente."
        }
        test("5xx is a server problem") {
            EducationalMessages.login(EducationalHttpException(503, "")) shouldBe
                "El servidor tuvo un problema. Inténtalo en unos minutos."
        }
        test("any network failure asks to check the connection") {
            networkErrors.forEach { error ->
                EducationalMessages.login(error) shouldBe
                    "No se pudo conectar con el servidor. Revisa la conexión e inténtalo de nuevo."
            }
        }
        test("a non-student account keeps the repository message") {
            EducationalMessages.login(IllegalArgumentException("La cuenta no pertenece a un estudiante.")) shouldBe
                "La cuenta no pertenece a un estudiante."
        }
        test("unknown http codes include the code") {
            EducationalMessages.login(EducationalHttpException(418, "")) shouldBe
                "No se pudo iniciar sesión (código 418)."
        }
    }

    context("correction") {
        test("400 asks for another fragment") {
            EducationalMessages.correction(EducationalHttpException(400, "")) shouldBe
                "No pudimos corregir ese texto. Intenta con otro fragmento."
        }
        test("401 is an expired session") {
            EducationalMessages.correction(EducationalHttpException(401, "")) shouldBe
                EducationalMessages.SessionExpired
        }
        test("404 asks to select again") {
            EducationalMessages.correction(EducationalHttpException(404, "")) shouldBe
                "Esa corrección ya no está disponible. Sombrea y toca IA otra vez."
        }
        test("502 and network failures are the same short message and retryable") {
            (networkErrors + EducationalHttpException(502, "")).forEach { error ->
                EducationalMessages.correction(error) shouldBe "Sin conexión con la IA."
                EducationalMessages.isRetryable(error) shouldBe true
            }
        }
        test("400 is not retryable") {
            EducationalMessages.isRetryable(EducationalHttpException(400, "")) shouldBe false
        }
    }

    test("no visible text mentions infrastructure") {
        val all = listOf(
            EducationalMessages.EmptyCredentials, EducationalMessages.NoSession,
            EducationalMessages.SessionExpired, EducationalMessages.AiUnavailable,
            EducationalMessages.SelectFirst, EducationalMessages.NotAllowedHere,
            EducationalMessages.AppNotSupported, EducationalMessages.TextChanged,
            EducationalMessages.AlreadyCorrect, EducationalMessages.Processing,
            EducationalMessages.ProcessingSlow, EducationalMessages.tooLong(5000),
        ) + networkErrors.map { EducationalMessages.login(it) } +
            networkErrors.map { EducationalMessages.correction(it) }
        all.forEach { text ->
            text shouldNotContainIgnoringCase "cloud run"
            text shouldNotContainIgnoringCase "backend"
            text shouldNotContainIgnoringCase "desplegado"
        }
    }
})
