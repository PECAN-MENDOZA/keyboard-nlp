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

## Prueba en dispositivo: modo experimental (OnePlus)

Procedimiento para repetir en un teléfono real la escritura controlada ("Participar en una
prueba") de las tres condiciones: normal, `ASSISTED` y `UNASSISTED`. Todos los comandos son de
`adb`/PowerShell, sin editor de código en el teléfono.

### 1. Stack local

Desde `C:\Users\Dovamul\Desktop\TESIS` (no desde este repo):

```powershell
.\local.ps1 Start   # backend + Postgres + (si está disponible) la IA
.\local.ps1 Check    # confirma que sigue arriba
adb reverse tcp:8080 tcp:8080
```

La IA (`IA-Correcci-n-Contextual`) es opcional para las pruebas `ASSISTED`; no tiene `/health`, se
sondea con una corrección real:

```powershell
$body = @{ originalText = 'prueba local'; studentId = 'local-health' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:5000/interno/corregir' `
    -ContentType 'application/json' -Body $body -TimeoutSec 90
```

### 2. Instalar el teclado en el teléfono

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell ime set com.mvptesis.keyboard.debug/dev.patrickgold.florisboard.FlorisImeService
```

El último comando hay que repetirlo cada vez que se reinstala el APK o tras un
`am force-stop` (ver "Casos de robustez" abajo): Android vuelve al IME por defecto
(LatinIME) y hay que reseleccionar FlorisBoard.

### 3. Investigador: crear estudio, participante y código

Con las rutas de `backend/docs/research-api.md` (sección "Participantes y códigos"). No pegar
contraseñas ni tokens en este README:

```http
POST /api/v1/auth/staff/login                                              → token de investigador
POST /api/v1/research/studies                                              → studyId
POST /api/v1/research/studies/{studyId}/protocols                          → protocolId
POST /api/v1/research/studies/{studyId}/protocols/{protocolId}/activate
POST /api/v1/research/studies/{studyId}/participants                       → participantId (P-001…)
POST /api/v1/research/studies/{studyId}/participants/{participantId}/access-code
                                                                             → código de 8 caracteres, una sola vez
```

El código canjeado en el teléfono asigna automáticamente la condición y la consigna (el alumno
nunca las elige): la primera sesión de cada participante es `TASK_A`, la segunda `TASK_B`, y el
orden asistida/sin-asistencia lo fija la secuencia del participante (impar → `ASSISTED_FIRST`).

### 4. Los tres checks del plan

1. **Normal** (sin canjear ningún código): corregir texto en cualquier app con Simple Notes o
   similar; debe comportarse exactamente igual que sin modo experimental.
2. **Asistida** (`ASSISTED`): canjear el código en "Participar en una prueba" → Comenzar →
   escribir → usar la IA (crea `correction_session` con `experiment_run_id`) → "Finalizar y
   guardar".
3. **Sin asistencia** (`UNASSISTED`): igual pero el botón IA muestra "La corrección está
   desactivada en esta tarea" y no llama al backend.

Verificar en la base del backend después de cada ejecución:

```sql
select status, duration_ms, incident_count, failure_reason, left(final_text, 60)
from experiment_runs order by created_at desc limit 1;

select experiment_run_id from correction_sessions order by created_at desc limit 1;
```

`experiment_run_id` debe llevar el id de la ejecución en la prueba asistida y quedar `NULL` en una
corrección normal posterior a una prueba cancelada o guardada.

### 5. Casos de robustez

- **Restauración PENDING/ACTIVE tras matar el proceso**: `adb shell am force-stop
  com.mvptesis.keyboard.debug`, reseleccionar el IME (paso 2), abrir el deeplink
  `ui://florisboard/settings/experiment` (o navegar a "Participar en una prueba"): una ejecución
  `PENDING` vuelve a la pantalla de confirmación, una `ACTIVE` vuelve a la tarea con el cronómetro
  continuado y el texto escrito hasta ese momento (borrador persistido en disco,
  `PrefsExperimentDraftStore`).
- **Cancelación con motivo**: desde la tarea, "Cancelar prueba" → elegir motivo (abandono,
  problema técnico, interrupción) → confirmar; el backend queda `CANCELLED` con `failure_reason`.
- **Cronómetro perdido tras reiniciar el teléfono**: la duración medida con
  `elapsedRealtime()` no es comparable entre arranques; la pantalla lo muestra como "cronómetro
  perdido" y solo ofrece "Cancelar (problema técnico)", no finalizar.

### 6. Resultado de la ejecución del 2026-09-15 (OnePlus 6, backend + IA locales)

| Caso | Resultado |
|---|---|
| Asistida (P-001, TASK_A) | `COMPLETED`, `duration_ms = 458273`, texto final guardado, `app_version = 0.6.0-debug+3afd841`, incidencia previa `AI_REQUEST_FAILED` conservada (`incident_count = 1`) |
| Sin asistencia (P-001, TASK_B) — botón IA | Aviso local "La corrección está desactivada en esta tarea"; ninguna `correction_session` creada (229 → 229) |
| Sin asistencia — restauración PENDING tras `force-stop` | OK, vuelve a la pantalla de confirmación |
| Sin asistencia — restauración ACTIVE tras `force-stop` | OK, cronómetro continuado (2:05) |
| Sin asistencia — cancelación | Diálogo con 3 motivos → backend `CANCELLED`, `failure_reason = "Cancelled by the student: interrupted"` |
| Corrección normal tras cancelar | Sesión nueva con `experiment_run_id = NULL` |

Hueco encontrado y cerrado en esta tarea: tras `force-stop` en `ACTIVE` el cronómetro se
restauraba pero el texto escrito se perdía (`ExperimentDraft` vivía solo en memoria); ahora se
guarda en disco por ejecución (`education/PrefsExperimentDraftStore.kt`).
