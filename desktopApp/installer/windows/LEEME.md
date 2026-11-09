# Instalador de Windows (MSI) personalizado

Se genera con:

```
gradlew.bat :desktopApp:packageMsiPersonalizado
```

El MSI queda en `desktopApp/build/compose/binaries/main/msi-personalizado/`. Para ver todo lo que hace jpackage y WiX,
agrega `-PmsiVerbose`.

| Archivo | Para qué sirve |
|---|---|
| `main.wxs` | Plantilla de WiX de jpackage (JDK 21) con el idioma español (2058) y las imágenes del asistente. `@RESOURCE_DIR@` se reemplaza al construir. |
| `MsiInstallerStrings_es.wxl` | Textos en español: los de jpackage traducidos y los de bienvenida/fin del asistente de WiX. Con este archivo el asistente completo sale en español. |
| `banner.bmp` | Franja superior de las pantallas intermedias (493 × 58, BMP de 24 bits). El título va a la izquierda, por eso el ícono está a la derecha. |
| `dialog.bmp` | Fondo de las pantallas de bienvenida y fin (493 × 312, BMP de 24 bits). WiX escribe el texto a partir de la columna 164, por eso la parte derecha es blanca. |
| `license.rtf` | Aviso de uso y componentes de terceros que se acepta antes de instalar (RTF en cp1252). |

Para cambiar un texto basta con editar el `.wxl` o el `.rtf` y volver a generar. Si se actualiza el JDK y jpackage
cambia su `main.wxs`, compara con el original (`jmod extract jdk.jpackage.jmod`, carpeta
`classes/jdk/jpackage/internal/resources`) y vuelve a aplicar los tres cambios marcados con "J-Autómata".

`packageMsi` de Compose sigue funcionando, pero genera el MSI sin personalizar (asistente en inglés y sin imágenes):
Compose siempre le pasa a jpackage su propia carpeta de recursos, por eso existe la tarea aparte.
