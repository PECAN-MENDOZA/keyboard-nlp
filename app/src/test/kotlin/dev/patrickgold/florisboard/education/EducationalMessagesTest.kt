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
        test("403 is a permission problem and unknown codes include the code") {
            EducationalMessages.correction(EducationalHttpException(403, "")) shouldBe
                "No tienes permiso para usar la corrección."
            EducationalMessages.correction(EducationalHttpException(418, "")) shouldBe
                "No se pudo corregir (código 418)."
        }
    }

    context("sentence tests") {
        test("401 is an expired session") {
            EducationalMessages.sentenceTest(EducationalHttpException(401, "")) shouldBe EducationalMessages.SessionExpired
        }
        test("404 means the test is gone") {
            EducationalMessages.sentenceTest(EducationalHttpException(404, "")) shouldBe
                "Esa prueba ya no está disponible."
        }
        test("409 is a test state conflict the student cannot fix alone") {
            EducationalMessages.sentenceTest(EducationalHttpException(409, """{"message":"Another test is in progress"}""")) shouldBe
                EducationalMessages.TestConflict
            EducationalMessages.TestConflict shouldBe "No se pudo continuar con la prueba. Avisa a tu profesor."
        }
        test("5xx is a server problem") {
            EducationalMessages.sentenceTest(EducationalHttpException(503, "")) shouldBe
                "El servidor tuvo un problema. Inténtalo en unos minutos."
        }
        test("any network failure asks to check the connection") {
            networkErrors.forEach { error ->
                EducationalMessages.sentenceTest(error) shouldBe
                    "No se pudo conectar con el servidor. Revisa la conexión e inténtalo de nuevo."
            }
            EducationalMessages.sentenceTest(IllegalStateException("x")) shouldBe "No se pudo continuar con la prueba."
        }
        test("unknown http codes include the code") {
            EducationalMessages.sentenceTest(EducationalHttpException(418, "")) shouldBe
                "No se pudo continuar con la prueba (código 418)."
        }
    }

    context("sentence test screens") {
        test("every screen text is non-empty") {
            listOf(
                EducationalMessages.TestsTitle, EducationalMessages.TestsEmpty, EducationalMessages.TestsLoading,
                EducationalMessages.TestsRefresh, EducationalMessages.TestStatusPending,
                EducationalMessages.TestStatusInProgress, EducationalMessages.TestStatusCompleted,
                EducationalMessages.TestStart, EducationalMessages.TestStarting, EducationalMessages.TestInProgress,
                EducationalMessages.WithHelp, EducationalMessages.WithoutHelp, EducationalMessages.SentenceStart,
                EducationalMessages.SentenceFinish, EducationalMessages.SentenceCorrecting,
                EducationalMessages.SentenceEmptyQuestion, EducationalMessages.Yes, EducationalMessages.No,
                EducationalMessages.Saving, EducationalMessages.SentenceSaveFailed, EducationalMessages.SentenceRetry,
                EducationalMessages.CancelTechnical, EducationalMessages.CancelTest, EducationalMessages.CancelTestTitle,
                EducationalMessages.CancelTestIntro, EducationalMessages.KeepGoing, EducationalMessages.Cancelling,
                EducationalMessages.TestCompleted, EducationalMessages.TestCancelled, EducationalMessages.ClockLost,
                EducationalMessages.BackHome, EducationalMessages.Back, EducationalMessages.LogoutDuringTest,
                EducationalMessages.CorrectionDisabledInSentence,
            ).forEach { text -> text.isNotBlank() shouldBe true }
        }
        test("the fixed texts of the brief") {
            EducationalMessages.TestsTitle shouldBe "Pruebas"
            EducationalMessages.TestsEmpty shouldBe "No tienes pruebas pendientes."
            EducationalMessages.TestStart shouldBe "Comenzar prueba"
            EducationalMessages.WithHelp shouldBe "Con ayuda"
            EducationalMessages.WithoutHelp shouldBe "Sin ayuda"
            EducationalMessages.SentenceStart shouldBe "Comenzar"
            EducationalMessages.SentenceFinish shouldBe "Terminar"
            EducationalMessages.SentenceEmptyQuestion shouldBe "¿Dejar esta oración en blanco?"
            EducationalMessages.TestCompleted shouldBe "¡Prueba completada! Gracias."
            EducationalMessages.SentenceSaveFailed shouldBe "No pudimos guardar"
            EducationalMessages.SentenceRetry shouldBe "Reintentar"
            EducationalMessages.CancelTechnical shouldBe "Cancelar (problema técnico)"
            EducationalMessages.CorrectionDisabledInSentence shouldBe "La corrección está desactivada en esta oración"
            EducationalMessages.ClockLost shouldBe
                "El teléfono se reinició durante la oración. Solo puedes cancelar la prueba."
        }
        test("SentenceProgress formats position and total") {
            EducationalMessages.sentenceProgress(3, 20) shouldBe "Oración 3 de 20"
            EducationalMessages.sentenceProgress(1, 3) shouldBe "Oración 1 de 3"
            EducationalMessages.sentenceCount(1) shouldBe "1 oración"
            EducationalMessages.sentenceCount(3) shouldBe "3 oraciones"
        }
        test("TestStartQuestion names the test") {
            EducationalMessages.testStartQuestion("Dictado 1") shouldBe
                "¿Comenzar la prueba Dictado 1? Tu profesor te dirá qué escribir."
        }
        test("the home button shows the pending count only when there is one") {
            EducationalMessages.testsWithPending(0) shouldBe "Pruebas"
            EducationalMessages.testsWithPending(1) shouldBe "Pruebas · 1 pendiente"
            EducationalMessages.testsWithPending(4) shouldBe "Pruebas · 4 pendientes"
            EducationalMessages.testInProgress(2, 3) shouldBe "Prueba en curso · oración 2 de 3"
        }
        test("assistance and status labels") {
            EducationalMessages.assistanceLabel(SentenceAssistance.ASSISTED) shouldBe "Con ayuda"
            EducationalMessages.assistanceLabel(SentenceAssistance.UNASSISTED) shouldBe "Sin ayuda"
            EducationalMessages.testStatusLabel("PENDING") shouldBe "Pendiente"
            EducationalMessages.testStatusLabel("IN_PROGRESS") shouldBe "En curso"
            EducationalMessages.testStatusLabel("COMPLETED") shouldBe "Completada"
        }
    }

    test("recommended label is pluralized") {
        EducationalMessages.recommendedLabel(1) shouldBe "Recomendada · 1 cambio"
        EducationalMessages.recommendedLabel(3) shouldBe "Recomendada · 3 cambios"
    }

    test("no visible text mentions infrastructure") {
        val all = listOf(
            EducationalMessages.EmptyCredentials, EducationalMessages.NoSession,
            EducationalMessages.SessionExpired, EducationalMessages.AiUnavailable,
            EducationalMessages.SelectFirst, EducationalMessages.NotAllowedHere,
            EducationalMessages.AppNotSupported, EducationalMessages.TextChanged,
            EducationalMessages.AlreadyCorrect, EducationalMessages.Processing,
            EducationalMessages.ProcessingSlow, EducationalMessages.tooLong(5000),
            EducationalMessages.Corrected, EducationalMessages.Editing,
            EducationalMessages.recommendedLabel(1), EducationalMessages.recommendedLabel(3),
            EducationalMessages.AppTitle, EducationalMessages.LoginIntro,
            EducationalMessages.AliasLabel, EducationalMessages.PinLabel,
            EducationalMessages.ShowPin, EducationalMessages.HidePin,
            EducationalMessages.LoginButton, EducationalMessages.LoggingIn,
            EducationalMessages.KeyboardSettings, EducationalMessages.Logout,
            EducationalMessages.SessionActive, EducationalMessages.greeting("ana"),
            EducationalMessages.Connected, EducationalMessages.ConnectedDetail,
            EducationalMessages.Checking, EducationalMessages.CheckingDetail,
            EducationalMessages.Unavailable, EducationalMessages.UnavailableDetail,
            EducationalMessages.Unchecked, EducationalMessages.UncheckedDetail,
            EducationalMessages.HowToTitle, EducationalMessages.HowTo1,
            EducationalMessages.HowTo2, EducationalMessages.HowTo3,
            EducationalMessages.OtherOption, EducationalMessages.EditChip,
            EducationalMessages.IgnoreChip, EducationalMessages.Retry,
            EducationalMessages.Undo, EducationalMessages.Done,
            EducationalMessages.Close, EducationalMessages.CloseDescription,
            EducationalMessages.AvatarDescription,
            EducationalMessages.CorrectionDisabledInSentence, EducationalMessages.TestConflict,
            EducationalMessages.TestsTitle, EducationalMessages.TestsEmpty,
            EducationalMessages.TestsLoading, EducationalMessages.TestsRefresh,
            EducationalMessages.TestStatusPending, EducationalMessages.TestStatusInProgress,
            EducationalMessages.TestStatusCompleted, EducationalMessages.testStartQuestion("Dictado 1"),
            EducationalMessages.TestStart, EducationalMessages.TestStarting,
            EducationalMessages.TestInProgress, EducationalMessages.sentenceProgress(1, 3),
            EducationalMessages.WithHelp, EducationalMessages.WithoutHelp,
            EducationalMessages.SentenceStart, EducationalMessages.SentenceFinish,
            EducationalMessages.SentenceCorrecting, EducationalMessages.SentenceEmptyQuestion,
            EducationalMessages.Yes, EducationalMessages.No,
            EducationalMessages.Saving, EducationalMessages.SentenceSaveFailed,
            EducationalMessages.SentenceRetry, EducationalMessages.CancelTechnical,
            EducationalMessages.CancelTest, EducationalMessages.CancelTestTitle,
            EducationalMessages.CancelTestIntro, EducationalMessages.CancelReasonAbandoned,
            EducationalMessages.CancelReasonTechnical, EducationalMessages.CancelReasonInterrupted,
            EducationalMessages.KeepGoing, EducationalMessages.Cancelling,
            EducationalMessages.TestCompleted, EducationalMessages.TestCancelled,
            EducationalMessages.ClockLost, EducationalMessages.BackHome, EducationalMessages.Back,
            EducationalMessages.LogoutDuringTest, EducationalMessages.testsWithPending(0),
            EducationalMessages.testsWithPending(1), EducationalMessages.testsWithPending(2),
            EducationalMessages.testInProgress(2, 3), EducationalMessages.testStatusLabel("PENDING"),
        ) + SentenceAssistance.entries.map { EducationalMessages.assistanceLabel(it) } +
            AttemptCancelReason.entries.map { EducationalMessages.cancelReasonLabel(it) } +
            networkErrors.map { EducationalMessages.login(it) } +
            networkErrors.map { EducationalMessages.correction(it) } +
            networkErrors.map { EducationalMessages.sentenceTest(it) } +
            listOf(400, 401, 403, 404, 409, 418, 502, 503).flatMap { code ->
                listOf(
                    EducationalMessages.login(EducationalHttpException(code, "")),
                    EducationalMessages.correction(EducationalHttpException(code, "")),
                    EducationalMessages.sentenceTest(EducationalHttpException(code, "")),
                )
            }
        all.forEach { text ->
            text shouldNotContainIgnoringCase "cloud run"
            text shouldNotContainIgnoringCase "backend"
            text shouldNotContainIgnoringCase "desplegado"
        }
    }
})
