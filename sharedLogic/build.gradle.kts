import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // DatabaseDriverFactory usa clases expect/actual (patrón oficial de SQLDelight). Kotlin aún las
    // marca como Beta y el IDE muestra una advertencia amarilla en cada archivo; este flag la silencia.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }

    jvm()

    // En AGP 9.x con 'com.android.kotlin.multiplatform.library', el bloque no ambiguo es androidLibrary:
    androidLibrary {
        namespace = "com.laicamist.proyecto_automatas.sharedLogic"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
        }
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

sqldelight {
    databases {
        create("CompilerDatabase") {
            // Debe coincidir con el paquete de los archivos que importan CompilerDatabase
            // (edu.itssg.compiler.data.db) y con la carpeta del .sq:
            // src/commonMain/sqldelight/edu/itssg/compiler/data/db/CompilerDatabase.sq
            packageName.set("edu.itssg.compiler.data.db")
        }
    }
}