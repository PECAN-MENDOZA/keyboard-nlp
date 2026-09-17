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

class EducationalApiRepositoryTest : FunSpec({
    test("the http exception keeps the raw body for the callers that need it") {
        val error = EducationalHttpException(404, """{"message":"No experiment run to restore"}""")
        error.body shouldBe """{"message":"No experiment run to restore"}"""
        error.message shouldBe """HTTP 404: {"message":"No experiment run to restore"}"""
    }
})
