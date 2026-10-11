# YouTube Calma

Personalización de **la APK proporcionada por el usuario**, YouTube ReVanced 20.40.45 (1557278144). Conserva el cliente nativo, sus reproductores y sus ajustes. No usa un cliente web.

[Descargar APK 0.1.1](https://github.com/miguelcoxcaballero/youtube-calma/releases/download/v0.1.1/YouTube-Calma-0.1.1.apk) · [Release y suma SHA-256](https://github.com/miguelcoxcaballero/youtube-calma/releases/tag/v0.1.1)

- Sin pestaña Inicio. La app abre directamente Suscripciones.
- Debajo del vídeo se conservan descripción y comentarios; se retiran las filas de vídeos recomendados antes de distribuir el contenido.
- Shorts se ocultan en Inicio, Suscripciones, canales, búsqueda, historial y descripción. Se ocultan los botones Shorts y Crear.
- La barra inferior solicita el botón nativo de Notificaciones; la campana y Crear se ocultan en la barra superior.
- El preset de [settings.json](settings.json), con las peticiones posteriores de [navigation-settings.json](navigation-settings.json), se aplica antes de inicializar los ajustes de ReVanced. Las actualizaciones conservan las elecciones posteriores y los contadores de SponsorBlock.
- El actualizador reutiliza Inhouse Read e Inhouse Photos. La comprobación automática se inicia al abrir la app; la manual está en los ajustes de ReVanced.

## Instalación

La APK requiere Android 9 o posterior y ReVanced GmsCore, como el archivo original. Conserva las cuatro arquitecturas incluidas en la base.

Conserva el paquete `app.revanced.android.youtube`, pero está firmada con la clave de Calma. **No puede instalarse encima de la APK original con otra firma**. Si tienes esa ReVanced instalada, exporta los ajustes y conserva tus descargas antes de desinstalarla e instalar esta. El YouTube oficial utiliza otro paquete y puede permanecer instalado. Las siguientes versiones de Calma se podrán actualizar normalmente.

## Código y verificación

Los cambios propios están en [patches/YouTubeCalma.kt](patches/YouTubeCalma.kt) y [src](src). No se aplica ningún paquete adicional de parches de terceros; se conectan las funciones que ya contiene el APK facilitado. [base.lock.json](base.lock.json) fija archivo, versión, tamaño, firma y SHA-256.

El build valida la entrada, compila la extensión aislando sus dependencias, aplica los cambios, alinea y firma. El cliente mantiene `versionName=20.40.45` para su protocolo; el actualizador usa `0.1.1` y Android `versionCode=1557278146`.

Pruebas: integridad de la APK, preset, bibliotecas nativas, procedencia del instalador, popup/descarga y recuperación de actualizaciones. La instrumentación Android comprueba los valores cargados por ReVanced, callbacks de navegación, filtros y conservación de estadísticas. Las pruebas sin iniciar sesión no verifican las suscripciones de una cuenta real ni la reproducción autenticada.

## Compilación

Requiere Java 17+, Python 3, SDK Android (plataforma 35 y build-tools 36), ECJ, Kotlin 2.0.21 y AndroidX Core. La APK base y las claves privadas no se incluyen en Git.

```sh
python3 scripts/setup-tools.py --android-sdk /ruta/al/sdk
# Guarda el archivo exacto del usuario en base/youtube-original.apk.
python3 scripts/build.py --morphe tools/morphe.jar \
  --build-tools /ruta/al/sdk/build-tools/36.0.0 --keystore /ruta/a/calma.p12
python3 tests/artifact.py
python3 tests/updater.py
```

La clave debe tener alias `calma`; establece `CALMA_KEYSTORE_PASSWORD` para una clave propia. Una clave distinta impide actualizar instalaciones anteriores. Las versiones publicadas conservan la clave existente.
