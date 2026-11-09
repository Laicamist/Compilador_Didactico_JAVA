import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":sharedUI"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.laicamist.proyecto_automatas.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // Nombre que muestran el instalador, el menú Inicio y la carpeta de instalación. Sin acento a propósito:
            // jpackage/WiX usan este nombre para rutas y archivos, y los caracteres fuera de ASCII dan problemas en
            // algunos Windows (por eso los textos de abajo van sin acentos). El título de la ventana sí dice "J-Autómata Studio".
            packageName = "J-Automata Studio"
            packageVersion = "1.0.0"
            description = "IDE didactico del compilador de Java (GLC v7) - Lenguajes y Automatas II"
            vendor = "Laicamist"
            copyright = "(c) 2026 Laicamist"

            // El historial y los ajustes usan SQLite por JDBC (java.sql; el controlador registra con java.logging).
            // La imagen recortada por jlink no los incluye si no se piden; sin ellos el instalador abre pero falla al
            // leer el historial. Para revisar otros módulos: ./gradlew :desktopApp:suggestRuntimeModules
            modules("java.sql", "java.logging")

            // Ícono del instalador y del acceso directo (salen de composeResources/drawable/JAutomataStudioIcon.png).
            windows {
                iconFile.set(project.file("icons/JAutomataStudio.ico"))
                menuGroup = "J-Automata Studio"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fijo para que instalar una versión nueva reemplace a la anterior en vez de duplicarla.
                upgradeUuid = "6F0B3C2E-8A41-4D7B-9C65-2E1A7D4F9B30"
            }
            linux {
                iconFile.set(project.file("icons/JAutomataStudio.png"))
                packageName = "j-automata-studio"
                shortcut = true
            }
            macOS {
                iconFile.set(project.file("icons/JAutomataStudio.icns"))
                bundleID = "com.laicamist.jautomatastudio"
            }
        }
    }
}
