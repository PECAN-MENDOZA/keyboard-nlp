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

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class EditorTextExtractorTest : FunSpec({
    test("selected text is extracted with its exact range") {
        val content = content(
            text = "El nino iva a la escuela.",
            selection = EditorRange(8, 11),
        )

        val result = EditorTextExtractor.extract(content)

        result as EducationalExtractionResult.Ready
        result.value.text shouldBe "iva"
        result.value.range shouldBe EditorRange(8, 11)
        result.value.source shouldBe EducationalTextSource.SELECTION
    }

    test("a selection spanning a whole sentence is kept verbatim") {
        val text = "El nino iva a la escuela."
        val content = content(text = text, selection = EditorRange(0, text.length))

        val result = EditorTextExtractor.extract(content)

        result as EducationalExtractionResult.Ready
        result.value.text shouldBe text
    }

    test("surrounding whitespace is left outside the range so replacing never glues words") {
        // "hola" + " voy al parque " sombreado con el espacio inicial y final (dedo poco preciso):
        // la IA devuelve el texto recortado; si el rango incluyera los espacios, aplicar la
        // sugerencia daria "holavoy al parque" y, con texto ya correcto, no se reconoceria
        // como igual (globo "0 cambios" en vez de "ya esta bien escrito").
        val text = "hola voy al parque ya"
        val content = content(text = text, selection = EditorRange(4, 19))

        val result = EditorTextExtractor.extract(content)

        result as EducationalExtractionResult.Ready
        result.value.text shouldBe "voy al parque"
        result.value.range shouldBe EditorRange(5, 18)
    }

    test("a selection with an offset keeps the trimmed range in absolute coordinates") {
        val content = EditorContent(
            text = " iva ",
            offset = 8,
            localSelection = EditorRange(0, 5),
            localComposing = EditorRange.Unspecified,
            localCurrentWord = EditorRange.Unspecified,
        )

        val result = EditorTextExtractor.extract(content)

        result as EducationalExtractionResult.Ready
        result.value.text shouldBe "iva"
        result.value.range shouldBe EditorRange(9, 12)
    }

    test("no selection (just a cursor) is blocked and asks to select first") {
        val text = "El nino iva a la escuela."
        val content = content(text = text, selection = EditorRange.cursor(10))

        val result = EditorTextExtractor.extract(content)

        result.shouldBeInstanceOf<EducationalExtractionResult.Blocked>()
        result.reason shouldBe EditorTextExtractor.SelectFirstMessage
    }

    test("a blank selection is blocked and asks to select first") {
        val content = content(text = "   hola", selection = EditorRange(0, 3))

        val result = EditorTextExtractor.extract(content)

        result.shouldBeInstanceOf<EducationalExtractionResult.Blocked>()
        result.reason shouldBe EditorTextExtractor.SelectFirstMessage
    }

    test("a selection longer than the backend limit is blocked") {
        val text = "a".repeat(EDUCATIONAL_BACKEND_MAX_CHARS + 1)
        val content = content(text = text, selection = EditorRange(0, text.length))

        val result = EditorTextExtractor.extract(content)

        result.shouldBeInstanceOf<EducationalExtractionResult.Blocked>()
    }

    test("an invalid selection range is blocked") {
        val content = content(text = "hola", selection = EditorRange.Unspecified)

        val result = EditorTextExtractor.extract(content)

        result.shouldBeInstanceOf<EducationalExtractionResult.Blocked>()
    }
})

private fun content(
    text: String,
    selection: EditorRange,
): EditorContent {
    return EditorContent(
        text = text,
        offset = 0,
        localSelection = selection,
        localComposing = EditorRange.Unspecified,
        localCurrentWord = EditorRange.Unspecified,
    )
}
