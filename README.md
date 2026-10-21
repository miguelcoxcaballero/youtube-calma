# YouTube Calma

Personalización de **la APK proporcionada por el usuario**, YouTube ReVanced 20.40.45 (1557278144). Conserva el cliente nativo, sus reproductores y sus ajustes. No usa un cliente web.

[Descargar APK 0.1.14 · B y A](https://github.com/miguelcoxcaballero/youtube-calma/releases/download/v0.1.14/YouTube-Calma-0.1.14.apk) · [Release y suma SHA-256](https://github.com/miguelcoxcaballero/youtube-calma/releases/tag/v0.1.14)

- Sin pestaña Inicio. La app abre directamente Suscripciones.
- Debajo del vídeo se conservan descripción y comentarios; se retiran las filas de vídeos recomendados antes de distribuir el contenido.
- Shorts se ocultan en Inicio, Suscripciones, canales, búsqueda, historial y descripción. Se ocultan los botones Shorts y Crear.
- La barra inferior muestra Suscripciones a la izquierda, tu cuenta en el centro y Notificaciones a la derecha, con el buzón nativo de avisos de YouTube; la campana y Crear se ocultan en la barra superior.
- El preset de [settings.json](settings.json), con las peticiones posteriores de [navigation-settings.json](navigation-settings.json), se aplica antes de inicializar los ajustes de ReVanced. Las actualizaciones conservan las elecciones posteriores y los contadores de SponsorBlock.
- Inhouse YouTube settings aparece debajo de ReVanced con su icono y la misma plantilla nativa de fila, incluida la reserva de espacio para iconos.
- **Inhouse services updater** comprueba al abrir la app. En **Inhouse YouTube settings → Actualizaciones · A/B** puedes elegir canal y comprobar manualmente.

## microG integrado y aviso antes de actualizar (0.1.14 · B)

Publicada en B por petición expresa tras confirmar el usuario que puede iniciar sesión. También llega a A. Conserva la integración de microG de la 0.1.13 y requiere Android 14 o posterior. Al actualizar desde una versión con microG externo hay que iniciar sesión de nuevo.

El popup muestra este aviso antes del botón **Update**, también en los clientes ya instalados:

> Android 14+. Built-in microG: sign in again after updating. If sign-in fails, wait 30 minutes and retry.

Verificada la APK firmada sobre la instalación 0.1.5 en Android 14, 15 y 16: código estrictamente mayor, identidad de instalación conservada y arranque sin microG externo ([informe](validation/0.1.14.json), [ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38305286531)).

Verificado el aviso en modo claro y oscuro, en la comprobación manual y automática del actualizador de la 0.1.11: visible sin abrir Details y sin descarga automática ([informe](validation/updater-notice-0.1.14.json)). Los fuentes de la APK están en el [tag v0.1.14](https://github.com/miguelcoxcaballero/youtube-calma/tree/v0.1.14); utiliza su compilador `experiments/embedded-gms/build-youtube.py`. Este árbol incorpora los fuentes de microG integrado; los catálogos siguen publicándose en `main`.

## microG integrado (0.1.13 · A experimental)

Incluye microG dentro de la APK: no requiere instalar una aplicación microG aparte. Requiere **Android 14 o posterior** y volver a iniciar sesión en Google. Se publicó inicialmente por el canal A; conserva el paquete, la firma y el actualizador compartido.

La APK firmada final superó la actualización sobre 0.1.5 y el arranque sin microG externo en Android 14, 15 y 16, conservando la identidad de la instalación ([informe](validation/0.1.13.json), [ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38271314852)).

La integración superó 39 comprobaciones por versión y llegó a la entrada de credenciales de Google en Android 14, 15 y 16 ([resultados](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38264289394)). El usuario confirmó después que puede iniciar sesión en su Galaxy S24 Ultra. Las pruebas automáticas no cubren sesiones autenticadas, reproducción, renovación de sesión ni notificaciones.

El código de esta APK está en el [tag v0.1.13](https://github.com/miguelcoxcaballero/youtube-calma/tree/v0.1.13), con el compilador `experiments/embedded-gms/build-youtube.py`. Este árbol continúa esa integración; consulta [AGENTS.md](AGENTS.md) antes de compilar. Los tags conservan los fuentes exactos de cada versión publicada.

## Colores del actualizador (0.1.12 · A)

El popup usa grises neutros en modo claro y oscuro, sin el tono anaranjado de la paleta anterior. Sincroniza Inhouse services updater 1.2.1 desde su repositorio compartido.

Verificada la APK pública, su firma y los recursos del actualizador. Supera las pruebas del popup y 247 comprobaciones nativas por API en Android 14, 15 y 16, incluida la actualización sobre 0.1.5 y la apertura del instalador. [Resultados](validation/0.1.12.json) · [Ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38178482369).

## Actualizador nuevo (0.1.11 · B)

Incluye la interfaz compartida de Inhouse services updater 1.2.0 para avisos, descarga y progreso. Publicada en B por petición expresa, también disponible en A. Instala esta versión sobre la anterior para recibir la interfaz nueva; se conservan los datos, el canal elegido y los recordatorios aplazados. Puedes comprobar actualizaciones manualmente desde los ajustes.

Verificada en Android 14, 15 y 16: interfaz compartida, selección A/B, posponer 14 días, actualización sobre una instalación anterior, confirmación del instalador y 247 comprobaciones nativas por API. [Resultados](validation/0.1.11.json) · [Ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38175508920).

## Icono de ajustes corregido (0.1.10 · A)

La fila de Inhouse mostraba un cuadrado blanco porque YouTube teñía también el fondo opaco del icono. Ahora el símbolo usa transparencia y el color del tema, conservando la alineación nativa. La prueba de renderizado comprueba el recurso de la APK con tintes claro y oscuro en ambos diseños de ajustes.

Verificado en Android 14, 15 y 16: renderizado del icono, 247 comprobaciones nativas por API, actualización sobre 0.1.5 y confirmación del instalador. [Resultados](validation/0.1.10.json) · [Ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38168020361). Sin cuenta autenticada ni teléfono físico.

## Instalación de actualizaciones (0.1.7 · A)

La 0.1.6 anunciaba el código 1557278151, pero su APK contenía 1557278150, igual que la 0.1.5. El instalador rechazaba correctamente esa actualización al 100 %. La 0.1.7 contiene 1557278152 y se instala conservando los datos. Cierra el aviso anterior y vuelve a comprobar para descargar la nueva APK.

El compilador genera la identidad del manifiesto desde `CalmaBuild.java`, incluida la metadata de versión. Antes de publicar el JSON se comprueba el manifiesto **de la APK firmada**, no solo las constantes de los fuentes. La prueba Android instala primero la APK pública 0.1.5 y exige un código estrictamente mayor antes de actualizarla. No se ha modificado el actualizador compartido ni eliminado sus verificaciones de integridad, versión o firma.

## Actualizaciones A/B (0.1.6)

**A: cambios recientes. B: versiones revisadas.** Aviso opcional con versión instalada/nueva, versiones saltadas y hasta tres cambios breves. «Recordármelo más tarde» aplaza 14 días; la comprobación manual sigue disponible. B solo se publica por petición expresa. A incluye todas las versiones A y B y ofrece la de mayor `versionCode`; B recibe solo versiones B. El código se mantiene en [Inhouse services updater](https://github.com/miguelcoxcaballero/inhouse-services-updater); cualquier cambio se sincroniza en las cinco apps. [Instrucciones](UPDATER.md).

## Notificaciones y web (0.1.5)

Notificaciones se añade a los modelos de la barra nativa antes de asignar sus posiciones, también cuando YouTube no la envía. Se conserva el modelo original si ya existe y se coloca después de Suscripciones. Abre el buzón original `FEactivity`, con sus avisos y contadores nativos. [Implementación](NOTIFICATIONS.md).

Pulsar el logo en los ajustes abre [Inhouse Software](https://inhouse.miguelcoxcaballero.com/).

## Iconos Inhouse (0.1.4)

El tejado blanco de Inhouse sustituye al triángulo de play del icono de YouTube, sobre fondo blanco. El pie de ajustes usa el mismo icono. Se conserva el icono nuevo al activar o desactivar las funciones Inhouse. [Recursos y adaptación al launcher](BRANDING.md).

## Ajustes Inhouse (0.1.3)

En los ajustes nativos, justo debajo de ReVanced, aparece **Inhouse YouTube settings**. El interruptor activa o desactiva las modificaciones de contenido, navegación y el preset de esta personalización; aplica el cambio reiniciando la app. Los ajustes y el actualizador permanecen accesibles.

Se guardan por separado las preferencias con Inhouse activado y desactivado. Al actualizar desde versiones que no guardaban la configuración anterior a Calma, la primera desactivación utiliza los valores originales del APK ReVanced proporcionado. No se cambian la cuenta, las estadísticas acumuladas ni la identidad de la aplicación.

El pie muestra el icono de la app, versión, fecha de publicación y enlace a este repositorio.

## Actualizador corregido en 0.1.2

La 0.1.0 y la 0.1.1 descargaban la actualización, pero fallaban al abrir el instalador porque su proveedor de archivos compartía autoridad con el original de YouTube. La 0.1.2 usa la autoridad independiente original de Inhouse Photos. Mantiene las comprobaciones de paquete, versión, hash y firma.

**Para reparar una instalación 0.1.0 o 0.1.1, descarga e instala la 0.1.7 desde el enlace de arriba, sin desinstalar Calma.** Esta vez el actualizador antiguo no puede aplicar su propia reparación. Se conservan la cuenta y los ajustes.

También se omite el aviso de la base que esperaba una instalación recién parcheada desde ReVanced Manager: Calma se distribuye aquí como APK firmada.

## Instalación

La 0.1.14 B y A, y la 0.1.13 A anterior, requieren Android 14 o posterior e incluyen microG. La 0.1.11 B y las anteriores requieren Android 9 o posterior y ReVanced GmsCore externo, como el archivo original. Se conservan las cuatro arquitecturas incluidas en la base.

Conserva el paquete `app.revanced.android.youtube`, pero está firmada con la clave de Calma. **No puede instalarse encima de la APK original con otra firma**. Si tienes esa ReVanced instalada, exporta los ajustes y conserva tus descargas antes de desinstalarla e instalar esta. El YouTube oficial utiliza otro paquete y puede permanecer instalado. Las siguientes versiones de Calma se podrán actualizar normalmente.

## Código y verificación

La 0.1.9 supera 193 comprobaciones nativas por API en Android 14, 15 y 16: orden de pestañas, destino del buzón, icono y alineación en ambos diseños de ajustes, actualización sobre 0.1.5 e instalador. [Resultados](validation/0.1.9.json) · [Ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38159739988). Sin cuenta autenticada ni teléfono físico.

La 0.1.7 supera la instalación sobre la APK pública 0.1.5 con aumento estricto del código Android, 183 comprobaciones nativas por API y la apertura de la confirmación del instalador en Android 14, 15 y 16. [Evidencia vinculada al APK firmado](validation/0.1.7.json) · [Ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38155816109). La descarga pública contiene el código 1557278152 y coincide con el catálogo. Sin cuenta autenticada ni teléfono físico.

La 0.1.6 supera las comprobaciones del actualizador compartido y las pruebas nativas en Android 14, 15 y 16: apertura real de ajustes, versión instalada, persistencia de A/B y aplazamiento de 14 días. [Resultados de esta versión](validation/0.1.6.json) · [Ejecución](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38152226040). Esas pruebas no comparaban el código del manifiesto con el catálogo; la 0.1.7 añade esa comprobación y una actualización desde 0.1.5.


Los cambios propios están en [patches/YouTubeCalma.kt](patches/YouTubeCalma.kt) y [src](src). No se aplica ningún paquete adicional de parches de terceros; se conectan las funciones que ya contiene el APK facilitado. [base.lock.json](base.lock.json) fija archivo, versión, tamaño, firma y SHA-256.

El build valida la entrada, compila la extensión aislando sus dependencias, aplica los cambios, alinea y firma. El cliente mantiene `versionName=20.40.45` para su protocolo. La 0.1.14 usa `versionCode=1557278159`; sus fuentes están en el tag enlazado arriba. Los fuentes anteriores de `main` conservan la identidad 0.1.12/1557278157.

La 0.1.5 ha superado 181 comprobaciones por versión y dos fases adicionales de cambio de perfil entre procesos en emuladores Android 14, 15 y 16. Incluyen los modelos nativos de Notificaciones, su posición, deduplicación y ruta al buzón; el clic real del logo hacia la web; iconos, ajustes, perfiles y apertura del instalador hasta su confirmación. [Resultados y alcance](validation/0.1.5.json) · [Ejecución Android](https://github.com/miguelcoxcaballero/youtube-calma/actions/runs/38141579965). La confirmación de instalación se cancela en las pruebas.

También se comprueban integridad de la APK, preset, bibliotecas nativas, procedencia del instalador, popup/progreso y recuperación de actualizaciones. La instrumentación Android comprueba los valores cargados por ReVanced, callbacks de navegación, filtros y conservación de estadísticas. Las pruebas sin iniciar sesión no verifican las suscripciones de una cuenta real ni la reproducción autenticada.

## Compilación

La compilación actual incluye microG dentro de la APK. `scripts/build.py` utiliza el mismo compilador que `experiments/embedded-gms/build-youtube.py`.

Requiere Java 21, Python 3, SDK Android (plataforma 35 y build-tools 36), ECJ y Kotlin. La APK base y las claves privadas no se incluyen en Git.

```sh
python3 scripts/setup-tools.py --android-sdk /ruta/al/sdk
# Guarda la APK exacta del usuario en base/youtube-original.apk.
# Usa el GmsCore fijado en experiments/embedded-gms/upstream.lock.json.
python3 scripts/build.py --gms-apk /ruta/a/gmscore.apk --morphe tools/morphe.jar \
  --build-tools /ruta/al/sdk/build-tools/36.0.0 --keystore /ruta/a/calma.p12
```

La salida y el informe están en `build/embedded-youtube`. El workflow de publicación firmada prepara `build/dist`, comprueba el artefacto y ejecuta las pruebas de actualización antes de publicar. Una compilación local no publica ni modifica los catálogos.

La clave debe tener alias `calma`; establece `CALMA_KEYSTORE_PASSWORD` para una clave propia. Una clave distinta impide actualizar instalaciones anteriores. Las versiones publicadas conservan la clave existente.

## Integrated update settings (0.1.8 · A)

B is the default update channel. Untested development builds on A require acknowledgement and a completed slide. Settings use the app name with an understated Inhouse/GitHub footer. Updates remain optional. [Android 14–16 validation](validation/0.1.8.json).
