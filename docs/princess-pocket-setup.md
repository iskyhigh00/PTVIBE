# Pocket integrado en Princess

En **Princess → Narrar texto**, elige **Pocket integrado** y pulsa
**Descargar voces Pocket**. El paquete español se descarga una vez, se valida y
se prepara en el almacenamiento privado de GlideLive. Después la síntesis se
realiza en el teléfono, dentro de GlideLive y sin conexión.

No se instala otra aplicación ni se abre un instalador. No hay que copiar o
importar ZIP desde otra aplicación. La descarga del modelo es independiente del
paquete Supertonic que ya pudiera estar instalado.

## Flujo

1. Descarga el paquete Pocket español desde el diálogo de narración. La descarga
   comprimida es de aproximadamente 207 MB; se necesita espacio adicional para
   extraer los modelos.
2. Elige el personaje de la obra y una de las voces del paquete preparado. El
   motor y el ID de voz se guardan por personaje.
3. Abre un TXT o pega el libreto, marca un fragmento y pulsa **Generar y escuchar**.
4. Escucha la toma y compárala con el texto antes de **Agregar a la obra**. Si el
   resultado omite palabras, no guardes esa toma: prueba un fragmento menor o una
   nueva toma. El modelo no garantiza una transcripción perfecta.
5. El WAV se agrega como una pista editable en el timeline y sigue disponible al
   reabrir la obra.

Pocket integrado es la opción inicial para personajes sin una elección de motor.
Supertonic sigue disponible como alternativa. Cambiar el motor o la voz invalida
la previsualización anterior para que no se guarde accidentalmente una toma de
otra voz. Los WAV ya guardados no se vuelven a generar ni se modifican.

Si una voz guardada no existe en el paquete integrado, el diálogo informa que ha
seleccionado la primera voz disponible y permite elegir otra. Las selecciones de
motores externos de versiones anteriores se sustituyen por una elección del
paquete integrado; no se intenta abrir aplicaciones externas.

## Voces e interpretación

El paquete contiene Lola y referencias incluidas para otros registros de voz.
Estas referencias condicionan al modelo durante la generación. Esta ruta no usa
los presets de cambios de pitch, texturas o estiramiento del audio de Supertonic.
No se afirma que una voz sea un modelo entrenado de un animal, de un niño o de
un acento chileno específico.

La generación se divide en segmentos cortos para reducir omisiones, conservando
el texto del libreto. Esto reduce una causa posible de errores, pero no verifica
por sí mismo que se hayan pronunciado todas las palabras. La duración y la
presencia de audio tampoco demuestran fidelidad al texto.

Los ajustes de tono que el usuario ya haya aplicado al **personaje de la obra**
siguen afectando a la mezcla de sus pistas. Si ese valor no es cero, el diálogo
lo advierte y ofrece **Restablecer tono del personaje**. Esa acción es explícita
porque también cambia cómo se escuchan las pistas anteriores del personaje.

## Compatibilidad y validación

Pocket integrado requiere un dispositivo Android ARM de 64 bits. El diálogo
muestra una explicación y ofrece Supertonic si el dispositivo no es compatible.
El Galaxy S26 es el dispositivo objetivo; la velocidad y el consumo de memoria
se deben medir en el teléfono.

Prueba pendiente en el teléfono: descarga completa y cancelada, conservación del
paquete sin conexión, comparación del mismo fragmento entre voces, revisión de
palabras omitidas, cancelar una generación y guardar/reabrir las pistas en la obra.
Las pruebas automáticas de compilación, segmentación y formato WAV no sustituyen
la escucha ni una comprobación de fidelidad al texto.
