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

class CorrectionSessionResponseTest : FunSpec({
    test("rich suggestion options take priority and are deduped and capped at three") {
        val response = response(
            suggestionOptions = listOf(
                CorrectionSuggestionOption("tuvo", recommended = true),
                CorrectionSuggestionOption("tubo"),
                CorrectionSuggestionOption("tuvo"),
                CorrectionSuggestionOption("otra"),
                CorrectionSuggestionOption("extra"),
            ),
        )

        val options = response.displayOptions()

        options.map { it.text } shouldBe listOf("tuvo", "tubo", "otra")
        options.first().recommended shouldBe true
    }

    test("blank rich options are ignored") {
        val response = response(
            suggestionOptions = listOf(
                CorrectionSuggestionOption("   "),
                CorrectionSuggestionOption("tuvo"),
            ),
        )

        response.displayOptions().map { it.text } shouldBe listOf("tuvo")
    }

    test("falls back to plain suggestions with the first one recommended") {
        val response = response(
            suggestions = listOf("tuvo", "tubo", "tuvo"),
        )

        val options = response.displayOptions()

        options.map { it.text } shouldBe listOf("tuvo", "tubo")
        options[0].recommended shouldBe true
        options[1].recommended shouldBe false
    }

    test("falls back to the corrected text when there are no suggestions") {
        val response = response(correctedText = "tuvo")

        val options = response.displayOptions()

        options.map { it.text } shouldBe listOf("tuvo")
        options.single().recommended shouldBe true
    }
})

private fun response(
    correctedText: String = "texto",
    suggestions: List<String> = emptyList(),
    suggestionOptions: List<CorrectionSuggestionOption> = emptyList(),
) = CorrectionSessionResponse(
    sessionId = "s1",
    originalText = "texto",
    correctedText = correctedText,
    suggestions = suggestions,
    suggestionOptions = suggestionOptions,
)
