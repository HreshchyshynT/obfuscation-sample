package com.example.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Usage

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

        val sdkConfiguration = project.configurations.create("sharedMappingsSdkClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
            attributes {
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

        val sdkFileCollection = sdkConfiguration.incoming.artifactView {
            attributes {
                attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    ArtifactTypeDefinition.JAR_TYPE,
                )
            }
        }.files

        val classesArtifacts = configuration.incoming.artifactView {
            attributes {
                attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    "android-classes-jar",
                )
            }
        }.artifacts

        val pluginFileMappingProvider = classesArtifacts.resolvedArtifacts.map { artifacts ->
            artifacts.associate { artifact ->
                val id = artifact.id.componentIdentifier
                val pluginId = when (id) {
                    is ProjectComponentIdentifier ->
                        id.projectPath.removePrefix(":").replace(":", "-")
                    is ModuleComponentIdentifier ->
                        "${id.group}--${id.module}"
                    else ->
                        id.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "-")
                }
                artifact.file.absolutePath to pluginId
            }
        }

        val hostVersionsProvider = project.provider {
            val result = mutableMapOf<String, String>()
            sdkConfiguration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val id = artifact.moduleVersion.id
                result["${id.group}:${id.name}"] = id.version
            }
            result
        }

        val checkTask = project.tasks.register(
            "checkPluginCompatibility",
            CheckPluginCompatibilityTask::class.java,
        ) {
            pluginArtifacts.from(classesFileCollection)
            hostSharedDepVersions.set(hostVersionsProvider)
            outputFile.set(
                project.layout.buildDirectory.file(
                    "shared-mappings/compatibility-state.properties"
                )
            )
        }

        val extractTask = project.tasks.register(
            "extractSharedClassList",
            ExtractSharedClassListTask::class.java,
        ) {
            inputFiles.from(classesFileCollection)
            sdkFiles.from(sdkFileCollection)
            compatibilityState.from(checkTask.flatMap { it.outputFile })
            pluginFileMapping.set(pluginFileMappingProvider)
            outputFile.set(
                project.layout.buildDirectory.file("shared-mappings/shared-class-list.txt")
            )
            classPluginIndexFile.set(
                project.layout.buildDirectory.file(
                    "shared-mappings/class-plugin-index.properties"
                )
            )
        }

        project.plugins.withType(AppPlugin::class.java) {
            val androidComponents = project.extensions
                .getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                val variantCapName = variant.name.replaceFirstChar { it.uppercase() }

                val generateMappingsTask = project.tasks.register(
                    "generate${variantCapName}PluginMappings",
                    GeneratePluginMappingsTask::class.java,
                ) {
                    mappingFile.set(
                        variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                    )
                    classPluginIndex.set(
                        extractTask.flatMap { it.classPluginIndexFile }
                    )
                    outputDir.set(
                        project.layout.buildDirectory.dir(
                            "shared-mappings/plugin-mappings/${variant.name}"
                        )
                    )
                }

                project.tasks.configureEach {
                    if (name == "assemble${variantCapName}") {
                        dependsOn(generateMappingsTask)
                    }
                }
            }
        }
    }
}
