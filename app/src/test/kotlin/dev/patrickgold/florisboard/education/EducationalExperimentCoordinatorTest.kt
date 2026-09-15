package dev.patrickgold.florisboard.education

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class EducationalExperimentCoordinatorTest : FunSpec({
    val session = EducationalSession("student_001", "jwt", "2099-01-01T00:00:00Z")

    fun run(condition: ExperimentCondition, status: String = "ACTIVE") = ExperimentRunResponse(
        id = "run-1",
        participantCode = "P01",
        condition = condition,
        taskVariant = "A",
        promptText = "Describe tu fin de semana.",
        status = status,
    )

    fun marker(
        condition: ExperimentCondition = ExperimentCondition.ASSISTED,
        status: String = "ACTIVE",
        firstKeyAtMs: Long? = null,
        bootId: String = "boot-1",
        runId: String = "run-1",
    ) = ExperimentMarker(runId, condition, status, firstKeyAtMs, bootId)

    class FakeElapsedClock(var value: Long) {
        fun now(): Long = value
    }

    class FakeMarkerStore(var marker: ExperimentMarker? = null) : ExperimentMarkerStore {
        val saved = mutableListOf<ExperimentMarker>()
        var clears = 0
        override fun load(): ExperimentMarker? = marker
        override fun save(marker: ExperimentMarker) {
            this.marker = marker
            saved += marker
        }
        override fun clear() {
            marker = null
            clears++
        }
    }

    data class SentCompletion(
        val runId: String,
        val text: String,
        val durationMs: Long,
        val completionKey: String,
        val appVersion: String,
    )

    class FakeExperimentApi(
        var activeRun: ExperimentRunResponse? = null,
        var activeResult: Result<ExperimentRunResponse?>? = null,
        var redeemResult: Result<ExperimentRunResponse>? = null,
        var startResult: Result<ExperimentRunResponse>? = null,
        var completeResult: Result<ExperimentRunResponse>? = null,
        var failFirstCompletion: Boolean = false,
        var completeGate: CompletableDeferred<Result<ExperimentRunResponse>>? = null,
        var cancelResult: Result<Unit> = Result.success(Unit),
    ) : EducationalExperimentApi {
        val redeemedCodes = mutableListOf<String>()
        val startedRunIds = mutableListOf<String>()
        var activeCalls = 0
        val sent = mutableListOf<SentCompletion>()
        val completionKeys get() = sent.map { it.completionKey }
        val lastDurationMs get() = sent.lastOrNull()?.durationMs
        val lastFinalText get() = sent.lastOrNull()?.text
        val lastAppVersion get() = sent.lastOrNull()?.appVersion
        val cancelReasons = mutableListOf<CancelReason>()

        override suspend fun redeem(token: String, code: String): Result<ExperimentRunResponse> {
            redeemedCodes += code
            return redeemResult ?: Result.success(run(ExperimentCondition.ASSISTED, status = "PENDING"))
        }

        override suspend fun start(token: String, runId: String): Result<ExperimentRunResponse> {
            startedRunIds += runId
            return startResult ?: Result.success(run(ExperimentCondition.ASSISTED))
        }

        override suspend fun active(token: String): Result<ExperimentRunResponse?> {
            activeCalls++
            return activeResult ?: Result.success(activeRun)
        }

        override suspend fun complete(
            token: String,
            runId: String,
            finalText: String,
            durationMs: Long,
            completionKey: String,
            appVersion: String,
        ): Result<ExperimentRunResponse> {
            sent += SentCompletion(runId, finalText, durationMs, completionKey, appVersion)
            completeGate?.let { return it.await() }
            completeResult?.let { return it }
            if (failFirstCompletion && sent.size == 1) {
                return Result.failure(SocketTimeoutException("timeout"))
            }
            return Result.success(run(ExperimentCondition.ASSISTED, status = "COMPLETED"))
        }

        override suspend fun cancel(token: String, runId: String, reason: CancelReason): Result<Unit> {
            cancelReasons += reason
            return cancelResult
        }
    }

    fun TestScope.coordinator(
        api: FakeExperimentApi = FakeExperimentApi(),
        clock: FakeElapsedClock = FakeElapsedClock(1_000),
        store: FakeMarkerStore = FakeMarkerStore(),
        bootId: String = "boot-1",
        currentSession: () -> EducationalSession? = { session },
    ) = EducationalExperimentCoordinator(
        scope = this,
        api = api,
        session = currentSession,
        elapsedRealtime = clock::now,
        appVersion = "1.2.3",
        store = store,
        bootId = { bootId },
    )

    /** Coordinator that redeemed and started an ACTIVE run of [condition], ready for the student to type. */
    fun TestScope.readyCoordinator(
        condition: ExperimentCondition = ExperimentCondition.ASSISTED,
        api: FakeExperimentApi = FakeExperimentApi(),
        clock: FakeElapsedClock = FakeElapsedClock(1_000),
        store: FakeMarkerStore = FakeMarkerStore(),
        currentSession: () -> EducationalSession? = { session },
    ): EducationalExperimentCoordinator {
        if (api.startResult == null) api.startResult = Result.success(run(condition))
        val coordinator = coordinator(api, clock, store, currentSession = currentSession)
        coordinator.redeem("ABCDEFGH")
        advanceUntilIdle()
        coordinator.start()
        advanceUntilIdle()
        coordinator.state.value shouldBe EducationalExperimentState.Active(run(condition), firstKeyAtMs = null)
        return coordinator
    }

    context("correction gates") {
        test("unassisted run blocks correction and exposes no run id") {
            runTest {
                val coordinator = readyCoordinator(ExperimentCondition.UNASSISTED)
                coordinator.correctionAllowed() shouldBe false
                coordinator.activeRunId() shouldBe null
            }
        }

        test("assisted run exposes its id") {
            runTest {
                val coordinator = readyCoordinator(ExperimentCondition.ASSISTED)
                coordinator.correctionAllowed() shouldBe true
                coordinator.activeRunId() shouldBe "run-1"
            }
        }

        test("without a run correction works as always") {
            runTest {
                val coordinator = coordinator()
                coordinator.correctionAllowed() shouldBe true
                coordinator.activeRunId() shouldBe null
            }
        }

        test("a redeemed but not started run neither blocks nor exposes an id") {
            runTest {
                val api = FakeExperimentApi(activeRun = run(ExperimentCondition.UNASSISTED, status = "PENDING"))
                val coordinator = coordinator(api)
                coordinator.restore()
                advanceUntilIdle()
                coordinator.state.value shouldBe EducationalExperimentState.Ready(run(ExperimentCondition.UNASSISTED, "PENDING"))
                coordinator.correctionAllowed() shouldBe true
                coordinator.activeRunId() shouldBe null

                api.activeRun = run(ExperimentCondition.ASSISTED, status = "PENDING")
                coordinator.clear()
                coordinator.restore()
                advanceUntilIdle()
                coordinator.activeRunId() shouldBe null
            }
        }

        test("failed completion keeps an unassisted run blocked") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(ExperimentCondition.UNASSISTED, FakeExperimentApi(failFirstCompletion = true), clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                coordinator.correctionAllowed() shouldBe false
                coordinator.activeRunId() shouldBe null
            }
        }

        test("while a completion is in flight both conditions are blocked and no id is exposed") {
            runTest(StandardTestDispatcher()) {
                val gate = CompletableDeferred<Result<ExperimentRunResponse>>()
                val api = FakeExperimentApi(completeGate = gate)
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(ExperimentCondition.ASSISTED, api, clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final") shouldBe true
                testScheduler.runCurrent()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completing>()
                coordinator.activeRunId() shouldBe null
                coordinator.correctionAllowed() shouldBe false

                gate.complete(Result.success(run(ExperimentCondition.ASSISTED, "COMPLETED")))
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
                coordinator.activeRunId() shouldBe null
                coordinator.correctionAllowed() shouldBe true
            }
        }

        test("a network failure of the completion keeps an assisted run blocked without an id until it is confirmed") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val api = FakeExperimentApi(failFirstCompletion = true)
                val coordinator = readyCoordinator(ExperimentCondition.ASSISTED, api, clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.run?.status shouldBe "ACTIVE"
                coordinator.activeRunId() shouldBe null
                coordinator.correctionAllowed() shouldBe false

                coordinator.retry()
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
                coordinator.correctionAllowed() shouldBe true
            }
        }

        test("a 409 closes the run locally so corrections work again and nothing is resent") {
            runTest {
                val api = FakeExperimentApi(completeResult = Result.failure(EducationalHttpException(409, "done")))
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(ExperimentCondition.UNASSISTED, api, clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.run?.status shouldBe "CLOSED"
                failed.retryable shouldBe false
                failed.pendingCompletion?.text shouldBe "Texto final"
                coordinator.activeRunId() shouldBe null
                coordinator.correctionAllowed() shouldBe true

                coordinator.retry()
                advanceUntilIdle()
                api.sent.size shouldBe 1
            }
        }

        test("a 401 keeps the run active and blocked until the completion is retried after re-login") {
            runTest {
                val api = FakeExperimentApi(completeResult = Result.failure(EducationalHttpException(401, "expired")))
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(ExperimentCondition.ASSISTED, api, clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.run?.status shouldBe "ACTIVE"
                failed.message shouldBe EducationalMessages.SessionExpired
                failed.retryable shouldBe true
                coordinator.activeRunId() shouldBe null
                coordinator.correctionAllowed() shouldBe false

                api.completeResult = null
                coordinator.retry()
                advanceUntilIdle()
                api.completionKeys.distinct().size shouldBe 1
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }

        test("a completed run stops gating correction") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(ExperimentCondition.UNASSISTED, clock = clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
                coordinator.correctionAllowed() shouldBe true
                coordinator.activeRunId() shouldBe null
            }
        }
    }

    context("redeem") {
        test("normalises the code and reaches Ready with the backend run") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                coordinator.redeem("  ab cd-ef gh ")
                advanceUntilIdle()
                api.redeemedCodes shouldBe listOf("ABCDEFGH")
                coordinator.state.value shouldBe
                    EducationalExperimentState.Ready(run(ExperimentCondition.ASSISTED, status = "PENDING"))
            }
        }

        test("rejects ambiguous or short codes locally without calling the backend") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                for (code in listOf("", "ABCDEFG", "ABCDEFGHI", "ABCDEFG0", "ABCDEFGO", "ABCDEFGI", "ABCDEFG1", "ABCD EF!")) {
                    coordinator.redeem(code)
                    advanceUntilIdle()
                    coordinator.state.value shouldBe
                        EducationalExperimentState.Failed(null, EducationalMessages.InvalidAccessCode, retryable = false)
                }
                api.redeemedCodes shouldBe emptyList()
            }
        }

        test("redeeming the same code twice is idempotent and just returns to Ready") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                api.redeemedCodes shouldBe listOf("ABCDEFGH", "ABCDEFGH")
                coordinator.state.value shouldBe
                    EducationalExperimentState.Ready(run(ExperimentCondition.ASSISTED, status = "PENDING"))
            }
        }

        test("backend errors are translated with the experiment catalogue") {
            runTest {
                val api = FakeExperimentApi(redeemResult = Result.failure(EducationalHttpException(400, "bad")))
                val coordinator = coordinator(api)
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                coordinator.state.value shouldBe
                    EducationalExperimentState.Failed(null, EducationalMessages.InvalidAccessCode, retryable = false)

                api.redeemResult = Result.failure(SocketTimeoutException("timeout"))
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.message shouldBe EducationalMessages.experiment(SocketTimeoutException("timeout"))
                failed.retryable shouldBe true
            }
        }

        test("a failed redeem from Ready keeps the already redeemed run") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                val runA = run(ExperimentCondition.ASSISTED, status = "PENDING")
                coordinator.state.value shouldBe EducationalExperimentState.Ready(runA)

                api.redeemResult = Result.failure(SocketTimeoutException("timeout"))
                coordinator.redeem("BCDEFGHJ")
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.run shouldBe runA
                failed.retryable shouldBe true
                failed.pendingCompletion shouldBe null

                coordinator.start()
                advanceUntilIdle()
                api.startedRunIds shouldBe listOf("run-1")
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Active>()
            }
        }

        test("without a session nothing is sent") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api, currentSession = { null })
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                api.redeemedCodes shouldBe emptyList()
                coordinator.state.value shouldBe
                    EducationalExperimentState.Failed(null, EducationalMessages.NoSession, retryable = false)
            }
        }

        test("a second redeem while redeeming is ignored") {
            runTest(StandardTestDispatcher()) {
                val gate = CompletableDeferred<Result<ExperimentRunResponse>>()
                val api = object : EducationalExperimentApi by FakeExperimentApi() {
                    var calls = 0
                    override suspend fun redeem(token: String, code: String): Result<ExperimentRunResponse> {
                        calls++
                        return gate.await()
                    }
                }
                val coordinator = EducationalExperimentCoordinator(
                    this, api, { session }, { 0L }, "1.2.3", FakeMarkerStore(), { "boot-1" },
                )
                coordinator.redeem("ABCDEFGH")
                testScheduler.runCurrent()
                coordinator.state.value shouldBe EducationalExperimentState.Redeeming
                coordinator.redeem("ABCDEFGH")
                testScheduler.runCurrent()
                api.calls shouldBe 1
                gate.complete(Result.success(run(ExperimentCondition.ASSISTED, "PENDING")))
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Ready>()
            }
        }
    }

    context("start") {
        test("waits for the backend and becomes Active with the returned run") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                coordinator.start()
                advanceUntilIdle()
                api.startedRunIds shouldBe listOf("run-1")
                coordinator.state.value shouldBe EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = null)
            }
        }

        test("start failure keeps the pending run so it can be retried") {
            runTest {
                val api = FakeExperimentApi(startResult = Result.failure(SocketTimeoutException("timeout")))
                val coordinator = coordinator(api)
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                coordinator.start()
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.run shouldBe run(ExperimentCondition.ASSISTED, "PENDING")
                failed.retryable shouldBe true
                failed.pendingCompletion shouldBe null

                api.startResult = null
                coordinator.retry()
                advanceUntilIdle()
                api.startedRunIds shouldBe listOf("run-1", "run-1")
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Active>()
            }
        }

        test("start is ignored outside Ready") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                coordinator.start()
                advanceUntilIdle()
                api.startedRunIds shouldBe emptyList()
                coordinator.state.value shouldBe EducationalExperimentState.Idle
            }
        }
    }

    context("duration and completion") {
        test("markFirstKey is idempotent and uses the monotonic clock") {
            runTest {
                val clock = FakeElapsedClock(5_000)
                val coordinator = readyCoordinator(clock = clock)
                coordinator.markFirstKey()
                clock.value = 9_000
                coordinator.markFirstKey()
                coordinator.state.value shouldBe EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = 5_000)
            }
        }

        test("markFirstKey does nothing before the run is active") {
            runTest {
                val coordinator = coordinator()
                coordinator.markFirstKey()
                coordinator.state.value shouldBe EducationalExperimentState.Idle
            }
        }

        test("complete sends text, duration, one key and the app version") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val api = FakeExperimentApi()
                val coordinator = readyCoordinator(api = api, clock = clock)
                coordinator.markFirstKey()
                clock.value = 31_500
                coordinator.complete("  Texto final  ") shouldBe true
                advanceUntilIdle()
                api.lastFinalText shouldBe "Texto final"
                api.lastDurationMs shouldBe 30_500
                api.lastAppVersion shouldBe "1.2.3"
                api.completionKeys.size shouldBe 1
                api.completionKeys.single().isNotBlank() shouldBe true
                coordinator.state.value shouldBe
                    EducationalExperimentState.Completed(run(ExperimentCondition.ASSISTED, status = "COMPLETED"))
            }
        }

        test("complete refuses blank text or a run without a first key") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = readyCoordinator(api = api)
                coordinator.complete("Texto final") shouldBe false
                coordinator.markFirstKey()
                coordinator.complete("   ") shouldBe false
                advanceUntilIdle()
                api.completionKeys shouldBe emptyList()
                coordinator.state.value shouldBe EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = 1_000)
            }
        }

        test("complete refuses a zero or negative duration and never coerces it") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val api = FakeExperimentApi()
                val coordinator = readyCoordinator(api = api, clock = clock)
                coordinator.markFirstKey()
                coordinator.complete("Texto final") shouldBe false
                clock.value = 999
                coordinator.complete("Texto final") shouldBe false
                advanceUntilIdle()
                api.sent shouldBe emptyList()
                coordinator.state.value shouldBe EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = 1_000)

                clock.value = 1_001
                coordinator.complete("Texto final") shouldBe true
                advanceUntilIdle()
                api.lastDurationMs shouldBe 1
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }

        test("retry reuses duration and completion key") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val api = FakeExperimentApi(failFirstCompletion = true)
                val coordinator = readyCoordinator(api = api, clock = clock)
                coordinator.markFirstKey()
                clock.value = 61_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                clock.value = 99_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                api.lastDurationMs shouldBe 60_000
                api.completionKeys.size shouldBe 2
                api.completionKeys.distinct().size shouldBe 1
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }

        test("the retried payload is immutable even if the text was edited in between") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val api = FakeExperimentApi(failFirstCompletion = true)
                val coordinator = readyCoordinator(api = api, clock = clock)
                coordinator.markFirstKey()
                clock.value = 61_000
                coordinator.complete("Texto final") shouldBe true
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                clock.value = 99_000
                coordinator.complete("Texto final editado") shouldBe true
                advanceUntilIdle()
                api.sent.size shouldBe 2
                api.sent[1] shouldBe api.sent[0]
                api.sent[1].text shouldBe "Texto final"
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }

        test("a failed completion keeps run, text, duration and key for Reintentar") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val api = FakeExperimentApi(failFirstCompletion = true)
                val coordinator = readyCoordinator(ExperimentCondition.UNASSISTED, api, clock)
                coordinator.markFirstKey()
                clock.value = 61_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.run shouldBe run(ExperimentCondition.UNASSISTED)
                failed.message shouldBe EducationalMessages.experiment(SocketTimeoutException("timeout"))
                failed.retryable shouldBe true
                failed.pendingCompletion shouldBe
                    PendingCompletion("Texto final", 60_000, api.completionKeys.single())

                coordinator.retry()
                advanceUntilIdle()
                api.completionKeys.distinct().size shouldBe 1
                api.lastFinalText shouldBe "Texto final"
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }

        test("a terminal backend answer is not retryable but the text is still kept") {
            runTest {
                val api = FakeExperimentApi(completeResult = Result.failure(EducationalHttpException(409, "done")))
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(api = api, clock = clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.message shouldBe EducationalMessages.experiment(EducationalHttpException(409, "done"))
                failed.retryable shouldBe false
                failed.pendingCompletion?.text shouldBe "Texto final"
            }
        }

        test("retry is a no-op when the failure is not retryable") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                coordinator.redeem("BAD")
                advanceUntilIdle()
                coordinator.state.value shouldBe
                    EducationalExperimentState.Failed(null, EducationalMessages.InvalidAccessCode, retryable = false)
                coordinator.retry()
                advanceUntilIdle()
                api.activeCalls shouldBe 0
                coordinator.state.value shouldBe
                    EducationalExperimentState.Failed(null, EducationalMessages.InvalidAccessCode, retryable = false)
            }
        }

        test("a second complete while completing is ignored") {
            runTest(StandardTestDispatcher()) {
                val gate = CompletableDeferred<Result<ExperimentRunResponse>>()
                val api = FakeExperimentApi(completeGate = gate)
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(api = api, clock = clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final") shouldBe true
                testScheduler.runCurrent()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completing>()
                coordinator.complete("Texto final") shouldBe false
                testScheduler.runCurrent()
                api.completionKeys.size shouldBe 1
                gate.complete(Result.success(run(ExperimentCondition.ASSISTED, "COMPLETED")))
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }

        test("two threads completing at once produce a single completion key") {
            repeat(10) {
                val gate = CompletableDeferred<Result<ExperimentRunResponse>>()
                val api = FakeExperimentApi(completeGate = gate)
                val clock = FakeElapsedClock(1_000)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val coordinator = EducationalExperimentCoordinator(
                    scope, api, { session }, clock::now, "1.2.3", FakeMarkerStore(), { "boot-1" },
                )
                coordinator.redeem("ABCDEFGH")
                withTimeout(5_000) { coordinator.state.first { it is EducationalExperimentState.Ready } }
                coordinator.start()
                withTimeout(5_000) { coordinator.state.first { it is EducationalExperimentState.Active } }
                coordinator.markFirstKey()
                clock.value = 2_000

                val armed = CountDownLatch(2)
                val go = CountDownLatch(1)
                val accepted = AtomicInteger()
                val threads = List(2) {
                    thread {
                        armed.countDown()
                        go.await()
                        if (coordinator.complete("Texto final")) accepted.incrementAndGet()
                    }
                }
                armed.await()
                go.countDown()
                threads.forEach { it.join() }
                accepted.get() shouldBe 1
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completing>()

                gate.complete(Result.success(run(ExperimentCondition.ASSISTED, "COMPLETED")))
                withTimeout(5_000) { coordinator.state.first { it is EducationalExperimentState.Completed } }
                api.completionKeys.size shouldBe 1
                scope.cancel()
            }
        }

        test("an expired session keeps the completion pending without calling the backend") {
            runTest {
                var current: EducationalSession? = session
                val api = FakeExperimentApi()
                val clock = FakeElapsedClock(1_000)
                val coordinator = readyCoordinator(api = api, clock = clock) { current }
                coordinator.markFirstKey()
                clock.value = 4_000
                current = null
                coordinator.complete("Texto final")
                advanceUntilIdle()
                api.completionKeys shouldBe emptyList()
                val failed = coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                failed.message shouldBe EducationalMessages.SessionExpired
                failed.retryable shouldBe true
                failed.pendingCompletion?.durationMs shouldBe 3_000
                coordinator.correctionAllowed() shouldBe false

                current = session
                coordinator.retry()
                advanceUntilIdle()
                api.lastDurationMs shouldBe 3_000
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }
    }

    context("experiment marker") {
        test("redeem, start and the first key persist the marker with the boot id") {
            runTest {
                val store = FakeMarkerStore()
                val clock = FakeElapsedClock(7_000)
                val coordinator = coordinator(clock = clock, store = store, bootId = "boot-42")
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                store.marker shouldBe marker(status = "PENDING", bootId = "boot-42")
                coordinator.start()
                advanceUntilIdle()
                store.marker shouldBe marker(status = "ACTIVE", bootId = "boot-42")
                coordinator.markFirstKey()
                store.marker shouldBe marker(status = "ACTIVE", firstKeyAtMs = 7_000, bootId = "boot-42")
                store.clears shouldBe 0
            }
        }

        test("the marker is cleared on completion, cancellation, logout and when nothing is active") {
            runTest {
                val completedStore = FakeMarkerStore()
                val clock = FakeElapsedClock(1_000)
                val completed = readyCoordinator(clock = clock, store = completedStore)
                completed.markFirstKey()
                clock.value = 2_000
                completed.complete("Texto final")
                advanceUntilIdle()
                completed.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
                completedStore.marker shouldBe null

                val cancelledStore = FakeMarkerStore()
                val cancelled = readyCoordinator(store = cancelledStore)
                cancelled.cancel(CancelReason.ABANDONED)
                advanceUntilIdle()
                cancelled.state.value shouldBe EducationalExperimentState.Cancelled
                cancelledStore.marker shouldBe null

                val clearedStore = FakeMarkerStore()
                val cleared = readyCoordinator(store = clearedStore)
                cleared.clear()
                clearedStore.marker shouldBe null

                val staleStore = FakeMarkerStore(marker(status = "ACTIVE", firstKeyAtMs = 10))
                val stale = coordinator(FakeExperimentApi(activeRun = null), store = staleStore)
                stale.restore()
                advanceUntilIdle()
                stale.state.value shouldBe EducationalExperimentState.Idle
                staleStore.marker shouldBe null
            }
        }

        test("restore within the same boot recovers the first key and the whole duration") {
            runTest {
                val api = FakeExperimentApi(activeRun = run(ExperimentCondition.ASSISTED))
                val store = FakeMarkerStore(marker(firstKeyAtMs = 400, bootId = "boot-1"))
                val clock = FakeElapsedClock(1_000)
                val coordinator = coordinator(api, clock, store, bootId = "boot-1")
                coordinator.restore()
                advanceUntilIdle()
                coordinator.state.value shouldBe
                    EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = 400, timerLost = false)
                coordinator.completionBlockedReason() shouldBe null
                coordinator.markFirstKey()
                coordinator.complete("Texto final") shouldBe true
                advanceUntilIdle()
                api.lastDurationMs shouldBe 600
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
            }
        }

        test("restore after a reboot loses the timer and refuses completion") {
            runTest {
                val api = FakeExperimentApi(activeRun = run(ExperimentCondition.UNASSISTED))
                val store = FakeMarkerStore(marker(ExperimentCondition.UNASSISTED, firstKeyAtMs = 400, bootId = "boot-1"))
                val clock = FakeElapsedClock(1_000)
                val coordinator = coordinator(api, clock, store, bootId = "boot-2")
                coordinator.restore()
                advanceUntilIdle()
                coordinator.state.value shouldBe
                    EducationalExperimentState.Active(run(ExperimentCondition.UNASSISTED), firstKeyAtMs = null, timerLost = true)
                coordinator.completionBlockedReason() shouldBe EducationalMessages.TimerLost
                coordinator.correctionAllowed() shouldBe false

                coordinator.markFirstKey()
                clock.value = 5_000
                coordinator.complete("Texto final") shouldBe false
                api.sent shouldBe emptyList()

                coordinator.cancel(CancelReason.TECHNICAL_PROBLEM)
                advanceUntilIdle()
                api.cancelReasons shouldBe listOf(CancelReason.TECHNICAL_PROBLEM)
                coordinator.state.value shouldBe EducationalExperimentState.Cancelled
                store.marker shouldBe null
            }
        }

        test("restore without a first key or for another run also loses the timer") {
            runTest {
                val noKey = coordinator(
                    FakeExperimentApi(activeRun = run(ExperimentCondition.ASSISTED)),
                    store = FakeMarkerStore(marker(firstKeyAtMs = null)),
                )
                noKey.restore()
                advanceUntilIdle()
                noKey.state.value shouldBe
                    EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = null, timerLost = true)

                val otherRun = coordinator(
                    FakeExperimentApi(activeRun = run(ExperimentCondition.ASSISTED)),
                    store = FakeMarkerStore(marker(firstKeyAtMs = 400, runId = "run-0")),
                )
                otherRun.restore()
                advanceUntilIdle()
                otherRun.state.value shouldBe
                    EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = null, timerLost = true)
            }
        }

        test("a restore failure with a marker keeps an unassisted run blocked and can be retried") {
            runTest {
                val api = FakeExperimentApi(
                    activeRun = run(ExperimentCondition.UNASSISTED),
                    activeResult = Result.failure(SocketTimeoutException("timeout")),
                )
                val store = FakeMarkerStore(marker(ExperimentCondition.UNASSISTED, firstKeyAtMs = 400))
                val coordinator = coordinator(api, store = store)
                coordinator.restore()
                advanceUntilIdle()
                coordinator.state.value shouldBe EducationalExperimentState.Failed(
                    ExperimentRunResponse(
                        id = "run-1", participantCode = "", condition = ExperimentCondition.UNASSISTED,
                        taskVariant = "", promptText = "", status = "ACTIVE",
                    ),
                    EducationalMessages.experiment(SocketTimeoutException("timeout")),
                    retryable = true,
                )
                coordinator.correctionAllowed() shouldBe false
                coordinator.activeRunId() shouldBe null
                store.marker shouldBe marker(ExperimentCondition.UNASSISTED, firstKeyAtMs = 400)

                api.activeResult = null
                coordinator.retry()
                advanceUntilIdle()
                api.activeCalls shouldBe 2
                coordinator.state.value shouldBe
                    EducationalExperimentState.Active(run(ExperimentCondition.UNASSISTED), firstKeyAtMs = 400)
            }
        }

        test("a restore failure without a marker never blocks normal use") {
            runTest {
                val api = FakeExperimentApi(activeResult = Result.failure(SocketTimeoutException("timeout")))
                val coordinator = coordinator(api)
                coordinator.restore()
                advanceUntilIdle()
                coordinator.state.value shouldBe EducationalExperimentState.Idle
                coordinator.correctionAllowed() shouldBe true
            }
        }
    }

    context("restore") {
        test("nothing to restore leaves Idle") {
            runTest {
                val api = FakeExperimentApi(activeRun = null)
                val coordinator = coordinator(api)
                coordinator.restore()
                advanceUntilIdle()
                api.activeCalls shouldBe 1
                coordinator.state.value shouldBe EducationalExperimentState.Idle
            }
        }

        test("an ACTIVE run without a marker is restored with the timer lost and a PENDING run as Ready") {
            runTest {
                val api = FakeExperimentApi(activeRun = run(ExperimentCondition.UNASSISTED))
                val coordinator = coordinator(api)
                coordinator.restore()
                advanceUntilIdle()
                coordinator.state.value shouldBe
                    EducationalExperimentState.Active(run(ExperimentCondition.UNASSISTED), firstKeyAtMs = null, timerLost = true)

                coordinator.clear()
                api.activeRun = run(ExperimentCondition.UNASSISTED, status = "PENDING")
                coordinator.restore()
                advanceUntilIdle()
                coordinator.state.value shouldBe EducationalExperimentState.Ready(run(ExperimentCondition.UNASSISTED, "PENDING"))
            }
        }

        test("restore never resets a run the student is already typing in") {
            runTest {
                val api = FakeExperimentApi(activeRun = run(ExperimentCondition.ASSISTED))
                val coordinator = readyCoordinator(api = api)
                coordinator.markFirstKey()
                coordinator.restore()
                advanceUntilIdle()
                api.activeCalls shouldBe 0
                coordinator.state.value shouldBe EducationalExperimentState.Active(run(ExperimentCondition.ASSISTED), firstKeyAtMs = 1_000)
            }
        }

        test("without a session restore stays Idle without calling the backend") {
            runTest {
                val api = FakeExperimentApi(activeRun = run(ExperimentCondition.ASSISTED))
                val coordinator = coordinator(api, currentSession = { null })
                coordinator.restore()
                advanceUntilIdle()
                api.activeCalls shouldBe 0
                coordinator.state.value shouldBe EducationalExperimentState.Idle
            }
        }
    }

    context("cancel and clear") {
        test("cancel from Active sends the reason and clears the run") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = readyCoordinator(ExperimentCondition.UNASSISTED, api)
                coordinator.cancel(CancelReason.ABANDONED)
                advanceUntilIdle()
                api.cancelReasons shouldBe listOf(CancelReason.ABANDONED)
                coordinator.state.value shouldBe EducationalExperimentState.Cancelled
                coordinator.correctionAllowed() shouldBe true
            }
        }

        test("cancel clears local state even when the backend refuses") {
            runTest {
                val api = FakeExperimentApi(cancelResult = Result.failure(EducationalHttpException(404, "gone")))
                val coordinator = coordinator(api)
                coordinator.redeem("ABCDEFGH")
                advanceUntilIdle()
                coordinator.cancel(CancelReason.TECHNICAL_PROBLEM)
                advanceUntilIdle()
                api.cancelReasons shouldBe listOf(CancelReason.TECHNICAL_PROBLEM)
                coordinator.state.value shouldBe EducationalExperimentState.Cancelled
            }
        }

        test("cancel from a failed completion is allowed") {
            runTest {
                val clock = FakeElapsedClock(1_000)
                val api = FakeExperimentApi(failFirstCompletion = true)
                val coordinator = readyCoordinator(api = api, clock = clock)
                coordinator.markFirstKey()
                clock.value = 2_000
                coordinator.complete("Texto final")
                advanceUntilIdle()
                coordinator.state.value.shouldBeInstanceOf<EducationalExperimentState.Failed>()
                coordinator.cancel(CancelReason.INTERRUPTED)
                advanceUntilIdle()
                api.cancelReasons shouldBe listOf(CancelReason.INTERRUPTED)
                coordinator.state.value shouldBe EducationalExperimentState.Cancelled
            }
        }

        test("cancel is ignored without a run") {
            runTest {
                val api = FakeExperimentApi()
                val coordinator = coordinator(api)
                coordinator.cancel(CancelReason.ABANDONED)
                advanceUntilIdle()
                api.cancelReasons shouldBe emptyList()
                coordinator.state.value shouldBe EducationalExperimentState.Idle
            }
        }

        test("clear on logout drops everything") {
            runTest {
                val coordinator = readyCoordinator(ExperimentCondition.UNASSISTED)
                coordinator.markFirstKey()
                coordinator.clear()
                coordinator.state.value shouldBe EducationalExperimentState.Idle
                coordinator.correctionAllowed() shouldBe true
            }
        }
    }
})
