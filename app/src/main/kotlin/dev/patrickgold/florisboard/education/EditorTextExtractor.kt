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
        val text = content.selectedText
        if (text.isBlank()) {
            return EducationalExtractionResult.Blocked(SelectFirstMessage)
        }
        if (text.length > EDUCATIONAL_BACKEND_MAX_CHARS) {
            return EducationalExtractionResult.Blocked(
                EducationalMessages.tooLong(EDUCATIONAL_BACKEND_MAX_CHARS),
            )
        }
        return EducationalExtractionResult.Ready(
            ExtractedEducationalText(
                text = text,
                range = content.selection,
                source = EducationalTextSource.SELECTION,
            )
        )
    }

    const val SelectFirstMessage = EducationalMessages.SelectFirst
}

