# YouTube Calma

Personalización de **la APK proporcionada por el usuario**, YouTube ReVanced 20.40.45 (1557278144). Conserva el cliente nativo, sus reproductores y sus ajustes. No usa un cliente web.

[Descargar APK 0.1.4](https://github.com/miguelcoxcaballero/youtube-calma/releases/download/v0.1.4/YouTube-Calma-0.1.4.apk) · [Release y suma SHA-256](https://github.com/miguelcoxcaballero/youtube-calma/releases/tag/v0.1.4)

- Sin pestaña Inicio. La app abre directamente Suscripciones.
- Debajo del vídeo se conservan descripción y comentarios; se retiran las filas de vídeos recomendados antes de distribuir el contenido.
- Shorts se ocultan en Inicio, Suscripciones, canales, búsqueda, historial y descripción. Se ocultan los botones Shorts y Crear.
- La barra inferior solicita el botón nativo de Notificaciones; la campana y Crear se ocultan en la barra superior.
- El preset de [settings.json](settings.json), con las peticiones posteriores de [navigation-settings.json](navigation-settings.json), se aplica antes de inicializar los ajustes de ReVanced. Las actualizaciones conservan las elecciones posteriores y los contadores de SponsorBlock.
- El actualizador reutiliza Inhouse Read e Inhouse Photos. La comprobación automática se inicia al abrir la app; la manual está en los ajustes de ReVanced.

## Iconos Inhouse (0.1.4)

El play blanco del icono de YouTube se sustituye por el símbolo blanco de Inhouse, editado con IA. Los logos interiores, incluido el del popup, usan blanco sobre negro. Se conserva el icono nuevo al activar o desactivar las funciones Inhouse. [Recursos y adaptación al launcher](BRANDING.md).

## Ajustes Inhouse (0.1.3)

En los ajustes nativos, justo debajo de ReVanced, aparece **Inhouse YouTube settings**. El interruptor activa o desactiva las modificaciones de contenido, navegación y el preset de esta personalización; aplica el cambio reiniciando la app. Los ajustes y el actualizador permanecen accesibles.

Se guardan por separado las preferencias con Inhouse activado y desactivado. Al actualizar desde versiones que no guardaban la configuración anterior a Calma, la primera desactivación utiliza los valores originales del APK ReVanced proporcionado. No se cambian la cuenta, las estadísticas acumuladas ni la identidad de la aplicación.

El pie muestra el [logo original de Inhouse Read](https://github.com/miguelcoxcaballero/inhouse-read/blob/main/public/icons/inhouse-read-logo.png), versión, fecha de publicación y enlace a este repositorio.

## Actualizador corregido en 0.1.2

La 0.1.0 y la 0.1.1 descargaban la actualización, pero fallaban al abrir el instalador porque su proveedor de archivos compartía autoridad con el original de YouTube. La 0.1.2 usa la autoridad independiente original de Inhouse Photos. Mantiene las comprobaciones de paquete, versión, hash y firma.

**Para reparar una instalación 0.1.0 o 0.1.1, descarga e instala la 0.1.4 desde el enlace de arriba, sin desinstalar Calma.** Esta vez el actualizador antiguo no puede aplicar su propia reparación. Se conservan la cuenta y los ajustes.

También se omite el aviso de la base que esperaba una instalación recién parcheada desde ReVanced Manager: Calma se distribuye aquí como APK firmada.

## Instalación

La APK requiere Android 9 o posterior y ReVanced GmsCore, como el archivo original. Conserva las cuatro arquitecturas incluidas en la base.

Conserva el paquete `app.revanced.android.youtube`, pero está firmada con la clave de Calma. **No puede instalarse encima de la APK original con otra firma**. Si tienes esa ReVanced instalada, exporta los ajustes y conserva tus descargas antes de desinstalarla e instalar esta. El YouTube oficial utiliza otro paquete y puede permanecer instalado. Las siguientes versiones de Calma se podrán actualizar normalmente.

## Código y verificación

Los cambios propios están en [patches/YouTubeCalma.kt](patches/YouTubeCalma.kt) y [src](src). No se aplica ningún paquete adicional de parches de terceros; se conectan las funciones que ya contiene el APK facilitado. [base.lock.json](base.lock.json) fija archivo, versión, tamaño, firma y SHA-256.

El build valida la entrada, compila la extensión aislando sus dependencias, aplica los cambios, alinea y firma. El cliente mantiene `versionName=20.40.45` para su protocolo; el actualizador usa `0.1.4` y Android `versionCode=1557278149`.

La 0.1.3 ha superado 167 comprobaciones por versión y dos fases adicionales de cambio de perfil entre procesos en emuladores Android 14, 15 y 16. Se comprueban la entrada nativa bajo ReVanced, la actividad de ajustes y su pie, la restauración de preferencias y la apertura real del instalador desde `cache/updates/` hasta su confirmación. [Resultados y alcance](validation/0.1.3.json) · [Ejecución Android](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38139358568). La confirmación de instalación se cancela en las pruebas.

También se comprueban integridad de la APK, preset, bibliotecas nativas, procedencia del instalador, popup/progreso y recuperación de actualizaciones. La instrumentación Android comprueba los valores cargados por ReVanced, callbacks de navegación, filtros y conservación de estadísticas. Las pruebas sin iniciar sesión no verifican las suscripciones de una cuenta real ni la reproducción autenticada.

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
