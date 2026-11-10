# Princess: flujo simplificado

- **Narrar texto** abre un editor a pantalla completa con tres pasos: texto,
  personaje y voz, escuchar y agregar. El botón principal y los controles de
  reproducción permanecen visibles durante el desplazamiento.
- Sin selección se narra todo el texto; con selección se narra solo ese fragmento.
  El límite sigue siendo 4.000 caracteres por audio: un TXT largo debe seleccionarse
  por fragmentos. Nunca se recorta automáticamente el texto para hacerlo caber.
- Los personajes se pueden crear desde el editor. Pocket recuerda su voz para
  cada personaje; Supertonic también recuerda la voz base elegida directamente.
- El editor reserva el espacio de las barras del sistema, recortes de pantalla y
  teclado. Los mensajes largos se desplazan con el contenido para no empujar
  el botón de agregar fuera de la pantalla.
- **Opciones de voz** contiene la elección del motor y los presets anteriores.
  Los ajustes manuales y efectos son opcionales; cambiar a una voz base de
  Supertonic restablece tono, ritmo y textura para esa nueva toma.
- **Agregar al timeline** guarda el audio y permite seguir narrando. **Volver** muestra el timeline. Una toma que todavía no se ha agregado es temporal.
- Princess separa **Timeline**, **Personajes** y **Biblioteca**. La lista de clips
  y los ajustes de mezcla se despliegan cuando se necesitan.
- **Más opciones** reúne grabación por micrófono, importación de voz y pausas.
  **Obra** reúne crear, exportar WAV y borrar (con confirmación).
- Los motores y paquetes siguen dentro de GlideLive. No cambia la síntesis ni el
  guardado de pistas existentes.

## Verificación

Las pruebas de texto cubren cursor sin selección, selección en ambas direcciones
(incluidos acentos) y selección de espacios sin narrar accidentalmente todo el
libreto. Se ejecutan las pruebas JVM y se compila el APK release.

Pendiente en teléfono: tamaño del editor y teclado en horizontal, selección desde
un TXT, creación de dos personajes, generación/escucha/agregado de ambas voces,
retorno al timeline y reapertura de la obra. No hubo un dispositivo Android
conectado durante esta revisión de interfaz.
