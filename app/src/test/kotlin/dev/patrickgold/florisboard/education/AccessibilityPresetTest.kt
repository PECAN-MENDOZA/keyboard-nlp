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

import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class AccessibilityPresetTest : FunSpec({
    test("font scale is unchanged when big keys is off") {
        AccessibilityPreset.effectiveFontScale(1.0f, bigKeys = false) shouldBe 1.0f
    }

    test("font scale grows when big keys is on") {
        AccessibilityPreset.effectiveFontScale(1.0f, bigKeys = true) shouldBe 1.3f
    }

    test("font scale is clamped to the maximum") {
        AccessibilityPreset.effectiveFontScale(1.8f, bigKeys = true) shouldBe 2.0f
    }

    test("row height is unchanged when big keys is off") {
        AccessibilityPreset.effectiveRowHeight(65.dp, bigKeys = false) shouldBe 65.dp
    }

    test("row height grows when big keys is on") {
        AccessibilityPreset.effectiveRowHeight(65.dp, bigKeys = true) shouldBe 78.dp
    }

    test("row height is clamped to the maximum") {
        AccessibilityPreset.effectiveRowHeight(100.dp, bigKeys = true) shouldBe 110.dp
    }

    test("without big keys key texts keep the FlorisBoard size and position") {
        AccessibilityPreset.keyLabelFontFactor("?123", bigKeys = false) shouldBe 1f
        AccessibilityPreset.keyLabelFontFactor("a", bigKeys = false) shouldBe 1f
        AccessibilityPreset.keyHintFontFactor(bigKeys = false) shouldBe 1f
        AccessibilityPreset.keyLabelVerticalBias(bigKeys = false, hasHint = true) shouldBe 0f
    }

    test("with big keys only single-character labels grow; wide labels go back to normal size") {
        AccessibilityPreset.keyLabelFontFactor("a", bigKeys = true) shouldBe 1f
        AccessibilityPreset.keyLabelFontFactor("ñ", bigKeys = true) shouldBe 1f
        val normal = AccessibilityPreset.effectiveFontScale(1f, bigKeys = true) *
            AccessibilityPreset.keyLabelFontFactor("?123", bigKeys = true)
        normal shouldBe (1f plusOrMinus 0.0001f)
        AccessibilityPreset.keyLabelFontFactor("ABC", bigKeys = true) shouldBe
            AccessibilityPreset.keyLabelFontFactor("?123", bigKeys = true)
    }

    test("with big keys the corner hint keeps its normal size and the letter moves down only if there is one") {
        (AccessibilityPreset.effectiveFontScale(1f, bigKeys = true) * AccessibilityPreset.keyHintFontFactor(bigKeys = true)) shouldBe
            (1f plusOrMinus 0.0001f)
        (AccessibilityPreset.keyLabelVerticalBias(bigKeys = true, hasHint = true) > 0f) shouldBe true
        AccessibilityPreset.keyLabelVerticalBias(bigKeys = true, hasHint = false) shouldBe 0f
    }
})
