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

class SentenceTestModelsTest : FunSpec({
    val json = Json { encodeDefaults = true; explicitNulls = true }

    test("an attempt response without nextPosition decodes it as null") {
        val response = json.decodeFromString<AttemptResponse>(
            """
            {"attemptId":"attempt-1","testId":"test-1","code":"ABC123","title":"Prueba 1",
             "status":"COMPLETED","sentences":[{"position":1,"assistance":"ASSISTED"}]}
            """.trimIndent(),
        )

        response shouldBe AttemptResponse(
            attemptId = "attempt-1",
            testId = "test-1",
            code = "ABC123",
            title = "Prueba 1",
            nextPosition = null,
            status = "COMPLETED",
            sentences = listOf(SentenceSlot(1, SentenceAssistance.ASSISTED)),
        )
    }

    test("an attempt response with nextPosition decodes it") {
        val response = json.decodeFromString<AttemptResponse>(
            """
            {"attemptId":"attempt-1","testId":"test-1","code":"ABC123","title":"Prueba 1",
             "nextPosition":2,"status":"ACTIVE",
             "sentences":[{"position":1,"assistance":"ASSISTED"},{"position":2,"assistance":"UNASSISTED"}]}
            """.trimIndent(),
        )

        response.nextPosition shouldBe 2
        response.sentenceCount shouldBe 2
    }

    test("assistanceOf reports the condition of the requested position") {
        val response = AttemptResponse(
            attemptId = "attempt-1", testId = "test-1", code = "ABC123", title = "Prueba 1",
            status = "ACTIVE",
            sentences = listOf(SentenceSlot(1, SentenceAssistance.ASSISTED), SentenceSlot(2, SentenceAssistance.UNASSISTED)),
        )

        response.assistanceOf(1) shouldBe SentenceAssistance.ASSISTED
        response.assistanceOf(2) shouldBe SentenceAssistance.UNASSISTED
    }

    test("assistanceOf defaults to UNASSISTED for a position outside the attempt") {
        val response = AttemptResponse(
            attemptId = "attempt-1", testId = "test-1", code = "ABC123", title = "Prueba 1",
            status = "ACTIVE", sentences = listOf(SentenceSlot(1, SentenceAssistance.ASSISTED)),
        )

        response.assistanceOf(99) shouldBe SentenceAssistance.UNASSISTED
    }

    test("finishing without ever typing serializes firstKeyOffsetMs as an explicit null") {
        val body = FinishSentenceRequest(
            finalText = "",
            firstKeyOffsetMs = null,
            finishedOffsetMs = 4_000,
            suggestionsOffered = 0,
            suggestionsAccepted = 0,
            suggestionsRejected = 0,
            suggestionsUndone = 0,
            skipped = true,
            completionKey = "key-1",
        )

        json.encodeToString(body) shouldBe
            "{\"finalText\":\"\",\"firstKeyOffsetMs\":null,\"finishedOffsetMs\":4000," +
            "\"suggestionsOffered\":0,\"suggestionsAccepted\":0,\"suggestionsRejected\":0," +
            "\"suggestionsUndone\":0,\"skipped\":true,\"completionKey\":\"key-1\"}"
    }

    test("clampSentence keeps the backend limit of 5000 characters without splitting a surrogate pair") {
        MaxSentenceLength shouldBe 5000
        clampSentence("hola") shouldBe "hola"
        clampSentence("a".repeat(5000)) shouldBe "a".repeat(5000)
        clampSentence("a".repeat(5001)) shouldBe "a".repeat(5000)
        // 4999 letras + un emoji (par sustituto) + una letra: el corte cae dentro del par y lo descarta.
        clampSentence("a".repeat(4999) + "😀x") shouldBe "a".repeat(4999)
    }

    test("suggestion counters start at zero and increment independently") {
        val counters = SuggestionCounters()
        counters shouldBe SuggestionCounters(0, 0, 0, 0)

        counters.offered().accepted().accepted().rejected().undone() shouldBe
            SuggestionCounters(offered = 1, accepted = 2, rejected = 1, undone = 1)
    }
})
