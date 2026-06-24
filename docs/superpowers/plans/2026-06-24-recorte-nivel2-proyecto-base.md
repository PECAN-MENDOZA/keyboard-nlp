# Recorte Nivel 2 del proyecto base FlorisBoard — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminar módulos del FlorisBoard base que no aportan al caso de uso educativo (devtools, pantallas de ajustes huérfanas, panel de emoji/multimedia y panel de historial de portapapeles), conservando intacto el núcleo del teclado, la Smartbar con el botón de Corrección IA, el copiar/pegar del sistema y el módulo `education/`.

**Architecture:** Eliminación incremental por módulo. Como es trabajo de borrado/refactor (no features nuevas), el "ciclo de prueba" de cada tarea es: borrar/editar → compilar (`./gradlew :app:assembleDebug`) → corregir referencias que el compilador de Kotlin marque → compilar limpio → smoke test manual → commit. El compilador de Kotlin (exhaustive `when`, referencias sin resolver) es la red de seguridad principal.

**Tech Stack:** Kotlin, Jetpack Compose, Android IME, Gradle. Navegación con `androidx.navigation` (rutas serializables en `Routes.kt`). Preferencias con JetPref (`AppPrefs.kt`).

## Global Constraints

- **NO tocar** el núcleo: `ime/keyboard/` (motor), `ime/theme/`, `ime/input/`, `ime/editor/`, `ime/window/` (salvo el branch puntual indicado), `ime/popup/`, `ime/nlp/` (salvo el desmontaje puntual de proveedores indicado), `ime/lifecycle/`, `ime/landscapeinput/`.
- **NO tocar** la Smartbar (`ime/smartbar/`) ni la función `EducationalCorrectionButton()` en `ime/smartbar/Smartbar.kt:289-315` ni sus 4 puntos de colocación (`Smartbar.kt:364,373,385,399`).
- **NO tocar** el módulo `education/` ni sus tests en `app/src/test/kotlin/dev/patrickgold/florisboard/education/`.
- **Extensiones (`app/ext/`, `lib/ext/`) FUERA DE ALCANCE** — no eliminar nada. Están entrelazadas con Temas, paquetes de idioma e importación de `.flex`.
- **Código de glide (`ime/text/gestures/GlideTyping*.kt`, `StatisticalGlideTypingClassifier.kt`) FUERA DE ALCANCE** — conservar (ya desactivado). Solo se borra la pantalla de ajustes de Gestos.
- **Conservar copiar/cortar/pegar/seleccionar del sistema**: KeyCodes `CLIPBOARD_COPY/CUT/PASTE/SELECT/SELECT_ALL/CLEAR_PRIMARY_CLIP` y la clase `ClipboardManager.kt` se mantienen.
- Compilar con `./gradlew :app:assembleDebug` (Git Bash) o `.\gradlew.bat :app:assembleDebug` (PowerShell) tras cada tarea. Debe terminar en `BUILD SUCCESSFUL`.
- Mensajes de commit en español, con prefijo `refactor(recorte):`.

---

## Smoke test manual (repetir tras las tareas que tocan el IME — Tareas 3 y 4)

Tras compilar e instalar el APK debug:
1. Abrir cualquier app con campo de texto; el teclado FlorisBoard aparece y escribe normal.
2. Copiar y pegar un texto con las teclas de la Smartbar (Copy/Paste) → funciona.
3. Sombrear texto → tocar botón IA (llave/varita) → aparecen sugerencias → "Usar" reemplaza el texto. El flujo educativo queda intacto.
4. (Tarea 3) Ya no existe acceso a emojis desde el teclado y no hay crash al cambiar de campo.
5. (Tarea 4) Ya no existe el panel de historial de portapapeles; copiar/pegar sigue OK.

---

## File Structure (mapa de cambios)

- **Borrar (directorios/archivos completos):**
  - `app/.../app/devtools/` (5 archivos)
  - `app/.../app/settings/smartbar/SmartbarScreen.kt`
  - `app/.../app/settings/media/MediaScreen.kt`
  - `app/.../app/settings/clipboard/ClipboardScreen.kt`
  - `app/.../app/settings/gestures/GesturesScreen.kt`
  - `app/.../ime/media/` (MediaInputLayout + `emoji/` 9 archivos + `emoticon/` 2 archivos)
  - `app/.../ime/clipboard/ClipboardInputLayout.kt`
- **Modificar:**
  - `app/.../app/Routes.kt` (definiciones + registros NavHost)
  - `app/.../app/settings/advanced/OtherScreen.kt` (quitar enlace a Devtools)
  - `app/.../app/AppPrefs.kt` (quitar grupo `emoji`; quitar `devtools` salvo lo que use el overlay; ver Tarea 1)
  - `app/.../ime/ImeUiMode.kt` (quitar `MEDIA`, `CLIPBOARD`)
  - `app/.../ime/text/key/KeyCode.kt` (quitar `IME_UI_MODE_MEDIA`, `IME_UI_MODE_CLIPBOARD`, `CLIPBOARD_CLEAR_HISTORY`, `CLIPBOARD_CLEAR_FULL_HISTORY`)
  - `app/.../ime/keyboard/KeyboardManager.kt` (quitar handlers de los KeyCodes y branch de modo MEDIA)
  - `app/.../ime/window/ImeWindow.kt` (quitar branches MEDIA/CLIPBOARD del `when`)
  - `app/.../ime/smartbar/quickaction/QuickActionArrangement.kt` (quitar 2 acciones)
  - `app/.../ime/smartbar/quickaction/QuickAction.kt` (quitar mapeos de nombre/tooltip)
  - `app/.../ime/nlp/` (desmontar proveedor de emoji y candidato de portapapeles — ver Tareas 3 y 4)

Rutas base abreviadas: `app/.../` = `app/src/main/kotlin/dev/patrickgold/florisboard/`.

---

### Task 1: Eliminar Devtools

**Files:**
- Delete: `app/.../app/devtools/AndroidLocalesScreen.kt`, `AndroidSettingsScreen.kt`, `DevtoolsOverlay.kt`, `DevtoolsScreen.kt`, `ExportDebugLogScreen.kt`
- Modify: `app/.../app/Routes.kt` (defs líneas 215-229; registros líneas 340-346)
- Modify: `app/.../app/settings/advanced/OtherScreen.kt:163-168` (enlace entrante)
- Modify: `app/.../app/AppPrefs.kt:199-233` (grupo `devtools`)

**Interfaces:**
- Consumes: nada de tareas previas.
- Produces: nada que tareas posteriores necesiten.

- [ ] **Step 1: Comprobar el baseline compila**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Si falla aquí, el árbol ya estaba roto: detener y reportar.

- [ ] **Step 2: Revisar quién usa `prefs.devtools` antes de borrar el grupo de prefs**

Buscar referencias para no romper el overlay de depuración del teclado:

Run: `git grep -n "prefs.devtools" app/src/main/kotlin`

Expected: referencias en `app/devtools/*` (se borran) y posiblemente en `ime/` (overlays de depuración: `DevtoolsOverlay`, `ImeRootView`, key touch boundaries). **Si hay referencias dentro de `ime/`, NO borres el grupo `devtools` de `AppPrefs.kt`** (déjalo; son flags internos baratos). Solo borra el grupo si las únicas referencias están en `app/devtools/`. Anota la decisión en el commit.

- [ ] **Step 3: Borrar el enlace entrante en OtherScreen**

En `app/.../app/settings/advanced/OtherScreen.kt`, eliminar el bloque `Preference` que navega a Devtools (alrededor de la línea 163-168):

```kotlin
Preference(
    icon = Icons.Default.Adb,
    title = stringRes(R.string.devtools__title),
    onClick = { navController.navigate(Routes.Devtools.Home) },
)
```

Eliminar también cualquier `import` que quede sin usar (`Icons.Default.Adb`, `Routes.Devtools` si no se usa más en el archivo). El compilador con `-Werror` no está activo, pero limpia los imports obvios.

- [ ] **Step 4: Borrar los 5 archivos de `app/devtools/`**

Run: `git rm app/src/main/kotlin/dev/patrickgold/florisboard/app/devtools/AndroidLocalesScreen.kt app/src/main/kotlin/dev/patrickgold/florisboard/app/devtools/AndroidSettingsScreen.kt app/src/main/kotlin/dev/patrickgold/florisboard/app/devtools/DevtoolsOverlay.kt app/src/main/kotlin/dev/patrickgold/florisboard/app/devtools/DevtoolsScreen.kt app/src/main/kotlin/dev/patrickgold/florisboard/app/devtools/ExportDebugLogScreen.kt`

**OJO:** si en el Step 2 viste que `DevtoolsOverlay` se usa desde `ime/` (p. ej. `ImeRootView`), NO lo borres y conserva sus referencias. En ese caso elimina solo las 4 pantallas de ajustes (`AndroidLocalesScreen`, `AndroidSettingsScreen`, `DevtoolsScreen`, `ExportDebugLogScreen`) y deja `DevtoolsOverlay.kt`.

- [ ] **Step 5: Quitar las rutas y registros de NavHost en Routes.kt**

En `app/.../app/Routes.kt`, eliminar el objeto `Devtools` con sus rutas (líneas ~215-229: `Home`, `AndroidLocales`, `AndroidSettings`, `ExportDebugLog`) y los registros del NavHost (líneas ~340-346):

```kotlin
composableWithDeepLink(Devtools.Home::class) { DevtoolsScreen() }
composableWithDeepLink(Devtools.AndroidLocales::class) { AndroidLocalesScreen() }
composableWithDeepLink(Devtools.AndroidSettings::class) { navBackStack ->
    val payload = navBackStack.toRoute<Devtools.AndroidSettings>()
    AndroidSettingsScreen(payload.name)
}
composableWithDeepLink(Devtools.ExportDebugLog::class) { ExportDebugLogScreen() }
```

Eliminar los `import` de esas pantallas al inicio de `Routes.kt`.

- [ ] **Step 6: (Condicional) Quitar el grupo `devtools` de AppPrefs**

Solo si en el Step 2 NO había referencias en `ime/`: eliminar el bloque `val devtools = Devtools()` + `inner class Devtools { ... }` (`AppPrefs.kt:199-233`). Si había referencias en `ime/`, saltar este paso.

- [ ] **Step 7: Compilar y corregir referencias colgantes**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Si el compilador marca referencias a `Routes.Devtools`, `DevtoolsScreen`, etc., eliminarlas en el sitio indicado y recompilar hasta limpio.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(recorte): eliminar pantallas de devtools y su acceso"
```

---

### Task 2: Eliminar pantallas de ajustes huérfanas (Smartbar/Media/Clipboard/Gestures)

Estas 4 pantallas de configuración no tienen navegación entrante (ya estaban ocultas del menú). Se borra solo la **pantalla de ajustes** y su ruta; los módulos IME correspondientes (Smartbar, gestos/glide) se conservan. Los paneles de media y clipboard se eliminan en las Tareas 3 y 4.

**Files:**
- Delete: `app/.../app/settings/smartbar/SmartbarScreen.kt`
- Delete: `app/.../app/settings/media/MediaScreen.kt`
- Delete: `app/.../app/settings/clipboard/ClipboardScreen.kt`
- Delete: `app/.../app/settings/gestures/GesturesScreen.kt`
- Modify: `app/.../app/Routes.kt` (defs: `Settings.Smartbar` ~149-151, `Settings.Gestures` ~174-175, `Settings.Clipboard` ~177-179, `Settings.Media` ~181-183; registros: ~311, ~325, ~327, ~329)

**Interfaces:**
- Consumes: nada.
- Produces: nada.

- [ ] **Step 1: Confirmar que no hay navegación entrante**

Run: `git grep -n "Routes.Settings.Smartbar\|Routes.Settings.Media\|Routes.Settings.Clipboard\|Routes.Settings.Gestures" app/src/main/kotlin`
Expected: solo aparecen las definiciones y registros dentro de `Routes.kt`. Si aparece alguna navegación en otra pantalla, eliminar ese `Preference`/enlace también.

- [ ] **Step 2: Borrar las 4 pantallas**

Run: `git rm app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/smartbar/SmartbarScreen.kt app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/media/MediaScreen.kt app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/clipboard/ClipboardScreen.kt app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/gestures/GesturesScreen.kt`

- [ ] **Step 3: Quitar rutas y registros en Routes.kt**

En `app/.../app/Routes.kt` eliminar las 4 definiciones de objeto (`Smartbar`, `Gestures`, `Clipboard`, `Media` dentro de `Settings`) y sus 4 registros del NavHost:

```kotlin
composableWithDeepLink(Settings.Smartbar::class) { SmartbarScreen() }
composableWithDeepLink(Settings.Gestures::class) { GesturesScreen() }
composableWithDeepLink(Settings.Clipboard::class) { ClipboardScreen() }
composableWithDeepLink(Settings.Media::class) { MediaScreen() }
```

Eliminar los `import` correspondientes a `SmartbarScreen`, `GesturesScreen`, `ClipboardScreen`, `MediaScreen` en `Routes.kt`.

- [ ] **Step 4: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Corregir cualquier referencia colgante que marque el compilador.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(recorte): eliminar pantallas de ajustes huerfanas (smartbar, media, clipboard, gestos)"
```

---

### Task 3: Eliminar el panel de Emoji/Multimedia del teclado

Quita el modo de UI MEDIA, el panel de emoji/emoticon, sus preferencias, el proveedor de sugerencias de emoji y la acción rápida de la Smartbar. Conserva todo el resto del teclado.

**Files:**
- Delete: `app/.../ime/media/MediaInputLayout.kt`
- Delete: `app/.../ime/media/emoji/` (9 archivos: `Emoji.kt`, `EmojiCategory.kt`, `EmojiData.kt`, `EmojiHistory.kt`, `EmojiPaletteView.kt`, `EmojiSet.kt`, `EmojiSuggestionProvider.kt`, `EmojiSuggestionType.kt`, `FlorisEmojiCompat.kt`)
- Delete: `app/.../ime/media/emoticon/` (`EmoticonKeyData.kt`, `EmoticonLayoutData.kt`)
- Modify: `app/.../ime/ImeUiMode.kt:19-29` (quitar `MEDIA`)
- Modify: `app/.../ime/text/key/KeyCode.kt:91` (quitar `IME_UI_MODE_MEDIA`)
- Modify: `app/.../ime/keyboard/KeyboardManager.kt:741` (handler) y `:779-783` (branch de commit en modo MEDIA)
- Modify: `app/.../ime/window/ImeWindow.kt:228` (branch MEDIA del `when`)
- Modify: `app/.../ime/smartbar/quickaction/QuickActionArrangement.kt:77` (acción `IME_UI_MODE_MEDIA`)
- Modify: `app/.../ime/smartbar/quickaction/QuickAction.kt:92,132` (nombre + tooltip)
- Modify: `app/.../app/AppPrefs.kt:247-306` (grupo `emoji`)
- Modify: `app/.../ime/nlp/` (donde se registre `EmojiSuggestionProvider`)

**Interfaces:**
- Consumes: nada de tareas previas (la pantalla `MediaScreen` ya fue borrada en Task 2).
- Produces: tras esta tarea, `ImeUiMode` ya no contiene `MEDIA`; la Tarea 4 quitará `CLIPBOARD` y dejará el enum con solo `TEXT`.

- [ ] **Step 1: Localizar el registro del proveedor de sugerencias de emoji**

Run: `git grep -n "EmojiSuggestionProvider\|EmojiData\|EmojiPaletteView\|ImeUiMode.MEDIA\|IME_UI_MODE_MEDIA" app/src/main/kotlin`
Expected: una lista de sitios. Anota todos los que estén FUERA de `ime/media/` (especialmente en `ime/nlp/NlpManager.kt` o `NlpProviders.kt`, `KeyboardManager.kt`, `ImeWindow.kt`, `quickaction/`). Esos son los puntos a desmontar; los de dentro de `ime/media/` desaparecen al borrar la carpeta.

- [ ] **Step 2: Desmontar el proveedor de emoji en NLP**

En el archivo de `ime/nlp/` que instancie/registre `EmojiSuggestionProvider` (visto en el Step 1), eliminar su import, su construcción y cualquier rama que lo consulte (p. ej. una lista de providers o un `when` por tipo de sugerencia). Dejar el resto del flujo NLP intacto. No recompilar aún.

- [ ] **Step 3: Quitar la acción rápida de emoji de la Smartbar**

En `app/.../ime/smartbar/quickaction/QuickActionArrangement.kt` eliminar la línea (≈77):

```kotlin
QuickAction.InsertKey(TextKeyData.IME_UI_MODE_MEDIA),
```

En `app/.../ime/smartbar/quickaction/QuickAction.kt` eliminar los mapeos (≈92 y ≈132):

```kotlin
KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media
KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media__tooltip
```

- [ ] **Step 4: Quitar el branch de UI en ImeWindow**

En `app/.../ime/window/ImeWindow.kt:226-230`, eliminar la rama MEDIA del `when (state.imeUiMode)`:

```kotlin
ImeUiMode.MEDIA -> ProvideActualLayoutDirection { MediaInputLayout() }
```

(Dejar la rama `CLIPBOARD` por ahora; se elimina en la Tarea 4.) Quitar el import de `MediaInputLayout`.

- [ ] **Step 5: Quitar handlers en KeyboardManager**

En `app/.../ime/keyboard/KeyboardManager.kt:740-742` eliminar:

```kotlin
KeyCode.IME_UI_MODE_MEDIA -> activeState.imeUiMode = ImeUiMode.MEDIA
```

En `KeyboardManager.kt:779-783`, eliminar la rama que trata el modo MEDIA en el commit de caracteres:

```kotlin
if (activeState.imeUiMode == ImeUiMode.MEDIA) {
    nlpManager.getAutoCommitCandidate()?.let { commitCandidate(it) }
    editorInstance.commitText(data.asString(isForDisplay = false))
    return@batchEdit
}
```

(Conservar el `else`/resto de la lógica de commit normal.)

- [ ] **Step 6: Quitar el valor del enum y el KeyCode**

En `app/.../ime/ImeUiMode.kt` quitar `MEDIA(1)` (y reajustar el `companion object`/`fromInt` si mapea por índice — revisar que el `value` 1 no se asuma en otro lado).
En `app/.../ime/text/key/KeyCode.kt:91` quitar `const val IME_UI_MODE_MEDIA = -212`.

- [ ] **Step 7: Borrar la carpeta `ime/media/`**

Run: `git rm -r app/src/main/kotlin/dev/patrickgold/florisboard/ime/media`

- [ ] **Step 8: Quitar el grupo `emoji` de AppPrefs**

En `app/.../app/AppPrefs.kt:247-306`, eliminar `val emoji = Emoji()` y toda la `inner class Emoji { ... }`. Quitar imports que queden sin uso (`EmojiSkinTone`, `EmojiHairStyle`, `EmojiHistory`, `EmojiSuggestionType`).

- [ ] **Step 9: Compilar y resolver todo lo colgante**

Run: `./gradlew :app:assembleDebug`
Expected: el compilador marcará cualquier referencia restante a `ImeUiMode.MEDIA`, `EmojiData`, `TextKeyData.IME_UI_MODE_MEDIA`, `prefs.emoji`, etc. Resolver cada una (eliminar uso) y recompilar hasta `BUILD SUCCESSFUL`. Buscar también `TextKeyData.IME_UI_MODE_MEDIA` por si existe la constante:

Run: `git grep -n "IME_UI_MODE_MEDIA\|prefs.emoji\|ime/media/emoji" app/src/main/kotlin`
Expected: sin resultados al terminar.

- [ ] **Step 10: Smoke test manual**

Compilar/instalar y ejecutar el smoke test (sección superior). Verificar que el teclado abre, escribe, copia/pega, el botón IA funciona, y no hay acceso ni crash relacionado con emoji.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "refactor(recorte): eliminar panel de emoji/multimedia del teclado"
```

---

### Task 4: Eliminar el panel de historial de Portapapeles (conservando copiar/pegar)

Quita el modo de UI CLIPBOARD, el panel de historial, su acción rápida y el candidato de sugerencia de portapapeles. **Conserva** `ClipboardManager`, la base de datos/proveedores y todas las operaciones copiar/cortar/pegar/seleccionar del sistema.

**Files:**
- Delete: `app/.../ime/clipboard/ClipboardInputLayout.kt`
- Modify: `app/.../ime/ImeUiMode.kt` (quitar `CLIPBOARD`; el enum queda con solo `TEXT`)
- Modify: `app/.../ime/text/key/KeyCode.kt:92` (quitar `IME_UI_MODE_CLIPBOARD`) y `:67-68` (quitar `CLIPBOARD_CLEAR_HISTORY = -36`, `CLIPBOARD_CLEAR_FULL_HISTORY = -37`)
- Modify: `app/.../ime/keyboard/KeyboardManager.kt:742` (handler de modo) y `:717-718` (handlers de clear history)
- Modify: `app/.../ime/window/ImeWindow.kt:229` (branch CLIPBOARD del `when`)
- Modify: `app/.../ime/smartbar/quickaction/QuickActionArrangement.kt:76` (acción `IME_UI_MODE_CLIPBOARD`)
- Modify: `app/.../ime/smartbar/quickaction/QuickAction.kt:91,131` (nombre + tooltip)
- Modify: `app/.../ime/nlp/SuggestionCandidate.kt:124-149` (clase `ClipboardSuggestionCandidate`) y su uso en `NlpManager`

**Interfaces:**
- Consumes: tras la Tarea 3, `ImeUiMode` solo tiene `TEXT` y `CLIPBOARD`. Esta tarea quita `CLIPBOARD`.
- Produces: enum `ImeUiMode` con un único valor `TEXT`; el `when` de `ImeWindow` puede simplificarse a expresión directa.

- [ ] **Step 1: Mapear referencias de historial vs operaciones básicas**

Run: `git grep -n "ImeUiMode.CLIPBOARD\|IME_UI_MODE_CLIPBOARD\|ClipboardInputLayout\|ClipboardSuggestionCandidate\|clearHistory\|clearFullHistory\|CLIPBOARD_CLEAR_HISTORY\|CLIPBOARD_CLEAR_FULL_HISTORY" app/src/main/kotlin`
Expected: lista de sitios a desmontar. **NO** toques las que usen `CLIPBOARD_COPY/CUT/PASTE/SELECT/SELECT_ALL/CLEAR_PRIMARY_CLIP` ni `performClipboardCopy/Cut/Paste/SelectAll` en `EditorInstance.kt` (esos se conservan).

- [ ] **Step 2: Desmontar el candidato de sugerencia de portapapeles**

En `app/.../ime/nlp/SuggestionCandidate.kt` eliminar la `data class ClipboardSuggestionCandidate` (≈124-149). En `NlpManager` (o donde se construya) eliminar el código que crea/emite ese candidato hacia la Smartbar. Mantener intacto el resto de candidatos.

- [ ] **Step 3: Quitar la acción rápida de portapapeles (panel) de la Smartbar**

En `QuickActionArrangement.kt` eliminar (≈76):

```kotlin
QuickAction.InsertKey(TextKeyData.IME_UI_MODE_CLIPBOARD),
```

**Conservar** las acciones `CLIPBOARD_COPY/CUT/PASTE/SELECT_ALL/CLEAR_PRIMARY_CLIP` que están en la misma lista.
En `QuickAction.kt` eliminar los mapeos (≈91 y ≈131):

```kotlin
KeyCode.IME_UI_MODE_CLIPBOARD -> R.string.quick_action__ime_ui_mode_clipboard
KeyCode.IME_UI_MODE_CLIPBOARD -> R.string.quick_action__ime_ui_mode_clipboard__tooltip
```

- [ ] **Step 4: Quitar el branch de UI en ImeWindow y simplificar el `when`**

En `app/.../ime/window/ImeWindow.kt:226-230`, eliminar la rama CLIPBOARD. Como tras la Tarea 3 ya no hay MEDIA, el `when` queda solo con `TEXT`; convertirlo en llamada directa:

```kotlin
// Antes (con ramas MEDIA/CLIPBOARD ya removidas):
//   when (state.imeUiMode) { ImeUiMode.TEXT -> TextInputLayout() }
// Después:
TextInputLayout()
```

Quitar el import de `ClipboardInputLayout`.

- [ ] **Step 5: Quitar handlers en KeyboardManager**

En `app/.../ime/keyboard/KeyboardManager.kt` eliminar:
- (≈742) `KeyCode.IME_UI_MODE_CLIPBOARD -> activeState.imeUiMode = ImeUiMode.CLIPBOARD`
- (≈717) `KeyCode.CLIPBOARD_CLEAR_HISTORY -> clipboardManager.clearHistory()`
- (≈718) `KeyCode.CLIPBOARD_CLEAR_FULL_HISTORY -> clipboardManager.clearFullHistory()`

**Conservar** los handlers `CLIPBOARD_CUT/COPY/PASTE/SELECT/SELECT_ALL/CLEAR_PRIMARY_CLIP`.

- [ ] **Step 6: Quitar valores de enum y KeyCodes**

En `app/.../ime/ImeUiMode.kt` quitar `CLIPBOARD(2)` (el enum queda con solo `TEXT(0)`; revisar `fromInt`/companion para que no asuma índices ausentes).
En `app/.../ime/text/key/KeyCode.kt` quitar `IME_UI_MODE_CLIPBOARD = -213`, `CLIPBOARD_CLEAR_HISTORY = -36`, `CLIPBOARD_CLEAR_FULL_HISTORY = -37`.

- [ ] **Step 7: Borrar el panel de historial**

Run: `git rm app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardInputLayout.kt`

Nota: NO borrar `ClipboardManager.kt`, `ClipboardHistory.kt`, `ClipboardSyncBehavior.kt`, `FlorisCopyToClipboardActivity.kt` ni `provider/*` — son backend/núcleo usado por copiar/pegar o declarados en el manifest. `clearHistory()/clearFullHistory()` en `ClipboardManager.kt` pueden quedar sin llamadas (inofensivo) o eliminarse si el compilador no las requiere; preferir dejarlas para minimizar superficie de cambio.

- [ ] **Step 8: Compilar y resolver lo colgante**

Run: `./gradlew :app:assembleDebug`
Expected: resolver referencias restantes a `ImeUiMode.CLIPBOARD`, `ClipboardInputLayout`, `ClipboardSuggestionCandidate`, `IME_UI_MODE_CLIPBOARD`. Recompilar hasta `BUILD SUCCESSFUL`. Verificar:

Run: `git grep -n "ImeUiMode.CLIPBOARD\|IME_UI_MODE_CLIPBOARD\|ClipboardInputLayout\|ClipboardSuggestionCandidate" app/src/main/kotlin`
Expected: sin resultados.

- [ ] **Step 9: Smoke test manual (énfasis en copiar/pegar)**

Compilar/instalar. Ejecutar el smoke test completo. **Crítico:** copiar y pegar con las teclas de la Smartbar debe seguir funcionando; ya no debe existir panel de historial de portapapeles; el botón IA y el flujo educativo intactos.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(recorte): eliminar panel de historial de portapapeles (conservar copiar/pegar)"
```

---

### Task 5: Limpieza final y verificación

**Files:**
- Modify (opcional): `app/.../app/AppPrefs.kt` (prefs que hayan quedado sin uso), recursos `strings.xml` huérfanos.

**Interfaces:**
- Consumes: estado tras Tareas 1-4.
- Produces: árbol limpio y compilable.

- [ ] **Step 1: Buscar imports/recursos huérfanos evidentes**

Run: `git grep -n "MediaScreen\|ClipboardScreen\|GesturesScreen\|SmartbarScreen\|DevtoolsScreen\|EmojiSuggestionProvider" app/src/main/kotlin`
Expected: sin resultados (todo eliminado).

- [ ] **Step 2: Ejecutar la suite de tests existente (especialmente education/)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; los tests de `education/` (CorrectionSessionResponseTest, EditorTextExtractorTest, EducationalSessionTest) pasan. Si algún test del base referenciaba módulos borrados, ajustarlo o eliminarlo si corresponde al alcance.

- [ ] **Step 3: Build limpio final**

Run: `./gradlew clean :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit final (si hubo limpieza)**

```bash
git add -A
git commit -m "refactor(recorte): limpieza final de referencias y recursos huerfanos"
```

---

## Notas de verificación cruzada (del análisis del código)

- `ImeUiMode` (en `ime/ImeUiMode.kt`) y su `fromInt`/companion: al quitar valores, revisar que ningún código asuma los enteros 1/2. Si el estado del teclado se serializa por `value`, mantener `TEXT(0)` con valor 0 evita migraciones.
- El `EducationalCorrectionButton()` y sus 4 colocaciones en `Smartbar.kt` son independientes de emoji/clipboard/glide: no se tocan.
- `GlideTypingGesture.Detector` se conserva (glide fuera de alcance); no eliminar `ime/text/gestures/`.
- Extensiones (`app/ext/`, `lib/ext/`) se conservan: `AddonBox`, `Ext.Import/View/Export/Edit` los usan Temas y paquetes de idioma.
