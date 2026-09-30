buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 trae Kotlin incorporado; se fija la misma versión de KGP que el plugin de Compose.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
