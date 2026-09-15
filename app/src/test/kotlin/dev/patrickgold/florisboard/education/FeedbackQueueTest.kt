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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackQueueTest : FunSpec({
    test("drain waits for a slow feedback so that completion always comes after it") {
        runTest(StandardTestDispatcher()) {
            val queue = FeedbackQueue(this)
            val slow = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            queue.enqueue { slow.await(); events += "feedback" }
            val finishing = launch {
                queue.drain()
                events += "complete"
            }
            testScheduler.runCurrent()
            events shouldBe emptyList()
            finishing.isCompleted shouldBe false

            slow.complete(Unit)
            advanceUntilIdle()
            events shouldBe listOf("feedback", "complete")
        }
    }

    test("feedback runs in the order it was enqueued") {
        runTest(StandardTestDispatcher()) {
            val queue = FeedbackQueue(this)
            val first = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            queue.enqueue { first.await(); events += "accept" }
            queue.enqueue { events += "undo" }
            testScheduler.runCurrent()
            events shouldBe emptyList()
            first.complete(Unit)
            advanceUntilIdle()
            events shouldBe listOf("accept", "undo")
        }
    }

    test("drain also waits for feedback enqueued while draining") {
        runTest(StandardTestDispatcher()) {
            val queue = FeedbackQueue(this)
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            queue.enqueue { first.await(); events += "first" }
            val finishing = launch {
                queue.drain()
                events += "complete"
            }
            testScheduler.runCurrent()
            queue.enqueue { second.await(); events += "second" }
            first.complete(Unit)
            advanceUntilIdle()
            events shouldBe listOf("first")
            finishing.isCompleted shouldBe false

            second.complete(Unit)
            advanceUntilIdle()
            events shouldBe listOf("first", "second", "complete")
        }
    }

    test("a failing feedback never blocks the queue or the drain") {
        runTest(StandardTestDispatcher()) {
            val queue = FeedbackQueue(this)
            val events = mutableListOf<String>()
            queue.enqueue { throw IllegalStateException("boom") }
            queue.enqueue { events += "next" }
            queue.drain()
            events shouldBe listOf("next")
        }
    }

    test("drain returns at once when nothing is queued") {
        runTest {
            FeedbackQueue(this).drain()
        }
    }
})
