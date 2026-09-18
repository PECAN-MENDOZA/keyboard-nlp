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

/** Operaciones de las pruebas de oraciones ya resueltas contra la URL del backend; se falsifica en tests. */
interface SentenceTestApi {
    suspend fun assigned(token: String): Result<List<AssignedTest>>
    suspend fun startAttempt(token: String, testId: String, appVersion: String): Result<AttemptResponse>
    suspend fun startSentence(token: String, attemptId: String, position: Int): Result<StartSentenceResponse>
    suspend fun finishSentence(
        token: String,
        attemptId: String,
        position: Int,
        body: FinishSentenceRequest,
    ): Result<FinishSentenceResponse>
    suspend fun cancel(token: String, attemptId: String, reason: AttemptCancelReason): Result<Unit>
}

/** Adaptador real: [EducationalApiRepository] probando cada URL de [baseUrls] como el resto del manager. */
class RepositorySentenceTestApi(
    private val repository: EducationalApiRepository,
    private val baseUrls: List<String>,
) : SentenceTestApi {
    override suspend fun assigned(token: String) =
        baseUrls.firstSuccessful { repository.assignedTests(it, token) }

    override suspend fun startAttempt(token: String, testId: String, appVersion: String) =
        baseUrls.firstSuccessful { repository.startAttempt(it, token, testId, appVersion) }

    override suspend fun startSentence(token: String, attemptId: String, position: Int) =
        baseUrls.firstSuccessful { repository.startSentence(it, token, attemptId, position) }

    override suspend fun finishSentence(token: String, attemptId: String, position: Int, body: FinishSentenceRequest) =
        baseUrls.firstSuccessful { repository.finishSentence(it, token, attemptId, position, body) }

    override suspend fun cancel(token: String, attemptId: String, reason: AttemptCancelReason) =
        baseUrls.firstSuccessful { repository.cancelAttempt(it, token, attemptId, reason) }
}
