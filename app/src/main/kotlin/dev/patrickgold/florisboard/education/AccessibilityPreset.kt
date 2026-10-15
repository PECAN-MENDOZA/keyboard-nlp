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

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Valores derivados de los switches de accesibilidad. Funciones puras: combinan el valor base de
 * Floris con el preajuste de accesibilidad sin mutar ninguna preferencia base (Enfoque 3).
 */
object AccessibilityPreset {
    /** Id del tema Snygg de alto contraste empaquetado (registrado en extension.json). */
    const val HIGH_CONTRAST_THEME_ID: String = "floris_high_contrast"

    private const val BIG_KEYS_FONT_FACTOR = 1.3f
    private const val BIG_KEYS_HEIGHT_FACTOR = 1.2f
    private const val MAX_FONT_SCALE = 2.0f
    private val MAX_ROW_HEIGHT = 110.dp

    fun effectiveFontScale(base: Float, bigKeys: Boolean): Float {
        val scaled = if (bigKeys) base * BIG_KEYS_FONT_FACTOR else base
        return scaled.coerceAtMost(MAX_FONT_SCALE)
    }

    fun effectiveRowHeight(base: Dp, bigKeys: Boolean): Dp {
        val scaled = if (bigKeys) base * BIG_KEYS_HEIGHT_FACTOR else base
        return scaled.coerceAtMost(MAX_ROW_HEIGHT)
    }

    /**
     * Factor extra para la etiqueta principal de una tecla. Teclas grandes agranda la fuente de
     * todo el teclado pero no el ancho de las teclas: una etiqueta de varios caracteres ("?123",
     * "ABC", el idioma de la barra espaciadora) ya no cabía y se cortaba ("?"). Esas vuelven a su
     * tamaño normal (el mismo que sin Teclas grandes, que sí cabe); solo crece la letra o símbolo
     * único.
     */
    fun keyLabelFontFactor(label: String, bigKeys: Boolean): Float =
        if (bigKeys && label.codePointCount(0, label.length) > 1) 1f / BIG_KEYS_FONT_FACTOR else 1f

    /**
     * Factor extra para el número o símbolo pequeño de la esquina: con Teclas grandes se queda
     * en su tamaño normal para no pisar la letra agrandada.
     */
    fun keyHintFontFactor(bigKeys: Boolean): Float = if (bigKeys) 1f / BIG_KEYS_FONT_FACTOR else 1f

    /**
     * Desplazamiento vertical (sesgo de alineación, -1 arriba … 1 abajo) de la letra principal.
     * Con Teclas grandes y un número/símbolo en la esquina superior, la letra baja un poco para
     * dejarle sitio; sin Teclas grandes o sin esquina queda centrada como en FlorisBoard.
     */
    fun keyLabelVerticalBias(bigKeys: Boolean, hasHint: Boolean): Float =
        if (bigKeys && hasHint) BIG_KEYS_LABEL_BIAS else 0f

    private const val BIG_KEYS_LABEL_BIAS = 0.35f
}
