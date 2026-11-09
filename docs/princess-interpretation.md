# Interpretación de cuento (experimental)

Activada de forma predeterminada en «Narrar texto», con un botón para comparar
con la interpretación desactivada. Conserva el preset, las voces descargadas y
el libreto original. Los WAV ya guardados no se modifican: hay que generar una
nueva toma para usar el algoritmo.

La API de Supertonic ofrece hablante, velocidad, pasos e idioma, sin un control
documentado de actuación por instrucciones:
https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/javascript-api/examples/tts_supertonic.rst

Implementación local en `NarrationPerformance.kt`:

1. Separa oraciones respetando el texto, con fragmentos de hasta 220 caracteres.
2. Genera cada frase con el preset existente.
3. Localiza los extremos hablados mediante amplitud. Alarga aproximadamente los
   últimos 280 ms usando solapamiento y correlación de ondas (tipo WSOLA), con
   fundido hacia el final original. No identifica fonemas ni palabras concretas.
4. Añade una curva continua de tono: arco de énfasis, subida de pregunta y caída
   de cierre. Las exclamaciones aumentan el arco. El remuestreo de esta curva
   también modifica ligeramente el ritmo; no es una conversión de voz neuronal.
5. Varía suavemente el ritmo y añade pausas entre frases según puntuación y rol.
   Una continuación por límite de longitud recibe una pausa menor.
6. Conserva el límite total de cinco minutos y guarda el WAV procesado como antes.

Aves, niños y personajes ágiles tienen más movimiento tonal y pausas cortas.
Mayores, tortuga, vaca y oso prolongan más los finales. Bruja y bandidos usan
cierres más prolongados y pausas dramáticas. Son reglas de caracterización;
no un modelo entrenado de emociones, acento chileno o voces animales.

Validación: pruebas de segmentación sin reescribir modismos, diferencias por
personaje y puntuación, límites de duración de los 56 presets, silencio y clips
breves. Comparaciones locales de antes/después del procesador Kotlin para loro,
tortuga y abuelito, usando muestras Supertonic del cambio anterior. No equivalen
a una escucha humana ni a una prueba del flujo Android completo.

Pendiente en el Galaxy S26: comparar el mismo fragmento con interpretación activa
y desactivada; escuchar un diálogo con pregunta/exclamación y un cierre pausado;
comprobar inteligibilidad, transiciones, velocidad de generación y resultado
al agregar el WAV a la obra. El alargamiento heurístico puede prolongar una
consonante o respiración: no se promete una vocal específica sin alineación.
