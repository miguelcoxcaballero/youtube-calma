# Iconos Inhouse

Familia de iconos Inhouse: YouTube, Instagram, Notes, Read y Photos comparten el mismo tejado blanco de Inhouse (18 de ancho y 5,2 de trazo sobre el lienzo de 108). En YouTube, el botón rojo conserva la geometría oficial y el tejado sustituye al triángulo de play, sobre fondo blanco. Instagram conserva su degradado y el marco de la cámara, con el tejado en lugar del objetivo.

`resources/res/drawable-nodpi/inhouse_launcher_art.png` es el área visible de 72 dp renderizada a 1254 px desde el SVG maestro y se empaqueta sin redibujarla. El icono adaptativo deja un margen de 18/108 para las máscaras del launcher. Todos los aliases de inicio usan el mismo recurso, también al actualizar una instalación existente.

El logo del pie de ajustes es el mismo icono de la app (`resources/assets/inhouse-logo.png`, 384 px), con independencia del tema. La personalización del popup se limita al color de su icono en el HTML anfitrión; los archivos originales de Inhouse Read y Photos no se modifican.

El icono de la fila nativa «Inhouse YouTube settings» usa solo el tejado, con las proporciones de la familia (12 de ancho y 3,4 de trazo sobre 24), sobre transparencia y el color de texto del tema, como ReVanced. YouTube aplica un tinte a toda su máscara: añadir un fondo opaco lo convertiría en un cuadrado sólido. La instrumentación comprueba los píxeles del recurso empaquetado con tintes claro y oscuro en ambos diseños nativos de ajustes.
