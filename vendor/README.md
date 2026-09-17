# Dependencias fijadas — Descarga Social 1.4.0 a 1.4.2

Todos estos componentes se ejecutan en el teléfono. No son servicios de descarga.
`build.sh` comprueba `SHA256SUMS` antes de compilar; no descarga dependencias.
Los nueve JAR se contrastaron además con el SHA-1 publicado por su repositorio al obtenerlos.

| Archivo | Componente y versión | Origen original |
|---|---|---|
| runtime/newpipe.jar | NewPipeExtractor v0.26.5 | https://jitpack.io/com/github/TeamNewPipe/NewPipeExtractor/v0.26.5/NewPipeExtractor-v0.26.5.jar |
| runtime/nanojson.jar | TeamNewPipe/nanojson e9d656ddb49a412a5a0a5d5ef20ca7ef09549996 | https://jitpack.io/com/github/TeamNewPipe/nanojson/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996/nanojson-e9d656ddb49a412a5a0a5d5ef20ca7ef09549996.jar |
| runtime/jsoup.jar | org.jsoup:jsoup 1.22.2 | Maven Central |
| runtime/jsr305.jar | com.google.code.findbugs:jsr305 3.0.2 | Maven Central |
| runtime/protobuf.jar | com.google.protobuf:protobuf-javalite 4.35.1 | Maven Central |
| runtime/rhino.jar | org.mozilla:rhino 1.8.1 | Maven Central |
| runtime/rhino-engine.jar | org.mozilla:rhino-engine 1.8.1 | Maven Central; conservado para escritorio, excluido de la APK |
| compat/desugar.jar | com.android.tools:desugar_jdk_libs_nio 2.1.5 | Google Maven |
| compat/desugar-config.jar | com.android.tools:desugar_jdk_libs_configuration_nio 2.1.5 | Google Maven |

Los JAR de fuentes en `sources` proceden de las mismas coordenadas y versiones que sus
binarios. NewPipeExtractor incluye su repositorio completo de la etiqueta v0.26.5.
La fuente de desugar 2.1.5 corresponde a la revisión de publicación
`73170c345e6a762fc6a1f0301bb15218850023ef` de https://github.com/google/desugar_jdk_libs;
`VERSION_JDK11_NIO.txt` identifica 2.1.5. Se conservan scripts de construcción y licencias.

Fuentes de los proyectos: https://github.com/TeamNewPipe/NewPipeExtractor,
https://github.com/TeamNewPipe/nanojson, https://github.com/jhy/jsoup,
https://github.com/protocolbuffers/protobuf, https://github.com/mozilla/rhino,
https://code.google.com/archive/p/jsr-305/ y https://github.com/google/desugar_jdk_libs.
La configuración de R8 conserva su licencia BSD en la aplicación y en su JAR.

Para Android 7–12, NewPipe requiere desugaring de las API Java NIO. D8 adapta llamadas
y L8 genera las clases `j$`; todos los DEX se incorporan a la APK. Android 7 ya carga
varios DEX de forma nativa. `package_resources.py` conserva recursos de Rhino/jsoup,
excluyendo firmas de JAR, fuentes y clases JVM.

Rhino se usa por NewPipe en modo interpretado con objetos estándar seguros para las
funciones del reproductor. No se usa JSR-223 (`javax.script`) ni el compilador JIT de
Rhino. D8 puede advertir de referencias opcionales a JDK Dynalink en este último;
no corresponden a la ruta interpretada. No se añaden clases ficticias del JDK.

Licencias: `../NOTICE.md` y `../app/src/main/assets/licenses/`.
El código fuente de la app y de las bibliotecas acompaña a la APK; la clave de firma
no se incorpora al proyecto ni al archivo ZIP de fuentes.
