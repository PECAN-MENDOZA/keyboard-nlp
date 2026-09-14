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
import java.time.Instant

class EducationalSessionTest : FunSpec({
    val now = Instant.parse("2026-06-22T12:00:00Z")

    test("a session whose expiry is in the past is expired") {
        session("2026-06-22T11:59:59Z").isExpired(now) shouldBe true
    }

    test("a session whose expiry is exactly now is expired") {
        session("2026-06-22T12:00:00Z").isExpired(now) shouldBe true
    }

    test("a session whose expiry is in the future is not expired") {
        session("2026-06-22T12:00:01Z").isExpired(now) shouldBe false
    }

    test("an unparseable expiry is treated as not expired so the student is not blocked") {
        session("not-a-date").isExpired(now) shouldBe false
    }

    test("a session persisted before the alias existed decodes with an empty alias") {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<EducationalSession>(
            """{"userId":"u1","token":"t","expiresAt":"2099-01-01T00:00:00Z"}""",
        )
        decoded.username shouldBe ""
    }
})

private fun session(expiresAt: String) = EducationalSession(
    userId = "student_a1b2c3d4",
    token = "token",
    expiresAt = expiresAt,
)
