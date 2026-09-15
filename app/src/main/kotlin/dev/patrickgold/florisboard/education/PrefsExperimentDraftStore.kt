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

import android.content.Context

/** [ExperimentDraftStore] sobre SharedPreferences (`experiment_draft`), texto plano por ejecución. */
class PrefsExperimentDraftStore(context: Context) : ExperimentDraftStore {
    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    override fun load(runId: String): String? {
        if (prefs.getString(KeyRunId, null) != runId) return null
        return prefs.getString(KeyText, null)
    }

    override fun save(runId: String, text: String) {
        // apply(): perder la última tecla si el proceso muere justo después es aceptable; commit()
        // en cada pulsación bloquearía el hilo principal.
        prefs.edit().putString(KeyRunId, runId).putString(KeyText, text).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PrefsName = "experiment_draft"
        private const val KeyRunId = "run_id"
        private const val KeyText = "text"
    }
}
