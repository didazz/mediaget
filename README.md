# MediaGet — Android 1.5.0

## Instalar y actualizar

El APK de esta versión es **MediaGet-1.5.0.apk**. Tras su publicación, estará en la sección **Releases** del repositorio. La dirección de ese repositorio se podrá añadir a [Obtainium](https://obtainium.imranr.dev/) para seguir las actualizaciones.

MediaGet es el nuevo nombre de Descarga Social. Esta versión conserva el identificador de aplicación y el certificado de firma para actualizar sobre la instalación anterior. La carpeta de los archivos descargados sigue siendo `Descargas/DescargaSocial`.

Android puede solicitar permiso para instalar desde el navegador o desde Obtainium, además de sus confirmaciones y controles de seguridad habituales.

Las descripciones oficiales en español e inglés están en [metadata/descriptions.json](metadata/descriptions.json). La interfaz usa el idioma del dispositivo y admite español e inglés; en Android 13 o posterior también puede elegirse el idioma desde los ajustes de la aplicación.

## Preparación de esta publicación

El APK es el archivo existente de MediaGet 1.5.0. La preparación de la publicación no recompila ni modifica la aplicación. Se comprobaron su huella SHA-256, firma JAR, certificado e integridad, así como las fuentes conservadas y los recursos ES/EN. Los resultados de pruebas de `VERIFICACION.json` y `PRUEBAS_ANDROID.md` pertenecen a la entrega original. No se ejecutó una nueva prueba en un dispositivo durante esta preparación.

Desarrollo con asistencia de IA.


Descargador universal de vídeos e imágenes. / Universal video and image downloader.

Actualización incremental de Descarga Social 1.4.2. Conserva `com.didazz.descargasocial`,
el certificado de firma, los extractores, el guardado local, las dos descargas concurrentes,
la cola y el servicio de descargas en segundo plano. No requiere una PWA, servidor propio,
cuenta ni API de extracción de pago.

## Qué cambia

- Nombre visible MediaGet en launcher, aplicación, notificaciones y diálogos.
- Interfaz, estados, notificaciones y errores presentados mediante recursos Android ES/EN.
- Inglés en `app/src/main/res/values/strings.xml` y español en `values-es/strings.xml`.
- Idioma automático del dispositivo; recursos ingleses como respaldo.
- `res/xml/locales_config.xml` habilita la selección nativa por aplicación en Android 13+.
- Descripciones corta y completa proporcionadas por el usuario, en ambos idiomas, en «Acerca de».
- Mensajes de trabajadores e historial nuevos guardados como claves y argumentos; se traducen
  al mostrarlos. La pantalla conserva enlace, selección, resultados y trabajador de análisis
  durante cambios de configuración. La cola y el servicio no se reinician por esta actualización
  de presentación. Los diálogos abiertos se cierran al cambiar idioma y se pueden reabrir traducidos.

## Funciones conservadas

Instagram y Facebook públicos; vídeos y Shorts de YouTube compatibles; detección del vídeo
principal en webs públicas; fotos/carruseles cuando la plataforma los proporciona;
calidad máxima compatible o ahorro; compartir enlaces; selección de elementos; dos trabajos
simultáneos; cola FIFO; cancelación individual; progreso y trabajo con la pantalla apagada;
historial y diagnóstico copiable. Se mantienen los límites y formatos de 1.4.2.

La carpeta de salida sigue siendo `Descargas/DescargaSocial` para conservar la continuidad
con los archivos anteriores. El nombre de paquete, permisos, identificadores de preferencias,
canal de notificaciones, acciones internas y clave no cambian. La aplicación se instala encima
de la versión anterior; no es necesario desinstalarla.

## Compilar

Requisitos: Java 17, Android SDK Platform 35, Build Tools 35.0.0, Python 3, Bash, zip y unzip.
Android mínimo: 7.0 / API 24. No se necesita Gradle. Las dependencias están incluidas y fijadas
por hash en `vendor/`; las herramientas SDK/JDK se instalan aparte.

```bash
export JAVA_HOME=/ruta/jdk17
export ANDROID_HOME=/ruta/android-sdk
export DESCARGA_SOCIAL_KEYSTORE=/ruta/DescargaSocial-clave-de-firma.jks
export DESCARGA_SOCIAL_STORE_PASSWORD='…'
bash build.sh
```

Alternativa sin `javac`: indicar `DESCARGA_SOCIAL_ECJ_JAR=/ruta/ecj-3.36.0.jar`.
El resultado es `dist/MediaGet-1.5.0.apk`. El script verifica hashes de dependencias,
compila los recursos y el código, alinea y firma, y exige el mismo certificado que 1.4.2.
La clave no se incluye en este archivo fuente; se conserva por separado en el proyecto.

## Traducciones y nuevos idiomas

Todos los textos de presentación se definen en los XML de recursos. `Messages` conserva
claves y argumentos sin elegir idioma ni almacenar traducciones en Java. `TextResources`
usa el contexto Android actual al mostrar un mensaje. `ResourceIds` se genera durante el build.

Para añadir un idioma:

1. Crear `res/values-XX/strings.xml` con las mismas claves y marcadores indexados.
2. Traducir su contenido y añadir el idioma en `res/xml/locales_config.xml`.
3. Compilar y probar los recursos y las pantallas en ese idioma.

La configuración sigue las referencias oficiales de [idioma por aplicación](https://developer.android.com/guide/topics/resources/app-languages) y [recursos localizados](https://developer.android.com/guide/topics/resources/localization).

No hay un `if` por idioma en la lógica de descarga. El adaptador ES/EN de `TextResources`
solo reconoce diagnósticos internos y registros antiguos; no selecciona el idioma del usuario.
Los extractores conservan sus textos internos de diagnóstico y clasificación para no alterar
el comportamiento de recuperación. No se muestran directamente excepciones desconocidas.
Los avisos legales originales, nombres de plataformas, rutas, nombres de archivos y códigos
HTTP/sistema mantienen su contenido original; no son textos de interfaz traducibles.

## Validación

```bash
export DESCARGA_SOCIAL_JSON_JAR=/ruta/json-20240303.jar
bash test.sh
```

Incluye regresiones de los extractores/almacenamiento/cola y comprobaciones ES/EN con los XML
reales. Los dobles Android de pruebas no se empaquetan en la aplicación.

**Consultar `PRUEBAS_ANDROID.md` y `VERIFICACION.json` para conocer exactamente qué pruebas se
han ejecutado y cuáles siguen pendientes. Una compilación o prueba simulada no acredita una
descarga real ni una interfaz ejecutada en Android.** Las pruebas instrumentadas están en
`android-tests/`; requieren un emulador o dispositivo de pruebas y la misma firma.

## Licencias

GPL-3.0-or-later para la aplicación. Se conservan NewPipeExtractor 0.26.5 y las mismas
versiones de todas las dependencias. Fuentes correspondientes y licencias en `vendor/`,
`NOTICE.md`, `LICENSE` y los avisos originales accesibles desde la aplicación.
