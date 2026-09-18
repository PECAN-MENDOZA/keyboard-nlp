# Keyboard NLP

Keyboard NLP is a work-in-progress personalized digital keyboard for Android.

This project is based on [FlorisBoard](https://github.com/florisboard/florisboard),
an open-source Android keyboard. The original attribution and Apache License 2.0
are preserved in [LICENSE](LICENSE).

The repository has been prepared as a clean base for future customization while
keeping the functional keyboard core intact.

## Build

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux or macOS:

```bash
./gradlew :app:assembleDebug
```

## Prueba en dispositivo: pruebas de oraciones (OnePlus)

Procedimiento para repetir en un teléfono real una **prueba de oraciones** (plan
`docs/superpowers/plans/2026-09-17-pruebas-de-oraciones.md`, protocolo
`documentos/protocolo-piloto-teclado.md`). Sustituye al modo experimental anterior (códigos
temporales, `ExperimentScreen`), eliminado el 2026-09-17. Todos los comandos son de `adb`/PowerShell.

### 1. Stack local

`.\local.ps1 Start` desde `C:\Users\Dovamul\Desktop\TESIS` con `RESEARCHER_EMAIL`/`RESEARCHER_PASSWORD`
en el entorno (ver `README-LOCAL.md`). `Start` deja `adb reverse tcp:8080 tcp:8080` activo para que
el teléfono llegue al backend por `http://127.0.0.1:8080`.

### 2. Instalar el teclado en el teléfono

`.\local.ps1 InstallKeyboard` (compila `:app:assembleDebug` e instala `com.mvptesis.keyboard.debug`).
Activar el IME `com.mvptesis.keyboard.debug/dev.patrickgold.florisboard.FlorisImeService` y anotar
`adb shell dumpsys package com.mvptesis.keyboard.debug | findstr versionName`.

### 3. Investigador: crear, activar y asignar la prueba

En el portal (`http://localhost:5173/research/tests`) o con la API de `backend/docs/research-api.md`
(sección "Pruebas de oraciones (investigador)"): `POST /api/v1/research/tests` → `PUT …/{id}` con las
oraciones (`kind` DICTATED/FREE, `assistance` ASSISTED/UNASSISTED) → `POST …/{id}/activate` →
`POST …/{id}/assignments` con `{"classroomId": …}` o `{"studentIds": […]}`. El alumno de prueba
(`student_001`, PIN demo) debe estar en el salón asignado.

### 4. Los checks en el teléfono

1. **Normal**: sin prueba en curso, corregir texto en cualquier app (Simple Notes) funciona como
   siempre y `correction_sessions.test_response_id` queda `NULL`.
2. **Con ayuda**: Inicio → **Pruebas** → la prueba → **Comenzar prueba** → "Oración 1 de N · Con
   ayuda" → **Comenzar** → escribir → botón IA → aceptar/rechazar/deshacer → **Terminar**. La
   corrección lleva `id_respuesta`; **Terminar** cierra el globo abierto (cuenta como rechazo) y
   está deshabilitado mientras una corrección está en curso ("Corrigiendo…").
3. **Sin ayuda**: "Sin ayuda" en la cabecera; el botón IA muestra "La corrección está desactivada
   en esta oración" y no llama al backend (el backend lo rechazaría con 400).
4. **Oración en blanco**: **Terminar** sin escribir → "¿Dejar esta oración en blanco?" → `skipped`.
5. **Final**: "¡Prueba completada! Gracias." y vuelta al inicio; la prueba deja de aparecer como
   pendiente.

Verificar en la base del backend (`docker exec tesis-postgres psql -U postgres -d florisboard`):

```sql
select status, model_version, app_version, incident_count
from test_attempts order by created_at desc limit 1;

select position, skipped, auto_error_count, suggestions_offered, suggestions_accepted,
       suggestions_rejected, suggestions_undone, duration_from_first_key_ms
from test_responses where attempt_id = (select id from test_attempts order by created_at desc limit 1)
order by position;

select test_response_id from correction_sessions order by created_at desc limit 1;
```

### 5. Casos de robustez (ver `docs/ux-smoke-test.md`, sección "Pruebas de oraciones")

- Cerrar la app a mitad de una oración y reabrir: se reanuda en la misma oración con el texto
  (borrador en prefs). Reiniciar el teléfono: solo cabe **Cancelar (problema técnico)**.
- Sin red al tocar **Terminar**: "No pudimos guardar" → **Reintentar** reenvía la misma
  `completion_key` (el backend no duplica).
- Atrás durante la prueba no cancela; se retoma desde **Pruebas**. Otro alumno que entra en el
  mismo teléfono nunca ve la oración del anterior.
- Cerrar sesión con una prueba en curso pide confirmación.

### 6. Resultado de la ejecución

Registro en `documentos/protocolo-piloto-teclado.md` §5 (DRYRUN-02 por API el 2026-09-17; pasada
con el OnePlus pendiente).
