# Pruebas instrumentadas

APK de pruebas separada; no se incluye en la entrega instalable MediaGet.
La prueba introduce un historial de ejemplo en la instalación desechable para verificar
la migración ES/EN; no debe ejecutarse sobre una instalación con historial que se quiera conservar. Usa una instalación
Android de pruebas y la misma firma. `build.sh` compila esta instrumentación para API 33+.

Prueba recursos Android en EN, ES, FR/DE como respaldo y variantes ES-MX/EN-GB. Cambia el
idioma por aplicación mediante LocaleManager y comprueba que la pantalla conserva enlace,
resultado del análisis, selección y trabajador de análisis. Inserta únicamente resultados
sintéticos de vídeo en la pantalla; **no transfiere archivos ni verifica una descarga real**.
Además ejecuta el método de unión de producción con pistas AVC/AAC sintéticas, usando
MediaExtractor y MediaMuxer reales. Las pistas de prueba son un patrón geométrico y un tono
generados con FFmpeg; no proceden de ninguna plataforma ni constituyen una descarga.

Restaura el modo de idioma automático al completar el recorrido.

El estado de ejecución de estas pruebas está en `../PRUEBAS_ANDROID.md`; que exista este
código o se pueda compilar no implica que se haya ejecutado.
