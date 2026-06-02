package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.BufferedInputStream
import java.io.File
import java.util.TreeMap
import java.util.TreeSet
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

@CacheableTask
abstract class ExtractSharedClassListTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sdkFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val compatibilityState: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val pluginFileMapping: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:OutputFile
    @get:Optional
    abstract val classPluginIndexFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val entries = TreeSet<String>()

        inputFiles.files.forEach { file ->
            when {
                file.name.endsWith(".aar") -> processAar(file, entries)
                file.name.endsWith(".jar") -> processJar(file, entries)
            }
        }

        sdkFiles.files.forEach { file ->
            if (file.name.endsWith(".jar")) {
                scanJarClasses(file, entries)
            }
        }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            entries.forEach { appendLine(it) }
        })

        logger.lifecycle("Extracted ${entries.size} shared mapping entries to ${output.path}")

        if (pluginFileMapping.isPresent && classPluginIndexFile.isPresent) {
            buildClassPluginIndex()
        }
    }

    private fun buildClassPluginIndex() {
        val mapping = pluginFileMapping.get()
        val invertedIndex = TreeMap<String, TreeSet<String>>()

        inputFiles.files.forEach { file ->
            val pluginId = mapping[file.absolutePath] ?: return@forEach
            val pluginEntries = TreeSet<String>()
            when {
                file.name.endsWith(".aar") -> processAar(file, pluginEntries)
                file.name.endsWith(".jar") -> processJar(file, pluginEntries)
            }
            pluginEntries.forEach { entry ->
                invertedIndex.getOrPut(entry) { TreeSet() }.add(pluginId)
            }
        }

        val indexFile = classPluginIndexFile.get().asFile
        indexFile.parentFile.mkdirs()
        indexFile.writeText(buildString {
            invertedIndex.forEach { (entry, pluginIds) ->
                appendLine("$entry=${pluginIds.joinToString(",")}")
            }
        })

        logger.lifecycle(
            "Built class-plugin index with ${invertedIndex.size} entries " +
                "across ${invertedIndex.values.flatten().toSet().size} plugins"
        )
    }

    private fun processAar(aarFile: File, entries: TreeSet<String>) {
        ZipFile(aarFile).use { zip ->
            val classesJarEntry = zip.getEntry("classes.jar") ?: return
            zip.getInputStream(classesJarEntry).use { inputStream ->
                scanJarForTxt(JarInputStream(BufferedInputStream(inputStream)), entries)
            }
        }
    }

    private fun processJar(jarFile: File, entries: TreeSet<String>) {
        jarFile.inputStream().buffered().use { inputStream ->
            scanJarForTxt(JarInputStream(inputStream), entries)
        }
    }

    private fun scanJarClasses(jarFile: File, entries: TreeSet<String>) {
        jarFile.inputStream().buffered().use { inputStream ->
            JarInputStream(inputStream).use { stream ->
                var entry: JarEntry? = stream.nextJarEntry
                while (entry != null) {
                    if (!entry.isDirectory
                        && entry.name.endsWith(".class")
                        && entry.name != "module-info.class"
                        && !entry.name.startsWith("META-INF/")
                    ) {
                        val className = entry.name
                            .removeSuffix(".class")
                            .replace('/', '.')
                        entries.add(className)
                    }
                    entry = stream.nextJarEntry
                }
            }
        }
    }

    private fun scanJarForTxt(jar: JarInputStream, entries: TreeSet<String>) {
        jar.use { stream ->
            var entry: JarEntry? = stream.nextJarEntry
            while (entry != null) {
                if (entry.name == "META-INF/shared-mappings-classes.txt") {
                    val content = stream.readBytes().toString(Charsets.UTF_8)
                    content.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .forEach { entries.add(it) }
                    return
                }
                entry = stream.nextJarEntry
            }
        }
    }
}
