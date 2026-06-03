package com.example.buildlogic


import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class CreateTempProguardRulesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val validationResultFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mappingsDir: DirectoryProperty

    @get:OutputFile
    abstract val generatedProGuardFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val result = AbiValidationResult.fromFile(validationResultFile.get().asFile)
        val outputFile = generatedProGuardFile.get().asFile
        val dir = mappingsDir.get().asFile

        if (result.hasCommonChange()) {
            outputFile.writeText("")
            logger.lifecycle("Shared deps or SDKs changed — clearing ProGuard rule for full regeneration")
            return
        }

        val mapFiles = dir.listFiles { f -> f.extension == "map" }?.toList().orEmpty()
        if (mapFiles.isEmpty()) {
            outputFile.writeText("")
            logger.lifecycle("No prior mapping files found — clearing ProGuard rule")
            return
        }

        val changedPluginIds = result.changedPlugins.toSet()
        val unchangedFiles = mapFiles.filter { file ->
            val pluginId = file.nameWithoutExtension
            pluginId !in changedPluginIds
        }

        if (unchangedFiles.isEmpty()) {
            outputFile.writeText("")
            logger.lifecycle("All plugins changed — clearing ProGuard rule for full regeneration")
            return
        }

        val merged = buildMergedMappings(unchangedFiles, includeCommon = !result.hasCommonChange())
        if (merged.isBlank()) {
            outputFile.writeText("")
            logger.lifecycle("No applicable mappings after filtering — clearing ProGuard rule")
            return
        }

        val mergedFile = outputFile.resolveSibling("merged-mappings.map")
        mergedFile.writeText(merged)
        val safePath = mergedFile.absolutePath.replace("\\", "/")
        outputFile.writeText("-applymapping \"$safePath\"\n")
        logger.lifecycle(
            "Generated ProGuard rule to apply selective mappings " +
                    "(${unchangedFiles.size} of ${mapFiles.size} plugins preserved)"
        )
    }

    private fun buildMergedMappings(files: List<java.io.File>, includeCommon: Boolean): String {
        val sb = StringBuilder()
        var commonWritten = false

        for (file in files) {
            val content = file.readText()
            val sections = parseSections(content)

            if (includeCommon && !commonWritten && sections.common.isNotBlank()) {
                sb.append(sections.common)
                commonWritten = true
            }

            if (sections.plugin.isNotBlank()) {
                sb.append(sections.plugin)
            }
        }

        return sb.toString()
    }

    private fun parseSections(content: String): MappingSections {
        val startIdx = content.indexOf(CreateSharedMappingsTask.COMMON_START_MARKER)
        val endIdx = content.indexOf(CreateSharedMappingsTask.COMMON_END_MARKER)

        if (startIdx == -1 || endIdx == -1) {
            return MappingSections(common = "", plugin = content)
        }

        val commonStart = content.indexOf("\n", startIdx).let { if (it == -1) startIdx else it + 1 }
        val common = content.substring(commonStart, endIdx)
        val pluginStart =
            content.indexOf("\n", endIdx).let { if (it == -1) content.length else it + 1 }
        val plugin = if (pluginStart < content.length) content.substring(pluginStart) else ""

        return MappingSections(common = common, plugin = plugin)
    }

    private data class MappingSections(val common: String, val plugin: String)
}
