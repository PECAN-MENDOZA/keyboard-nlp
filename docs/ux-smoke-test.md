Última ejecución: 2026-09-14 — ver .superpowers/sdd/progress.md para lo ya verificado por el controlador.

# Prueba manual de experiencia del alumno (OnePlus)

Requisitos: `..\local.ps1 Start` en verde, `..\local.ps1 InstallKeyboard`, teclado seleccionado.
Marcar cada casilla con la fecha y el resultado.

## App
- [ ] Sin sesión: abre en "Teclado adaptativo" con Alias/PIN; no hay menú de FlorisBoard como inicio.
- [ ] PIN incorrecto: tarjeta roja "Alias o PIN incorrectos…"; el alias se conserva, el PIN se borra; el teclado NO muestra ningún globo.
- [ ] Sin red (modo avión): "No se pudo conectar con el servidor…".
- [ ] Login correcto: el formulario desaparece; "Hola, student_001 · sesión activa"; tarjeta "● Conectado".
- [ ] Reiniciar la app: sigue con sesión; no aparece el formulario.
- [ ] "Ajustes del teclado" abre el menú antiguo con flecha atrás.
- [ ] "Cerrar sesión" vuelve al formulario.

## Teclado — mensajes
- [ ] Primera apertura del teclado tras login: globo ámbar "Sombrea el texto…" que se cierra solo (~6 s) y no vuelve.
- [ ] IA sin selección: globo ámbar "Sombrea el texto que quieres corregir y toca IA." se cierra solo (~4 s).
- [ ] IA en campo de contraseña (login de cualquier app): "Aquí no se puede usar la corrección."
- [ ] IA con texto ya correcto: globo verde "Tu texto ya está bien escrito" ~3 s; el teclado no se colapsa.
- [ ] IA con la IA detenida (`..\local.ps1 Stop` solo IA): globo rojo "Sin conexión con la IA." con ↻ Reintentar y ✕.
- [ ] Sesión vencida (borrar token o esperar): globo ámbar "Tu sesión terminó…" con ✕.

## Teclado — globos (probar en Google Keep, WhatsApp y Chrome)
- [ ] Al llegar sugerencias: las teclas desaparecen, la app ocupa toda la pantalla, aparecen avatar + hasta 3 globos con cambios resaltados; el primero dice "Recomendada · N cambios".
- [ ] Tocar fuera de los globos llega a la app (mover cursor, tocar botones de la app).
- [ ] Toque corto en un globo: reemplaza el texto, vuelven las teclas, tira "✓ Corregido · ↶ Deshacer" ~3 s.
- [ ] ↶ Deshacer dentro de los 3 s restaura el texto original.
- [ ] Escribir una tecla después de aplicar retira la tira sin deshacer.
- [ ] "✎ Editar": aplica la recomendada, vuelven las teclas, tira "✎ Editando · ✓ Listo · ↶ Deshacer"; editar en el campo real; "✓ Listo" cierra la tira (el portal docente muestra la sesión como editada).
- [ ] Pulsación larga en "Otra opción": aplica esa opción y entra en edición.
- [ ] En edición, mover el cursor fuera del texto editado cierra la tira (Listo implícito) sin perder el texto.
- [ ] "Dejar como está": los globos se van, el texto no cambia, vuelven las teclas.
- [ ] Botón atrás con globos visibles: se van con el teclado; volver a tocar el mismo campo los muestra de nuevo.
- [ ] Cambiar de app con globos visibles: al volver al teclado no hay globos (estado limpio).
- [ ] Arrastrar el avatar mueve el grupo; al reabrir, la posición se recuerda; nunca sale de la pantalla.
- [ ] Accesibilidad → fuente para dislexia y teclas grandes: los globos usan la fuente y siguen legibles.
- [ ] Modo oscuro del sistema: globos legibles (paleta oscura).
- [ ] Ninguna pantalla muestra "Cloud Run", "backend" ni "desplegado".
