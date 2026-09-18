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

package dev.patrickgold.florisboard.app.settings.tests

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.education.AssignedTest
import dev.patrickgold.florisboard.education.EducationalMessages
import dev.patrickgold.florisboard.education.SentenceTestState
import dev.patrickgold.florisboard.education.currentSentence
import dev.patrickgold.florisboard.educationalCorrectionManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen

/**
 * "Pruebas": la lista de pruebas asignadas al alumno. Tocar una pendiente pide confirmación y la
 * inicia; en cuanto hay una oración por resolver (recién iniciada o retomada por `loadTests()`,
 * que nunca lista con una prueba en curso) esta pantalla se sustituye por [TestSentenceScreen],
 * así atrás desde la oración vuelve al inicio.
 */
@Composable
fun TestsScreen() = FlorisScreen {
    title = EducationalMessages.TestsTitle
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current

    content {
        val manager by context.educationalCorrectionManager()
        val tests = manager.tests
        val state by tests.state.collectAsState()
        val lastError by tests.lastError.collectAsState()
        var confirmStart by remember { mutableStateOf<AssignedTest?>(null) }
        var navigated by remember { mutableStateOf(false) }

        // Un resultado anterior (completada, cancelada, fallo) ya se vio: se vuelve a listar.
        LaunchedEffect(Unit) {
            tests.leaveToHome()
            tests.loadTests()
        }
        LaunchedEffect(state) {
            when {
                state.currentSentence() != null -> if (!navigated) {
                    navigated = true
                    navController.navigate(Routes.Settings.TestSentence) {
                        popUpTo(Routes.Settings.Tests) { inclusive = true }
                    }
                }
                // Una cancelación que terminó estando aquí (se salió de la oración mientras
                // se cancelaba) o una prueba completada: de vuelta a la lista.
                state is SentenceTestState.Completed || state == SentenceTestState.Cancelled -> {
                    tests.leaveToHome()
                    tests.loadTests()
                }
                // Idle no es una pantalla: se pide la lista (el coordinador ya publica la lista
                // cuando se le pidió durante una reanudación; esto cubre cualquier otra vuelta a
                // Idle). No hay bucle: desde Idle, loadTests pasa a LoadingTests o a Failed.
                state == SentenceTestState.Idle -> tests.loadTests()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (val current = state) {
                is SentenceTestState.Choosing -> ChoosingList(
                    tests = current.tests,
                    onPick = { confirmStart = it },
                    onRefresh = { tests.loadTests() },
                )
                is SentenceTestState.Starting -> ProgressCard(EducationalMessages.TestStarting)
                is SentenceTestState.Failed -> ErrorCard(
                    message = current.message,
                    onRetry = {
                        tests.leaveToHome()
                        tests.loadTests()
                    },
                    onCancel = {
                        tests.leaveToHome()
                        navController.popBackStack()
                    },
                    cancelLabel = EducationalMessages.BackHome,
                )
                // Idle (transitorio), LoadingTests o una oración en curso (se navega a ella).
                else -> ProgressCard(EducationalMessages.TestsLoading)
            }
            lastError?.let { message -> TransientErrorCard(message, onDismiss = tests::dismissError) }
        }

        confirmStart?.let { test ->
            StartTestDialog(
                test = test,
                onConfirm = {
                    confirmStart = null
                    tests.start(test)
                },
                onDismiss = { confirmStart = null },
            )
        }
    }
}

@Composable
private fun ChoosingList(tests: List<AssignedTest>, onPick: (AssignedTest) -> Unit, onRefresh: () -> Unit) {
    if (tests.isEmpty()) {
        Text(EducationalMessages.TestsEmpty)
    } else {
        tests.forEach { test ->
            TestRow(test = test, onClick = { onPick(test) }.takeIf { test.isPending })
            Spacer(Modifier.height(8.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    SecondaryAction(text = EducationalMessages.TestsRefresh, onClick = onRefresh)
}

/** Título, código y estado de una prueba; solo las pendientes se pueden tocar. */
@Composable
private fun TestRow(test: AssignedTest, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(test.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${test.code} · ${EducationalMessages.sentenceCount(test.sentenceCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                EducationalMessages.testStatusLabel(test.status),
                style = MaterialTheme.typography.labelLarge,
                color = if (test.isPending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (onClick != null) {
        Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) { content() }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun StartTestDialog(test: AssignedTest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(test.title) },
        text = { Text(EducationalMessages.testStartQuestion(test.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EducationalMessages.TestStart)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EducationalMessages.Back)
            }
        },
    )
}
