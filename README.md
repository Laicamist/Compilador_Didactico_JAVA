# J-Autómata Studio — Compilador didáctico de Java

Simulador didáctico del compilador de Java para la materia **Lenguajes y Autómatas II**. Aplicación multiplataforma (Kotlin Multiplatform + Compose Multiplatform) que replica de forma visual el análisis léxico, sintáctico y semántico de la **GLC v7**, con tablas de tokens, símbolos y errores, árboles sintáctico y abstracto-semántico, y código intermedio (triplos, cuádruplos y 3 direcciones) con su optimización (U3).

## Qué hace

| Fase | Resultado en la app |
|---|---|
| Léxico (AFD) | Tabla de tokens con `IDn` y errores léxicos con la redacción de la U1 |
| Sintáctico (GLC v7, descendente recursivo) | Árbol de derivación y recuperación en modo pánico (`;` / `}`) |
| Semántico | Tipos (TD), declaraciones previas, ámbitos de bloque, constructores, métodos de Java y árbol abstracto-semántico con orden de ejecución |
| Código intermedio | Triplos, cuádruplos y 3 direcciones (T1–T3, `free`, pendientes `Pn`) |
| Optimización (U3) | `SE`, `FO`, `PC`, `RS`, `CM` y su variante `D`, con la tabla de la materia |

Además: editor con resaltado, errores en línea, deshacer/rehacer, buscar y reemplazar; paneles tabulares que se pueden filtrar, ordenar y copiar; visor de árboles con zoom, plegado, minimapa y recorrido animado (derivación, cuádruplos y 3 direcciones); historial y ajustes guardados con SQLDelight; exportación a PDF, CSV, PNG/SVG y `.java` (en Desktop a `Documentos/J-Autómata Studio`, en Android a `Descargas/J-Autómata Studio`).

## Estructura

| Módulo | Contenido |
|---|---|
| `sharedLogic` | Motor del compilador (`edu.itssg.compiler.core`: `lexer`, `parser`, `semantic`, `ir`, `optimizer`, `error`, `security`) y persistencia (`data.db`, SQLDelight) |
| `sharedUI` | Interfaz Compose (`edu.itssg.compiler.ui`: `viewmodel`, `editor`, `tables`, `tree`, `workspace`, `theme`…) |
| `desktopApp` | Punto de entrada de escritorio (Windows, macOS, Linux) |
| `androidApp` | Punto de entrada de Android |
| `iosApp` | Proyecto de Xcode (pendiente de conectar con la interfaz Compose) |

Reglas implementadas y su fuente en las láminas: [`docs/BACKEND_REGLAS.md`](docs/BACKEND_REGLAS.md).

## Requisitos

- JDK 21 (el daemon de Gradle lo descarga solo si no está instalado).
- IntelliJ IDEA o Android Studio con el plugin de Kotlin Multiplatform.
- Android SDK para la app de Android; Xcode en macOS para iOS.

## Ejecutar

```bash
# Escritorio
./gradlew :desktopApp:run

# Android (con un emulador o teléfono conectado)
./gradlew :androidApp:installDebug

# Todas las pruebas
./gradlew allTests
# Solo el motor del compilador en la JVM
./gradlew :sharedLogic:jvmTest
```

En Windows usa `gradlew.bat` en lugar de `./gradlew`.

## Estado

- Fase 2 (backend) y Fase 3 (frontend) implementadas: Inicio con hojas "¿Cómo funciona?" y tour, espacio de trabajo,
  paneles tabulares, visor de árboles, Historial, Ajustes y exportación (PDF, CSV, PNG/SVG de los árboles y `.java`).
- Pendiente: empaquetado de escritorio, ajustes finales de Android e iOS.

---

Proyecto final de Lenguajes y Autómatas II · Created by: Laicamist
