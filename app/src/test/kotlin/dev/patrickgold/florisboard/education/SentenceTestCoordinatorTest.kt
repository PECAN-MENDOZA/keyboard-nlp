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

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SentenceTestCoordinatorTest : FunSpec({
    val session = EducationalSession("student_001", "jwt", "2099-01-01T00:00:00Z")

    fun assigned(status: String = "PENDING") = AssignedTest(
        testId = "test-1", code = "T1", title = "Dictado 1", sentenceCount = 3, status = status,
    )

    /** Intento de tres oraciones: 1 con ayuda, 2 sin ayuda, 3 con ayuda. */
    fun attempt(next: Int? = 1, status: String = "IN_PROGRESS") = AttemptResponse(
        attemptId = "attempt-1",
        testId = "test-1",
        code = "T1",
        title = "Dictado 1",
        nextPosition = next,
        status = status,
        sentences = listOf(
            SentenceSlot(1, SentenceAssistance.ASSISTED),
            SentenceSlot(2, SentenceAssistance.UNASSISTED),
            SentenceSlot(3, SentenceAssistance.ASSISTED),
        ),
    )

    fun started(position: Int, alreadyStarted: Boolean = false) = StartSentenceResponse(
        responseId = "resp-$position",
        position = position,
        assistance = attempt().assistanceOf(position),
        alreadyStarted = alreadyStarted,
    )

    fun finished(next: Int?) = FinishSentenceResponse(
        responseId = "resp-x",
        nextPosition = next,
        attemptStatus = if (next == null) "COMPLETED" else "IN_PROGRESS",
    )

    fun draft(
        position: Int = 1,
        bootId: String = "boot-1",
        ownerUserId: String = "student_001",
        attemptId: String = "attempt-1",
        text: String = "Hola mun",
        completionKey: String? = null,
    ) = SentenceDraft(
        ownerUserId = ownerUserId,
        attemptId = attemptId,
        position = position,
        bootId = bootId,
        pressedAtElapsedMs = 1_000,
        firstKeyAtElapsedMs = 1_400,
        text = text,
        offered = 2,
        accepted = 1,
        rejected = 1,
        undone = 0,
        completionKey = completionKey,
    )

    class FakeClock(var value: Long) {
        fun now(): Long = value
    }

    class FakeDraftStore(var draft: SentenceDraft? = null) : SentenceTestDraftStore {
        val saved = mutableListOf<SentenceDraft>()
        var clears = 0
        override fun load(): SentenceDraft? = draft
        override fun save(draft: SentenceDraft) {
            this.draft = draft
            saved += draft
        }
        override fun clear() {
            draft = null
            clears++
        }
    }

    class FakeSentenceTestApi : SentenceTestApi {
        var assignedResult: Result<List<AssignedTest>> = Result.success(listOf(assigned()))
        var startAttemptResult: Result<AttemptResponse> = Result.success(attempt(next = 1))
        var startSentenceResult: (Int) -> Result<StartSentenceResponse> = { Result.success(started(it)) }
        /** Se consumen en orden; agotados, la finalización pasa a la siguiente oración. */
        val finishResults = ArrayDeque<Result<FinishSentenceResponse>>()
        var cancelResult: Result<Unit> = Result.success(Unit)

        val calls = mutableListOf<String>()
        val finished = mutableListOf<FinishSentenceRequest>()
        val cancelled = mutableListOf<AttemptCancelReason>()

        override suspend fun assigned(token: String): Result<List<AssignedTest>> {
            calls += "assigned"
            return assignedResult
        }

        override suspend fun startAttempt(token: String, testId: String, appVersion: String): Result<AttemptResponse> {
            calls += "startAttempt:$testId:$appVersion"
            return startAttemptResult
        }

        override suspend fun startSentence(token: String, attemptId: String, position: Int): Result<StartSentenceResponse> {
            calls += "startSentence:$attemptId:$position"
            return startSentenceResult(position)
        }

        override suspend fun finishSentence(
            token: String,
            attemptId: String,
            position: Int,
            body: FinishSentenceRequest,
        ): Result<FinishSentenceResponse> {
            calls += "finish:$attemptId:$position"
            finished += body
            return finishResults.removeFirstOrNull() ?: Result.success(finished(next = position + 1))
        }

        override suspend fun cancel(token: String, attemptId: String, reason: AttemptCancelReason): Result<Unit> {
            calls += "cancel:$attemptId:$reason"
            cancelled += reason
            return cancelResult
        }
    }

    fun TestScope.coordinator(
        api: FakeSentenceTestApi = FakeSentenceTestApi(),
        clock: FakeClock = FakeClock(1_000),
        drafts: FakeDraftStore = FakeDraftStore(),
        bootId: () -> String = { "boot-1" },
        currentSession: () -> EducationalSession? = { session },
        onSessionRejected: () -> Unit = {},
        keys: ArrayDeque<String> = ArrayDeque(listOf("key-1", "key-2", "key-3")),
    ) = SentenceTestCoordinator(
        scope = this,
        api = api,
        session = currentSession,
        elapsedRealtime = clock::now,
        appVersion = "1.2.3",
        drafts = drafts,
        bootId = bootId,
        newCompletionKey = { keys.removeFirst() },
        onSessionRejected = onSessionRejected,
    )

    /** Coordinador en `AtSentence(1)` tras elegir y comenzar la prueba. */
    fun TestScope.atSentence(
        api: FakeSentenceTestApi = FakeSentenceTestApi(),
        clock: FakeClock = FakeClock(1_000),
        drafts: FakeDraftStore = FakeDraftStore(),
        onSessionRejected: () -> Unit = {},
    ): SentenceTestCoordinator {
        val coordinator = coordinator(api, clock, drafts, onSessionRejected = onSessionRejected)
        coordinator.loadTests()
        advanceUntilIdle()
        coordinator.start(assigned())
        advanceUntilIdle()
        coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 1), 1)
        return coordinator
    }

    /** Coordinador en `Writing` de la oración [position] (con ayuda en 1 y 3, sin ayuda en 2). */
    fun TestScope.writing(
        position: Int = 1,
        api: FakeSentenceTestApi = FakeSentenceTestApi(),
        clock: FakeClock = FakeClock(1_000),
        drafts: FakeDraftStore = FakeDraftStore(),
    ): SentenceTestCoordinator {
        api.startAttemptResult = Result.success(attempt(next = position))
        val coordinator = coordinator(api, clock, drafts)
        coordinator.loadTests()
        advanceUntilIdle()
        coordinator.start(assigned())
        advanceUntilIdle()
        coordinator.pressStart()
        advanceUntilIdle()
        coordinator.state.value.shouldBeInstanceOf<SentenceTestState.Writing>().position shouldBe position
        return coordinator
    }

    context("1. choosing and starting a test") {
        test("loadTests lists the assigned tests and start opens the first sentence with the gate closed") {
            runTest {
                val api = FakeSentenceTestApi()
                val coordinator = coordinator(api)
                coordinator.state.test {
                    awaitItem() shouldBe SentenceTestState.Idle
                    coordinator.loadTests()
                    awaitItem() shouldBe SentenceTestState.LoadingTests
                    advanceUntilIdle()
                    awaitItem() shouldBe SentenceTestState.Choosing(listOf(assigned()))
                    coordinator.start(assigned())
                    awaitItem() shouldBe SentenceTestState.Starting(assigned())
                    advanceUntilIdle()
                    awaitItem() shouldBe SentenceTestState.AtSentence(attempt(next = 1), 1)
                }
                api.calls shouldBe listOf("assigned", "startAttempt:test-1:1.2.3")
                coordinator.correctionAllowed() shouldBe false
                coordinator.activeResponseId() shouldBe null
                coordinator.inProgress() shouldBe true
            }
        }

        test("without a test in progress correction works as always") {
            runTest {
                val coordinator = coordinator()
                coordinator.correctionAllowed() shouldBe true
                coordinator.activeResponseId() shouldBe null
                coordinator.inProgress() shouldBe false
            }
        }

        test("loadTests with a test already in progress resumes it instead of listing") {
            runTest {
                val api = FakeSentenceTestApi()
                api.assignedResult = Result.success(listOf(assigned(status = "IN_PROGRESS")))
                api.startAttemptResult = Result.success(attempt(next = 2))
                val coordinator = coordinator(api)
                coordinator.loadTests()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
            }
        }

        test("a failed start goes back to the list with a transient error") {
            runTest {
                val api = FakeSentenceTestApi()
                api.startAttemptResult = Result.failure(EducationalHttpException(409, "Test is not active"))
                val coordinator = coordinator(api)
                coordinator.loadTests()
                advanceUntilIdle()
                coordinator.start(assigned())
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Choosing(listOf(assigned()))
                coordinator.lastError.value shouldNotBe null
                coordinator.dismissError()
                coordinator.lastError.value shouldBe null
            }
        }

        test("a failed list is a Failed state that leaveToHome clears") {
            runTest {
                val api = FakeSentenceTestApi()
                api.assignedResult = Result.failure(SocketTimeoutException("timeout"))
                val coordinator = coordinator(api)
                coordinator.loadTests()
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<SentenceTestState.Failed>()
                coordinator.leaveToHome()
                coordinator.state.value shouldBe SentenceTestState.Idle
            }
        }
    }

    context("2. pressing Comenzar") {
        test("an assisted sentence opens the gate and exposes the backend response id") {
            runTest {
                val api = FakeSentenceTestApi()
                val clock = FakeClock(1_000)
                val drafts = FakeDraftStore()
                val coordinator = atSentence(api, clock, drafts)
                clock.value = 5_000
                coordinator.pressStart()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Writing(
                    attempt = attempt(next = 1),
                    position = 1,
                    responseId = "resp-1",
                    pressedAtElapsedMs = 5_000,
                    firstKeyAtElapsedMs = null,
                    counters = SuggestionCounters(),
                    text = "",
                )
                api.calls.last() shouldBe "startSentence:attempt-1:1"
                coordinator.correctionAllowed() shouldBe true
                coordinator.activeResponseId() shouldBe "resp-1"
                drafts.draft shouldBe SentenceDraft(
                    ownerUserId = "student_001", attemptId = "attempt-1", position = 1,
                    bootId = "boot-1", pressedAtElapsedMs = 5_000,
                )
            }
        }

        test("an unassisted sentence keeps the gate closed and exposes no id") {
            runTest {
                val coordinator = writing(position = 2)
                coordinator.correctionAllowed() shouldBe false
                coordinator.activeResponseId() shouldBe null
                coordinator.inProgress() shouldBe true
            }
        }

        test("a failed Comenzar stays at the sentence with a transient error") {
            runTest {
                val api = FakeSentenceTestApi()
                api.startSentenceResult = { Result.failure(SocketTimeoutException("timeout")) }
                val drafts = FakeDraftStore()
                val coordinator = atSentence(api, drafts = drafts)
                coordinator.pressStart()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 1), 1)
                coordinator.lastError.value shouldNotBe null
                drafts.saved shouldBe emptyList()
            }
        }
    }

    context("3. writing and finishing") {
        test("the first key is fixed once and finish sends offsets, counters and the next sentence follows") {
            runTest {
                val api = FakeSentenceTestApi()
                val clock = FakeClock(1_000)
                val drafts = FakeDraftStore()
                val coordinator = writing(1, api, clock, drafts)

                clock.value = 1_400
                coordinator.onTextChanged("h")
                clock.value = 1_900
                coordinator.onTextChanged("ho")
                coordinator.onTextChanged("hola")
                val current = coordinator.state.value.shouldBeInstanceOf<SentenceTestState.Writing>()
                current.firstKeyAtElapsedMs shouldBe 1_400
                current.text shouldBe "hola"
                drafts.draft?.text shouldBe "hola"
                drafts.draft?.firstKeyAtElapsedMs shouldBe 1_400

                coordinator.onSuggestionsOffered()
                coordinator.onSuggestionsOffered()
                coordinator.onSuggestionAccepted()
                coordinator.onSuggestionRejected()
                coordinator.onSuggestionUndone()

                clock.value = 7_000
                coordinator.state.test {
                    awaitItem().shouldBeInstanceOf<SentenceTestState.Writing>()
                    coordinator.finish()
                    awaitItem().shouldBeInstanceOf<SentenceTestState.Finishing>().position shouldBe 1
                    advanceUntilIdle()
                    // El intento del estado ya apunta a la oración actual (sin nextPosition viejo).
                    awaitItem() shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
                }
                api.finished.single() shouldBe FinishSentenceRequest(
                    finalText = "hola",
                    firstKeyOffsetMs = 400,
                    finishedOffsetMs = 6_000,
                    suggestionsOffered = 2,
                    suggestionsAccepted = 1,
                    suggestionsRejected = 1,
                    suggestionsUndone = 1,
                    skipped = false,
                    completionKey = "key-1",
                )
                // La clave quedó en el borrador antes de enviar; al avanzar, el borrador se borra.
                drafts.saved.last().completionKey shouldBe "key-1"
                drafts.draft shouldBe null
                coordinator.correctionAllowed() shouldBe false
                coordinator.activeResponseId() shouldBe null
            }
        }

        test("text without a first key (should not happen) is measured from Comenzar") {
            runTest {
                val api = FakeSentenceTestApi()
                val clock = FakeClock(1_000)
                val drafts = FakeDraftStore(
                    draft(position = 1, text = "restaurado").copy(firstKeyAtElapsedMs = null),
                )
                val coordinator = coordinator(api, clock, drafts)
                api.assignedResult = Result.success(listOf(assigned("IN_PROGRESS")))
                coordinator.resume()
                advanceUntilIdle()
                clock.value = 3_000
                coordinator.finish()
                advanceUntilIdle()
                api.finished.single().firstKeyOffsetMs shouldBe 0
                api.finished.single().finishedOffsetMs shouldBe 2_000
            }
        }
    }

    context("4. finishing an empty sentence") {
        test("empty text is skipped without a first key offset") {
            runTest {
                val api = FakeSentenceTestApi()
                val clock = FakeClock(1_000)
                val coordinator = writing(1, api, clock)
                clock.value = 2_500
                coordinator.finish()
                advanceUntilIdle()
                api.finished.single() shouldBe FinishSentenceRequest(
                    finalText = "",
                    firstKeyOffsetMs = null,
                    finishedOffsetMs = 1_500,
                    suggestionsOffered = 0,
                    suggestionsAccepted = 0,
                    suggestionsRejected = 0,
                    suggestionsUndone = 0,
                    skipped = true,
                    completionKey = "key-1",
                )
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
            }
        }
    }

    context("5. failed finish and retry") {
        test("a network failure keeps the pending request and retry resends the same completion key") {
            runTest {
                val api = FakeSentenceTestApi()
                api.finishResults += Result.failure(SocketTimeoutException("timeout"))
                val clock = FakeClock(1_000)
                val drafts = FakeDraftStore()
                val coordinator = writing(1, api, clock, drafts)
                clock.value = 1_200
                coordinator.onTextChanged("hola")
                clock.value = 4_000
                coordinator.finish()
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<SentenceTestState.FinishFailed>()
                failed.retryable shouldBe true
                failed.pending.completionKey shouldBe "key-1"
                coordinator.inProgress() shouldBe true
                coordinator.correctionAllowed() shouldBe false
                // El borrador (con la clave) sigue en disco mientras el backend no confirme.
                drafts.draft?.completionKey shouldBe "key-1"

                clock.value = 9_000
                coordinator.retry()
                advanceUntilIdle()
                api.finished.size shouldBe 2
                api.finished[1] shouldBe api.finished[0]
                api.finished[1].completionKey shouldBe "key-1"
                api.finished[1].finishedOffsetMs shouldBe 3_000
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
                drafts.draft shouldBe null
            }
        }

        test("a 4xx other than already finished is not retryable") {
            runTest {
                val api = FakeSentenceTestApi()
                api.finishResults += Result.failure(EducationalHttpException(409, """{"message":"Sentence out of order"}"""))
                val coordinator = writing(1, api)
                coordinator.finish()
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<SentenceTestState.FinishFailed>()
                failed.retryable shouldBe false
                coordinator.retry()
                advanceUntilIdle()
                api.finished.size shouldBe 1
            }
        }

        test("409 already finished is a logical success: the attempt is asked for the next position") {
            runTest {
                val api = FakeSentenceTestApi()
                api.finishResults += Result.failure(EducationalHttpException(409, """{"message":"Sentence already finished"}"""))
                val drafts = FakeDraftStore()
                val coordinator = writing(1, api, drafts = drafts)
                api.startAttemptResult = Result.success(attempt(next = 2))
                coordinator.finish()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
                api.calls.takeLast(2) shouldBe listOf("finish:attempt-1:1", "startAttempt:test-1:1.2.3")
                drafts.draft shouldBe null
            }
        }

        test("409 already finished on the last sentence: a 409 already completed lookup completes the attempt") {
            runTest {
                val api = FakeSentenceTestApi()
                api.finishResults += Result.failure(EducationalHttpException(409, """{"message":"Sentence already finished"}"""))
                val drafts = FakeDraftStore()
                val coordinator = writing(3, api, drafts = drafts)
                api.startAttemptResult = Result.failure(EducationalHttpException(409, """{"message":"Test already completed"}"""))
                coordinator.onTextChanged("fin")
                coordinator.finish()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Completed(attempt(next = null))
                coordinator.inProgress() shouldBe false
                drafts.draft shouldBe null
            }
        }

        test("a 401 while finishing rejects the session and stays retryable") {
            runTest {
                val api = FakeSentenceTestApi()
                api.finishResults += Result.failure(EducationalHttpException(401, ""))
                var rejected = 0
                val coordinator = coordinator(api, onSessionRejected = { rejected++ })
                coordinator.loadTests()
                advanceUntilIdle()
                coordinator.start(assigned())
                advanceUntilIdle()
                coordinator.pressStart()
                advanceUntilIdle()
                coordinator.finish()
                advanceUntilIdle()
                rejected shouldBe 1
                coordinator.state.value.shouldBeInstanceOf<SentenceTestState.FinishFailed>().retryable shouldBe true
            }
        }
    }

    context("6. last sentence") {
        test("finishing the last sentence completes the attempt") {
            runTest {
                val api = FakeSentenceTestApi()
                api.finishResults += Result.success(finished(next = null))
                val drafts = FakeDraftStore()
                val coordinator = writing(3, api, drafts = drafts)
                coordinator.onTextChanged("fin")
                coordinator.finish()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Completed(attempt(next = null))
                coordinator.inProgress() shouldBe false
                coordinator.correctionAllowed() shouldBe true
                drafts.draft shouldBe null
                coordinator.leaveToHome()
                coordinator.state.value shouldBe SentenceTestState.Idle
            }
        }
    }

    context("7. resuming") {
        test("without a session nothing is asked and the state stays Idle") {
            runTest {
                val api = FakeSentenceTestApi()
                val coordinator = coordinator(api, currentSession = { null })
                coordinator.resume()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Idle
                api.calls shouldBe emptyList()
            }
        }

        test("without a test in progress resume ends in Idle") {
            runTest {
                val api = FakeSentenceTestApi()
                val coordinator = coordinator(api)
                coordinator.resume()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Idle
                api.calls shouldBe listOf("assigned")
            }
        }

        test("a matching draft from the same boot restores Writing with text and counters") {
            runTest {
                val api = FakeSentenceTestApi()
                api.assignedResult = Result.success(listOf(assigned("IN_PROGRESS")))
                api.startAttemptResult = Result.success(attempt(next = 1))
                api.startSentenceResult = { Result.success(started(it, alreadyStarted = true)) }
                val drafts = FakeDraftStore(draft(position = 1, bootId = "boot-1", completionKey = "key-old"))
                val coordinator = coordinator(api, drafts = drafts)
                coordinator.resume()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Writing(
                    attempt = attempt(next = 1),
                    position = 1,
                    responseId = "resp-1",
                    pressedAtElapsedMs = 1_000,
                    firstKeyAtElapsedMs = 1_400,
                    counters = SuggestionCounters(offered = 2, accepted = 1, rejected = 1, undone = 0),
                    text = "Hola mun",
                )
                api.calls shouldBe listOf("assigned", "startAttempt:test-1:1.2.3", "startSentence:attempt-1:1")
                coordinator.activeResponseId() shouldBe "resp-1"

                // Terminar tras reanudar reutiliza la clave guardada en el borrador.
                coordinator.finish()
                advanceUntilIdle()
                api.finished.single().completionKey shouldBe "key-old"
                api.finished.single().finalText shouldBe "Hola mun"
            }
        }

        test("a draft from another boot loses the clock and can only be cancelled") {
            runTest {
                val api = FakeSentenceTestApi()
                api.assignedResult = Result.success(listOf(assigned("IN_PROGRESS")))
                val drafts = FakeDraftStore(draft(position = 1, bootId = "boot-0"))
                val coordinator = coordinator(api, drafts = drafts, bootId = { "boot-1" })
                coordinator.resume()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.ClockLost(attempt(next = 1), 1)
                coordinator.inProgress() shouldBe true
                coordinator.correctionAllowed() shouldBe false
                coordinator.finish()
                coordinator.pressStart()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.ClockLost(attempt(next = 1), 1)

                coordinator.state.test {
                    awaitItem().shouldBeInstanceOf<SentenceTestState.ClockLost>()
                    coordinator.cancel(AttemptCancelReason.TECHNICAL_PROBLEM)
                    awaitItem() shouldBe SentenceTestState.Cancelling(attempt(next = 1))
                    advanceUntilIdle()
                    awaitItem() shouldBe SentenceTestState.Cancelled
                }
                api.cancelled shouldBe listOf(AttemptCancelReason.TECHNICAL_PROBLEM)
                drafts.draft shouldBe null
                coordinator.inProgress() shouldBe false
                coordinator.leaveToHome()
                coordinator.state.value shouldBe SentenceTestState.Idle
            }
        }

        test("a draft of another sentence, attempt or student is ignored") {
            runTest {
                val api = FakeSentenceTestApi()
                api.assignedResult = Result.success(listOf(assigned("IN_PROGRESS")))
                api.startAttemptResult = Result.success(attempt(next = 2))
                listOf(
                    draft(position = 1),
                    draft(position = 2, attemptId = "attempt-9"),
                    draft(position = 2, ownerUserId = "student_002"),
                ).forEach { stale ->
                    val drafts = FakeDraftStore(stale)
                    val coordinator = coordinator(api, drafts = drafts)
                    coordinator.resume()
                    advanceUntilIdle()
                    coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
                    drafts.draft shouldBe null
                }
            }
        }

        test("resume never overrides a sentence in progress") {
            runTest {
                val api = FakeSentenceTestApi()
                val coordinator = writing(1, api)
                coordinator.onTextChanged("hola")
                val before = coordinator.state.value
                val calls = api.calls.size
                coordinator.resume()
                advanceUntilIdle()
                coordinator.state.value shouldBe before
                api.calls.size shouldBe calls
            }
        }

        test("another student logging in on the same phone never sees the previous sentence") {
            runTest {
                val api = FakeSentenceTestApi()
                val drafts = FakeDraftStore()
                var current: EducationalSession? = session
                api.startAttemptResult = Result.success(attempt(next = 1))
                val coordinator = coordinator(api, drafts = drafts, currentSession = { current })
                coordinator.loadTests()
                advanceUntilIdle()
                coordinator.start(assigned())
                advanceUntilIdle()
                coordinator.pressStart()
                advanceUntilIdle()
                coordinator.onTextChanged("texto de student_001")
                drafts.draft?.ownerUserId shouldBe "student_001"

                // student_001 pierde la sesión (401) y student_002 entra en el mismo teléfono.
                current = null
                coordinator.resume()
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<SentenceTestState.Writing>()
                current = EducationalSession("student_002", "jwt-2", "2099-01-01T00:00:00Z")
                api.assignedResult = Result.success(listOf(assigned()))
                val calls = api.calls.size
                coordinator.resume()
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Idle
                coordinator.inProgress() shouldBe false
                coordinator.correctionAllowed() shouldBe true
                drafts.draft shouldBe null
                api.calls.drop(calls) shouldBe listOf("assigned")
            }
        }

        test("the owner change also drops an operation in flight") {
            runTest {
                val api = FakeSentenceTestApi()
                var current: EducationalSession? = session
                val coordinator = coordinator(api, currentSession = { current })
                coordinator.loadTests()
                // Sin avanzar: `assigned` sigue en vuelo cuando entra el otro alumno.
                coordinator.state.value shouldBe SentenceTestState.LoadingTests
                current = EducationalSession("student_002", "jwt-2", "2099-01-01T00:00:00Z")
                api.assignedResult = Result.success(listOf(assigned("IN_PROGRESS")))
                api.startAttemptResult = Result.success(attempt(next = 2))
                coordinator.resume()
                advanceUntilIdle()
                // Solo cuenta la reanudación del segundo alumno (la lista del primero se canceló).
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
                api.calls.last() shouldBe "startAttempt:test-1:1.2.3"
                api.calls.count { it == "startAttempt:test-1:1.2.3" } shouldBe 1
            }
        }

        test("a failed cancel keeps the sentence with a transient error; a closed attempt counts as cancelled") {
            runTest {
                val api = FakeSentenceTestApi()
                api.cancelResult = Result.failure(SocketTimeoutException("timeout"))
                val coordinator = writing(1, api)
                coordinator.onTextChanged("hola")
                val before = coordinator.state.value
                coordinator.cancel(AttemptCancelReason.ABANDONED)
                advanceUntilIdle()
                coordinator.state.value shouldBe before
                coordinator.lastError.value shouldNotBe null

                api.cancelResult = Result.failure(EducationalHttpException(409, "Attempt is not in progress"))
                coordinator.cancel(AttemptCancelReason.ABANDONED)
                advanceUntilIdle()
                coordinator.state.value shouldBe SentenceTestState.Cancelled
            }
        }

        test("clear forgets everything, including the draft") {
            runTest {
                val drafts = FakeDraftStore()
                val coordinator = writing(1, drafts = drafts)
                coordinator.onTextChanged("hola")
                coordinator.clear()
                coordinator.state.value shouldBe SentenceTestState.Idle
                drafts.draft shouldBe null
                coordinator.correctionAllowed() shouldBe true
            }
        }
    }

    context("8. counters") {
        test("suggestion events only count while writing") {
            runTest {
                val api = FakeSentenceTestApi()
                val coordinator = atSentence(api)
                coordinator.onSuggestionsOffered()
                coordinator.onSuggestionAccepted()
                coordinator.onSuggestionRejected()
                coordinator.onSuggestionUndone()
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 1), 1)

                coordinator.pressStart()
                advanceUntilIdle()
                coordinator.onSuggestionsOffered()
                coordinator.onSuggestionAccepted()
                coordinator.state.value.shouldBeInstanceOf<SentenceTestState.Writing>().counters shouldBe
                    SuggestionCounters(offered = 1, accepted = 1)

                coordinator.finish()
                advanceUntilIdle()
                api.finished.single().suggestionsOffered shouldBe 1
                api.finished.single().suggestionsAccepted shouldBe 1

                // Tras terminar, en AtSentence(2), los eventos vuelven a ignorarse.
                coordinator.onSuggestionsOffered()
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
                coordinator.onTextChanged("x")
                coordinator.state.value shouldBe SentenceTestState.AtSentence(attempt(next = 2), 2)
            }
        }
    }
})
