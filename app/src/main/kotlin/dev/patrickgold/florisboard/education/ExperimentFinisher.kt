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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Secuencia de "Finalizar y guardar", sin Android, para probarla en JVM:
 * 1. cierra lo que la tira de corrección tenga abierto (una sugerencia visible se registra como
 *    rechazo; una edición en curso, con su texto final); si hay una corrección procesándose no se
 *    finaliza;
 * 2. cierra la frontera en el coordinador ([EducationalExperimentCoordinator.beginCompletion]):
 *    texto y duración congelados al toque, ya en disco, pantalla no editable;
 * 3. espera a que TODO el feedback pendiente llegue al backend ([FeedbackQueue.drain]): el backend
 *    cierra el feedback al completar, así que el orden feedback → complete fija la aceptación;
 * 4. envía exactamente el payload congelado ([EducationalExperimentCoordinator.submitCompletion]).
 *
 * Los pasos 3 y 4 corren en [scope] (el del manager), no en el de la pantalla: una vez congelada
 * la frontera, que la pantalla desaparezca no debe dejar la finalización sin enviar.
 */
class ExperimentFinisher(
    private val scope: CoroutineScope,
    private val strip: CorrectionStrip,
    private val feedbackQueue: FeedbackQueue,
    private val experiment: EducationalExperimentCoordinator,
) {
    /** Lo que la tira de corrección puede tener abierto al tocar "Finalizar". */
    enum class OpenCorrection { NONE, SUGGESTIONS, APPLIED, EDITING, PROCESSING }

    /** Acciones de la tira que el manager expone; cerrar una sugerencia o una edición encola su feedback. */
    interface CorrectionStrip {
        fun openCorrection(): OpenCorrection
        fun ignoreSuggestion()
        fun finishEdit()
        fun dismiss()
    }

    /** Devuelve `false` si no se finalizó nada (corrección en curso o el coordinador lo rechazó). */
    suspend fun finish(text: String): Boolean {
        when (strip.openCorrection()) {
            OpenCorrection.PROCESSING -> return false
            OpenCorrection.SUGGESTIONS -> strip.ignoreSuggestion()
            OpenCorrection.EDITING -> strip.finishEdit()
            OpenCorrection.APPLIED -> strip.dismiss()
            OpenCorrection.NONE -> Unit
        }
        if (!experiment.beginCompletion(text)) return false
        return scope.async {
            feedbackQueue.drain()
            experiment.submitCompletion()
        }.await()
    }
}
