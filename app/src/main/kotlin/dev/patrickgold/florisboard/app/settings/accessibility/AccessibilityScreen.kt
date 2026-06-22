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

package dev.patrickgold.florisboard.app.settings.accessibility

import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

@Composable
fun AccessibilityScreen() = FlorisScreen {
    title = stringRes(R.string.accessibility__group__title)
    previewFieldVisible = true

    content {
        PreferenceGroup(title = stringRes(R.string.accessibility__group__title)) {
            SwitchPreference(
                prefs.accessibility.dyslexiaFont,
                title = stringRes(R.string.accessibility__dyslexia_font__label),
                summary = stringRes(R.string.accessibility__dyslexia_font__summary),
            )
            SwitchPreference(
                prefs.accessibility.highContrastKeyboard,
                title = stringRes(R.string.accessibility__high_contrast__label),
                summary = stringRes(R.string.accessibility__high_contrast__summary),
            )
            SwitchPreference(
                prefs.accessibility.bigKeys,
                title = stringRes(R.string.accessibility__big_keys__label),
                summary = stringRes(R.string.accessibility__big_keys__summary),
            )
        }
    }
}
