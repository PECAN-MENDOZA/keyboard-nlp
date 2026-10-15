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
        val error = EducationalHttpException(409, """{"message":"Sentence already finished"}""")
        error.body shouldBe """{"message":"Sentence already finished"}"""
        error.message shouldBe """HTTP 409: {"message":"Sentence already finished"}"""
    }

    test("the session check fails only on 401 or when the server does not answer properly") {
        EducationalApiRepository.isReachableWithSession(401) shouldBe false
        EducationalApiRepository.isReachableWithSession(403) shouldBe true
        EducationalApiRepository.isReachableWithSession(404) shouldBe true
        EducationalApiRepository.isReachableWithSession(500) shouldBe false
        EducationalApiRepository.isReachableWithSession(502) shouldBe false
    }

    test("only a 401 means the backend rejected the student's session") {
        EducationalHttpException(401, "").isSessionRejected() shouldBe true
        EducationalHttpException(403, "").isSessionRejected() shouldBe false
        EducationalHttpException(429, "").isSessionRejected() shouldBe false
        java.net.ConnectException("x").isSessionRejected() shouldBe false
    }
})
