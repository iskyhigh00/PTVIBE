# Timeline multipista de Princess

El timeline usa los mismos `Placement` que mezcla y exporta `DramaRenderer`.
Cada personaje tiene un carril, junto a música y ambientes/efectos. Los clips
que coinciden dentro de un carril se muestran en subfilas para poder seleccionarlos.
No se dibujan formas de onda en esta versión.

- Los audios narrados, grabados e importados aparecen automáticamente.
- Tocar la regla coloca el cursor; «Desde cursor» prepara y reproduce la mezcla.
- Mantener pulsado un clip permite arrastrarlo; se guarda una vez al soltarlo.
- Tocar un clip abre inicio y duración exactos en segundos, recorte de inicio del
  archivo, volumen, silencio, bucle y entradas/salidas suaves.
- El control del carril silencia o activa todos sus clips. No es un bus de audio
  independiente: es una operación sobre los clips actuales del carril.
- Zoom y desplazamiento horizontal permiten organizar las posiciones.
- «Escuchar todo» comienza en cero incluso si el primer clip de la lista se movió.

Las obras anteriores conservan su distribución secuencial hasta la primera
edición en timeline. En ese momento se fijan sus posiciones actuales para que
mover un clip no desplace los demás. El modo de música de fondo se convierte
en una duración fija que se puede ampliar desde el editor. Agregar nuevos
clips sigue usando el final de la distribución para las entradas secuenciales.

Inicio absoluto, duración, recorte, silencio y bucle se guardan en `project.json`.
Los campos ausentes mantienen los valores y reglas anteriores. No se modifican
ni recortan destructivamente los archivos de audio. El recorte de inicio se mide
en segundos del archivo original; la duración del timeline refleja el tono del
personaje y el ajuste de duración. El bucle repite la parte restante del archivo.

Al editar la obra se invalida la mezcla cargada, para que la siguiente escucha
use los cambios. Escuchar un clip aislado ignora su silencio intencionalmente
para permitir revisarlo, manteniendo recorte y duración. Voces silenciadas no
provocan reducción automática de música. Se renderiza/exporta la misma mezcla
que muestra el timeline; no es todavía mezcla multibus en tiempo real.

Cobertura automatizada en `PrincessTimelineTest`: solapamiento real de señales,
independencia de posiciones tras anclar una obra antigua, recorte y duración,
bucles de una fuente recortada, silencio sin ducking, música sin voces y
persistencia de los campos nuevos. Las pruebas existentes siguen cubriendo el
formato secuencial, fondos, nivelación y reproducción.

Pendiente de validación táctil en el Galaxy S26: arrastre frente al desplazamiento
horizontal, legibilidad en horizontal/vertical, selección de clips simultáneos,
reproducción desde cursor, superposición de voz/música/ambiente y reapertura de
una obra editada. La primera versión no incluye edición por forma de onda,
transiciones automáticas ni arrastre entre carriles.
