package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateCompatManifestTask : DefaultTask() {

    @get:Input
    abstract val sharedDependencyCoordinates: ListProperty<String>

    @get:Input
    abstract val resolvedVersions: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val coordinates = sharedDependencyCoordinates.get()
        val versions = resolvedVersions.get()

        val output = outputDir.get().asFile.resolve("META-INF/shared-mappings-compat.properties")
        output.parentFile.mkdirs()

        if (coordinates.isEmpty()) {
            output.writeText("")
            logger.lifecycle("No shared dependencies declared, generated empty compatibility manifest")
            return
        }

        val content = buildString {
            for (coordinate in coordinates.sorted()) {
                val version = versions[coordinate]
                if (version != null) {
                    val escapedCoordinate = coordinate.replace(":", "\\:")
                    appendLine("dep.$escapedCoordinate=$version")
                } else {
                    logger.warn("Could not resolve version for shared dependency: $coordinate")
                }
            }
        }

        output.writeText(content)
        logger.lifecycle("Generated compatibility manifest with ${coordinates.size} entries at ${output.path}")
    }
}
