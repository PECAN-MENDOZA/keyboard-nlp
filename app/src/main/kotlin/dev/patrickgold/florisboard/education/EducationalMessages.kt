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

    /** Vale la pena ofrecer "Reintentar": IA caída (502) o fallo de red transitorio. */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is EducationalHttpException -> error.status == 502
        else -> error.isNetworkFailure()
    }

    private fun Throwable.isNetworkFailure(): Boolean =
        this is ConnectException || this is NoRouteToHostException ||
            this is SocketTimeoutException || this is UnknownHostException
}
