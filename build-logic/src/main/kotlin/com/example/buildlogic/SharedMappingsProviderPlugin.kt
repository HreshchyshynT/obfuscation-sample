package com.example.buildlogic

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

class SharedMappingsProviderPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "sharedMappingsProvider",
            SharedMappingsProviderExtension::class.java,
        )

        project.plugins.withType(LibraryPlugin::class.java) {
            val androidComponents = project.extensions
                .getByType(LibraryAndroidComponentsExtension::class.java)
            val android = project.extensions
                .getByType(LibraryExtension::class.java)

            androidComponents.onVariants { variant ->
                val variantCapName = variant.name.replaceFirstChar { it.uppercase() }

                val generateClassListTask = project.tasks.register(
                    "generate${variantCapName}SharedClassList",
                    GenerateSharedClassListTask::class.java,
                ) {
                    additionalEntries.set(extension.additionalEntries)
                    outputDir.set(
                        project.layout.buildDirectory.dir(
                            "generated/shared-mappings/${variant.name}"
                        )
                    )
                }

                val generateManifestTask = project.tasks.register(
                    "generate${variantCapName}CompatManifest",
                    GenerateCompatManifestTask::class.java,
                ) {
                    sharedDependencyCoordinates.set(extension.sharedDependencies)
                    resolvedVersions.set(project.provider {
                        resolveSharedDependencyVersions(
                            project,
                            variant.name,
                            extension.sharedDependencies.get(),
                        )
                    })
                    outputDir.set(
                        project.layout.buildDirectory.dir(
                            "generated/shared-mappings-compat/${variant.name}"
                        )
                    )
                }

                android.sourceSets.getByName(variant.name) {
                    resources.srcDir(generateClassListTask.map { it.outputDir })
                    resources.srcDir(generateManifestTask.map { it.outputDir })
                }

                project.afterEvaluate {
                    val compileKotlinTask = project.tasks.findByName(
                        "compile${variantCapName}Kotlin"
                    )
                    val compileJavaTask = project.tasks.findByName(
                        "compile${variantCapName}JavaWithJavac"
                    )

                    generateClassListTask.configure {
                        compileKotlinTask?.let { task ->
                            dependsOn(task)
                            classesDirs.from(task.outputs.files)
                        }
                        compileJavaTask?.let { task ->
                            dependsOn(task)
                            classesDirs.from(task.outputs.files)
                        }
                    }
                }
            }
        }
    }

    private fun resolveSharedDependencyVersions(
        project: Project,
        variantName: String,
        coordinates: List<String>,
    ): Map<String, String> {
        if (coordinates.isEmpty()) return emptyMap()

        val configName = "${variantName}CompileClasspath"
        val config = project.configurations.findByName(configName) ?: return emptyMap()
        val coordinateSet = coordinates.toSet()
        val result = mutableMapOf<String, String>()

        config.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val id = artifact.moduleVersion.id
            val coordinate = "${id.group}:${id.name}"
            if (coordinate in coordinateSet) {
                result[coordinate] = id.version
            }
        }

        return result
    }
}
