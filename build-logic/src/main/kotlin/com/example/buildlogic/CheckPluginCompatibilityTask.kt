package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.BufferedInputStream
import java.io.File
import java.util.Properties
import java.util.TreeMap
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

@CacheableTask
abstract class CheckPluginCompatibilityTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginArtifacts: ConfigurableFileCollection

    @get:Input
    abstract val hostSharedDepVersions: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun check() {
        val state = TreeMap<String, String>()
        var mismatchCount = 0

        pluginArtifacts.files.forEach { file ->
            val properties = when {
                file.name.endsWith(".aar") -> extractFromAar(file)
                file.name.endsWith(".jar") -> extractFromJar(file)
                else -> null
            }

            if (properties == null) {
                return@forEach
            } else {
                val hostVersions = hostSharedDepVersions.get()
                properties.stringPropertyNames()
                    .filter { it.startsWith("dep.") }
                    .sorted()
                    .forEach { key ->
                        val coordinate = key.removePrefix("dep.")
                        val pluginVersion = properties.getProperty(key)
                        val hostVersion = hostVersions[coordinate]

                        state["${file.name}.$coordinate.plugin"] = pluginVersion
                        state["${file.name}.$coordinate.host"] = hostVersion ?: "untracked"

                        if (hostVersion != null && hostVersion != pluginVersion) {
                            logger.warn(
                                "Version mismatch for $coordinate: " +
                                    "plugin ${file.name} uses $pluginVersion, " +
                                    "host uses $hostVersion. " +
                                    "Shared mappings will be regenerated."
                            )
                            mismatchCount++
                        }
                    }
            }
        }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            state.forEach { (key, value) ->
                appendLine("$key=$value")
            }
        })

        if (mismatchCount > 0) {
            logger.lifecycle(
                "Compatibility check: $mismatchCount version mismatch(es) detected. " +
                    "Shared mappings will be regenerated."
            )
        } else {
            logger.lifecycle("Compatibility check passed. Shared mappings are up to date.")
        }
    }

    private fun extractFromAar(aarFile: File): Properties? {
        ZipFile(aarFile).use { zip ->
            val classesJarEntry = zip.getEntry("classes.jar") ?: return null
            zip.getInputStream(classesJarEntry).use { inputStream ->
                return extractFromJarStream(JarInputStream(BufferedInputStream(inputStream)))
            }
        }
    }

    private fun extractFromJar(jarFile: File): Properties? {
        jarFile.inputStream().buffered().use { inputStream ->
            return extractFromJarStream(JarInputStream(inputStream))
        }
    }

    private fun extractFromJarStream(jar: JarInputStream): Properties? {
        jar.use { stream ->
            var entry = stream.nextJarEntry
            while (entry != null) {
                if (entry.name == "META-INF/shared-mappings-compat.properties") {
                    val props = Properties()
                    props.load(stream.readBytes().inputStream())
                    return props
                }
                entry = stream.nextJarEntry
            }
        }
        return null
    }
}
