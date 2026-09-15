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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ExperimentCondition { ASSISTED, UNASSISTED }

@Serializable
data class RedeemAccessCodeRequest(val code: String)

@Serializable
data class ExperimentRunResponse(
    val id: String,
    val participantCode: String,
    val condition: ExperimentCondition,
    val taskVariant: String,
    val promptText: String,
    // PENDING (canjeada, sin iniciar) | ACTIVE | COMPLETED | CANCELLED | EXPIRED
    val status: String,
    val startedAt: String? = null,
    val expiresAt: String? = null,
)

@Serializable
enum class CancelReason { ABANDONED, TECHNICAL_PROBLEM, INTERRUPTED }

@Serializable
data class CancelExperimentRequest(val reason: CancelReason)

@Serializable
data class CompleteExperimentRequest(
    @SerialName("texto_final")
    val finalText: String,
    @SerialName("duracion_ms")
    val durationMs: Long,
    @SerialName("completion_key")
    val completionKey: String,
    @SerialName("app_version")
    val appVersion: String,
)
