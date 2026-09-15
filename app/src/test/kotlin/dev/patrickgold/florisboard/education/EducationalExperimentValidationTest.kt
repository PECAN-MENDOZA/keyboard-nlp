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

/** Reglas puras de la pantalla "Participar en una prueba" (sin Android). */
class EducationalExperimentValidationTest : FunSpec({
    val run = ExperimentRunResponse(
        id = "run-1",
        participantCode = "P-042",
        condition = ExperimentCondition.ASSISTED,
        taskVariant = "A",
        promptText = "Escribe un correo.",
        status = "PENDING",
    )
    val pending = PendingCompletion(text = "hola", durationMs = 1_000, completionKey = "k")

    context("code") {
        test("code normalization rejects ambiguous characters") {
            normalizeExperimentCode(" abcd-2345 ") shouldBe "ABCD2345"
            normalizeExperimentCode("ABCDO345") shouldBe null
        }
        test("code normalization rejects wrong lengths") {
            normalizeExperimentCode("ABCD234") shouldBe null
            normalizeExperimentCode("ABCD23456") shouldBe null
            normalizeExperimentCode("") shouldBe null
        }
        test("code input is upper-cased, keeps only ascii letters and digits and caps at 8") {
            filterExperimentCodeInput("ab cd-23") shouldBe "ABCD23"
            filterExperimentCodeInput("abcd23456789") shouldBe "ABCD2345"
            filterExperimentCodeInput("ñé!á") shouldBe ""
        }
        test("the validate button needs a complete, acceptable code") {
            experimentCodeReady("ABCD2345") shouldBe true
            experimentCodeReady("ABCD234") shouldBe false
            experimentCodeReady("ABCDO345") shouldBe false
        }
        test("an inline hint appears only once the code is complete but unacceptable") {
            experimentCodeHint("ABCD234") shouldBe null
            experimentCodeHint("ABCD2345") shouldBe null
            experimentCodeHint("ABCDO345") shouldBe EducationalMessages.InvalidAccessCode
        }
    }

    context("duration") {
        test("duration rejects a reset monotonic clock") {
            experimentDurationMs(60_000, 1_000) shouldBe null
            experimentDurationMs(1_000, 61_000) shouldBe 60_000
        }
        test("duration rejects a zero interval") {
            experimentDurationMs(5_000, 5_000) shouldBe null
        }
        test("elapsed time is shown as a dash before the first key and as m:ss afterwards") {
            formatExperimentElapsed(null) shouldBe EducationalMessages.ExperimentElapsedNone
            formatExperimentElapsed(0) shouldBe "0:00"
            formatExperimentElapsed(65_000) shouldBe "1:05"
            formatExperimentElapsed(3_600_000) shouldBe "1:00:00"
            formatExperimentElapsed(-500) shouldBe "0:00"
        }
    }

    context("finish") {
        test("finish is enabled only with text, while not saving and without a blocking reason") {
            experimentFinishEnabled(text = "hola", completing = false, blockedReason = null) shouldBe true
            experimentFinishEnabled(text = "   ", completing = false, blockedReason = null) shouldBe false
            experimentFinishEnabled(text = "", completing = false, blockedReason = null) shouldBe false
            experimentFinishEnabled(text = "hola", completing = true, blockedReason = null) shouldBe false
            experimentFinishEnabled(text = "hola", completing = false, blockedReason = EducationalMessages.TimerLost) shouldBe false
        }
        test("the text is capped at the maximum length") {
            limitExperimentText("a".repeat(ExperimentMaxTextLength + 5)).length shouldBe ExperimentMaxTextLength
            limitExperimentText("hola") shouldBe "hola"
        }
    }

    context("failed") {
        test("a failed redeem shows the code field again with a close action") {
            experimentFailedActions(EducationalExperimentState.Failed(null, "x", retryable = false)) shouldBe
                ExperimentFailedActions(codeField = true, runCard = false, retry = false, cancel = false, close = true)
        }
        test("a failed start keeps the run card and offers retry and cancel") {
            experimentFailedActions(EducationalExperimentState.Failed(run, "x", retryable = true)) shouldBe
                ExperimentFailedActions(codeField = false, runCard = true, retry = true, cancel = true, close = false)
            experimentFailedActions(EducationalExperimentState.Failed(run, "x", retryable = false)) shouldBe
                ExperimentFailedActions(codeField = false, runCard = true, retry = false, cancel = true, close = false)
        }
        test("a failed completion offers retry only when retryable and keeps cancel while the run is active") {
            val active = run.copy(status = "ACTIVE")
            experimentFailedActions(EducationalExperimentState.Failed(active, "x", retryable = true, pending)) shouldBe
                ExperimentFailedActions(codeField = false, runCard = false, retry = true, cancel = true, close = false)
            experimentFailedActions(EducationalExperimentState.Failed(active, "x", retryable = false, pending)) shouldBe
                ExperimentFailedActions(codeField = false, runCard = false, retry = false, cancel = true, close = false)
        }
        test("a run closed by the backend only offers close") {
            val closed = run.copy(status = "CLOSED")
            experimentFailedActions(EducationalExperimentState.Failed(closed, "x", retryable = false, pending)) shouldBe
                ExperimentFailedActions(codeField = false, runCard = false, retry = false, cancel = false, close = true)
        }
        test("a failed restore of an active run offers retry and cancel") {
            val active = run.copy(status = "ACTIVE", promptText = "")
            experimentFailedActions(EducationalExperimentState.Failed(active, "x", retryable = true)) shouldBe
                ExperimentFailedActions(codeField = false, runCard = false, retry = true, cancel = true, close = false)
        }
        test("a failed restore of a pending run retries by restoring, never by starting a run the student has not seen") {
            val fromMarker = run.copy(promptText = "")
            experimentFailedActions(EducationalExperimentState.Failed(fromMarker, "x", retryable = true)) shouldBe
                ExperimentFailedActions(
                    codeField = false, runCard = false, retry = true, cancel = true, close = false,
                    retryByRestore = true,
                )
            experimentFailedActions(EducationalExperimentState.Failed(fromMarker, "x", retryable = false)).retryByRestore shouldBe false
            experimentFailedActions(EducationalExperimentState.Failed(run, "x", retryable = true)).retryByRestore shouldBe false
        }
    }

    context("draft") {
        test("the draft only survives while the run is still writable") {
            experimentDraftShouldClear(EducationalExperimentState.Idle) shouldBe true
            experimentDraftShouldClear(EducationalExperimentState.Cancelled) shouldBe true
            experimentDraftShouldClear(EducationalExperimentState.Completed(run)) shouldBe true
            experimentDraftShouldClear(EducationalExperimentState.Redeeming) shouldBe false
            experimentDraftShouldClear(EducationalExperimentState.Ready(run)) shouldBe false
            experimentDraftShouldClear(EducationalExperimentState.Starting(run)) shouldBe false
            experimentDraftShouldClear(EducationalExperimentState.Active(run, firstKeyAtMs = null)) shouldBe false
            experimentDraftShouldClear(EducationalExperimentState.Completing(run, "x", 1_000, "k")) shouldBe false
            experimentDraftShouldClear(EducationalExperimentState.Failed(run, "x", retryable = true)) shouldBe false
        }
    }

    test("condition labels never expose the raw enum and no selector wording") {
        EducationalMessages.conditionLabel(ExperimentCondition.ASSISTED) shouldBe "Con asistencia de la IA"
        EducationalMessages.conditionLabel(ExperimentCondition.UNASSISTED) shouldBe "Sin asistencia"
    }
})
