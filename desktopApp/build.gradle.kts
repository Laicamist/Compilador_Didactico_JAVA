import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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

// Datos del instalador, compartidos por la configuración de Compose y por el MSI personalizado (abajo).
val installerName = "J-Automata Studio"
val installerVersion = "1.0.0"
val installerVendor = "Laicamist"
val installerDescription = "IDE didactico del compilador de Java (GLC v7) - Lenguajes y Automatas II"
val installerCopyright = "(c) 2026 Laicamist"
val installerUpgradeUuid = "6F0B3C2E-8A41-4D7B-9C65-2E1A7D4F9B30"
val repositoryUrl = "https://github.com/Laicamist/Compilador_Didactico_JAVA"

compose.desktop {
    application {
        mainClass = "com.laicamist.proyecto_automatas.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // Nombre que muestran el instalador, el menú Inicio y la carpeta de instalación. Sin acento a propósito:
            // jpackage/WiX usan este nombre para rutas y archivos, y los caracteres fuera de ASCII dan problemas en
            // algunos Windows (por eso los textos de abajo van sin acentos). El título de la ventana sí dice "J-Autómata Studio".
            packageName = installerName
            packageVersion = installerVersion
            description = installerDescription
            vendor = installerVendor
            copyright = installerCopyright

            // El historial y los ajustes usan SQLite por JDBC (java.sql; el controlador registra con java.logging).
            // La imagen recortada por jlink no los incluye si no se piden; sin ellos el instalador abre pero falla al
            // leer el historial. Para revisar otros módulos: ./gradlew :desktopApp:suggestRuntimeModules
            modules("java.sql", "java.logging")

            // Ícono del instalador y del acceso directo (salen de composeResources/drawable/JAutomataStudioIcon.png).
            windows {
                iconFile.set(project.file("icons/JAutomataStudio.ico"))
                menuGroup = installerName
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fijo para que instalar una versión nueva reemplace a la anterior en vez de duplicarla.
                upgradeUuid = installerUpgradeUuid
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

// ------------------------------------------------------------------------------------------------------------------
// Instalador de Windows personalizado: ./gradlew :desktopApp:packageMsiPersonalizado
//
// Asistente en español, imágenes propias (banner y pantalla de bienvenida/fin), pantalla de aviso/licencia, elegir
// accesos directos y ligas al repositorio en "Agregar o quitar programas". Las plantillas están en
// installer/windows (ver installer/windows/LEEME.md).
//
// Por qué una tarea aparte: packageMsi de Compose siempre le pasa a jpackage su propia carpeta de recursos (vacía en
// Windows) y la borra justo antes de ejecutarlo, así que no hay forma de meterle main.wxs ni el .wxl en español. Aquí
// se toma la app ya armada por createDistributable y se llama a jpackage directamente, con el mismo JDK y el mismo
// WiX que usa Compose.
// ------------------------------------------------------------------------------------------------------------------

val installerTemplatesDir = layout.projectDirectory.dir("installer/windows")
val msiResourcesDir = layout.buildDirectory.dir("installer/msi-resources")
val customMsiOutputDir = layout.buildDirectory.dir("compose/binaries/main/msi-personalizado")

val prepareMsiResources = tasks.register<Sync>("prepareMsiResources") {
    group = "compose desktop"
    description = "Copia las plantillas del instalador y pone en main.wxs la ruta absoluta de las imágenes."
    from(installerTemplatesDir) {
        include("main.wxs", "*.wxl", "*.bmp")
    }
    into(msiResourcesDir)
    val resourcesPath = msiResourcesDir.get().asFile.absolutePath
    inputs.property("resourcesPath", resourcesPath)
    filteringCharset = "UTF-8"
    filesMatching("main.wxs") {
        filter { line -> line.replace("@RESOURCE_DIR@", resourcesPath) }
    }
}

tasks.register<Exec>("packageMsiPersonalizado") {
    group = "compose desktop"
    description = "Genera el MSI con el asistente en español, imágenes, licencia y ligas del proyecto."

    // Las tareas de Compose se registran en afterEvaluate; este bloque corre después, al crear la tarea.
    val createDistributable = tasks.named<AbstractJPackageTask>("createDistributable")
    val composeMsi = tasks.named<AbstractJPackageTask>("packageMsi")
    dependsOn(createDistributable, prepareMsiResources)
    // WiX 3.11 que Compose descarga (no existe si se usa la variable WIX_PATH).
    dependsOn(rootProject.tasks.matching { it.name == "unzipWix" })

    val isWindows = System.getProperty("os.name").startsWith("Windows")
    onlyIf("Solo se puede generar un MSI en Windows") { isWindows }

    // Copias locales: las lambdas de abajo no deben capturar el script (lo exige el configuration cache).
    val appName = installerName
    val appVersion = installerVersion
    val appVendor = installerVendor
    val appDescription = installerDescription
    val appCopyright = installerCopyright
    val upgradeUuid = installerUpgradeUuid
    val repoUrl = repositoryUrl

    val appImage = createDistributable.flatMap { it.destinationDir }.map { it.dir(appName) }
    val jpackage = composeMsi.flatMap { it.javaHome }.map { File(it, "bin/jpackage.exe").absolutePath }
    val wixDir = composeMsi.flatMap { it.wixToolsetDir }.map { it.asFile.absolutePath }.orElse("")
    val resourcesDir = msiResourcesDir
    val licenseFile = installerTemplatesDir.file("license.rtf")
    val outputDir = customMsiOutputDir
    val verbose = providers.gradleProperty("msiVerbose").isPresent

    inputs.dir(appImage)
    inputs.dir(installerTemplatesDir)
    outputs.dir(outputDir)

    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            add("--type"); add("msi")
            add("--app-image"); add(appImage.get().asFile.absolutePath)
            add("--dest"); add(outputDir.get().asFile.absolutePath)
            add("--name"); add(appName)
            add("--app-version"); add(appVersion)
            add("--vendor"); add(appVendor)
            add("--description"); add(appDescription)
            add("--copyright"); add(appCopyright)
            add("--resource-dir"); add(resourcesDir.get().asFile.absolutePath)
            add("--license-file"); add(licenseFile.asFile.absolutePath)
            add("--about-url"); add(repoUrl)
            add("--win-help-url"); add("$repoUrl#readme")
            add("--win-update-url"); add("$repoUrl/releases")
            add("--win-dir-chooser")
            add("--win-per-user-install")
            add("--win-menu")
            add("--win-menu-group"); add(appName)
            add("--win-shortcut")
            add("--win-shortcut-prompt")
            add("--win-upgrade-uuid"); add(upgradeUuid)
            if (verbose) add("--verbose")
        }
    })

    doFirst {
        executable = jpackage.get()
        // jpackage no sobrescribe: se borra el MSI anterior.
        outputDir.get().asFile.deleteRecursively()
        val wix = wixDir.get()
        if (wix.isNotEmpty()) {
            environment("PATH", wix + File.pathSeparator + (System.getenv("PATH") ?: ""))
        }
    }
}
