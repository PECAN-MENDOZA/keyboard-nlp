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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Orquestación de "Finalizar y guardar" (manager → cola de feedback → coordinador) sin Android:
 * la frontera se congela al tocar, el feedback se drena después y se envía exactamente lo congelado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentFinisherTest : FunSpec({
    val session = EducationalSession("student_001", "jwt", "2099-01-01T00:00:00Z")

    fun run(status: String) = ExperimentRunResponse(
        id = "run-1", participantCode = "P01", condition = ExperimentCondition.ASSISTED,
        taskVariant = "A", promptText = "Describe tu fin de semana.", status = status,
    )

    class FakeClock(var value: Long)

    class FakeStore : ExperimentMarkerStore {
        var marker: ExperimentMarker? = null
        override fun load() = marker
        override fun save(marker: ExperimentMarker) { this.marker = marker }
        override fun clear() { marker = null }
    }

    data class Sent(val text: String, val durationMs: Long, val appVersion: String)

    /** Backend mínimo: canje y arranque inmediatos; la finalización queda registrada en [events]. */
    class FakeApi(val events: MutableList<String>) : EducationalExperimentApi {
        val sent = mutableListOf<Sent>()
        override suspend fun redeem(token: String, code: String) = Result.success(run("PENDING"))
        override suspend fun start(token: String, runId: String) = Result.success(run("ACTIVE"))
        override suspend fun active(token: String): Result<ExperimentRunResponse?> = Result.success(null)
        override suspend fun complete(
            token: String, runId: String, finalText: String, durationMs: Long, completionKey: String, appVersion: String,
        ): Result<ExperimentRunResponse> {
            sent += Sent(finalText, durationMs, appVersion)
            events += "complete"
            return Result.success(run("COMPLETED"))
        }
        override suspend fun cancel(token: String, runId: String, reason: CancelReason) = Result.success(Unit)
    }

    /** Tira de corrección falsa: cerrar una sugerencia o una edición encola su feedback, como el manager real. */
    class FakeStrip(
        var open: ExperimentFinisher.OpenCorrection,
        private val queue: FeedbackQueue,
        private val events: MutableList<String>,
        private val slowFeedback: CompletableDeferred<Unit>? = null,
    ) : ExperimentFinisher.CorrectionStrip {
        override fun openCorrection() = open
        override fun ignoreSuggestion() {
            open = ExperimentFinisher.OpenCorrection.NONE
            queue.enqueue { slowFeedback?.await(); events += "reject" }
        }
        override fun finishEdit() {
            open = ExperimentFinisher.OpenCorrection.NONE
            queue.enqueue { slowFeedback?.await(); events += "finishEdit" }
        }
        override fun dismiss() {
            open = ExperimentFinisher.OpenCorrection.NONE
            events += "dismiss"
        }
    }

    class Fixture(val scope: TestScope, open: ExperimentFinisher.OpenCorrection, slowFeedback: CompletableDeferred<Unit>? = null) {
        val events = mutableListOf<String>()
        val api = FakeApi(events)
        val clock = FakeClock(1_000)
        val store = FakeStore()
        val queue = FeedbackQueue(scope)
        val strip = FakeStrip(open, queue, events, slowFeedback)
        val experiment = EducationalExperimentCoordinator(
            scope = scope, api = api, session = { session }, elapsedRealtime = { clock.value },
            appVersion = "1.2.3", store = store, bootId = { "boot-1" },
        )
        val finisher = ExperimentFinisher(scope, strip, queue, experiment)

        /** Ejecución ACTIVE (canjeada e iniciada), con la primera tecla en t = 1 s si [firstKey]. */
        fun startTyping(firstKey: Boolean = true) {
            experiment.redeem("ABCDEFGH")
            scope.advanceUntilIdle()
            experiment.start()
            scope.advanceUntilIdle()
            if (firstKey) experiment.markFirstKey()
            experiment.state.value shouldBe EducationalExperimentState.Active(run("ACTIVE"), firstKeyAtMs = if (firstKey) 1_000 else null)
        }
    }

    test("the duration and the text are frozen at the tap even if a feedback takes 30 s to drain") {
        runTest(StandardTestDispatcher()) {
            val slow = CompletableDeferred<Unit>()
            val f = Fixture(this, ExperimentFinisher.OpenCorrection.SUGGESTIONS, slow)
            f.startTyping()
            f.clock.value = 31_000
            val result = async { f.finisher.finish("Texto A") }
            testScheduler.runCurrent()
            // Frontera publicada y persistida al tocar; el feedback sigue en vuelo, nada se ha enviado.
            f.experiment.state.value.shouldBeInstanceOf<EducationalExperimentState.Completing>().text shouldBe "Texto A"
            f.store.marker?.pendingCompletion?.durationMs shouldBe 30_000
            f.api.sent shouldBe emptyList()
            f.events shouldBe emptyList()
            result.isCompleted shouldBe false

            // Un segundo toque durante el drenaje no abre otra finalización.
            f.finisher.finish("Texto A editado") shouldBe false

            f.clock.value = 61_000
            slow.complete(Unit)
            advanceUntilIdle()
            result.await() shouldBe true
            f.events shouldBe listOf("reject", "complete")
            f.api.sent shouldBe listOf(Sent("Texto A", 30_000, "1.2.3"))
            f.experiment.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
        }
    }

    test("an open edit is closed and its feedback sent before the completion") {
        runTest(StandardTestDispatcher()) {
            val f = Fixture(this, ExperimentFinisher.OpenCorrection.EDITING)
            f.startTyping()
            f.clock.value = 5_000
            f.finisher.finish("Texto A") shouldBe true
            advanceUntilIdle()
            f.events shouldBe listOf("finishEdit", "complete")
            f.api.sent shouldBe listOf(Sent("Texto A", 4_000, "1.2.3"))
        }
    }

    test("an applied suggestion is just dismissed and nothing is frozen while a correction is processing") {
        runTest(StandardTestDispatcher()) {
            val applied = Fixture(this, ExperimentFinisher.OpenCorrection.APPLIED)
            applied.startTyping()
            applied.clock.value = 5_000
            applied.finisher.finish("Texto A") shouldBe true
            advanceUntilIdle()
            applied.events shouldBe listOf("dismiss", "complete")

            val processing = Fixture(this, ExperimentFinisher.OpenCorrection.PROCESSING)
            processing.startTyping()
            processing.clock.value = 5_000
            processing.finisher.finish("Texto A") shouldBe false
            processing.experiment.state.value shouldBe EducationalExperimentState.Active(run("ACTIVE"), firstKeyAtMs = 1_000)
            processing.store.marker?.status shouldBe "ACTIVE"
            processing.api.sent shouldBe emptyList()
            processing.events shouldBe emptyList()
        }
    }

    test("a refused completion closes the open suggestion but never drains or sends") {
        runTest(StandardTestDispatcher()) {
            val f = Fixture(this, ExperimentFinisher.OpenCorrection.SUGGESTIONS)
            f.startTyping(firstKey = false)
            // Sin primera tecla no hay duración que congelar.
            f.finisher.finish("Texto A") shouldBe false
            advanceUntilIdle()
            f.events shouldBe listOf("reject")
            f.api.sent shouldBe emptyList()
            f.experiment.state.value shouldBe EducationalExperimentState.Active(run("ACTIVE"), firstKeyAtMs = null)
        }
    }

    test("the frozen completion is still sent if the screen that tapped Finalizar goes away during the drain") {
        runTest(StandardTestDispatcher()) {
            val slow = CompletableDeferred<Unit>()
            val f = Fixture(this, ExperimentFinisher.OpenCorrection.SUGGESTIONS, slow)
            f.startTyping()
            f.clock.value = 11_000
            val screen = launch { f.finisher.finish("Texto A") }
            testScheduler.runCurrent()
            f.experiment.state.value.shouldBeInstanceOf<EducationalExperimentState.Completing>()
            screen.cancel()
            slow.complete(Unit)
            advanceUntilIdle()
            f.events shouldBe listOf("reject", "complete")
            f.api.sent shouldBe listOf(Sent("Texto A", 10_000, "1.2.3"))
            f.experiment.state.value.shouldBeInstanceOf<EducationalExperimentState.Completed>()
        }
    }
})
