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
import android.provider.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** [SentenceTestDraftStore] sobre SharedPreferences (`sentence_test_draft`), JSON con kotlinx.serialization. */
class PrefsSentenceTestDraftStore(context: Context) : SentenceTestDraftStore {
    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): SentenceDraft? {
        val raw = prefs.getString(Key, null) ?: return null
        return runCatching { json.decodeFromString<SentenceDraft>(raw) }.getOrNull()
    }

    override fun save(draft: SentenceDraft) {
        // commit(): el borrador (y sobre todo la clave de finalización) debe estar en disco antes
        // de que el proceso pueda morir; se guarda por tecla, pero el JSON es diminuto.
        prefs.edit().putString(Key, json.encodeToString(draft)).commit()
    }

    override fun clear() {
        prefs.edit().remove(Key).commit()
    }

    companion object {
        private const val PrefsName = "sentence_test_draft"
        private const val Key = "draft"

        /** Identifica el arranque actual del teléfono: `elapsedRealtime` solo es comparable dentro del mismo. */
        fun bootId(context: Context): String =
            Settings.Global.getString(context.contentResolver, "boot_count")
                ?: SentenceTestCoordinator.UnknownBootId
    }
}
