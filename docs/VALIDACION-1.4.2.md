# Verificación Android — 1.4.2

## Comprobado en el entorno de construcción

- 228 comprobaciones de la base 1.2.1: enlaces, extractores, formatos, HLS, reintentos y cancelación.
- 21 comprobaciones de la cola: dos trabajadores como máximo, FIFO, incorporación de
  nuevas peticiones, cancelación individual, conservación del turno durante la limpieza,
  callbacks tardíos, historial tras muerte del proceso y ocho despachadores en competencia.
- 54 escenarios de geometría: ventana de teléfono, paisaje, tableta, teclado y texto
  al 100 %, 130 % y 200 %. Los rectángulos permanecen dentro de la ventana y no se
  superponen; el botón principal permanece al pie. Esto verifica geometría, no un render real.
- 99 comprobaciones de guardado: dos archivos en paralelo, cancelar uno sin afectar al otro,
  nombres coincidentes, MediaStore simulado, archivos reales del modo Android 7–9,
  y recuperación de huérfanos sin borrar un archivo completo ni el parcial activo de otro trabajador.
  Incluye temporales de YouTube en dos trabajadores, cancelación independiente y recuperación
  después de una muerte del proceso; el transporte y MediaStore son dobles de prueba.
- 76 comprobaciones del módulo YouTube: reconocimiento de enlaces, aislamiento por dominio,
  elección de AVC/AAC con sonido, presupuesto conjunto de tamaño, ahorro de datos, rangos HTTP,
  transferencia exacta por bloques, archivo truncado o no multimedia, cancelación y fases de progreso.
- 51 comprobaciones del ensamblado con API nativa simulada: las tres aperturas por descriptor,
  límites de lectura, cierre de descriptores, secuencia de ambas pistas, conservación de tiempos,
  errores de cada fase, cancelación y liberación de todos los recursos sin ocultar el error original.
- 7 comprobaciones con sistema de archivos real: alias de la raíz de caché, recuperación
  de registros anteriores, limpieza idempotente y rechazo de enlaces de archivos o tareas
  hacia otros destinos. La misma prueba reproduce el rechazo repetido de 1.4.1.
- 13 comprobaciones del diagnóstico: fase, tipos, códigos HTTP/sistema, punto de fallo,
  privacidad del texto copiado, causas y errores de limpieza juntos y ciclos acotados.
- Total: 549 comprobaciones y escenarios locales, todas aprobadas.
- Compilación con Android API 35, minSdk 24, versionCode 8; firma v2/v3 y alineación verificadas.
- Mismo paquete y certificado que 1.4.1. Se corrigen recuperación de caché y propagación
  de errores, se registran fases de preparación y se añade Detalles/Copiar. Los extractores,
  transferencias de YouTube, selección de calidad, ensamblado de 1.4.1 y cola no cambian.
- Ninguna prueba visita los enlaces multimedia del usuario. Los dobles de Android
  y el transporte de prueba se incluyen solamente en el código fuente de tests, no en la APK.

## Evidencia del nuevo fallo y la corrección 1.4.2

- El usuario informa de fallo antes de recibir bytes. La captura conserva únicamente
  el mensaje genérico; no demuestra si falló la recuperación, el destino o la transferencia.
- `test-cache.sh` reproduce con archivos reales que 1.4.1 rechaza una raíz privada con
  alias simbólico, deja el temporal y vuelve a rechazarlo en recuperaciones sucesivas.
- La versión nueva recupera ese registro, conserva archivos ajenos y permite guardar la
  siguiente petición. Se prueba el flujo con MediaStore simulado y con archivos reales
  para Android 7–9. No se borra el conjunto de datos de la aplicación.
- Este defecto es compatible con el síntoma, pero **no está probado que sea la causa
  exacta en el móvil**. Las rutas de Android pueden usar enlaces o montajes vinculados.
- Los nuevos errores incluyen diagnóstico copiable; las excepciones se recorren de
  forma acotada, conservando la original y sus fallos de limpieza. No se copian URLs,
  credenciales ni mensajes arbitrarios de terceros. Un temporal sin limpiar impide
  reintentar automáticamente el mismo archivo.
- El botón nativo Detalles/Copiar está compilado, pero no se ha ejecutado en un dispositivo.

Prueba individual: `bash test-cache.sh`. Para el contraste, definir
`DESCARGA_SOCIAL_CACHE_BASELINE` con el `YoutubeWorkFiles.java` de 1.4.1 y ejecutar
`bash test-cache.sh --baseline`. Este resultado no simula MediaExtractor: usa las
operaciones reales de archivos y enlaces del sistema de construcción.

## Evidencia previa del fallo y la corrección 1.4.1

- El usuario informa de que las barras de vídeo y audio terminan y el fallo aparece al unirlas.
  Su captura muestra el error genérico de 1.4.0; no contiene la excepción nativa ni un registro Android.
- Se identificaron tres aperturas por ruta de archivos privados, contrarias al requisito de
  acceso indicado en el contrato de `MediaExtractor.setDataSource(String)`.
- `test-mux.sh` usa el método de producción con dobles que reproducen esa frontera: una ruta
  privada falla; un descriptor abierto por la app y acotado al tamaño del archivo es aceptado.
- Ejecutando esa prueba contra el archivo original de 1.4.0 se reproduce la primera apertura
  fallida y el mensaje genérico. La versión 1.4.1 pasa con las tres aperturas por descriptor.
- El descriptor original se cierra al retornar `setDataSource`, tal como permite el contrato
  oficial. No se amplían permisos ni se trasladan las pistas a una carpeta pública.
- Esta evidencia identifica y corrige un defecto concreto compatible con los síntomas. No
  establece que se haya leído el error interno exacto del teléfono, ni valida el códec nativo.

Para repetir únicamente esta regresión: `bash test-mux.sh`. Para contrastarla con la versión
anterior, definir `DESCARGA_SOCIAL_MUX_BASELINE` con la ruta de su `YoutubeDownload.java`
y ejecutar `bash test-mux.sh --baseline`. Los dobles se compilan en un directorio separado
y nunca se incorporan a la APK.

## Consulta pública previa de YouTube — 10 de septiembre de 2026

- NewPipeExtractor 0.26.5 sin modificar reconoció el vídeo público `aqz-KE-bpKQ`,
  «Big Buck Bunny 60fps 4K — Official Blender Foundation», sin restricción de edad.
  Devolvió vídeo combinado y pistas separadas, incluyendo AVC 1080p60 y AAC.
- Las solicitudes de metadatos devolvieron 200. La comprobación se hizo con el
  transporte habitual del entorno de trabajo, que utiliza su proxy HTTPS configurado.
- Las solicitudes parciales a los archivos de `googlevideo.com` agotaron el tiempo
  de espera sin recibir bytes; no se ha comprobado una descarga real completa.
- La prueba opcional con el adaptador exacto de producción se detuvo por
  `UnknownHostException` en su comprobación DNS. No se relajó la validación de destinos.
- El guardado mediante `MediaMuxer` está compilado contra el SDK oficial, pero no
  se ha ejecutado. Las pruebas de rangos y almacenamiento no equivalen a probar esa API.

## Comprobación pendiente en un dispositivo Android

El usuario ya confirmó que 1.2.1 descarga correctamente varios vídeos. Esta versión
1.4.2 no se ha ejecutado en un teléfono ni en un emulador en este entorno.

1. Instalar 1.4.2 encima de la app existente, conservando los datos. Comprobar la versión
   en Acerca de y crear una petición nueva con el vídeo que falló. Si falla otra vez,
   abrir Detalles y Copiar para obtener fase, código y punto de fallo; no usar el
   error histórico de una versión anterior. Si finaliza, verificar imagen y sonido.
2. En vertical, comprobar que el campo, selector de calidad y botón principal permanecen
   accesibles. Analizar un archivo propio: revisar vista previa y botón de descarga.
3. Girar el móvil, abrir el teclado y aumentar el texto del sistema. Comprobar que
   las barras del sistema no cubren controles. En espacio muy reducido se admite
   desplazamiento interno del formulario; las listas tienen desplazamiento propio.
4. Iniciar un vídeo suficientemente largo y volver a **Nuevo**. Añadir otro:
   ambos deben acumular bytes independientemente en **Descargas**.
5. Añadir un tercero: debe esperar en cola y comenzar cuando haya un turno libre.
6. Cancelar uno de los activos y otro de los que esperan. El otro vídeo debe seguir;
   cancelar un resultado antiguo no debe afectar a un trabajo nuevo.
7. Cambiar de aplicación, bloquear la pantalla y regresar: conservar trabajos, progreso
   y cancelación individual. Repetir también desde la notificación desplegada.
8. Añadir dos peticiones del mismo contenido: deben conservarse dos archivos completos
   con nombres diferentes. Confirmar que no aparecen parciales como vídeos terminados.
9. Con historial acumulado, usar **Limpiar**: los archivos y las peticiones activas permanecen.
10. Verificar una imagen, un carrusel, un vídeo directo y un HLS finito autorizado,
    además de permisos denegados y almacenamiento de Android 7–9 si se dispone de esos dispositivos.

## Comprobaciones específicas de YouTube

1. Compartir un vídeo público propio o autorizado desde YouTube. Debe detectarse como
   YouTube sin abrir un navegador y mostrar un único resultado con sus dimensiones.
2. Descargar con máxima calidad compatible. Comprobar que el MP4 final tiene imagen
   y sonido, duración completa y sincronización, especialmente en vídeos de 60 fps.
3. Repetir con ahorro de datos, un enlace `youtu.be` y un Short. Revisar calidad y orientación.
4. Descargar dos vídeos a la vez; añadir un tercero a la cola y cambiar de app.
   La calidad de cada petición debe mantenerse. Revisar vídeo/sonido/unión/guardado
   en el progreso. Si una petición espera más de tres minutos, debe renovar sus formatos.
5. Cancelar durante la descarga de audio y durante la unión: no debe publicarse un MP4
   incompleto ni perderse el resultado del otro trabajador. Comprobar espacio recuperado.
6. Comprobar falta de espacio, pérdida de conexión y rechazo de acceso. La aplicación
   debe informar del fallo; no debe guardar HTML, duplicar bloques ni intentar iniciar sesión.
7. En Android 7–8, comprobar la compatibilidad Java NIO, la extracción y el MP4 con sonido;
   en Android reciente, repetir bloqueando la pantalla y desde la notificación.

Forzar la detención, reiniciar el teléfono o agotar los límites de Android puede interrumpir
la cola; no equivale a cambiar de aplicación. El historial indicará que hay que analizar
de nuevo las peticiones interrumpidas.
