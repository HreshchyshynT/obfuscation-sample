package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.BufferedInputStream
import java.io.File
import java.util.TreeSet
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

@CacheableTask
abstract class ExtractSharedClassListTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val classes = TreeSet<String>()
        val additionalEntries = TreeSet<String>()

        inputFiles.files.forEach { file ->
            when {
                file.name.endsWith(".aar") -> processAar(file, classes, additionalEntries)
                file.name.endsWith(".jar") -> processJar(file, classes, additionalEntries)
            }
        }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("# Classes extracted from plugin dependencies")
            classes.forEach { appendLine(it) }
            if (additionalEntries.isNotEmpty()) {
                appendLine()
                appendLine("# Additional entries from shared-mappings-classes.txt")
                additionalEntries.forEach { appendLine(it) }
            }
        })

        logger.lifecycle("Extracted ${classes.size} classes and ${additionalEntries.size} additional entries to ${output.path}")
    }

    private fun processAar(aarFile: File, classes: TreeSet<String>, additional: TreeSet<String>) {
        ZipFile(aarFile).use { zip ->
            val classesJarEntry = zip.getEntry("classes.jar") ?: return
            zip.getInputStream(classesJarEntry).use { inputStream ->
                scanJarStream(
                    JarInputStream(BufferedInputStream(inputStream)),
                    classes,
                    additional,
                )
            }
        }
    }

    private fun processJar(jarFile: File, classes: TreeSet<String>, additional: TreeSet<String>) {
        jarFile.inputStream().buffered().use { inputStream ->
            scanJarStream(JarInputStream(inputStream), classes, additional)
        }
    }

    private fun scanJarStream(
        jar: JarInputStream,
        classes: TreeSet<String>,
        additional: TreeSet<String>,
    ) {
        jar.use { stream ->
            var entry: JarEntry? = stream.nextJarEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "META-INF/shared-mappings-classes.txt" -> {
                        val content = stream.readBytes().toString(Charsets.UTF_8)
                        content.lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .forEach { additional.add(it) }
                    }
                    name.endsWith(".class") && !name.startsWith("META-INF/") && name != "module-info.class" -> {
                        val className = name
                            .removeSuffix(".class")
                            .replace('/', '.')
                        classes.add(className)
                    }
                }
                entry = stream.nextJarEntry
            }
        }
    }
}
