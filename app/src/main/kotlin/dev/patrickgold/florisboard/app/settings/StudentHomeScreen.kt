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

package dev.patrickgold.florisboard.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.education.EducationalBackendConnectionState
import dev.patrickgold.florisboard.education.LoginState
import dev.patrickgold.florisboard.educationalCorrectionManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.jetpref.datastore.ui.Preference
import org.florisboard.lib.compose.FlorisErrorCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.stringRes

/**
 * Inicio de la app: la cuenta del alumno. Sin sesión muestra el login; con sesión muestra el
 * saludo, la conexión y cómo corregir. El formulario nunca se ve con una sesión activa.
 */
@Composable
fun StudentHomeScreen() = FlorisScreen {
    title = "Teclado adaptativo"
    navigationIconVisible = false
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current

    content {
        val manager by context.educationalCorrectionManager()
        val session by manager.session.collectAsState()
        val activeSession = session?.takeUnless { it.isExpired() }

        val isImeEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
        val isImeSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
        if (!isImeEnabled) {
            FlorisErrorCard(
                modifier = Modifier.padding(8.dp),
                showIcon = false,
                text = stringRes(R.string.settings__home__ime_not_enabled),
                onClick = { InputMethodUtils.showImeEnablerActivity(context) },
            )
        } else if (!isImeSelected) {
            FlorisWarningCard(
                modifier = Modifier.padding(8.dp),
                showIcon = false,
                text = stringRes(R.string.settings__home__ime_not_selected),
                onClick = { InputMethodUtils.showImePicker(context) },
            )
        }

        if (activeSession == null) {
            LoginSection(
                loginState = manager.loginState.collectAsState().value,
                onLogin = manager::login,
            )
        } else {
            AccountSection(
                alias = activeSession.userId,
                connectionState = manager.connectionState.collectAsState().value,
                onCheckConnection = manager::checkBackendConnection,
            )
        }

        Preference(
            icon = Icons.Outlined.Keyboard,
            title = "Ajustes del teclado",
            onClick = { navController.navigate(Routes.Settings.KeyboardSettings) },
        )

        if (activeSession != null) {
            OutlinedButton(
                onClick = manager::logout,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Cerrar sesión")
            }
        }
    }
}

@Composable
private fun LoginSection(
    loginState: LoginState,
    onLogin: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pinVisible by remember { mutableStateOf(false) }
    val isLoading = loginState is LoginState.Loading

    // Un fallo conserva el alias y borra el PIN para que el alumno solo reescriba lo corto.
    LaunchedEffect(loginState) {
        if (loginState is LoginState.Failed) pin = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("Inicia sesión para usar la corrección con IA.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isLoading,
            label = { Text("Alias") },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
            label = { Text("PIN") },
            trailingIcon = {
                IconButton(onClick = { pinVisible = !pinVisible }) {
                    Icon(
                        imageVector = if (pinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (pinVisible) "Ocultar PIN" else "Ver PIN",
                    )
                }
            },
        )
        if (loginState is LoginState.Failed) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    text = loginState.message,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onLogin(username, pin) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isLoading) "Iniciando sesión…" else "Iniciar sesión")
        }
    }
}

@Composable
private fun AccountSection(
    alias: String,
    connectionState: EducationalBackendConnectionState,
    onCheckConnection: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (connectionState is EducationalBackendConnectionState.Unknown) onCheckConnection()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hola, $alias", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("sesión activa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        val (label, detail) = when (connectionState) {
            is EducationalBackendConnectionState.Connected -> "● Conectado" to "La corrección IA está lista."
            is EducationalBackendConnectionState.Checking -> "⏳ Comprobando…" to "Un momento."
            is EducationalBackendConnectionState.Unavailable -> "○ Sin conexión con el servidor" to "Toca para volver a intentar."
            EducationalBackendConnectionState.Unknown -> "○ Sin comprobar" to "Toca para comprobar la conexión."
        }
        Card(modifier = Modifier.fillMaxWidth(), onClick = onCheckConnection) {
            Column(Modifier.padding(12.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Cómo corregir", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("1. Escribe en cualquier app.")
                Text("2. Sombrea el texto con el dedo.")
                Text("3. Toca el botón IA del teclado.")
            }
        }
    }
}
