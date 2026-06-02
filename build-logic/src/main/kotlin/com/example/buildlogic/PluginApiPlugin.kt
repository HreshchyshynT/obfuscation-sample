package com.example.buildlogic

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.internal.extensions.stdlib.capitalized

class PluginApiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "pluginApiConfig",
            PluginApiExtension::class.java,
        )

        project.plugins.withType(LibraryPlugin::class.java) {
            val androidComponents = project.extensions
                .getByType(LibraryAndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                val variantCapName = variant.name.capitalized()
                val taskName = "generate${variantCapName}SharedClassList"

                val generateTask = project.tasks.register(
                    taskName,
                    GenerateSharedClassListTask::class.java,
                ) {
                    additionalEntries.set(extension.additionalEntries)
                    outputDir.set(
                        project.layout.buildDirectory.dir(
                            "generated/shared-mappings/${variant.name}"
                        )
                    )
                }

                variant.sources.resources?.addGeneratedSourceDirectory(
                    generateTask,
                    ){ it.outputDir }

                project.afterEvaluate {
                    val compileKotlinTask = project.tasks.findByName(
                        "compile${variantCapName}Kotlin"
                    )
                    val compileJavaTask = project.tasks.findByName(
                        "compile${variantCapName}JavaWithJavac"
                    )

                    generateTask.configure {
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
}
