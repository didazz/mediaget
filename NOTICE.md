# Avisos

## MediaGet

MediaGet 1.5.0 (anteriormente Descarga Social) es software libre distribuido bajo GNU General Public License, versión 3. Consulta el texto íntegro en `LICENSE`.

Este proyecto se desarrolló tomando como referencia el proyecto libre **InstaDownload**, publicado por Orang Studio en <https://github.com/Orang-Studio/InstaDownload> bajo GNU General Public License v3.0. Las modificaciones y la nueva interfaz se distribuyen bajo la misma licencia.

La compatibilidad con la representación cifrada de direcciones de xplayer se implementó tomando
como referencia el código fuente de **yt-dlp** en <https://github.com/yt-dlp/yt-dlp>, publicado bajo
The Unlicense. No se incluye el ejecutable ni ninguna biblioteca de yt-dlp.

## Marcas y afiliación

Instagram, Facebook, YouTube, Google, Meta y las marcas de los sitios analizados pertenecen a sus respectivos titulares. MediaGet es una aplicación independiente y no está afiliada, patrocinada ni avalada por esos servicios.

## Bibliotecas integradas para YouTube

Se distribuye NewPipeExtractor 0.26.5, de Team NewPipe, bajo GPL-3.0-or-later,
sin cambios en su código. Se invoca dentro del proceso Android; no se utiliza ningún
servicio extractor remoto. Su código fuente correspondiente está incluido en
`vendor/sources/NewPipeExtractor-v0.26.5.zip`.

También se incluyen nanojson (Apache-2.0), jsoup (MIT), protobuf-javalite (BSD-3-Clause),
Rhino (principalmente MPL-2.0; su aviso completo conserva las otras licencias de archivos),
JSR-305 (Apache-2.0 y los avisos originales de sus anotaciones), y desugar_jdk_libs_nio
(OpenJDK GPL-2.0 con Classpath Exception y avisos adicionales). La configuración de
desugaring de R8 usa BSD-3-Clause. Versiones, procedencia y fuentes en `vendor/README.md`.

Los textos íntegros de licencia y los avisos se incluyen en `app/src/main/assets/licenses`
y pueden consultarse sin conexión en **Acerca de → Licencias**. Los archivos fuente de
las dependencias conservan sus avisos originales. Las bibliotecas no están modificadas;
D8/L8 las convierten al formato Android durante la construcción.

## Uso responsable

La aplicación está destinada a guardar contenido público que el usuario tenga derecho a descargar. Cada usuario es responsable de respetar los derechos de autor, la privacidad, las leyes aplicables y las condiciones del servicio de origen.
