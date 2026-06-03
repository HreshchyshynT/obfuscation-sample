package com.example.buildlogic


import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.BasePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.configure
import java.io.File

class HostAppPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val extension = extensions.getOrCreateHostAbiExtension()

        val pluginsApisConfiguration =
            configurations.create(HostAppExtension.PLUGINS_APIS_CONFIGURATION) {
                isCanBeConsumed = false
                isCanBeResolved = true
                isTransitive = false
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

        val sdkConfiguration = configurations.create(HostAppExtension.PLUGIN_SDKS_CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
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

        plugins.withType(BasePlugin::class.java) {
            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants(selector().withBuildType("release")) { variant ->
                    val variantCapName = variant.name.capitalized()
                    val sharedDir = rootProject.file("shared-metadata/${variant.name}/")
                    if (!sharedDir.exists()) {
                        sharedDir.mkdirs()
                    }
                    val abiLockFile = File(sharedDir, HOST_ABI_LOCK_FILE_NAME)

                    val validateAbi = project.tasks.register(
                        "validatePluginAbi$variantCapName",
                        ValidateHostAbiTask::class.java,
                    ) {
                        sharedDepsVersions.set(buildSharedDepsVersions(extension))
                        pluginVersions.set(buildArtifactVersions(pluginsApisConfiguration))
                        sdkVersions.set(buildSdkVersions(extension, sdkConfiguration))
                        sdkSourceFiles.from(buildSdkSourceFiles(extension))
                        rootDirectory.set(rootProject.layout.projectDirectory)
                        lockFile.set(abiLockFile)
                        resultFile.set(
                            layout.buildDirectory.file("tmp/${variant.name}/abi/validation-result.json")
                        )
                        pendingLockFile.set(
                            layout.buildDirectory.file("tmp/${variant.name}/abi/pending-lock.json")
                        )
                    }

                    val extractTask = project.tasks.register(
                        "extractSharedClassList$variantCapName",
                        ExtractSharedClassListTask::class.java,
                    ) {
                        configureTask(variant, sdkConfiguration, pluginsApisConfiguration)
                    }

                    val createSharedMappings = tasks.register(
                        "createSharedMappings$variantCapName",
                        CreateSharedMappingsTask::class.java,
                    ) {
                        val mappingsArtifact =
                            variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                        freshR8MappingFile.set(mappingsArtifact)
                        classPluginIndexFile.set(extractTask.flatMap { it.classPluginIndexFile })
                        commonPrefixes.set(extension.commonDependencyPrefixes)
                        outputDir.set(sharedDir)

                        validationResultFile.set(validateAbi.flatMap { it.resultFile })

                        onlyIf {
                            val result = AbiValidationResult.fromFile(
                                validationResultFile.get().asFile
                            )
                            result.hasAnyChange()
                        }
                    }

                    val updateAbiLock = tasks.register(
                        "updateAbiLock$variantCapName",
                        UpdateAbiLockTask::class.java,
                    ) {
                        pendingLockFile.set(validateAbi.flatMap { it.pendingLockFile })
                        lockFile.set(abiLockFile)
                    }

                    val proguardRulesTask = tasks.register(
                        "createTempProguardRules$variantCapName",
                        CreateTempProguardRulesTask::class.java,
                    ) {
                        validationResultFile.set(validateAbi.flatMap { it.resultFile })
                        mappingsDir.set(sharedDir)
                        val proguardFile =
                            layout.buildDirectory.file(getTempProguardPath(variantCapName))
                        generatedProGuardFile.set(proguardFile)
                    }

                    variant.proguardFiles.add(proguardRulesTask.flatMap { it.generatedProGuardFile })

                    tasks.named { it == "minify${variantCapName}WithR8" }.configureEach {
                        dependsOn(proguardRulesTask)
                        finalizedBy(createSharedMappings)
                    }

                    createSharedMappings.configure {
                        finalizedBy(updateAbiLock)
                    }
                }
            }
        }
    }

    private fun Project.buildSharedDepsVersions(
        extension: HostAppExtension,
    ) = provider {
        val configurationDeps =
            project.configurations.getByName("implementation").dependencies
        extension.trackedDependencies.get().associateWith { target ->
            val found = configurationDeps.find {
                "${it.group}:${it.name}".contains(target)
            }
            (found?.version ?: "unknown")
        }
    }

    private fun Project.buildArtifactVersions(
        config: Configuration,
    ) = config.incoming.jarArtifactView().artifacts.resolvedArtifacts.map { artifactResults ->
        artifactResults.associate { artifact ->
            val id = artifact.id.componentIdentifier
            val version = when (id) {
                is ModuleComponentIdentifier -> id.version
                else -> "sha256:${hashFileContent(artifact.file)}"
            }
            derivePluginId(id) to version
        }
    }

    private fun Project.buildSdkVersions(
        extension: HostAppExtension,
        sdkConfig: Configuration,
    ) = sdkConfig.incoming.jarArtifactView().artifacts.resolvedArtifacts.map { artifactResults ->
        artifactResults.associate { artifact ->
            val id = artifact.id.componentIdentifier
            val version = when (id) {
                is ModuleComponentIdentifier -> id.version
                else -> ValidateHostAbiTask.NEEDS_SOURCE_HASH
            }
            derivePluginId(id) to version
        }
    }

    private fun Project.buildSdkSourceFiles(
        extension: HostAppExtension,
    ) = extension.sharedModules.map { sharedModules ->
        sharedModules.map { path -> project(path).fileTree("src") }
    }

    private fun getTempProguardPath(variantName: String): String {
        return "tmp/${variantName}/proguard-rules.pro"
    }

    companion object {
        private const val HOST_ABI_LOCK_FILE_NAME = "host-plugin-abi.lock"
    }
}
