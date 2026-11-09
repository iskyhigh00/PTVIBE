# Pocket integrado: fidelidad y alcance de la corrección

La integración elimina la aplicación externa, no sustituye los pesos españoles
por un modelo entrenado nuevo. El usuario detectó palabras ausentes en Pocket.
No se debe presentar esta incidencia como resuelta únicamente porque hay WAV.

Se compararon tres referencias Pocket y dos MOSS con el mismo texto:

> El zorro se escondió detrás del árbol. ¿Quién anda ahí?, preguntó la lechuza.
> ¡Soy yo, no tengas miedo!, respondió el ratón.

La comparación de Pocket pasó de temperatura 0,7/un paso a 0,5/cuatro pasos y
llamadas por fragmentos cortos. Whisper tiny multilingüe, local y sin prompt,
recupera más del bloque de la lechuza con la referencia alegre, pero sigue
transcribiendo frases distintas (por ejemplo «Oye» en lugar de «Soy yo»).
Las muestras de MOSS tampoco demuestran fidelidad completa y consumieron entre
2,80 y 3,43 GiB de RSS en el host; esas cifras no son mediciones Android.

Este ASR es imperfecto: «sorro»/«lechusa» no demuestran mala pronunciación en
español seseante. Cambios en bloques completos justifican revisar el audio,
pero no distinguen definitivamente una omisión de una pronunciación que el ASR
no reconoció. No se validó cada palabra mediante escucha humana.

En los seis segmentos Pocket examinados el texto/tokenización se conservó y
no se alcanzó el límite de frames. La parada fue EOS del modelo. Aumentar el
límite no explica ni resuelve por sí solo esos resultados; perder palabras al
inicio de una frase tampoco se explica solo por cortar su final.

Cambios defensivos del renderizador integrado:

- Rangos de texto contiguos de hasta 14 palabras/120 caracteres habituales;
  conservar exactamente el libreto concatenando segmentos se comprueba y prueba.
- Rechazar palabras excepcionalmente largas, en lugar de truncarlas en silencio.
- Recoger errores del productor/decodificador y rechazar un límite de frames
  alcanzado, incluso si ya había audio parcial.
- Guardar el WAV únicamente si terminan todos los fragmentos; cancelar borra
  el destino incompleto. No aplicar pitch/stretch/EQ al audio Pocket.

Estos cambios evitan pérdidas por la aplicación y errores parciales conocidos,
pero NO garantizan que un modelo generativo pronuncie cada palabra correctamente.
Antes de usar una toma en una obra hay que compararla con el libreto. Supertonic
sigue disponible como alternativa; no se cambia a él silenciosamente si Pocket
falla. La prueba de interfaz, descarga privada, cancelación y síntesis Android
completa sigue pendiente en el Galaxy S26.

El engine usa PocketTTS.cpp y ORT 1.20 integrados en ARM64. Los modelos se
obtienen dentro de GlideLive y las referencias de personaje van en el APK.
No hay instalación de motor externo, selector TTS del sistema ni importación
manual del paquete en otra aplicación.

## Cierre prematuro reproducido y protección de la toma (build 65)

Se reprodujo un fallo claro con la referencia `grave.wav` incluida en la app:
«Al abrir la puerta del castillo, apareció un enorme dragón.» Son diez palabras,
pero la generación devolvió estado de éxito con solo 10.080 muestras/0,42 s.
La API no perdió datos: el propio modelo señaló EOS antes de tiempo.

El renderizador rechaza ahora un fragmento de cinco palabras o más si ni
siquiera alcanza una duración físicamente razonable (ocho palabras por segundo,
contando aparte la pausa del motor). Rechaza también silencio. Reintenta hasta
3 veces el mismo texto y voz con el mismo motor, descartando los buffers de cada
intento rechazado. Solo concatena los aceptados. Si no consigue una toma válida,
no escribe el WAV y muestra error. Esto no es reconocimiento de palabras.

Prueba de la C API con un único handle y RNG fijo al principio de cada caso:

- Semilla 1234: intento 1 = 0,42 s rechazado; intento 2 = 5,46 s aceptado.
- Diez semillas (1234 y 0–8): cinco primeros intentos cortados; todos se recuperan
  en el segundo. Los otros cinco pasan en el primero. No se concatenó audio malo.
- ASR del primer resultado recuperado reconoce la estructura mucho más completa,
  pero no permite garantizar la pronunciación de «dragón». No se afirma fidelidad
  semántica perfecta ni un arreglo universal de todos los fallos del modelo.

Se descartó subir globalmente EOS a 0/8 frames: en una matriz controlada de
16 muestras causó cuatro errores nuevos de límite, frente a cero en la política
original. La política EOS de producción se mantiene.

Además se corrigió un error excepcional de memoria: si falla reservar un bloque
PCM nativo, el stream devuelve error incluso tras entregar bloques anteriores,
y no un falso fin correcto. Se inyectó ese fallo en la API real: tras 1.920
muestras parciales devolvió -3. No hay evidencia de que fuera el corte del usuario.

Pruebas añadidas de escritura/lectura WAV y mezcla comprueban que se conserva la
región final completa. El reproductor también conserva su última muestra de
origen, antes omitida al interpolar; esa pérdida era menor a una milésima de
segundo y por sí sola no explicaba frases cortadas. Las tres suites C++ se
registran ahora con CTest y se ejecutan también en GitHub Actions.
