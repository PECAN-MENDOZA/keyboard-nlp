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

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Único lugar donde viven los textos que ve el alumno. Login y corrección se traducen por
 * separado: un 401 al iniciar sesión es "PIN incorrecto", un 401 al corregir es "sesión vencida".
 * Ningún texto menciona infraestructura (servidor concreto, nube, despliegue).
 */
object EducationalMessages {
    const val EmptyCredentials = "Escribe tu alias y tu PIN."
    const val NoSession = "Inicia sesión en la app del teclado para usar la corrección."
    const val SessionExpired = "Tu sesión terminó. Abre la app del teclado para iniciar sesión."
    const val AiUnavailable = "Sin conexión con la IA."
    const val SelectFirst = "Sombrea el texto que quieres corregir y toca IA."
    const val NotAllowedHere = "Aquí no se puede usar la corrección."
    const val AppNotSupported = "Esta app no permite corregir. Prueba en otra."
    const val TextChanged = "El texto cambió. Sombrea y toca IA otra vez."
    const val AlreadyCorrect = "Tu texto ya está bien escrito"
    const val Processing = "Revisando tu texto…"
    const val ProcessingSlow = "La primera vez tarda más"
    const val Corrected = "Corregido"
    const val Editing = "Editando"

    // Experimento
    const val InvalidAccessCode = "Ese código no sirve. Pídele uno nuevo al investigador."

    // App
    const val AppTitle = "Teclado adaptativo"
    const val LoginIntro = "Inicia sesión para usar la corrección con IA."
    const val AliasLabel = "Alias"
    const val PinLabel = "PIN"
    const val ShowPin = "Ver PIN"
    const val HidePin = "Ocultar PIN"
    const val LoginButton = "Iniciar sesión"
    const val LoggingIn = "Iniciando sesión…"
    const val KeyboardSettings = "Ajustes del teclado"
    const val Logout = "Cerrar sesión"
    const val SessionActive = "sesión activa"
    fun greeting(alias: String): String = "Hola, $alias"
    const val Connected = "● Conectado"
    const val ConnectedDetail = "La corrección IA está lista."
    const val Checking = "⏳ Comprobando…"
    const val CheckingDetail = "Un momento."
    const val Unavailable = "○ Sin conexión con el servidor"
    const val UnavailableDetail = "Toca para volver a intentar."
    const val Unchecked = "○ Sin comprobar"
    const val UncheckedDetail = "Toca para comprobar la conexión."
    const val HowToTitle = "Cómo corregir"
    const val HowTo1 = "1. Escribe en cualquier app."
    const val HowTo2 = "2. Sombrea el texto con el dedo."
    const val HowTo3 = "3. Toca el botón IA del teclado."
    // Globos
    const val OtherOption = "Otra opción"
    const val EditChip = "✎ Editar"
    const val IgnoreChip = "Dejar como está"
    const val Retry = "↻ Reintentar"
    const val Undo = "↶ Deshacer"
    const val Done = "✓ Listo"
    const val Close = "✕"
    const val CloseDescription = "Cerrar"
    const val AvatarDescription = "Asistente IA. Arrastra para mover las sugerencias"

    fun recommendedLabel(changes: Int): String =
        if (changes == 1) "Recomendada · 1 cambio" else "Recomendada · $changes cambios"

    fun tooLong(max: Int): String = "Sombrea un texto más corto (máximo $max letras)."

    fun login(error: Throwable): String = when (error) {
        is EducationalHttpException -> when (error.status) {
            401, 403 -> "Alias o PIN incorrectos. Revisa los datos que te dio tu docente."
            in 500..599 -> "El servidor tuvo un problema. Inténtalo en unos minutos."
            else -> "No se pudo iniciar sesión (código ${error.status})."
        }
        is IllegalArgumentException -> error.message ?: "No se pudo iniciar sesión."
        else -> if (error.isNetworkFailure()) {
            "No se pudo conectar con el servidor. Revisa la conexión e inténtalo de nuevo."
        } else {
            "No se pudo iniciar sesión."
        }
    }

    fun correction(error: Throwable): String = when (error) {
        is EducationalHttpException -> when (error.status) {
            400 -> "No pudimos corregir ese texto. Intenta con otro fragmento."
            401 -> SessionExpired
            403 -> "No tienes permiso para usar la corrección."
            404 -> "Esa corrección ya no está disponible. Sombrea y toca IA otra vez."
            502 -> AiUnavailable
            else -> "No se pudo corregir (código ${error.status})."
        }
        else -> if (error.isNetworkFailure()) AiUnavailable else "No se pudo corregir."
    }

    fun experiment(error: Throwable): String = when (error) {
        is EducationalHttpException -> when (error.status) {
            400 -> InvalidAccessCode
            401 -> SessionExpired
            404 -> "Esa prueba ya no está disponible."
            409 -> "Esa prueba ya fue guardada."
            else -> "No se pudo continuar con la prueba (código ${error.status})."
        }
        else -> if (error.isNetworkFailure()) {
            "No se pudo conectar con el servidor. Revisa la conexión e inténtalo de nuevo."
        } else {
            "No se pudo continuar con la prueba."
        }
    }

    /** Vale la pena ofrecer "Reintentar": IA caída (502) o fallo de red transitorio. */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is EducationalHttpException -> error.status == 502
        else -> error.isNetworkFailure()
    }

    private fun Throwable.isNetworkFailure(): Boolean =
        this is ConnectException || this is NoRouteToHostException ||
            this is SocketTimeoutException || this is UnknownHostException
}
