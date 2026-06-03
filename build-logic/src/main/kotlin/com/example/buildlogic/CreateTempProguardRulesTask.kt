package com.example.buildlogic


import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class CreateTempProguardRulesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val validationResultFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sharedMappingsFile: RegularFileProperty

    @get:OutputFile
    abstract val generatedProGuardFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val result = AbiValidationResult.fromFile(validationResultFile.get().asFile)
        val outputFile = generatedProGuardFile.get().asFile
        val mappingFile = sharedMappingsFile.get().asFile

        if (!result.hasAnyChange() && mappingFile.exists()) {
            val safePath = mappingFile.absolutePath.replace("\\", "/")
            outputFile.writeText("-applymapping \"$safePath\"\n")
            logger.lifecycle("Generated temporary ProGuard rule to apply shared mappings")
        } else {
            outputFile.writeText("")
            logger.lifecycle("Clearing temporary ProGuard rule to force fresh optimization")
        }
    }
}
