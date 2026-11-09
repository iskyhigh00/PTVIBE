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
