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

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.keyboard.KeyboardState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EducationalPrivacyPolicyTest : FunSpec({
    val session = EducationalSession(
        userId = "student_001",
        token = "token",
        expiresAt = "2999-01-01T00:00:00Z",
    )

    fun editorInfo(inputType: Int, imeOptions: Int = 0): FlorisEditorInfo {
        val info = EditorInfo()
        info.inputType = inputType
        info.imeOptions = imeOptions
        return FlorisEditorInfo.wrap(info)
    }

    val normalText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL

    test("blocks when there is no session") {
        val reason = EducationalPrivacyPolicy.evaluate(
            editorInfo = editorInfo(normalText),
            keyboardState = KeyboardState.new(),
            session = null,
        )
        reason shouldBe EducationalMessages.NoSession
    }

    test("allows a normal text field with an active session") {
        val reason = EducationalPrivacyPolicy.evaluate(
            editorInfo = editorInfo(normalText),
            keyboardState = KeyboardState.new(),
            session = session,
        )
        reason shouldBe null
    }

    test("blocks password fields") {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val reason = EducationalPrivacyPolicy.evaluate(
            editorInfo = editorInfo(password),
            keyboardState = KeyboardState.new(),
            session = session,
        )
        reason shouldBe EducationalMessages.NotAllowedHere
    }

    test("blocks fields that opt out of personalized learning") {
        val reason = EducationalPrivacyPolicy.evaluate(
            editorInfo = editorInfo(normalText, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            keyboardState = KeyboardState.new(),
            session = session,
        )
        reason shouldBe EducationalMessages.NotAllowedHere
    }

    test("blocks raw input editors") {
        val reason = EducationalPrivacyPolicy.evaluate(
            editorInfo = editorInfo(InputType.TYPE_NULL),
            keyboardState = KeyboardState.new(),
            session = session,
        )
        reason shouldBe EducationalMessages.AppNotSupported
    }
})
