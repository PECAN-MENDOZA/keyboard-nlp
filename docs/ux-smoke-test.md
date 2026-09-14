Última ejecución: 2026-09-14 en OnePlus 6 (Android 15, Simple Notes) por el controlador SDD vía adb; 17/28 casos verificados, el resto marcado como pendiente para la sesión manual con el teléfono en mano.

# Prueba manual de experiencia del alumno (OnePlus)

Requisitos: `..\local.ps1 Start` en verde, `..\local.ps1 InstallKeyboard`, teclado seleccionado.
Marcar cada casilla con la fecha y el resultado.

## App
- [x] Sin sesión: abre en "Teclado adaptativo" con Alias/PIN; no hay menú de FlorisBoard como inicio.
- [x] PIN incorrecto: tarjeta roja "Alias o PIN incorrectos…"; el alias se conserva, el PIN se borra; el teclado NO muestra ningún globo.
- [ ] Sin red (modo avión): "No se pudo conectar con el servidor…". (pendiente: requiere modo avión)
- [x] Login correcto: el formulario desaparece; "Hola, student_001 · sesión activa"; tarjeta "● Conectado".
- [ ] Reiniciar la app: sigue con sesión; no aparece el formulario. (pendiente)
- [x] "Ajustes del teclado" abre el menú antiguo con flecha atrás.
- [x] "Cerrar sesión" vuelve al formulario.

## Teclado — mensajes
- [ ] Primera apertura del teclado tras login: globo ámbar "Sombrea el texto…" que se cierra solo (~6 s) y no vuelve. (pendiente: pref onboardingHintShown ya estaba en true en el OnePlus)
- [ ] IA sin selección: globo ámbar "Sombrea el texto que quieres corregir y toca IA." se cierra solo (~4 s). (pendiente)
- [ ] IA en campo de contraseña (login de cualquier app): "Aquí no se puede usar la corrección." (pendiente)
- [x] IA con texto ya correcto: globo verde "Tu texto ya está bien escrito" ~3 s; el teclado no se colapsa.
- [x] IA con la IA detenida (`..\local.ps1 Stop` solo IA): globo rojo "Sin conexión con la IA." con ↻ Reintentar y ✕.
- [ ] Sin sesión iniciada, tocar IA muestra el globo "Inicia sesión en la app del teclado para usar la corrección."; con sesión vencida (borrar token o esperar), "Tu sesión terminó…". Ambos ámbar con ✕. (pendiente)

## Teclado — globos (probar en Google Keep, WhatsApp y Chrome)
- [x] Al llegar sugerencias: las teclas desaparecen, la app ocupa toda la pantalla, aparecen avatar + hasta 3 globos con cambios resaltados; el primero dice "Recomendada · N cambios".
- [x] Tocar fuera de los globos llega a la app (mover cursor, tocar botones de la app).
- [x] Toque corto en un globo: reemplaza el texto, vuelven las teclas, tira "✓ Corregido · ↶ Deshacer" ~3 s.
- [x] ↶ Deshacer dentro de los 3 s restaura el texto original.
- [ ] Escribir una tecla después de aplicar retira la tira sin deshacer. (pendiente)
- [x] "✎ Editar": aplica la recomendada, vuelven las teclas, tira "✎ Editando · ✓ Listo · ↶ Deshacer"; editar en el campo real; "✓ Listo" cierra la tira (el portal docente muestra la sesión como editada).
- [ ] Pulsación larga en "Otra opción": aplica esa opción y entra en edición. (pendiente)
- [ ] En edición, mover el cursor fuera del texto editado cierra la tira (Listo implícito) sin perder el texto. (pendiente)
- [x] "Dejar como está": los globos se van, el texto no cambia, vuelven las teclas.
- [x] Botón atrás con globos visibles: se van con el teclado; volver a tocar el mismo campo los muestra de nuevo.
- [ ] Cambiar de app con globos visibles: al volver al teclado no hay globos (estado limpio). (pendiente)
- [x] Arrastrar el avatar mueve el grupo; al reabrir, la posición se recuerda; nunca sale de la pantalla.
- [ ] Accesibilidad → fuente para dislexia y teclas grandes: los globos usan la fuente y siguen legibles. (pendiente)
- [x] Modo oscuro del sistema: globos legibles (paleta oscura).
- [x] Ninguna pantalla muestra "Cloud Run", "backend" ni "desplegado".
