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

import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard.KeyboardState

object EducationalPrivacyPolicy {
    fun evaluate(
        editorInfo: FlorisEditorInfo,
        keyboardState: KeyboardState,
        session: EducationalSession?,
    ): String? {
        if (session == null) {
            return "Inicia sesión para usar la corrección IA."
        }
        if (editorInfo.isRawInputEditor) {
            return "Esta aplicación no entrega texto suficiente para corregir."
        }
        if (keyboardState.isIncognitoMode || editorInfo.imeOptions.flagNoPersonalizedLearning) {
            return "La corrección IA está bloqueada en modo privado."
        }
        val variation = editorInfo.inputAttributes.variation
        if (variation == InputAttributes.Variation.PASSWORD ||
            variation == InputAttributes.Variation.VISIBLE_PASSWORD ||
            variation == InputAttributes.Variation.WEB_PASSWORD
        ) {
            return "La corrección IA está bloqueada en campos de contraseña."
        }
        if (editorInfo.inputAttributes.type == InputAttributes.Type.NUMBER &&
            variation == InputAttributes.Variation.PASSWORD
        ) {
            return "La corrección IA está bloqueada en campos sensibles."
        }
        return null
    }
}

