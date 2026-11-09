# Investigación: voces de personajes para cuentos

El objetivo es distinguir personajes mediante voces e interpretación generadas
por el modelo. Cambiar el tono o estirar el WAV de una voz genérica no cumplió
ese objetivo en la prueba del usuario. No se debe presentar otra colección de
etiquetas sobre la misma voz como una solución de calidad ya comprobada.

## Motor y elenco son dos problemas diferentes

Un motor nuevo puede producir mejores transiciones y permitir voces por
referencia, pero su voz predeterminada no constituye un elenco de cuento. Hace
falta un banco de referencias actuadas y una escucha del mismo diálogo con
distintos personajes. La clonación de timbre tampoco garantiza que conserve
la emoción, el ritmo o la actuación de la referencia.

## Opciones verificadas en fuentes primarias

| Opción | Qué aporta | Español y Android | Descarga y límites |
| --- | --- | --- | --- |
| Pocket TTS + PocketTTS Android Engine | Clonación por referencia; generación nueva sin nuestros efectos de tono/alargamiento | Pocket actual documenta español. La implementación Android permite voces locales y `synthesizeToFile` mediante la interfaz estándar Android TTS | El ZIP español FP32 de la release 0.5.5 mide 207.487.086 bytes, aproximadamente 198 MiB. Es tamaño descargado, no RAM. El paquete básico incluye Lola: una sola voz no satisface por sí sola el elenco pedido |
| MOSS-TTS-Nano | Modelo de 100M parámetros con clonación por referencia y salida 48 kHz | Español documentado. El ejemplo Android oficial genera prompts pretokenizados; texto arbitrario exige integrar su tokenizador, por ejemplo SentencePiece local | TTS ONNX y decoder suman aproximadamente 718 MB. El encoder para referencias nuevas añade unos 45 MB. No se encontró un control documentado de actuación por instrucciones |
| Qwen3-TTS 1.7B VoiceDesign | Crea una voz original desde una descripción de timbre/edad/interpretación, sin modificar el pitch del WAV | Español documentado. Es una opción para diseñar referencias en escritorio; no se ha validado este modelo VoiceDesign en el Galaxy S26 | Solo `model.safetensors` pesa 3,83 GB, más codec, tokenizador y runtime. Ese peso no equivale a RAM. No se descargó ni ejecutó en este entorno |

Fuentes:

- [Pocket TTS oficial](https://github.com/kyutai-labs/pocket-tts): idiomas actuales,
  generación por CPU y clonación de voz. MIT.
- [PocketTTS Android Engine](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine):
  adaptación Android, configuración de paquetes y voces por referencia. MIT.
- [Release Android 0.5.5](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/tag/v0.5.5):
  APK y paquetes de idioma, incluido español FP32.
- [MOSS-TTS-Nano oficial](https://github.com/OpenMOSS/MOSS-TTS-Nano):
  capacidades, modelos ONNX e instrucciones de inferencia.
- [Ejemplo Android MOSS](https://github.com/OpenMOSS/MOSS-TTS-Nano/blob/main/examples/android_onnx_runtime/README.md):
  alcance del ejemplo con prompts pretokenizados; no equivale a un editor de
  libretos completos ya integrado.
- [Qwen3-TTS oficial](https://github.com/QwenLM/Qwen3-TTS):
  `generate_voice_design(text, language, instruct)`, español y diferencias entre
  VoiceDesign, CustomVoice y Base. La variante Base 0.6B de clonación no es un
  sustituto del diseño de voces por descripción.
- [Pesos oficiales VoiceDesign](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign/blob/main/model.safetensors)
  y [artículo técnico](https://arxiv.org/abs/2601.15621): tamaño del archivo y
  publicación bajo Apache-2.0. No hay un benchmark de este cambio en el S26.

## Prueba local realizada con MOSS

El agente de investigación ejecutó el runtime ONNX oficial por CPU, dos hilos,
con SentencePiece y texto español libre:

> ¡No pasarás por mi bosque! ¿Quién se atreve a despertar al gran dragón?

Se generaron tres identidades incorporadas, sin los efectos de pitch ni
alargamiento de GlideLive:

| Voz incorporada | Audio generado | Tiempo de generación en este host |
| --- | --- | --- |
| Adam | 4,96 s | 4,50 s |
| Bella | 4,64 s | 4,40 s |
| Soyo | 5,44 s | 5,10 s |

La inicialización tardó 5,98 s y el máximo RSS observado del proceso fue
aproximadamente 1,85 GB. Son mediciones del host de desarrollo, no de Android
ni una predicción de la latencia o RAM en el Galaxy S26. Se conservaron los WAV
en `tooling/moss-es-samples` fuera del repositorio. Generar audio técnicamente
válido no demuestra todavía que resulte inteligible, expresivo o apropiado
para animales de cuento: esa valoración requiere escuchar las muestras.

## Referencias de voces y ruta de calidad

Ruta inmediata: permitir elegir un motor Android local, como Pocket, y sus
voces españolas sin aplicar nuestros efectos. Para Pocket hay que instalar
su APK y descargar/configurar el paquete dentro de ese motor. No se debe
cambiar silenciosamente al motor predeterminado ni usar una voz de red.

Ruta para crear un elenco: diseñar o grabar referencias originales actuadas
por personaje; después probar cómo las reconstruye el motor móvil con texto
que no aparece en esas referencias. Qwen VoiceDesign permite investigar esta
ruta sin depender de una identidad real. Crear el banco una vez en escritorio
no demuestra que Pocket o MOSS vaya a conservar toda la interpretación.

Se encontró una colección de referencias sintéticas ya generadas con Qwen en
[Faster Qwen3-TTS Studio](https://hangry-labs.github.io/faster-qwen3-tts-studio/examples/?lang=es),
incluido un narrador mayor grave y una voz femenina alegre. Su
[guía](https://github.com/hangry-labs/faster-qwen3-tts-studio/blob/main/examples/README.md)
declara que no usa grabaciones de hablantes terceros y conserva recetas,
textos y hashes. Los [FLAC españoles](https://github.com/hangry-labs/faster-qwen3-tts-studio/tree/main/examples/assets/references/spanish)
son útiles para una prueba pequeña de transferencia entre motores. La licencia
MIT raíz y las [notas de licencias](https://github.com/hangry-labs/faster-qwen3-tts-studio/blob/main/docs/LICENSING.md)
deben revisarse para el material exacto antes de redistribuir ese banco en la
app; aquí no se presenta como un banco propio ni como voces animales acabadas.

## Elenco de prueba que debe existir antes de prometer muchos presets

| Personaje | Diferencia que se busca escuchar en la generación nativa |
| --- | --- |
| Loro parlanchín | Ataques rápidos, intención juguetona y un timbre pequeño reconocible |
| Ratón nervioso | Timbre ligero, vacilación y sorpresa; no una voz adulta acelerada |
| Oso bonachón | Resonancia grande, calidez y tranquilidad sin remuestreo grave |
| Tortuga paciente | Fraseo reposado y finales naturales, sin consonantes estiradas |
| Zorro astuto | Complicidad, contraste entre insinuación y énfasis |
| Bruja traviesa | Textura áspera y actuación caricaturesca sin saturación artificial |
| Bandido fanfarrón | Presencia e intención exagerada, distinta del narrador |
| Niña aventurera | Timbre juvenil e iniciativa, sin convertir por pitch una voz adulta |
| Abuelito cuentacuentos | Edad percibida, pausas intencionales y calidez sin trémolo añadido |
| Narradora cercana | Base clara y expresiva que permita comparar al resto del elenco |

Estos son objetivos de casting, no voces creadas ni evaluadas en este cambio.

## Escucha A/B necesaria

1. Usar el mismo diálogo corto para cada candidato: afirmación, pregunta,
   exclamación y una frase tranquila. Incluir modismos chilenos sin reescribirlos.
2. Comparar Supertonic sin efectos, cada nuevo motor sin efectos y, cuando
   exista, el mismo motor condicionado con una referencia actuada.
3. Escuchar pares sin mostrar los nombres de personaje. Poder reconocer
   diferencias de identidad y actuación antes de leer la etiqueta.
4. Evaluar naturalidad de transiciones, intención del diálogo, artefactos,
   pérdida de palabras y consistencia en tres fragmentos diferentes. Para
   este proyecto se admite pronunciación imperfecta si la actuación sirve.
5. Repetir en el S26 y registrar memoria, tiempo, temperatura y estabilidad.
   Comprobar además guardar el WAV y mezclarlo con música en el timeline.
6. Conservar solo voces que el usuario prefiera al ejemplo anterior. No
   sustituir esta evaluación por un test de que el WAV no esté vacío ni por
   cambiar el nombre de un preset.
