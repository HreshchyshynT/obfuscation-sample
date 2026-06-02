package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.TreeSet

@CacheableTask
abstract class GenerateSharedClassListTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val additionalEntries: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val classes = TreeSet<String>()

        classesDirs.files.forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.walk()
                .filter { it.isFile && it.extension == "class" }
                .filter { it.name != "module-info.class" }
                .forEach { file ->
                    val relativePath = file.relativeTo(dir).path
                    val className = relativePath
                        .removeSuffix(".class")
                        .replace('/', '.')
                        .replace('\\', '.')
                    classes.add(className)
                }
        }

        additionalEntries.getOrElse(emptyList()).forEach { classes.add(it) }

        val output = outputDir.get().asFile.resolve(SHARED_MAPPINGS_CLASSES_FILE)
        output.parentFile.mkdirs()
        output.writeText(buildString {
            classes.forEach { appendLine(it) }
        })

        logger.lifecycle("Generated shared class list with ${classes.size} entries at ${output.path}")
    }

    companion object {
        const val SHARED_MAPPINGS_CLASSES_FILE = "META-INF/shared-mappings-classes.txt"
    }
}
