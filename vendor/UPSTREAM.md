# Actualizadores Inhouse reutilizados (0.3.6)

Esta versión **compila y utiliza código de ambos proyectos**:

- **Inhouse Read**, commit `e575b76bbf19e379b639bbfa43b2d48bb9efac26`: detección de la app, comparación de versiones, lectura del manifiesto con caché desactivada, recuperación desde GitHub Releases, popup, progreso y callbacks Java al WebView. [Fuente JS](https://github.com/miguelcoxcaballero/inhouse-read/blob/e575b76bbf19e379b639bbfa43b2d48bb9efac26/src/js/android-update.js) · [CSS](https://github.com/miguelcoxcaballero/inhouse-read/blob/e575b76bbf19e379b639bbfa43b2d48bb9efac26/src/css/android-update.css).
- **Inhouse Photos**, commit `dc97ccef18f2a327a5fcdae4c6727268039f2b3c`: instalador Kotlin original, descarga reanudable, redirecciones, progreso, SHA-256, comprobación del paquete, versión y firma, permisos e instalador Android. [Fuente](https://github.com/miguelcoxcaballero/Inhouse-Photos/blob/dc97ccef18f2a327a5fcdae4c6727268039f2b3c/mobile/android/app/src/main/kotlin/app/alextran/immich/UpdateInstaller.kt).

Los archivos originales siguen sin cambios en `vendor/`. `scripts/vendor-updater.py` adapta importaciones/exportaciones, marca, repositorio y nombres de APK de Read. La única modificación de su planificación es ejecutar su función de comprobación al iniciar, sin esperar al canvas/idle de la biblioteca. Se exponen el manifiesto ya validado y el resultado de conexión para pasarlos al popup y distinguir un fallo de red de una versión actual. CSS y componentes del popup permanecen originales.

`scripts/vendor-photos-installer.py` extrae el flujo de instalación, descarga y verificación de Photos. Sustituye exclusivamente su conexión Flutter: `MainActivity` por `Activity`, `MethodChannel.Result` por una interfaz y los eventos de canal por callbacks Java. Cambia la autoridad FileProvider a la de esta app. La descarga y la verificación se conservan literalmente en Kotlin, no se reescriben en Java. Se compilan con Kotlin 2.0.21 y se incluye su runtime en el APK. El reinicio Flutter y las actualizaciones Shorebird/iOS no se incluyen en una app Java/WebView.

Los callbacks de progreso de Read envían los eventos de Photos al popup original. El manifiesto confirmado se entrega a la pantalla translúcida del popup, evitando una segunda consulta y una pantalla de ajustes de actualización como fondo. El puente del instalador se mantiene únicamente en esa pantalla local de recursos propios; nunca se añade a Instagram.

Las versiones 0.3.2–0.3.5 solo compilaban el actualizador de Read. Photos era una referencia. Eso ya no describe la integración actual. La comprobación de aquellas versiones dependía de la carga de Instagram y de un WebView sin adjuntar, y esperaba al arranque diferido de Read; se ha eliminado esa dependencia. El aviso pendiente ahora se conserva aunque la app pierda el foco y se muestra al recuperarlo.

Android continúa exigiendo autorizar la fuente y confirmar la instalación, como en ambos proyectos originales.
