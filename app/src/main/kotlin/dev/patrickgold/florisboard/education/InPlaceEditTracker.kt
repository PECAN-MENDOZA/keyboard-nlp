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

/** Ancla de un rango editable: inicio absoluto y ventanas de texto antes y después. */
data class EditAnchor(val start: Int, val prefix: String, val suffix: String)

sealed class TrackedRange {
    data class Inside(val text: String, val range: EditorRange) : TrackedRange()
    object Lost : TrackedRange()
}

/**
 * Sigue el texto que el alumno edita directamente en el campo de la app sin enganchar cada
 * tecla: el inicio del rango es fijo mientras el prefijo no cambie, y el final se localiza
 * buscando el sufijo original. Si el prefijo cambia, el sufijo desaparece o el cursor sale del
 * rango, la edición se da por perdida y el manager ejecuta un "Listo" implícito.
 */
object InPlaceEditTracker {
    const val WINDOW = 32

    fun anchor(content: EditorContent, range: EditorRange): EditAnchor? {
        if (content.offset < 0 || range.isNotValid) return null
        val local = range.translatedBy(-content.offset)
        if (local.isNotValid || local.start < 0 || local.end > content.text.length) return null
        return EditAnchor(
            start = range.start,
            prefix = content.text.substring(0, local.start).takeLast(WINDOW),
            suffix = content.text.substring(local.end).take(WINDOW),
        )
    }

    fun resolve(anchor: EditAnchor, content: EditorContent): TrackedRange {
        if (content.offset < 0) return TrackedRange.Lost
        val text = content.text
        val localStart = anchor.start - content.offset
        if (localStart < 0 || localStart > text.length) return TrackedRange.Lost

        // El caché del editor puede ser más corto que la ventana: se compara lo que hay.
        val before = text.substring(0, localStart)
        val n = minOf(before.length, anchor.prefix.length)
        if (before.takeLast(n) != anchor.prefix.takeLast(n)) return TrackedRange.Lost

        // El cursor vive dentro del rango mientras se edita; el sufijo real empieza en el cursor
        // o después, así que buscar desde ahí evita falsos positivos con texto recién escrito.
        val cursor = content.localSelection.start
        if (cursor < localStart || cursor > text.length) return TrackedRange.Lost

        val localEnd = if (anchor.suffix.isEmpty()) {
            text.length
        } else {
            val idx = text.indexOf(anchor.suffix, startIndex = cursor)
            if (idx < 0) return TrackedRange.Lost
            idx
        }

        return TrackedRange.Inside(
            text = text.substring(localStart, localEnd),
            range = EditorRange(anchor.start, anchor.start + (localEnd - localStart)),
        )
    }
}
