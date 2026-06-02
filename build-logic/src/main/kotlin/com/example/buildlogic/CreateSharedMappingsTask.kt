package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class CreateSharedMappingsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val abiChangedFlagFile: BoolFlagFile

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val freshR8MappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classPluginIndexFile: RegularFileProperty

    @get:Input
    abstract val commonPrefixes: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun createSharedMappings() {
        val inputFile = freshR8MappingFile.get().asFile
        if (!inputFile.exists()) {
            throw GradleException("Input mapping file not found at ${inputFile.absolutePath}")
        }

        val prefixes = commonPrefixes.get()
        val classPluginIndex = parseClassPluginIndex(classPluginIndexFile.get().asFile)
        val allPluginIds = classPluginIndex.values.flatten().toSet()
        val allSharedNames = prefixes + classPluginIndex.keys

        val dir = outputDir.get().asFile.apply { mkdirs() }
        val combinedFile = File(dir, COMBINED_MAPPINGS_FILE_NAME)
        val combinedWriter = combinedFile.bufferedWriter()
        val pluginBuffers = allPluginIds.associateWith { StringBuilder() }

        logger.lifecycle("Parsing host mapping: ${inputFile.absolutePath}")

        var insideTargetClass = false
        var currentPluginIds: Set<String> = emptySet()
        var extractedClassesCount = 0
        var totalLinesWritten = 0

        combinedWriter.use { writer ->
            inputFile.useLines { lines ->
                for (line in lines) {
                    if (" -> " in line && line.endsWith(":")) {
                        val originalClass = line.split(" -> ")[0].trim()

                        if (allSharedNames.any { originalClass.startsWith(it) }) {
                            insideTargetClass = true
                            extractedClassesCount++

                            currentPluginIds = if (prefixes.any { originalClass.startsWith(it) }) {
                                allPluginIds
                            } else {
                                classPluginIndex[originalClass]
                                    ?: findOwnerPluginIds(classPluginIndex, originalClass)
                            }

                            writer.write(line)
                            writer.newLine()
                            totalLinesWritten++
                            appendToPluginBuffers(pluginBuffers, currentPluginIds, line)
                        } else {
                            insideTargetClass = false
                            currentPluginIds = emptySet()
                        }
                    } else if (insideTargetClass) {
                        writer.write(line)
                        writer.newLine()
                        totalLinesWritten++
                        appendToPluginBuffers(pluginBuffers, currentPluginIds, line)
                    }
                }
            }
        }

        pluginBuffers.forEach { (pluginId, buffer) ->
            val pluginFile = File(dir, "$pluginId.map")
            pluginFile.writeText(buffer.toString())
            logger.lifecycle("  Per-plugin mapping: ${pluginFile.name} (${buffer.lines().size} lines)")
        }

        logger.lifecycle(
            "Extracted $extractedClassesCount classes ($totalLinesWritten lines) " +
                    "into combined + ${pluginBuffers.size} per-plugin files."
        )
    }

    private fun appendToPluginBuffers(
        buffers: Map<String, StringBuilder>,
        pluginIds: Set<String>,
        line: String,
    ) {
        for (pluginId in pluginIds) {
            buffers[pluginId]?.appendLine(line)
        }
    }

    private fun findOwnerPluginIds(
        classPluginIndex: Map<String, Set<String>>,
        className: String,
    ): Set<String> {
        val dollarIdx = className.indexOf('$')
        if (dollarIdx > 0) {
            val outerClass = className.substring(0, dollarIdx)
            classPluginIndex[outerClass]?.let { return it }
        }
        return emptySet()
    }

    private fun parseClassPluginIndex(file: File): Map<String, Set<String>> {
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .filter { it.isNotBlank() && "=" in it }
            .associate { line ->
                val (className, pluginIds) = line.split("=", limit = 2)
                className.trim() to pluginIds.split(",").map { it.trim() }.toSet()
            }
    }

    companion object {
        const val COMBINED_MAPPINGS_FILE_NAME = "common-mappings.map"
    }
}
