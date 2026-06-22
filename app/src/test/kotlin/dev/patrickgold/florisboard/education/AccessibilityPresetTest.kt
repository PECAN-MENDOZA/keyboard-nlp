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
})
