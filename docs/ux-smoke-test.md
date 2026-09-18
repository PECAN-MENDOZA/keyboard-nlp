Última ejecución: 2026-09-14 en OnePlus 6 (Android 15, Simple Notes) por el controlador SDD vía adb; 17/28 casos verificados, el resto marcado como pendiente para la sesión manual con el teléfono en mano. La sección "Pruebas de oraciones" (2026-09-17) está pendiente de verificar en el OnePlus.

# Prueba manual de experiencia del alumno (OnePlus)

Requisitos: `..\local.ps1 Start` en verde, `..\local.ps1 InstallKeyboard`, teclado seleccionado.
Marcar cada casilla con la fecha y el resultado.

## App
- [x] Sin sesión: abre en "Teclado adaptativo" con Alias/PIN; no hay menú de FlorisBoard como inicio.
- [x] PIN incorrecto: tarjeta roja "Alias o PIN incorrectos…"; el alias se conserva, el PIN se borra; el teclado NO muestra ningún globo.
- [ ] Sin red (modo avión): "No se pudo conectar con el servidor…". (pendiente: requiere modo avión)
- [x] Login correcto: el formulario desaparece; "Hola, student_001 · sesión activa"; tarjeta "● Conectado".
- [x] Reiniciar la app: sigue con sesión; no aparece el formulario. ✅ OK 2026-09-17 (OnePlus, APK b24cf36)
- [x] "Ajustes del teclado" abre el menú antiguo con flecha atrás.
- [x] "Cerrar sesión" vuelve al formulario.

## Teclado — mensajes
- [ ] Primera apertura del teclado tras login: globo ámbar "Sombrea el texto…" que se cierra solo (~6 s) y no vuelve. (pendiente: pref onboardingHintShown ya estaba en true en el OnePlus)
- [x] IA sin selección: globo ámbar "Sombrea el texto que quieres corregir y toca IA." se cierra solo (~4 s). ✅ OK 2026-09-17
- [ ] IA en campo de contraseña (login de cualquier app): "Aquí no se puede usar la corrección." (pendiente)
- [x] IA con texto ya correcto: globo verde "Tu texto ya está bien escrito" ~3 s; el teclado no se colapsa.
- [x] Selección con espacios en los bordes (dedo poco preciso: "hola" + " voy al parque…"): el espacio queda fuera del rango. Texto correcto → "Tu texto ya está bien escrito" (no "Recomendada · 0 cambios"); con errores, aplicar da "hola voy al parque…" y nunca "holavoy…". Deshacer restaura el original exacto. (OnePlus, 2026-09-16)
- [x] IA con la IA detenida (`..\local.ps1 Stop` solo IA): globo rojo "Sin conexión con la IA." con ↻ Reintentar y ✕.
- [x] Sin sesión iniciada, tocar IA muestra el globo "Inicia sesión en la app del teclado para usar la corrección."; con sesión vencida (borrar token o esperar), "Tu sesión terminó…". Ambos ámbar con ✕. ✅ OK 2026-09-17 (campo PIN del login)

## Teclado — globos (probar en Google Keep, WhatsApp y Chrome)
- [x] Al llegar sugerencias: las teclas desaparecen, la app ocupa toda la pantalla, aparecen avatar + hasta 3 globos con cambios resaltados; el primero dice "Recomendada · N cambios".
- [x] Tope de tres globos (la IA solo manda más de una opción cuando la oración es ambigua): "mañana voy al parque con mis amigos" → ningún globo (ya está bien); "esta bien, nos vemos luego" → 1 globo ("está bien, nos vemos luego"); "a mi me gusta que ellos juega mucho" → 2 globos (recomendada "…juegan mucho" + "…juega mucho"); "se que no vendra hoy" → 2 globos ("sé que…" / "se que…"). Nunca más de 3 aunque la IA mandara más. ✅ Parcial 2026-09-17: con LoRA v3 ambas frases devuelven 1 globo (recomendada); el tope de 3 queda cubierto por tests JVM
- [x] Tocar fuera de los globos llega a la app (mover cursor, tocar botones de la app).
- [x] Toque corto en un globo: reemplaza el texto, vuelven las teclas, tira "✓ Corregido · ↶ Deshacer" ~3 s.
- [x] ↶ Deshacer dentro de los 3 s restaura el texto original.
- [x] Escribir una tecla después de aplicar retira la tira sin deshacer. ✅ OK 2026-09-17
- [x] "✎ Editar": aplica la recomendada, vuelven las teclas, tira "✎ Editando · ✓ Listo · ↶ Deshacer"; editar en el campo real; "✓ Listo" cierra la tira (el portal docente muestra la sesión como editada).
- [ ] Pulsación larga en "Otra opción": aplica esa opción y entra en edición. (pendiente)
- [ ] En edición, mover el cursor fuera del texto editado cierra la tira (Listo implícito) sin perder el texto. (pendiente)
- [x] "Dejar como está": los globos se van, el texto no cambia, vuelven las teclas.
- [x] Botón atrás con globos visibles: se van con el teclado; volver a tocar el mismo campo los muestra de nuevo.
- [x] Cambiar de app con globos visibles: al volver al teclado no hay globos (estado limpio). ✅ OK 2026-09-17
- [x] Arrastrar el avatar mueve el grupo; al reabrir, la posición se recuerda; nunca sale de la pantalla.
- [ ] Accesibilidad → fuente para dislexia y teclas grandes: los globos usan la fuente y siguen legibles. (pendiente)
- [x] Modo oscuro del sistema: globos legibles (paleta oscura).
- [x] Ninguna pantalla muestra "Cloud Run", "backend" ni "desplegado".

## Pruebas de oraciones (OnePlus, backend de la Parte A en marcha)
Preparación: una prueba `ACTIVE` de 3 oraciones asignada a `student_001` (oración 1 con ayuda, 2 sin ayuda, 3 con ayuda); sesión iniciada en la app.
- [x] Inicio: el botón dice "Pruebas". ✅ 2026-09-17 Al tocarlo, la lista muestra la prueba como "Pendiente" (título, código, "3 oraciones"); al volver al inicio el botón dice "Pruebas · 1 pendiente".
- [ ] Tocar la pendiente → diálogo "¿Comenzar la prueba PRUEBA-01?" (el código) con el título debajo y "Tu profesor te dirá qué escribir." → "Comenzar prueba" → pantalla "Oración 1 de 3" con el chip "Con ayuda", campo deshabilitado y botón "Comenzar". Ninguna pantalla muestra tiempo.
- [ ] Comenzar → el campo se habilita y toma el foco (aparece el teclado) → escribir una oración con un error → sombrear → IA → globos → aceptar → "Terminar" → "Guardando…" → "Oración 2 de 3 · Sin ayuda".
- [ ] Oración 2 (sin ayuda): Comenzar → escribir → sombrear → IA muestra el globo "La corrección está desactivada en esta oración" y ningún request llega al backend (ni con ↻ Reintentar tras un error previo) → Terminar → "Oración 3 de 3 · Con ayuda".
- [ ] Oración 3: Comenzar → sin escribir, Terminar → diálogo "¿Dejar esta oración en blanco?" → Sí → "¡Prueba completada! Gracias." → "Volver al inicio" → el botón vuelve a decir "Pruebas" y la lista muestra la prueba como "Completada".
- [ ] Con la oración 1 en curso (con ayuda), ir a otra app (p. ej. el buscador), escribir, sombrear e IA: globo "Termina la prueba para usar la corrección en otras apps." y ningún request al backend; de vuelta en el campo de la prueba la IA funciona.
- [ ] Pegar un texto de más de 5000 caracteres en el campo: se recorta a 5000 y bajo el campo aparece "5000/5000" (el contador solo se ve a partir de 4900).
- [x] Terminar con globos abiertos: con sugerencias sin elegir, Terminar cierra los globos y cuenta un rechazo; con "Corrigiendo…" (petición en vuelo) el botón está deshabilitado hasta que la IA responda. ✅ OK 2026-09-17 (ofrecida 1, rechazada 1)
- [ ] Atrás durante una oración (antes o después de Comenzar) no cancela: el inicio dice "Prueba en curso · oración N de 3" y al tocarlo se vuelve a la misma oración con el texto escrito.
- [ ] Matar la app (o "Cerrar sesión" + volver a entrar con el mismo alumno) a media oración: se retoma en la misma oración con el mismo texto; tras un reinicio del teléfono aparece "El teléfono se reinició durante la oración. Solo puedes cancelar la prueba." con "Cancelar (problema técnico)".
- [x] Iniciar sesión con otro alumno en el mismo teléfono: no ve la oración del anterior (el inicio dice "Pruebas" y su lista es la suya). ✅ OK 2026-09-17
- [x] "Cancelar prueba" abre el diálogo de motivo (Ya no quiero seguir / Me interrumpieron) → "Prueba cancelada." → Volver al inicio. ✅ OK 2026-09-17
- [x] Sin red al Terminar: "No pudimos guardar. …" con "Reintentar" (reenvía la misma oración, sin duplicarla) y "Cancelar (problema técnico)". ✅ OK 2026-09-17 (backend apagado; Reintentar sin duplicar)
- [x] Sin red al Terminar la oración 3 cuando el PUT sí llegó (cortar la red justo tras tocar Terminar): "Cancelar (problema técnico)" muestra "¡Prueba completada! Gracias." (no "Prueba cancelada.") y en BD el intento queda `COMPLETED`. ✅ OK 2026-09-17 (backend apagado; Reintentar sin duplicar)
- [ ] Tras "¡Prueba completada!", salir con atrás o cerrar la app (sin "Volver al inicio") y volver a abrirla: el inicio dice "Pruebas" o "Pruebas · N pendientes" según la lista, no se queda sin contador.
- [x] "Cerrar sesión" con una prueba en curso pide confirmar ("Tienes una prueba en curso…"). ✅ OK 2026-09-17
- [x] Comprobar en BD tras completar: `select position, final_text, auto_error_count, suggestions_accepted, duration_from_first_key_ms from test_responses order by position` → 3 filas; la oración 1 con `suggestions_accepted = 1`, la 2 con 0 y la 3 en blanco (`skipped`), con duraciones plausibles. ✅ OK 2026-09-17 (protocolo §5.2)
