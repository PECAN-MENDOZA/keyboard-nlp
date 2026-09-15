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

    context("experiment") {
        test("400 is an invalid access code only when redeeming") {
            EducationalMessages.experiment(EducationalHttpException(400, ""), ExperimentOp.REDEEM) shouldBe
                EducationalMessages.InvalidAccessCode
        }
        test("400 on start, complete or cancel means the run is no longer active") {
            listOf(ExperimentOp.START, ExperimentOp.COMPLETE, ExperimentOp.CANCEL).forEach { op ->
                EducationalMessages.experiment(EducationalHttpException(400, ""), op) shouldBe
                    EducationalMessages.ExperimentNotActive
            }
            EducationalMessages.ExperimentNotActive shouldBe "Esta prueba ya no está activa. Avisa al investigador."
            EducationalMessages.experiment(EducationalHttpException(400, ""), ExperimentOp.RESTORE) shouldBe
                "No se pudo continuar con la prueba (código 400)."
        }
        test("401 is an expired session") {
            ExperimentOp.entries.forEach { op ->
                EducationalMessages.experiment(EducationalHttpException(401, ""), op) shouldBe
                    EducationalMessages.SessionExpired
            }
        }
        test("404 means the run is gone") {
            EducationalMessages.experiment(EducationalHttpException(404, ""), ExperimentOp.COMPLETE) shouldBe
                "Esa prueba ya no está disponible."
        }
        test("409 on complete is a conflict the student can retry") {
            EducationalMessages.experiment(EducationalHttpException(409, ""), ExperimentOp.COMPLETE) shouldBe
                EducationalMessages.ExperimentCompletionConflict
            EducationalMessages.ExperimentCompletionConflict shouldBe "No pudimos guardar tu texto. Toca Reintentar."
        }
        test("the pending completion notice asks to retry") {
            EducationalMessages.ExperimentCompletionPending shouldBe
                "No pudimos confirmar que tu texto se guardó. Toca Reintentar."
        }
        test("any network failure asks to check the connection") {
            ExperimentOp.entries.forEach { op ->
                networkErrors.forEach { error ->
                    EducationalMessages.experiment(error, op) shouldBe
                        "No se pudo conectar con el servidor. Revisa la conexión e inténtalo de nuevo."
                }
            }
        }
        test("unknown http codes include the code") {
            EducationalMessages.experiment(EducationalHttpException(418, ""), ExperimentOp.START) shouldBe
                "No se pudo continuar con la prueba (código 418)."
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
            EducationalMessages.InvalidAccessCode, EducationalMessages.TimerLost,
            EducationalMessages.CorrectionDisabledInTask,
            EducationalMessages.ExperimentTitle, EducationalMessages.ExperimentNoSession,
            EducationalMessages.ExperimentBackHome, EducationalMessages.ExperimentCodeIntro,
            EducationalMessages.ExperimentCodeLabel, EducationalMessages.ExperimentValidateCode,
            EducationalMessages.ExperimentValidating, EducationalMessages.ExperimentReadyTitle,
            EducationalMessages.ExperimentReadyIntro, EducationalMessages.ExperimentParticipantLabel,
            EducationalMessages.ExperimentPromptLabel, EducationalMessages.ExperimentConditionLabel,
            EducationalMessages.ConditionAssisted, EducationalMessages.ConditionUnassisted,
            EducationalMessages.ExperimentStart, EducationalMessages.ExperimentStarting,
            EducationalMessages.ExperimentTextLabel, EducationalMessages.ExperimentElapsedLabel,
            EducationalMessages.ExperimentElapsedNone, EducationalMessages.ExperimentFinish,
            EducationalMessages.ExperimentSaving, EducationalMessages.ExperimentSavingDetail,
            EducationalMessages.ExperimentCancel, EducationalMessages.ExperimentCancelTitle,
            EducationalMessages.ExperimentCancelIntro, EducationalMessages.CancelReasonAbandoned,
            EducationalMessages.CancelReasonTechnical, EducationalMessages.CancelReasonInterrupted,
            EducationalMessages.ExperimentKeepGoing, EducationalMessages.ExperimentRetry,
            EducationalMessages.ExperimentCloseAction, EducationalMessages.ExperimentBack,
            EducationalMessages.ExperimentCompleted, EducationalMessages.ExperimentCompletedDetail,
            EducationalMessages.ExperimentCancelled, EducationalMessages.ExperimentCancelledDetail,
            EducationalMessages.ExperimentPrivacy, EducationalMessages.ExperimentCompletionRejected,
            EducationalMessages.ExperimentRestoring, EducationalMessages.ExperimentCancelling,
            EducationalMessages.ExperimentNotActive, EducationalMessages.ExperimentCompletionPending,
            EducationalMessages.ExperimentCompletionConflict, EducationalMessages.LogoutDuringExperiment,
            EducationalMessages.textCounter(12, 10_000), EducationalMessages.elapsedDescription("1:05"),
        ) + ExperimentCondition.entries.map { EducationalMessages.conditionLabel(it) } +
            CancelReason.entries.map { EducationalMessages.cancelReasonLabel(it) } +
            networkErrors.map { EducationalMessages.login(it) } +
            networkErrors.map { EducationalMessages.correction(it) } +
            ExperimentOp.entries.flatMap { op -> networkErrors.map { EducationalMessages.experiment(it, op) } } +
            listOf(400, 401, 403, 404, 409, 418, 502, 503).flatMap { code ->
                listOf(
                    EducationalMessages.login(EducationalHttpException(code, "")),
                    EducationalMessages.correction(EducationalHttpException(code, "")),
                ) + ExperimentOp.entries.map { EducationalMessages.experiment(EducationalHttpException(code, ""), it) }
            }
        all.forEach { text ->
            text shouldNotContainIgnoringCase "cloud run"
            text shouldNotContainIgnoringCase "backend"
            text shouldNotContainIgnoringCase "desplegado"
        }
    }
})
