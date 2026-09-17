# Verificación de MediaGet 1.5.0 — 11 de septiembre de 2026

Actualización incremental de 1.4.2. **No se presenta como una validación completa de todas
las descargas reales**: las comprobaciones realizadas y sus límites se separan a continuación.

## Resultado comprobado

| Área | Evidencia real | Resultado / límite |
|---|---|---|
| Build completo | SDK 35, Build Tools 35.0.0, Java 17/ECJ; recursos, Java, D8/L8 y firma | Sin errores; se mantienen 2 avisos antiguos de HLS y 4 de JIT opcional Rhino |
| Identidad Android | APK inspeccionada con aapt2 y apksigner | MediaGet; paquete `com.didazz.descargasocial`; versión 1.5.0/código 9; misma firma v2/v3 |
| Requisitos y permisos | Manifiesto compilado | Mínimo API 24, objetivo 35; permisos anteriores, sin permisos nuevos |
| Recursos ES/EN | XML reales, marcadores y referencias | 248 recursos por idioma, sin claves ni marcadores ausentes |
| Regresión de la base | 549 comprobaciones locales | Pasan; las pruebas Android simuladas se identifican como tales |
| Internacionalización local | 911 comprobaciones con XML y mensajes reales | Pasan: formatos, etiquetas, errores, mensajes anidados y cola sin cambios al alternar idioma de presentación |
| Recursos en Android | Instrumentación sobre Android 15/API 35 | Resolución real EN, ES, EN-GB, ES-MX y respaldo FR/DE; 1.502 comprobaciones incluyendo historial mixto antiguo/nuevo |
| Cambio de idioma por aplicación | LocaleManager real en la instalación de pruebas | ES → EN → FR → ES: conserva instancia, trabajador, enlace, resultado, selección, estado ocupado, progreso e historial |
| Unión nativa compatible | Método de producción, MediaExtractor/MediaMuxer reales, pistas sintéticas AVC/AAC cuyo primer PTS es 0 | MP4 no vacío con ambas pistas correctas |
| Total instrumentado final | `docs/validation-1.5.0/native-results-final.txt` | 1.556 comprobaciones nativas superadas; no son descargas desde Internet |
| Interfaz | Capturas reales del emulador revisadas visualmente | ES/EN a 360 × 800; EN a 320 × 568 con texto al 130 %, controles y acción principal visibles; descripción completa en diálogo desplazable con botones accesibles |
| Geometría adicional | 54 escenarios dentro de la regresión local | Prueba matemática, no capturas de cada dispositivo o escala |

## Lo que no se ha verificado

- No se han repetido transferencias reales desde Instagram, Facebook, YouTube y webs genéricas
  en esta actualización. Sus consultas y transportes conservan el código de 1.4.2.
- No se ha realizado una descarga real en segundo plano mientras cambia el idioma. Se ha probado
  la invariancia de la cola y el estado de pantalla, pero eso no sustituye una transferencia real.
- El intercambio del idioma global del dispositivo no completó un recorrido visual fiable en ES:
  el emulador tardó en volver a disponer de la actividad tras reiniciar servicios. Sí se verificaron
  recursos Android con ambas configuraciones y cambios reales mediante idioma por aplicación.
- No se han probado todas las resoluciones, escalas de accesibilidad ni teléfonos físicos.
- El emulador sin aceleración presentó avisos «System UI isn't responding». Las capturas con
  ese diálogo o pantallas vacías no se usan como evidencia de una interfaz verificada. La
  instrumentación final de la aplicación sí terminó con resultado PASS.

## Primera muestra de unión: fallo registrado, no ocultado

La primera prueba nativa empleó un AAC sintético creado por FFmpeg con preroll/PTS inicial
negativo (−0,023220 s según ffprobe). El método de unión devolvió el error `error_315`
(falta vídeo o sonido). Se conserva el resultado en `native-results.txt`; **esa prueba falló**.

No se ha cambiado la lógica de unión para ampliar su soporte de timestamps. Se generó después
una muestra compatible que empieza en cero; la instrumentación comprobó en las APIs Android
que ambas pistas empezaban en PTS 0 y que la unión conservaba AVC y AAC. Ese es el alcance del
resultado nativo positivo, no una afirmación de compatibilidad con cualquier archivo multimedia.
Las muestras y su receta están en `android-tests/`.

## Conservación de funcionalidad y datos

Comparados byte a byte con 1.4.2, están intactos los extractores de Instagram/Facebook/web/HLS,
los transportes HTTP/rangos de YouTube, MediaItem, MediaValidator, PublicWebUrlPolicy y la
ruta SocialExtractor. La selección y unión de YouTube solo cambian sus textos por referencias
a recursos. Se mantienen selección de calidad, dos trabajadores, cola, cancelación, permisos,
carpeta de salida, preferencias, paquete y certificado.

Los mensajes nuevos guardan claves y argumentos, no el idioma de la pantalla. Los textos
reconocidos del historial anterior se traducen al presentarlos sin borrar sus registros.
Los códigos técnicos, rutas/nombres de archivos y avisos legales originales se conservan.

## Recorrido que falta realizar en un teléfono

Instalar la actualización encima de la anterior. Comprobar una imagen, un vídeo y un carrusel;
iniciar dos descargas y añadir otra a la cola; cambiar entre ES/EN y volver al idioma del sistema
mientras continúan; salir de la app y apagar la pantalla; comprobar los archivos completos y
cancelar únicamente una petición. Revisar también botones, mensajes y notificaciones con la
escala de texto habitual del dispositivo.

Las referencias y evidencias de 1.4.2 quedan en `docs/VALIDACION-1.4.2.md` como historial,
no como resultados nuevos de esta versión.
