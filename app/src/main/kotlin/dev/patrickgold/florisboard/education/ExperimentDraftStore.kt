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

/**
 * Persistencia en disco del texto que el alumno lleva escrito en la tarea activa
 * ("Participar en una prueba"), por ejecución. A diferencia de `rememberSaveable`, sobrevive a que
 * Android mate el proceso (no solo a la rotación de pantalla): sin esto, una restauración ACTIVE
 * tras `force-stop` recupera el cronómetro pero no el texto. La implementación Android vive en
 * [PrefsExperimentDraftStore].
 */
interface ExperimentDraftStore {
    /**
     * Texto guardado para [runId] del alumno [ownerUserId], o `null` si no hay borrador o pertenece
     * a otra ejecución u otra cuenta.
     */
    fun load(runId: String, ownerUserId: String): String?
    fun save(runId: String, ownerUserId: String, text: String)
    /** Alumno dueño del borrador guardado, o `null` si no hay borrador. */
    fun ownerUserId(): String?
    fun clear()
}
