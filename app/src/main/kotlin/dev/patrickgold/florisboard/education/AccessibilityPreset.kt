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
}
