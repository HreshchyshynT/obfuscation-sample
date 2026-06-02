package com.example.buildlogic


import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.BasePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.configure
import java.io.File

class HostAppPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val extension = extensions.getOrCreateHostAbiExtension()

        // create configurations that let us inspect PluginApis and SDK files:

        val pluginsApisConfiguration =
            configurations.create(HostAppExtension.PLUGINS_APIS_CONFIGURATION) {
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
                    println("Task receives dependencies: ${extension.trackedDependencies.get()}")


                    val variantCapName = variant.name.capitalized()
                    val sharedDir = rootProject.file("shared-metadata/${variant.name}/")
                    if (!sharedDir.exists()) {
                        sharedDir.mkdirs()
                    }
                    val abiLockFile = File(sharedDir, HOST_ABI_LOCK_FILE_NAME)
                    if (!abiLockFile.exists()) {
                        abiLockFile.createNewFile()
                    }
                    val commonMappingsFile = File(sharedDir, CreateSharedMappingsTask.COMBINED_MAPPINGS_FILE_NAME)


                    val validateAbi = project.tasks.register(
                        "validatePluginAbi$variantCapName",
                        ValidateHostAbiTask::class.java,
                    ) {
                        val srcFiles = extension.sharedModules.map { sharedModules ->
                            sharedModules.map { path -> project(path).fileTree("src") }
                        }
                        sdkSourceFiles.from(srcFiles)

                        sharedMappingsFile.set(commonMappingsFile)

                        val versionProvider = project.provider {
                            val configurationDeps =
                                project.configurations.getByName("implementation").dependencies
                            extension.trackedDependencies.get().joinToString(";") { target ->
                                val found = configurationDeps.find {
                                    "${it.group}:${it.name}".contains(target)
                                }
                                "$target:${found?.version ?: "unknown"}"
                            }
                        }
                        dependenciesVersions.set(versionProvider)
                        lockFile.set(abiLockFile)
                        val abiChangedFile =
                            layout.buildDirectory.file(getAbiChangedFlagFilePath(variant.name))
                        abiChangedFlagFile.set(abiChangedFile)
                    }

                    val extractTask = project.tasks.register(
                        "extractSharedClassList$variantCapName",
                        ExtractSharedClassListTask::class.java,
                    ) {
                        dependsOn(validateAbi)
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

                        abiChangedFlagFile.set(validateAbi.flatMap { it.abiChangedFlagFile })

                        onlyIf { abiChangedFlagFile.read() }
                    }

                    val proguardRulesTask = tasks.register(
                        "createTempProguardRules$variantCapName",
                        CreateTempProguardRulesTask::class.java,
                    ) {
                        abiChangedFlagFile.set(validateAbi.flatMap { it.abiChangedFlagFile })
                        sharedMappingsFile.set(commonMappingsFile)
                        val proguardFile =
                            layout.buildDirectory.file(getTempProguardPath(variantCapName))
                        generatedProGuardFile.set(proguardFile)
                    }

                    variant.proguardFiles.add(proguardRulesTask.flatMap { it.generatedProGuardFile })

                    tasks.named { it == "minify${variantCapName}WithR8" }.configureEach {
                        dependsOn(proguardRulesTask)
                        finalizedBy(createSharedMappings)
                    }
                }
            }
        }

    }

    private fun getTempProguardPath(variantName: String): String {
        return "tmp/${variantName}/proguard-rules.pro"
    }

    private fun getAbiChangedFlagFilePath(variantName: String): String {
        return "tmp/${variantName}/abi/status.txt"
    }

    companion object {
        private const val HOST_ABI_LOCK_FILE_NAME = "host-plugin-abi.lock"
    }
}
