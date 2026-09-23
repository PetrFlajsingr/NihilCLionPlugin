import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

group = "cz.nihil_engine.utils_plugin"
version = "2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        clion("263.5153.33") // CLion 2026.3 EAP
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:

        composeUI()

        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("com.intellij.clion")
        bundledPlugin("com.intellij.cmake")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("org.intellij.plugins.markdown")
        bundledPlugin("org.jetbrains.plugins.clion.radler")
        bundledPlugin("PythonCore")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263.5153"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    runIde {
        providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
        environment("PYTHONDONTWRITEBYTECODE", "1")
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "25"
        targetCompatibility = "25"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
