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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EducationalExperimentModelsTest : FunSpec({
    val json = Json { encodeDefaults = true }

    test("correction without experiment stays normal") {
        json.encodeToString(ProcessCorrectionRequest("texto")) shouldBe
            "{\"texto_original\":\"texto\",\"id_ejecucion\":null}"
    }

    test("correction with an active experiment run carries id_ejecucion") {
        json.encodeToString(ProcessCorrectionRequest("texto", "run-1")) shouldBe
            "{\"texto_original\":\"texto\",\"id_ejecucion\":\"run-1\"}"
    }

    test("redeem access code request serializes the code") {
        json.encodeToString(RedeemAccessCodeRequest("K7MP2XQ9")) shouldBe
            "{\"code\":\"K7MP2XQ9\"}"
    }

    test("cancel experiment request serializes the reason enum") {
        json.encodeToString(CancelExperimentRequest(CancelReason.ABANDONED)) shouldBe
            "{\"reason\":\"ABANDONED\"}"
    }

    test("completion carries duration and idempotency key") {
        val body = CompleteExperimentRequest("Texto final", 60_000, "completion-1", "debug")
        json.decodeFromString<CompleteExperimentRequest>(json.encodeToString(body)) shouldBe body
    }

    test("a marker written before owner and pending completion existed still loads") {
        val lenient = Json { ignoreUnknownKeys = true }
        lenient.decodeFromString<ExperimentMarker>(
            """{"runId":"run-1","condition":"UNASSISTED","status":"ACTIVE","firstKeyAtMs":400,"bootId":"7"}""",
        ) shouldBe ExperimentMarker("run-1", ExperimentCondition.UNASSISTED, "ACTIVE", 400, "7", ownerUserId = "", pendingCompletion = null)
    }

    test("a marker round-trips its owner and the pending completion payload") {
        val marker = ExperimentMarker(
            runId = "run-1", condition = ExperimentCondition.ASSISTED, status = "COMPLETING", firstKeyAtMs = null,
            bootId = "7", ownerUserId = "student_001",
            pendingCompletion = PendingCompletionMarker("Texto final", 60_000, "key-1", "1.2.3"),
            pendingCancel = CancelReason.INTERRUPTED,
        )
        json.decodeFromString<ExperimentMarker>(json.encodeToString(marker)) shouldBe marker
    }

    test("experiment run response decodes the real backend shape") {
        val response = json.decodeFromString<ExperimentRunResponse>(
            """
            {"id":"run-1","participantCode":"P-001","condition":"ASSISTED","taskVariant":"TASK_A",
             "promptText":"Cuenta que hiciste el fin de semana","status":"PENDING","startedAt":null,
             "expiresAt":"2026-09-15T10:00:00Z"}
            """.trimIndent(),
        )

        response shouldBe ExperimentRunResponse(
            id = "run-1",
            participantCode = "P-001",
            condition = ExperimentCondition.ASSISTED,
            taskVariant = "TASK_A",
            promptText = "Cuenta que hiciste el fin de semana",
            status = "PENDING",
            startedAt = null,
            expiresAt = "2026-09-15T10:00:00Z",
        )
    }
})
