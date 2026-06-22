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

package dev.patrickgold.florisboard.app.settings.education

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.education.EducationalCorrectionState
import dev.patrickgold.florisboard.educationalCorrectionManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import org.florisboard.lib.compose.stringRes

@Composable
fun EducationScreen() = FlorisScreen {
    title = stringRes(R.string.settings__education__title)
    previewFieldVisible = true

    content {
        val context = LocalContext.current
        val educationalCorrectionManager by context.educationalCorrectionManager()
        val state by educationalCorrectionManager.state.collectAsState()
        val session by educationalCorrectionManager.session.collectAsState()
        val isLoggingIn by educationalCorrectionManager.isLoggingIn.collectAsState()
        // Una sesion vencida no cuenta como activa: hay que iniciar sesion de nuevo.
        val activeSession = session?.takeUnless { it.isExpired() }

        var username by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var pinVisible by remember { mutableStateOf(false) }

        PreferenceGroup(title = "Corrección IA") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "Para corregir: sombrea el texto con el dedo y toca el botón IA del teclado.",
                )
            }
        }

        PreferenceGroup(title = "Cuenta") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = activeSession?.let { "Sesión activa: ${it.userId}" }
                        ?: "Sin sesión. Inicia sesión para usar la corrección.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Alias pseudónimo") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (pinVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    label = { Text("PIN") },
                    trailingIcon = {
                        IconButton(onClick = { pinVisible = !pinVisible }) {
                            Icon(
                                imageVector = if (pinVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (pinVisible) {
                                    "Ocultar PIN"
                                } else {
                                    "Ver PIN"
                                },
                            )
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        educationalCorrectionManager.login(username, pin)
                        pin = ""
                    },
                    enabled = !isLoggingIn,
                ) {
                    Text(if (isLoggingIn) "Iniciando sesión..." else "Iniciar sesión")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = educationalCorrectionManager::logout,
                    enabled = activeSession != null,
                ) {
                    Text("Cerrar sesión")
                }
                if (state is EducationalCorrectionState.Message) {
                    Spacer(Modifier.height(12.dp))
                    Text((state as EducationalCorrectionState.Message).text)
                }
            }
        }
    }
}
