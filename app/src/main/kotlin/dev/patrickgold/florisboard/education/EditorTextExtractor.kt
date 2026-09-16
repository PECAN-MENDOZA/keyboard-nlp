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

/**
 * Extrae el texto a corregir desde el editor activo.
 *
 * La unica fuente valida es el **texto que el alumno sombrea (selecciona) a mano**.
 * Si no hay seleccion, no se adivina nada: se le pide al alumno que sombree primero.
 * Esto cumple el contrato del backend (una unidad de correccion elegida por el alumno)
 * y evita la ambiguedad de extraer automaticamente una oracion o una ventana de texto.
 */
object EditorTextExtractor {
    fun extract(content: EditorContent): EducationalExtractionResult {
        if (content.offset < 0 || content.localSelection.isNotValid) {
            return EducationalExtractionResult.Blocked(EducationalMessages.AppNotSupported)
        }
        if (!content.localSelection.isSelectionMode) {
            return EducationalExtractionResult.Blocked(SelectFirstMessage)
        }
        val raw = content.selectedText
        if (raw.isBlank()) {
            return EducationalExtractionResult.Blocked(SelectFirstMessage)
        }
        // Los espacios que el dedo arrastra al sombrear quedan FUERA del rango: la IA
        // devuelve el texto recortado, y si el rango los incluyera, aplicar la sugerencia
        // los borraria (pegando "hola" + "voy") y un texto ya correcto no se reconoceria
        // como igual (globo "0 cambios" en vez de "ya esta bien escrito").
        val leading = raw.length - raw.trimStart().length
        val trailing = raw.length - raw.trimEnd().length
        val text = raw.trim()
        val selection = content.selection
        val range = EditorRange(selection.start + leading, selection.end - trailing)
        if (text.length > EDUCATIONAL_BACKEND_MAX_CHARS) {
            return EducationalExtractionResult.Blocked(
                EducationalMessages.tooLong(EDUCATIONAL_BACKEND_MAX_CHARS),
            )
        }
        return EducationalExtractionResult.Ready(
            ExtractedEducationalText(
                text = text,
                range = range,
                source = EducationalTextSource.SELECTION,
            )
        )
    }

    const val SelectFirstMessage = EducationalMessages.SelectFirst
}

