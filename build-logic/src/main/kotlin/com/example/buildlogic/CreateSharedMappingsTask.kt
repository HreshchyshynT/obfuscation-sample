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
    abstract val validationResultFile: RegularFileProperty

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
        val commonBuffers = allPluginIds.associateWith { StringBuilder() }
        val pluginBuffers = allPluginIds.associateWith { StringBuilder() }

        logger.lifecycle("Parsing host mapping: ${inputFile.absolutePath}")

        var insideTargetClass = false
        var currentPluginIds: Set<String> = emptySet()
        var isCommonClass = false
        var extractedClassesCount = 0

        inputFile.useLines { lines ->
            for (line in lines) {
                if (" -> " in line && line.endsWith(":")) {
                    val originalClass = line.split(" -> ")[0].trim()

                    if (allSharedNames.any { originalClass.startsWith(it) }) {
                        insideTargetClass = true
                        extractedClassesCount++

                        isCommonClass = prefixes.any { originalClass.startsWith(it) }
                        currentPluginIds = if (isCommonClass) {
                            allPluginIds
                        } else {
                            classPluginIndex[originalClass]
                                ?: findOwnerPluginIds(classPluginIndex, originalClass)
                        }

                        appendToBuffers(
                            if (isCommonClass) commonBuffers else pluginBuffers,
                            currentPluginIds,
                            line,
                        )
                    } else {
                        insideTargetClass = false
                        currentPluginIds = emptySet()
                    }
                } else if (insideTargetClass) {
                    appendToBuffers(
                        if (isCommonClass) commonBuffers else pluginBuffers,
                        currentPluginIds,
                        line,
                    )
                }
            }
        }

        allPluginIds.forEach { pluginId ->
            val pluginFile = File(dir, "$pluginId.map")
            pluginFile.bufferedWriter().use { writer ->
                val common = commonBuffers[pluginId]
                if (common != null && common.isNotEmpty()) {
                    writer.write("$COMMON_START_MARKER\n")
                    writer.write(common.toString())
                    writer.write("$COMMON_END_MARKER\n")
                }
                val plugin = pluginBuffers[pluginId]
                if (plugin != null && plugin.isNotEmpty()) {
                    writer.write(plugin.toString())
                }
            }
            logger.lifecycle("  Per-plugin mapping: ${pluginFile.name}")
        }

        logger.lifecycle(
            "Extracted $extractedClassesCount classes " +
                "into ${allPluginIds.size} per-plugin files."
        )
    }

    private fun appendToBuffers(
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
        const val COMMON_START_MARKER = "# COMMON_START"
        const val COMMON_END_MARKER = "# COMMON_END"
    }
}
