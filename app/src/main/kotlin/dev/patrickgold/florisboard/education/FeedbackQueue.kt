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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cola FIFO de envíos de feedback (aceptar, rechazar, terminar edición, deshacer), sin Android.
 * Los envíos se completan en el orden en que se encolaron aunque el primero sea lento, y
 * [drain] permite esperar a TODOS —incluidos los encolados mientras se esperaba— antes de
 * Terminar la oración de una prueba (`EducationalCorrectionManager.finishSentence`): el backend
 * cierra el feedback de la oración al terminarla, así que un feedback que llegara después se
 * perdería (400 "Feedback is closed", terminal) y la decisión quedaría sin registrar.
 */
class FeedbackQueue(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val lock = Any()
    private val pending = mutableListOf<Job>()

    fun enqueue(block: suspend () -> Unit) {
        val job = scope.launch {
            mutex.withLock {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Un feedback fallido no debe bloquear los siguientes ni la finalización.
                }
            }
        }
        synchronized(lock) { pending += job }
        job.invokeOnCompletion { synchronized(lock) { pending -= job } }
    }

    /** Espera a que termine todo lo encolado, incluido lo que se encole mientras se espera. */
    suspend fun drain() {
        while (true) {
            val snapshot = synchronized(lock) { pending.toList() }
            if (snapshot.isEmpty()) return
            snapshot.joinAll()
        }
    }
}
