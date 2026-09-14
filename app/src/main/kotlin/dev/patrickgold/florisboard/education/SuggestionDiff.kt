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

data class DiffSegment(val text: String, val changed: Boolean)

/**
 * Marca qué palabras de la sugerencia difieren del texto original. Trabaja por tokens
 * (palabras con su puntuación pegada) usando la subsecuencia común más larga, de modo que
 * una palabra insertada o sustituida queda marcada y las demás no. Los segmentos concatenados
 * devuelven la sugerencia tal cual (espacios incluidos) para poder pintarla.
 */
object SuggestionDiff {
    private val tokenRegex = Regex("""\S+|\s+""")

    fun compute(original: String, suggestion: String): List<DiffSegment> {
        if (suggestion.isEmpty()) return emptyList()
        val a = original.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val bTokens = tokenRegex.findAll(suggestion).map { it.value }.toList()
        val b = bTokens.filter { it.isNotBlank() }

        // LCS clásica entre palabras del original (a) y de la sugerencia (b).
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val unchanged = BooleanArray(b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { unchanged[j] = true; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }

        var wordIndex = 0
        return bTokens.map { token ->
            if (token.isBlank()) {
                DiffSegment(token, changed = false)
            } else {
                DiffSegment(token, changed = !unchanged[wordIndex++])
            }
        }
    }

    fun changedCount(segments: List<DiffSegment>): Int = segments.count { it.changed }

    /**
     * Número de cambios entre original y sugerencia: palabras marcadas en la sugerencia más
     * palabras del original que desaparecieron (borrados no dejan segmento, pero sí son cambio).
     */
    fun countChanges(original: String, suggestion: String): Int {
        val segments = compute(original, suggestion)
        val originalWords = original.split(Regex("""\s+""")).count { it.isNotEmpty() }
        val keptWords = segments.count { !it.changed && it.text.isNotBlank() }
        return maxOf(changedCount(segments), originalWords - keptWords)
    }
}
