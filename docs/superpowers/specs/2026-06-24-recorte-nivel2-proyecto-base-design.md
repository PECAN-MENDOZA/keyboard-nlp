# Recorte Nivel 2 del proyecto base FlorisBoard

Fecha: 2026-06-24
Rama: mvp
Estado: Diseño aprobado (pendiente revisión final del usuario)

## Contexto y objetivo

El proyecto es un fork educativo de FlorisBoard cuya única función propia es el flujo
de **Corrección IA** (módulo `education/`). El objetivo es **simplificar la experiencia
del estudiante y reducir el código/peso de la app** eliminando módulos del teclado base
que no aportan al caso de uso educativo.

El menú principal de Ajustes (`app/settings/HomeScreen.kt`) ya oculta varias secciones
para enfoque de tesis. Este trabajo va más allá: **elimina de verdad** los módulos
autocontenidos (pantalla + panel + backend), no solo los oculta.

Restricción clave: el **botón de Corrección IA vive dentro de la Smartbar**, por lo que
la barra Smartbar NO se elimina. Tampoco se toca el núcleo del teclado (motor de teclado,
temas, entrada/feedback, editor, ventana IME, popups, ciclo de vida, NLP).

## Alcance

### Módulos a eliminar (6)

1. **Emoji / Multimedia**
   - Eliminar: panel `ime/media/` (subcarpetas `emoji/` y `emoticon/`, `MediaInputLayout.kt`),
     pantalla `app/settings/media/MediaScreen.kt` y su ruta, la tecla de cambio a emoji del
     teclado y su manejo de `KeyCode`, y las sugerencias de emoji en la Smartbar/NLP.
   - Limpiar prefs de emoji en `AppPrefs.kt` (skin tone, historial, tipos de sugerencia).

2. **Portapapeles — historial (eliminación PARCIAL)**
   - Eliminar: panel de historial (`ime/clipboard/ClipboardInputLayout.kt`, `ClipboardHistory.kt`),
     pantalla `app/settings/clipboard/ClipboardScreen.kt` y su ruta, la acción de portapapeles
     en la Smartbar (quickaction) y las sugerencias de portapapeles.
   - **Conservar**: el wrapper básico del portapapeles del sistema dentro de `ClipboardManager`
     del que dependen las teclas copiar/cortar/pegar. El copiar/pegar del sistema sigue funcionando.

3. **Gestos / Glide typing (eliminación DIRIGIDA)**
   - Eliminar: el clasificador de escritura por deslizamiento (glide) en `ime/text/gestures/`
     y la pantalla `app/settings/gestures/GesturesScreen.kt` con su ruta.
   - **Conservar**: los swipes básicos de entrada (borrar deslizando, mover cursor en la barra
     espaciadora) para no romper la interacción del teclado.

4. **Gestión de extensiones (UI)**
   - Eliminar: pantallas `app/ext/` (`Home`, `List`, `Edit`, `View`, `Import`, `Export`,
     `CheckUpdates`) y sus rutas.
   - **Conservar**: `lib/ext/` (framework de carga de temas y teclados integrados).

5. **Devtools / depuración**
   - Eliminar: pantallas `app/devtools/` (`DevtoolsScreen`, `AndroidLocales`, `AndroidSettings`,
     `ExportDebugLog`), sus rutas y el gate por flag `prefs.devtools.enabled`.

6. **Pantalla de configuración de Smartbar**
   - Eliminar: `app/settings/smartbar/SmartbarScreen.kt` y su ruta.
   - **Conservar**: el módulo `ime/smartbar/` (la barra y el botón IA).

### Fuera de alcance (se conservan)

- Núcleo del teclado: `ime/keyboard/`, `ime/theme/`, `ime/input/`, `ime/editor/`,
  `ime/window/`, `ime/popup/`, `ime/nlp/`, `ime/lifecycle/`, `ime/landscapeinput/`.
- Smartbar (`ime/smartbar/`) y el botón de Corrección IA.
- Módulo educativo (`education/`) y su pantalla.
- Diccionario de usuario y Backup/Restaurar (no se eliminan en este recorte).

## Enfoque de implementación

Eliminación **incremental, módulo por módulo**, recompilando tras cada uno para aislar
roturas. Por cada módulo:

1. Borrar los archivos de UI/panel/backend propios del módulo.
2. Quitar su entrada en `Routes.kt` y su registro en el NavGraph/NavHost.
3. Limpiar referencias cruzadas: teclas/acciones de teclado (`KeyCode`), acciones rápidas
   de la Smartbar, proveedores de sugerencias NLP, y preferencias en `AppPrefs.kt`.
4. Recompilar (`gradlew assembleDebug`) y corregir referencias rotas antes de continuar.

**Orden (de menor a mayor riesgo):**
Devtools → Config Smartbar → Extensiones UI → Gestos/Glide → Emoji/Multimedia → Portapapeles (historial).

## Riesgos y mitigaciones

- **Romper copiar/pegar al tocar el portapapeles** → recorte parcial: quitar solo
  historial/UI/prefs, mantener el wrapper del portapapeles del sistema en `ClipboardManager`.
- **Tecla de emoji huérfana** → quitar la tecla del layout y su manejo de `KeyCode`.
- **Romper swipes básicos al quitar gestos** → eliminar solo el glide, conservar swipes de entrada.
- **Preferencias colgadas** → limpiar `AppPrefs.kt` de las prefs de los módulos eliminados.
- **Referencias de extensiones a temas/teclados** → conservar `lib/ext/`, eliminar solo la UI.

## Verificación

- Compilación correcta (`gradlew assembleDebug`) tras cada módulo eliminado.
- Prueba manual: abrir el teclado, escribir, copiar/pegar, y ejecutar el flujo completo de
  Corrección IA (login → sombrear texto → botón IA → sugerencias → aplicar). Debe quedar intacto.
- Los tests existentes de `education/` deben seguir pasando.
- Verificar que no quedan rutas muertas ni entradas de menú apuntando a pantallas eliminadas.
