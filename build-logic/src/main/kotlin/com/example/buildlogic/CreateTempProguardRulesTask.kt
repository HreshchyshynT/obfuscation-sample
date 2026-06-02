package com.example.buildlogicimport org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class CreateTempProguardRulesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val abiChangedFlagFile: BoolFlagFile

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sharedMappingsFile: RegularFileProperty

    @get:OutputFile
    abstract val generatedProGuardFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val abiChanged = abiChangedFlagFile.read()
        val outputFile = generatedProGuardFile.get().asFile
        val mappingFile = sharedMappingsFile.get().asFile

        if (!abiChanged && mappingFile.exists()) {
            // Escape file windows paths safely if necessary
            val safePath = mappingFile.absolutePath.replace("\\", "/")
            outputFile.writeText("-applymapping \"$safePath\"\n")
            logger.lifecycle("ABI Changed: Generated temporary ProGuard rule to apply shared mappings")
        } else {
            // Clear the file so R8 receives no mapping directives
            outputFile.writeText("")
            logger.lifecycle("ABI Changed: Clearing temporary ProGuard rule to force fresh optimization")
        }
    }
}
