# Instalar Pocket TTS desde Princess

En **Princess → Narrar texto**, GlideLive prepara el motor y el paquete español.
No hace falta buscar el APK en un navegador. La instalación utiliza Pocket TTS
Android Engine 0.5.5, de su repositorio original:
https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/tag/v0.5.5

## Instalación del motor

1. Pulsa **Instalar Pocket TTS**. GlideLive descarga el APK y comprueba su tamaño,
   SHA-256, nombre de paquete y versión antes de abrir el instalador de Android.
2. Si Android lo pide, autoriza a GlideLive a instalar aplicaciones de esta
   fuente y vuelve. GlideLive continúa cuando detecta el permiso; también queda
   disponible **Continuar instalación de Pocket TTS** para reintentar.
3. Confirma la instalación en la pantalla de Android. La app no puede confirmar
   esa pantalla en nombre del usuario.
4. Al volver a GlideLive se detecta el motor instalado y se selecciona Pocket.
   Si la instalación se cancela o falla, no se marca como instalado.

## Paquete español

1. Pulsa **Descargar español y abrir Pocket**. GlideLive descarga y verifica el
   ZIP español FP32 (207.487.086 bytes), lo guarda y abre Pocket.
2. En Pocket pulsa **Import language pack (.zip)** (Importar paquete de idioma) y elige
   **PocketTTS-spanish-FP32.zip**. En Android 10 o posterior se encuentra en
   **Descargas/GlideLive/Pocket**. En Android 8/9, en
   **Android/data/com.glidelive/files/Download/Pocket**.
3. Vuelve a GlideLive. Las voces se actualizan al volver a primer plano; no es
   necesario cerrar y volver a abrir el diálogo. También hay actualización manual.
4. Elige la voz y genera una muestra. La voz base española es Lola; instalar el
   motor no instala automáticamente una biblioteca de personajes de cuentos.

El ZIP se verifica antes de publicarlo en Descargas. Una descarga incompleta se
elimina y no se ofrece para importar. Si el archivo descargado y verificado sigue
disponible, se reutiliza. No se modifica el paquete Supertonic que ya existe.

## Otras voces

En Pocket selecciona el paquete español y pulsa **Import reference voice (.wav)** (Importar voz de referencia). Los perfiles añadidos aparecen como voces distintas en GlideLive al
volver a primer plano. La interpretación depende de la referencia y del modelo;
GlideLive no añade cambios de pitch ni estiramiento en esta ruta de generación.

## Por qué queda una selección de archivo en Pocket

Se revisó el código de `MainActivity.kt` y `AndroidManifest.xml` de Pocket 0.5.5.
La app importa modelos y referencias mediante su selector de documentos interno.
No expone un intent público para importar directamente un ZIP o WAV. Su único
receptor `ACTION_SEND` es lectura de **texto plano**, no importación de voces.
Por eso GlideLive descarga y abre Pocket con instrucciones, pero no afirma haber
importado automáticamente un archivo que Pocket todavía no ha aceptado.

## Verificación pendiente en el teléfono

Instalar con el permiso inicialmente denegado y luego permitido, cancelar y
reintentar el instalador, volver desde Pocket tras importar el ZIP y un WAV,
comprobar detección automática, y generar/guardar una voz española sin conexión.
Las pruebas de descarga/compilación no sustituyen estos pasos Android.
