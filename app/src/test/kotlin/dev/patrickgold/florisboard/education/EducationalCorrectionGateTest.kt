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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContainIgnoringCase

class EducationalCorrectionGateTest : FunSpec({
    test("only an unassisted sentence blocks IA") {
        correctionBlockMessage(null) shouldBe null
        correctionBlockMessage(SentenceAssistance.ASSISTED) shouldBe null
        correctionBlockMessage(SentenceAssistance.UNASSISTED) shouldBe
            "La corrección está desactivada en esta tarea."
    }

    test("the block message is the shared constant and mentions no infrastructure") {
        correctionBlockMessage(SentenceAssistance.UNASSISTED) shouldBe EducationalMessages.CorrectionDisabledInTask
        EducationalMessages.CorrectionDisabledInTask shouldNotContainIgnoringCase "cloud run"
        EducationalMessages.CorrectionDisabledInTask shouldNotContainIgnoringCase "backend"
        EducationalMessages.CorrectionDisabledInTask shouldNotContainIgnoringCase "desplegado"
    }
})
