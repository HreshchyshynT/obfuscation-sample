package com.example.buildlogic

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.gradle.AppPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

class SharedMappingsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.extensions.create(
            "sharedMappings",
            SharedMappingsExtension::class.java,
        )

        val configuration = project.configurations.create("sharedMappingsClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(
                    BuildTypeAttr.ATTRIBUTE,
                    project.objects.named(BuildTypeAttr::class.java, "release"),
                )
                attribute(
                    Usage.USAGE_ATTRIBUTE,
                    project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
                )
            }
        }

        val classesFileCollection = configuration.incoming.artifactView {
            attributes {
                attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    "android-classes-jar",
                )
            }
        }.files

        val extractTask = project.tasks.register(
            "extractSharedClassList",
            ExtractSharedClassListTask::class.java,
        ) {
            inputFiles.from(classesFileCollection)
            outputFile.set(
                project.layout.buildDirectory.file("shared-mappings/shared-class-list.txt")
            )
        }

        project.plugins.withType(AppPlugin::class.java) {
            project.tasks.configureEach {
                if (name == "assembleRelease") {
                    dependsOn(extractTask)
                }
            }
        }
    }
}
