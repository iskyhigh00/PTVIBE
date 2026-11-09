# Probar las voces nuevas en el teléfono

GlideLive ahora puede utilizar un motor TTS de Android instalado por separado.
No incluye Pocket dentro del APK ni descarga su paquete automáticamente.

1. Instala [Pocket TTS Android Engine 0.5.5](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/tag/v0.5.5).
2. En Pocket, descarga o importa su paquete **Spanish FP32** (~207 MB comprimido;
   ~439 MB descomprimido). Este tamaño no es una medición de memoria RAM.
3. Para obtener más voces que la base Lola, crea perfiles de voz en Pocket con
   audios de referencia. Como primera prueba puedes importar las muestras de
   narrador grave y voz alegre generadas durante esta investigación. Cada una
   dura entre 5 y 15 segundos. Es una nueva generación a partir de referencias,
   no el WAV con tono o velocidad alterados.
4. En GlideLive → Princess → Narrar texto, elige **Otro motor Android**, pulsa
   actualizar, elige Pocket y una voz española sin conexión. Elige el personaje
   y genera una toma nueva. Motor y voz se recuerdan para ese personaje.
5. Agrega las tomas a la obra. En el timeline, mantén pulsado un clip para moverlo,
   o selecciónalo y escribe su inicio exacto. Pon la música en cero y extiende
   su duración con «Repetir audio» si quieres fondo durante el diálogo.

La generación de esta ruta mantiene pitch y velocidad del motor en sus valores
neutros, sin el procesador experimental de GlideLive. Si el personaje ya tenía
un tono manual en la mezcla, el diálogo avisa y permite restablecerlo expresamente.
Los WAV antiguos no se regeneran ni se transforman automáticamente.

Pruebas todavía necesarias en Galaxy S26: instalación/descubrimiento de motor,
importación de perfiles, español, fidelidad del texto, cancelación, generación y
escucha del WAV, guardado/reapertura de la obra y manejo táctil del timeline.
La transferencia de carácter desde una referencia no garantiza la misma actuación
ni acento chileno. No se incluye un elenco de 56 voces nuevas.

Fuentes:

- [Engine Android y código](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine)
- [Modelo Pocket](https://github.com/kyutai-labs/pocket-tts)
- [Referencias sintéticas españolas usadas para las muestras](https://github.com/hangry-labs/faster-qwen3-tts-studio/tree/main/examples/assets/references/spanish)

Las muestras de esta investigación se generaron con el runtime Pocket C++ y
modelo español, sin pitch/stretch/EQ. No son una grabación de la aplicación en
el S26. La muestra alegre tiene menor duración que las otras: comprobar por
escucha si acelera el texto o pierde contenido antes de incorporarla a una obra.
