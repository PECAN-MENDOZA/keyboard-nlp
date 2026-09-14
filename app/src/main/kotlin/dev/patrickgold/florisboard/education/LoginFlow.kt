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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Máquina de estados del login, sin Android, para poder probarla en JVM. Valida campos, evita
 * envíos dobles, traduce errores con [EducationalMessages.login] y entrega la sesión por
 * [onSuccess]. El manager la envuelve y persiste la sesión.
 */
class LoginFlow(
    private val scope: CoroutineScope,
    private val authenticate: suspend (username: String, pin: String) -> Result<EducationalSession>,
    private val onSuccess: (EducationalSession) -> Unit,
) {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    fun submit(username: String, pin: String) {
        if (_state.value is LoginState.Loading) return
        val user = username.trim()
        if (user.isEmpty() || pin.isBlank()) {
            _state.value = LoginState.Failed(EducationalMessages.EmptyCredentials)
            return
        }
        _state.value = LoginState.Loading
        scope.launch {
            authenticate(user, pin)
                .onSuccess { session ->
                    runCatching { onSuccess(session) }
                        .onSuccess { _state.value = LoginState.Idle }
                        .onFailure { error -> _state.value = LoginState.Failed(EducationalMessages.login(error)) }
                }
                .onFailure { error ->
                    _state.value = LoginState.Failed(EducationalMessages.login(error))
                }
        }
    }

    fun reset() {
        if (_state.value !is LoginState.Loading) _state.value = LoginState.Idle
    }
}
