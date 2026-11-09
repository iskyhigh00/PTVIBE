# Presets de narración de Princess

En **Princess → Narrar texto**, elige el personaje, una familia y un preset.
Luego selecciona el fragmento y pulsa **Generar y escuchar**. El preset aplica
la voz base, velocidad, tono y textura juntos. **Agregar a la obra** conserva
el flujo habitual de pistas WAV editables.

El preset se guarda por personaje dentro de `project.json` y se recupera al
volver a abrir la obra. Los ajustes opcionales son temporales: al volver al
personaje se recupera su preset. Elegir otra familia solo cambia el catálogo
visible; hay que elegir un preset para aplicarlo. Un ID desconocido recupera
la narradora predeterminada sin impedir abrir una obra antigua.

## Catálogo: 56 recetas

| Familia | Presets |
| --- | --- |
| Narradores (4) | Narradora cercana, Narrador cercano, Cuentacuentos, Relatora entusiasta |
| Niños y niñas (6) | Niño curioso, Niño travieso, Niño tímido, Niña curiosa, Niña aventurera, Niña tranquila |
| Adultos (6) | Mujer amable, Hombre amable, Vecina alegre, Vecino sereno, Heroína decidida, Héroe decidido |
| Viejitos y viejitas (6) | Abuelita tierna, Abuelito sabio, Abuelita alegre, Abuelito juguetón, Anciana misteriosa, Anciano gruñón |
| Personajes de cuento (10) | Bandido grave, Bandido escurridizo, Bandida astuta, Bruja de cuento, Mago bondadoso, Hada luminosa, Gigante, Duende travieso, Rey solemne, Reina serena |
| Aves (10) | Loro parlanchín, Cotorra conversadora, Chincol curioso, Chucao del bosque, Loica alegre, Picaflor veloz, Búho sabio, Cóndor solemne, Pato de cuento, Gallina inquieta |
| Animales (14) | Vaca tranquila, Toro fuerte, Perrito juguetón, Perro guardián, Gata elegante, Gato pícaro, Ratón pequeño, Conejo nervioso, Zorro astuto, Lobo de cuento, Oso bonachón, León imponente, Rana de laguna, Tortuga paciente |

Las recetas son decisiones de caracterización, pendientes de valoración auditiva
en el teléfono. Son combinaciones distintas de las diez voces existentes y
procesamiento local; no son 56 nuevas voces entrenadas. Los animales hablan
como personajes de cuento, no producen cantos ni sonidos zoológicos reales.
Las voces infantiles y de mayores también son caracterizaciones.

## Español y motor

Se usa `lang=es`. El motor no documenta un selector de dialecto `es-CL` ni
instrucciones para actuar como un personaje. Se conserva literalmente el texto
seleccionado, incluidos modismos chilenos, sin agregar instrucciones que el
motor podría leer en voz alta. El acento chileno no está garantizado por estas
recetas. No se cambia ni se descarga de nuevo el paquete instalado.

Fuentes consultadas:

- [Supertonic en Sherpa](https://k2-fsa.github.io/sherpa/onnx/tts/supertonic.html): selección de hablante e idioma.
- [Parámetros del motor](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/javascript-api/examples/tts_supertonic.rst): diez hablantes, velocidad, pasos e idiomas, incluido `es`.
- [Empaquetado de voces](https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/supertonic/generate_voices_bin.py): orden alfabético de archivos de estilo, F1–F5 y M1–M5.

## Procesamiento y validación

Los presets mantienen tono entre −6 y +6 semitonos, velocidad de 0,75 a 1,4 y
la compensación de velocidad dentro del rango admitido por el narrador. Las
texturas se aplican después del cambio de tono: brillo o calidez con un filtro
suave, aspereza con saturación y modulación leve, o temblor/vibración con
modulación de amplitud. Se conserva la duración y el formato PCM del WAV.
Natural conserva las muestras originales.

Las pruebas unitarias cubren límites, unicidad de recetas, cobertura de familias,
serialización de personajes y pistas, lectura de obras antiguas, silencio y
procesamiento PCM a distintas frecuencias. Esas pruebas no evalúan acento,
inteligibilidad ni parecido con una voz infantil o animal.

Validación local del cambio:

- 134 pruebas unitarias aprobadas, incluidas ocho nuevas de presets y efectos.
- Pruebas de host del motor C++ aprobadas.
- `assembleRelease` y lint vital aprobados; APK local 0.2.60 con firma verificada.
  Este número corresponde al APK local, no a una publicación en GitHub Actions.
- 56 recetas generadas con Sherpa-ONNX 2.0.1 y el paquete INT8 instalado por la
  app (SHA-256 verificado), usando `lang=es` y una referencia del procesamiento
  en Python. WAV mono PCM de 16 bits, sin muestras no finitas ni audio vacío.
  Las muestras de referencia no sustituyen una prueba del renderizador Android.

Comandos de compilación y pruebas:

```sh
bash ./gradlew testDebugUnitTest assembleRelease -PbuildNumber=60 --no-daemon
cmake -S app/src/test/cpp -B build/host-tests
cmake --build build/host-tests
./build/host-tests/engine_tests
```

Prueba pendiente en el Galaxy S26: generar fragmentos con dos personajes de
familias diferentes, escuchar tanto la previsualización como la obra, guardar,
cerrar y reabrir la app, y comprobar que se mantienen los presets y las pistas.
Los controles de tono y volumen del personaje en la obra siguen aplicándose al
WAV: para valorar el preset por sí solo, usar tono del personaje en cero.
