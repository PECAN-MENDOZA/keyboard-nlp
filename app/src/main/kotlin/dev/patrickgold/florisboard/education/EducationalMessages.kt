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
import java.util.Locale

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

    // Pruebas de oraciones
    const val CorrectionDisabledInSentence = "La corrección está desactivada en esta oración"
    /** Con una prueba en curso solo se corrige en el campo de la oración; en otra app se avisa. */
    const val CorrectionOnlyInTestField = "Termina la prueba para usar la corrección en otras apps."
    /** El backend ya cerró la oración (409 "Sentence is not open"): solo queda Terminar. */
    const val SentenceClosed = "Esta oración ya se cerró. Toca Terminar."
    const val TestNotAssigned = "Esta prueba no está asignada a tu cuenta."
    const val TestsTitle = "Pruebas"
    const val TestsEmpty = "No tienes pruebas pendientes."
    const val TestsLoading = "Buscando tus pruebas…"
    const val TestsRefresh = "Actualizar"
    const val TestStatusPending = "Pendiente"
    const val TestStatusInProgress = "En curso"
    const val TestStatusCompleted = "Completada"
    const val TestStartQuestion = "¿Comenzar la prueba %s?"
    const val TestStartHint = "Tu profesor te dirá qué escribir."
    const val TestStart = "Comenzar prueba"
    const val TestStarting = "Preparando la prueba…"
    const val TestInProgress = "Prueba en curso"
    const val SentenceProgress = "Oración %d de %d"
    const val WithHelp = "Con ayuda"
    const val WithoutHelp = "Sin ayuda"
    const val SentenceStart = "Comenzar"
    const val SentenceFinish = "Terminar"
    const val SentenceCorrecting = "Corrigiendo…"
    const val SentenceEmptyQuestion = "¿Dejar esta oración en blanco?"
    const val Yes = "Sí"
    const val No = "No"
    const val Saving = "Guardando…"
    const val SentenceSaveFailed = "No pudimos guardar"
    const val SentenceRetry = "Reintentar"
    const val CancelTechnical = "Cancelar (problema técnico)"
    const val CancelTest = "Cancelar prueba"
    const val CancelTestTitle = "¿Cancelar la prueba?"
    const val CancelTestIntro = "Elige el motivo. Lo que no se haya guardado se perderá."
    const val CancelReasonAbandoned = "Ya no quiero seguir"
    const val CancelReasonTechnical = "Tuve un problema técnico"
    const val CancelReasonInterrupted = "Me interrumpieron"
    const val KeepGoing = "Seguir con la prueba"
    const val Cancelling = "Cancelando la prueba…"
    const val TestCompleted = "¡Prueba completada! Gracias."
    const val TestCancelled = "Prueba cancelada."
    const val ClockLost = "El teléfono se reinició durante la oración. Solo puedes cancelar la prueba."
    const val BackHome = "Volver al inicio"
    const val Back = "Volver"
    const val TestConflict = "No se pudo continuar con la prueba. Avisa a tu profesor."
    const val LogoutDuringTest =
        "Tienes una prueba en curso. Si cierras sesión se perderá el texto que no se haya guardado."

    /** Pregunta del diálogo de inicio con el código de la prueba ("¿Comenzar la prueba PRUEBA-01?"). */
    fun testStartQuestion(code: String): String = TestStartQuestion.format(Locale.ROOT, code)

    /** Contador "n/5000" del campo de la oración; solo cerca del tope (desde 100 antes), null si no. */
    fun sentenceLengthCounter(length: Int): String? =
        if (length >= MaxSentenceLength - SentenceCounterMargin) "$length/$MaxSentenceLength" else null

    private const val SentenceCounterMargin = 100

    fun sentenceProgress(position: Int, total: Int): String = SentenceProgress.format(Locale.ROOT, position, total)

    fun sentenceCount(count: Int): String = if (count == 1) "1 oración" else "$count oraciones"

    /** Botón de inicio: "Pruebas" a secas o con las pendientes cuando ya se listaron. */
    fun testsWithPending(pending: Int): String = when (pending) {
        0 -> TestsTitle
        1 -> "$TestsTitle · 1 pendiente"
        else -> "$TestsTitle · $pending pendientes"
    }

    fun testInProgress(position: Int, total: Int): String =
        "$TestInProgress · ${sentenceProgress(position, total).replaceFirstChar { it.lowercase() }}"

    fun assistanceLabel(assistance: SentenceAssistance): String = when (assistance) {
        SentenceAssistance.ASSISTED -> WithHelp
        SentenceAssistance.UNASSISTED -> WithoutHelp
    }

    fun testStatusLabel(status: String): String = when (status) {
        AssignedTest.StatusInProgress -> TestStatusInProgress
        AssignedTest.StatusCompleted -> TestStatusCompleted
        else -> TestStatusPending
    }

    fun cancelReasonLabel(reason: AttemptCancelReason): String = when (reason) {
        AttemptCancelReason.ABANDONED -> CancelReasonAbandoned
        AttemptCancelReason.TECHNICAL_PROBLEM -> CancelReasonTechnical
        AttemptCancelReason.INTERRUPTED -> CancelReasonInterrupted
    }

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

    /**
     * Errores de la corrección. Dentro de una prueba el backend revalida lo que el teclado ya
     * bloquea: 400 "A sentence test is in progress" (corrección desde otra app), 400
     * "…disabled for this sentence" (oración sin ayuda) y 409 "Sentence is not open" (oración ya
     * cerrada); si llegan, el texto dice qué hacer en vez de sugerir otro fragmento.
     */
    fun correction(error: Throwable): String = when (error) {
        is EducationalHttpException -> when (error.status) {
            400 -> when {
                error.bodyMentions("test is in progress") -> CorrectionOnlyInTestField
                error.bodyMentions("disabled for this sentence") -> CorrectionDisabledInSentence
                else -> "No pudimos corregir ese texto. Intenta con otro fragmento."
            }
            401 -> SessionExpired
            403 -> "No tienes permiso para usar la corrección."
            404 -> "Esa corrección ya no está disponible. Sombrea y toca IA otra vez."
            409 -> if (error.bodyMentions("not open")) SentenceClosed else "No se pudo corregir (código 409)."
            502 -> AiUnavailable
            else -> "No se pudo corregir (código ${error.status})."
        }
        else -> if (error.isNetworkFailure()) AiUnavailable else "No se pudo corregir."
    }

    /**
     * Errores de las pruebas de oraciones. Un 409 es un conflicto de estado de la prueba (otra en
     * curso, ya completada, oración fuera de orden…): el alumno no puede arreglarlo, avisa al
     * profesor; un 404 es una prueba que ya no existe; un 403, una prueba (o un intento) que no
     * es de este alumno.
     */
    fun sentenceTest(error: Throwable): String = when (error) {
        is EducationalHttpException -> when (error.status) {
            401 -> SessionExpired
            403 -> TestNotAssigned
            404 -> "Esa prueba ya no está disponible."
            409 -> TestConflict
            in 500..599 -> "El servidor tuvo un problema. Inténtalo en unos minutos."
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

    private fun EducationalHttpException.bodyMentions(fragment: String): Boolean = body.contains(fragment, ignoreCase = true)

    private fun Throwable.isNetworkFailure(): Boolean =
        this is ConnectException || this is NoRouteToHostException ||
            this is SocketTimeoutException || this is UnknownHostException
}
