package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class GeneratePluginMappingsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val mappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classPluginIndex: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val mappingSource = mappingFile.orNull?.asFile
        if (mappingSource == null || !mappingSource.exists()) {
            logger.lifecycle("No R8 mapping file found, skipping plugin mapping generation")
            return
        }

        val index = loadIndex()
        if (index.isEmpty()) {
            logger.lifecycle("Class-plugin index is empty, skipping plugin mapping generation")
            return
        }

        val allPluginIds = index.values.flatten().toSet()
        val headerLines = mutableListOf<String>()
        val perPluginBlocks = allPluginIds.associateWith { mutableListOf<String>() }
        val hostBlocks = mutableListOf<String>()

        var currentBlock = StringBuilder()
        var currentClassName: String? = null
        var inHeader = true

        mappingSource.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (inHeader && line.startsWith("#")) {
                    headerLines.add(line)
                    continue
                }
                inHeader = false

                val isClassLine = !line.startsWith(" ") && !line.startsWith("#") && line.endsWith(":")
                if (isClassLine) {
                    flushBlock(currentClassName, currentBlock, index, perPluginBlocks, hostBlocks)
                    currentBlock = StringBuilder()
                    currentClassName = line.substringBefore(" -> ").trim()
                }
                currentBlock.appendLine(line)
            }
        }
        flushBlock(currentClassName, currentBlock, index, perPluginBlocks, hostBlocks)

        val outputDirectory = outputDir.get().asFile
        outputDirectory.mkdirs()

        val headerText = if (headerLines.isNotEmpty()) {
            headerLines.joinToString("\n", postfix = "\n")
        } else {
            ""
        }

        File(outputDirectory, "host-mappings.map").writeText(headerText + hostBlocks.joinToString(""))

        perPluginBlocks.forEach { (pluginId, blocks) ->
            File(outputDirectory, "$pluginId.map").writeText(headerText + blocks.joinToString(""))
            logger.lifecycle("Generated $pluginId.map with ${blocks.size} class entries")
        }

        logger.lifecycle(
            "Generated plugin mappings: ${hostBlocks.size} host entries, " +
                "${allPluginIds.size} plugin files"
        )
    }

    private fun loadIndex(): Map<String, Set<String>> {
        val indexFile = classPluginIndex.get().asFile
        if (!indexFile.exists()) return emptyMap()

        val result = mutableMapOf<String, Set<String>>()
        indexFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val separatorIndex = line.indexOf("=")
                if (separatorIndex > 0) {
                    val entry = line.substring(0, separatorIndex)
                    val pluginIds = line.substring(separatorIndex + 1).split(",").toSet()
                    result[entry] = pluginIds
                }
            }
        return result
    }

    private fun flushBlock(
        className: String?,
        block: StringBuilder,
        index: Map<String, Set<String>>,
        perPluginBlocks: Map<String, MutableList<String>>,
        hostBlocks: MutableList<String>,
    ) {
        if (className == null || block.isEmpty()) return
        val blockStr = block.toString()

        val matchedPlugins = mutableSetOf<String>()
        for ((entry, pluginIds) in index) {
            if (className == entry
                || className.startsWith("$entry.")
                || className.startsWith("$entry$")
            ) {
                matchedPlugins.addAll(pluginIds)
            }
        }

        if (matchedPlugins.isNotEmpty()) {
            hostBlocks.add(blockStr)
            matchedPlugins.forEach { pluginId ->
                perPluginBlocks[pluginId]?.add(blockStr)
            }
        }
    }
}
