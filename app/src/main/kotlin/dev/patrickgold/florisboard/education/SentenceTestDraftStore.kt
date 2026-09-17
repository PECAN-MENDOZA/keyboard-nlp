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

import kotlinx.serialization.Serializable

/**
 * Borrador de la oración en curso: sobrevive a que Android mate el proceso. Guarda de quién es
 * ([ownerUserId] = `EducationalSession.userId`), en qué intento y posición va, en qué arranque
 * del teléfono se midió `pressedAtElapsedMs` (los `elapsedRealtime` solo son comparables dentro
 * del mismo [bootId]), lo escrito, los contadores de sugerencias y, desde que se toca Terminar,
 * la clave idempotente con la que se envía (y reenvía) la finalización.
 */
@Serializable
data class SentenceDraft(
    val ownerUserId: String,
    val attemptId: String,
    val position: Int,
    val bootId: String,
    val pressedAtElapsedMs: Long,
    val firstKeyAtElapsedMs: Long? = null,
    val text: String = "",
    val offered: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val undone: Int = 0,
    val completionKey: String? = null,
)

/** Persistencia del borrador; la implementación Android vive en [PrefsSentenceTestDraftStore]. */
interface SentenceTestDraftStore {
    fun load(): SentenceDraft?
    fun save(draft: SentenceDraft)
    fun clear()
}
