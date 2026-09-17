# Descarga Social para Android

Aplicación Android nativa para guardar imágenes, vídeos, Reels y carruseles de publicaciones públicas de Instagram y Facebook, vídeos y Shorts de YouTube, y para localizar el vídeo principal de otras páginas públicas. El procesamiento se realiza en el propio teléfono: no utiliza una web, un servidor intermediario ni una API de descarga de terceros.

## Requisitos

- JDK 17.
- Android SDK Platform 35.
- Android Build Tools 35.0.0.
- Android 7.0 (API 24) o posterior en el dispositivo.
- Bash, Python 3, zip, unzip y sha256sum para construir el paquete.

No se necesita Gradle ni Android Studio. El script busca por defecto el JDK en `/workspace/toolchains/jdk17` y el SDK en `/workspace/toolchains/android-sdk`. Se pueden cambiar mediante `JAVA_HOME` y `ANDROID_HOME`.

## Compilar

```bash
chmod +x build.sh
export DESCARGA_SOCIAL_KEYSTORE=/ruta/DescargaSocial-clave-de-firma.jks
export DESCARGA_SOCIAL_STORE_PASSWORD='…'
export DESCARGA_SOCIAL_KEY_PASSWORD='…'
./build.sh
```

El resultado firmado se genera en:

```text
dist/DescargaSocial-1.4.2.apk
```

Para crear una actualización instalable sobre la versión anterior hay que indicar la clave conservada mediante `DESCARGA_SOCIAL_KEYSTORE`, sus contraseñas y, si fuera distinto, `DESCARGA_SOCIAL_KEY_ALIAS`. El script verifica además la huella del certificado oficial y se niega a publicar una APK firmada con otra clave. No compartas nunca el archivo JKS ni sus contraseñas.

## Pruebas locales

Las pruebas deterministas del reconocimiento de enlaces y de los extractores de Facebook, web genérica y HLS se ejecutan con:

```bash
chmod +x test.sh
export DESCARGA_SOCIAL_JSON_JAR=/ruta/json.jar
./test.sh
```

El JAR se usa únicamente para reproducir fuera de Android la clase `org.json` que ya forma parte del sistema operativo; no se empaqueta en la APK.

Las dependencias de YouTube están fijadas e incluidas en `vendor`, junto con sus fuentes
y avisos de licencia. No hay descargas de dependencias durante la compilación.
La prueba opcional `./test.sh --live-youtube` consulta únicamente los metadatos del vídeo
público de Blender Foundation `aqz-KE-bpKQ`, mediante el adaptador de producción.
No descarga contenido ni verifica la unión nativa. En este entorno esa prueba está
limitada por la resolución DNS; las pruebas predeterminadas no necesitan Internet.

Alternativamente puede utilizarse Java 17 con Eclipse ECJ 3.36.0, indicando
`DESCARGA_SOCIAL_ECJ_JAR=/ruta/ecj.jar` tanto para las pruebas como para `build.sh`.
ECJ es una herramienta de compilación; tampoco se incorpora a la APK.

## Uso

1. Abre una publicación pública de Instagram o Facebook, un vídeo o Short de YouTube, o una página pública cuyo vídeo quieras guardar.
2. Comparte el enlace con **Descarga Social** o pégalo dentro de la aplicación.
3. Revisa el contenido detectado y confirma la descarga. En una web genérica se ofrece únicamente el candidato principal; los anuncios, GIF y clips secundarios se filtran.
4. Los archivos se guardan en `Descargas/DescargaSocial`.

## Corrección 1.4.2: recuperación de temporales y diagnóstico

El usuario informa de un fallo inmediato al descargar tras instalar 1.4.1. La captura
solo contiene el mensaje genérico; la excepción real del teléfono sigue sin conocerse.
Se reproduce en archivos reales otro defecto: si la raíz de la caché tiene un alias
simbólico, 1.4.1 rechaza su propia carpeta temporal. El registro queda pendiente y
las siguientes peticiones vuelven a fallar durante la recuperación, antes de transferir.

1.4.2 resuelve primero la raíz de caché autorizada y compara los hijos contra esa raíz.
Recupera registros antiguos que usen el alias, pero sigue rechazando enlaces hacia
otro trabajo, archivos inesperados o destinos exteriores. Los trabajos nuevos guardan
la ruta resuelta. Un fallo de limpieza no sustituye a la excepción original y bloquea
reintentos automáticos sobre un temporal que no se ha podido retirar.

Esto corrige un defecto reproducido; **no demuestra que el teléfono del usuario esté
usando un alias simbólico ni que esta sea su excepción exacta**. Android también puede
usar montajes vinculados para sus rutas de datos. La evidencia de plataforma se puede
consultar en [el inicio de Android](https://android.googlesource.com/platform/system/core/+/refs/heads/main/rootdir/init.rc).

Para cerrar esa falta de diagnóstico, las peticiones fallidas muestran **Detalles → Copiar**:
fase, clase de excepción, código de almacenamiento o HTTP y punto de fallo en el código
de la app. No se copian mensajes de excepción sin filtrar, rutas locales, direcciones
multimedia, cookies ni credenciales. El historial guarda ese texto; nada se envía
automáticamente. Los errores antiguos no contienen datos que no se registraron entonces.
**Acerca de** muestra 1.4.2 para identificar la versión instalada.

Pasan 549 comprobaciones locales. La recuperación del alias usa archivos y enlaces
reales del sistema; las pruebas de MediaStore y ensamblado siguen usando dobles de API.
La descarga completa en el teléfono sigue pendiente de confirmación.

## Corrección 1.4.1: acceso a las pistas al unir YouTube

El usuario observó que 1.4.0 completaba las fases de vídeo y audio y fallaba al unirlas.
La revisión encontró tres usos de `MediaExtractor.setDataSource(String)` con rutas de
la caché privada: vídeo, audio y comprobación del MP4 final. Android documenta que esa
sobrecarga puede abrir el archivo desde otro proceso, que no tiene acceso a la caché
privada de la app. Ahora la aplicación abre cada archivo y entrega un descriptor con
su longitud exacta. Los archivos mantienen sus permisos privados.

Referencia: [contrato oficial de MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor#setDataSource(java.lang.String)).

También se conservan los errores de entrada/salida con su fase: abrir vídeo, abrir sonido,
crear, unir, finalizar o comprobar el MP4. La liberación de recursos no oculta el fallo
original ni convierte una cancelación en un error genérico. La extracción de YouTube,
la selección de calidad, las transferencias, las demás plataformas y la cola no cambian.

Pasan 512 comprobaciones locales, incluidas 51 del ensamblado con una API Android
simulada. La misma prueba reproduce el uso de rutas privadas y el mensaje genérico
de 1.4.0. Es una prueba del contrato de acceso y de la secuencia de operaciones;
no ejecuta los códecs nativos. La corrección debe confirmarse con el mismo vídeo en
el teléfono. No se afirma haber reproducido la excepción interna exacta del móvil.

## Actualización 1.4.0: YouTube

- Detecta automáticamente enlaces `youtube.com/watch?v=…`, `youtu.be/…`, Shorts,
  enlaces de inserción y enlaces `/live/…` de vídeos ya procesados. Un enlace con
  un vídeo y una lista selecciona solo ese vídeo; no descarga listas completas.
- Utiliza NewPipeExtractor 0.26.5 dentro de la APK. No requiere cuenta ni token de API.
- Guarda un MP4 con vídeo AVC y sonido AAC. **Máxima calidad compatible** elige la
  resolución disponible más alta en esos formatos; no promete 4K cuando YouTube lo
  ofrece únicamente en VP9/AV1. **Ahorrar datos** prefiere hasta 360p; si no existe
  ninguna resolución igual o menor, elige la menor disponible.
- Si YouTube entrega las pistas por separado, Android las une con `MediaMuxer`, sin
  recodificar. Se muestra la fase actual: vídeo, sonido, unión y guardado del MP4.
  Los porcentajes y MB corresponden a la fase indicada, no al conjunto de fases.
- Cada petición conserva su elección de calidad. Los formatos se guardan en memoria
  durante tres minutos y se consultan de nuevo al descargar si han caducado en la cola.
  Las direcciones temporales no se guardan en el historial. Un rechazo del servidor
  requiere analizar de nuevo el enlace; no se intenta eludirlo.
- Mantiene los dos trabajadores en segundo plano. La extracción de metadatos se
  serializa para proteger la caché del motor; las transferencias pueden ser simultáneas.
- Las pistas separadas se guardan temporalmente en la caché privada, por petición.
  Se comprueba el espacio para pistas y MP4, y se retiran al completar, fallar o cancelar.
  Tras una muerte del proceso se recuperan esos temporales en el siguiente uso.
- No admite directos en curso, estrenos aún sin procesar, vídeos privados, de pago,
  protegidos, ni aquellos que exijan iniciar sesión o verificar la edad. No incluye
  extracción de audio por separado. Se conserva el límite de 2 GiB por resultado.

En la entrega inicial 1.4.0 se compiló y firmó la APK y pasaron 461 comprobaciones locales. La consulta
directa del motor NewPipe reconoció el vídeo público de Blender y sus formatos hasta
1080p60 AVC con AAC. La transferencia desde `googlevideo.com` agotó el tiempo de
espera en este entorno. El adaptador de producción quedó limitado por el DNS local.
**La descarga completa de YouTube y la unión con sonido requieren validación en Android**;
no se ha instalado esta versión en un teléfono ni en un emulador. Véase `PRUEBAS_ANDROID.md`.

## Actualización 1.3.0: interfaz compacta y peticiones simultáneas

- Dos pestañas: **Nuevo** para pegar, analizar y seleccionar; **Descargas** para seguir los trabajos.
- Se elimina el desplazamiento de la página completa. El botón principal queda fijo al pie,
  el selector de calidad abre un diálogo y las vistas previas ocupan el espacio restante.
  En ventanas anchas el editor y las vistas previas se distribuyen en dos columnas.
- Las listas de elementos y tareas se desplazan dentro de su propio panel. En ventanas
  muy pequeñas, con el teclado abierto o texto ampliado, el formulario puede desplazarse
  internamente para conservar controles legibles. No se reduce artificialmente el tamaño de letra.
- Dos peticiones pueden descargar al mismo tiempo, en cualquiera de las plataformas.
  Las siguientes esperan en orden de llegada, con hasta 20 peticiones pendientes en total.
  Un carrusel conserva su orden dentro de su propia petición.
- Al confirmar una descarga, el enlace queda libre para pegar el siguiente. Cada petición
  guarda una copia de su selección y calidad: analizar otra no modifica lo que ya descarga.
- Progreso y cancelación por petición, también desde la notificación desplegada.
  Cancelar una no detiene las demás. La cola avanza cuando el trabajador anterior ha
  terminado de cerrar su conexión y limpiar su archivo parcial.
- Las peticiones activas aparecen primero. Se conservan hasta 50 resultados de historial;
  **Limpiar** retira únicamente resultados finalizados de la lista, nunca archivos descargados.
- Los destinos pendientes y los controles de red están aislados por petición.
  Los nombres se reservan de forma sincronizada para no sobrescribir archivos entre trabajadores.
- El historial conserva estados y etiquetas, sin direcciones multimedia ni credenciales.
  Tras una muerte del proceso se indica la interrupción; no se reproducen enlaces caducados.

## Descargas en segundo plano (base 1.2.1 conservada)

- La descarga confirmada se ejecuta en un servicio nativo `dataSync`, no en la actividad.
  Permite cambiar de aplicación o apagar la pantalla durante una descarga normal.
- En Android 13 y posteriores se solicita permiso para mostrar notificaciones.
  Si se deniega, el servicio puede ejecutarse igualmente; la cancelación queda disponible
  dentro de la app. El permiso de notificaciones es recomendable para ver el progreso fuera.
- La lista y la notificación desplegada muestran MB recibidos por petición.
  Si el servidor declara el tamaño, la barra individual usa ese tamaño; si no, es
  indeterminada. La notificación resumida muestra cuántas descargan y cuántas esperan.
- Cancelar interrumpe el trabajo y cierra la conexión desde un hilo independiente.
- Hasta tres intentos por archivo ante cortes transitorios, con esperas de 2 y 5 segundos.
  Cada intento reinicia el archivo fallido desde cero: no se promete reanudación por bytes.
  No se reintentan rechazos de acceso, límites 429, errores de certificados, DRM o disco lleno.
- Los archivos parciales no se publican como completados. Se eliminan al fallar o cancelar.
  Se registra el destino pendiente para recuperarlo con seguridad tras una muerte del proceso;
  jamás se borra un archivo que ya haya pasado a completado.
- El bloqueo parcial de CPU dura solo durante la descarga. Se libera al terminar, cancelar
  o destruir el servicio. Cada lote tiene un límite de dos horas; también se respeta
  `Service.onTimeout` de Android 15 y posteriores.
- No se reinician automáticamente descargas tras forzar la detención, reiniciar el móvil
  o una muerte del proceso. En esos casos hay que abrir la app y analizar de nuevo.
  Las restricciones del fabricante, Android o el servidor todavía pueden interrumpirlas.

La corrección de segundo plano no modifica los mecanismos de extracción específicos.
No certifica que un enlace concreto sea descargable ni evita las restricciones del servidor.

Validación de esta entrega: 549 comprobaciones y escenarios locales, compilación API 35
y verificación de firma v2/v3. Las pruebas de almacenamiento usan la clase de producción
con un MediaStore simulado y archivos reales para el guardado antiguo; no sustituyen un
dispositivo Android. El usuario confirmó el funcionamiento físico de 1.2.1; esta
corrección 1.4.2 requiere comprobarse en el móvil. Consultar `PRUEBAS_ANDROID.md`.

La implementación respeta los [límites oficiales del servicio dataSync](https://developer.android.com/develop/background-work/services/fgs/timeout)
y utiliza los [insets del sistema y del teclado](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
para mantener los controles dentro de la ventana.

En Android 10 y versiones posteriores se usa `MediaStore`, sin solicitar permiso general de almacenamiento. En Android 7, 8 y 9 se solicita el permiso de escritura solo cuando es necesario.

## Privacidad y límites

La aplicación no pide credenciales, no contiene anuncios, analítica ni seguimiento y no conserva cookies de páginas genéricas. Solo admite contenido público accesible sin iniciar sesión. Las Historias de Facebook y el contenido privado, restringido por edad, eliminado o que exija iniciar sesión no son compatibles.

El extractor web reconoce vídeo MP4, WebM, MPEG-TS y emisiones HLS finitas y sin cifrar que la propia página publique directamente. También puede expandir documentos JSON acotados de calidades y reconocer la representación de URL de xplayer vigente al publicar esta versión. Puntúa las fuentes por evidencia de contenido principal, duración, resolución y tamaño, y penaliza anuncios, pre-rolls, bucles, GIF y archivos pequeños. Puede seguir una espera declarada mediante `Refresh` dentro del mismo sitio, con un máximo de 15 segundos; no ejecuta JavaScript, no pulsa anuncios, no abre ventanas emergentes y no elude DRM. YouTube usa su módulo independiente y no pasa por este extractor genérico.

La compatibilidad con páginas genéricas es experimental: si una web construye la dirección solo mediante JavaScript, exige una cookie, un captcha o una sesión, ofrece únicamente DASH o cambia su camuflaje, será necesario añadir o actualizar un módulo específico.

Los sitios pueden modificar o limitar sus interfaces en cualquier momento; una limitación temporal de la red o de la plataforma puede impedir una descarga. Los extractores están separados para que una actualización de uno no altere los demás.

Por seguridad, las páginas y todos sus recursos deben usar HTTPS público. Se bloquean redes locales, credenciales dentro de URLs, puertos alternativos, redirecciones inseguras, archivos que no coincidan con su firma real, emisiones en directo y descargas de más de 2 GiB.

Descarga únicamente contenido propio o para el que tengas permiso. Respeta los derechos de autor, la privacidad de sus autores y las condiciones de cada plataforma.

## Licencia

Este proyecto se distribuye bajo GNU General Public License v3.0. Consulta `LICENSE` y `NOTICE.md`.
