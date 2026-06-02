package com.example.buildlogic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class CreateSharedMappingsTask : DefaultTask() {
    // to let gradle skip this task when flag remains the same
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val abiChangedFlagFile: BoolFlagFile

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val freshR8MappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val modulesMappingsFile: RegularFileProperty

    @get:OutputFile
    abstract val destinationStableMappingFile: RegularFileProperty


    companion object {
        // TODO: read from abi extension
        private val TARGET_PREFIXES = listOf(
            "kotlin.coroutines.",
            "kotlinx.coroutines.",
            "kotlin.jvm.functions.",
        )
    }

    @TaskAction
    fun createSharedMappings() {
        val inputFile = freshR8MappingFile.get().asFile
        val outputFile = destinationStableMappingFile.get().asFile

        if (!inputFile.exists()) {
            throw GradleException("Error: Input mapping file not found at ${inputFile.absolutePath}")
        }

        logger.lifecycle("Parsing host mapping: ${inputFile.absolutePath}")

        var insideTargetClass = false
        var extractedClassesCount = 0
        var totalLinesWritten = 0

        val packageNames = TARGET_PREFIXES + modulesMappingsFile.get().asFile.readLines().filter { it.isNotBlank() }

        // Use Kotlin's idiomatic streaming wrappers to handle large mapping files efficiently without memory strain
        outputFile.bufferedWriter().use { writer ->
            inputFile.useLines { lines ->
                for (line in lines) {
                    // Lines defining a class mapping look like: "original.class.Name -> obfuscated_name:"
                    if (" -> " in line && line.endsWith(":")) {
                        val originalClass = line.split(" -> ")[0].trim()

                        // Check if this class matches any of our target prefixes
                        if (packageNames.any { prefix -> originalClass.startsWith(prefix) }) {
                            insideTargetClass = true
                            extractedClassesCount++
                            writer.write(line)
                            writer.newLine()
                            totalLinesWritten++
                        } else {
                            insideTargetClass = false
                        }
                    }
                    // If it's a member mapping line (fields/methods) or a metadata comment,
                    // only write it if it belongs to a target class we are keeping
                    else if (insideTargetClass) {
                        writer.write(line)
                        writer.newLine()
                        totalLinesWritten++
                    }
                }
            }
        }

        logger.lifecycle("Success! Extracted $extractedClassesCount matching classes ($totalLinesWritten total lines written).")
        logger.lifecycle("Filtered mapping saved to: ${outputFile.absolutePath}")
    }
}
