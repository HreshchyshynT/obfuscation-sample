package com.example.buildlogicimport org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.findByType
import javax.inject.Inject

abstract class HostAppExtension @Inject constructor(
    objects: ObjectFactory,
    private val project: Project,
){
    /**
     * Projects whose source code forms the ABI boundary (e.g., ":plugin_sdk", ":plugin:api").
     */
    val sharedModules: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Group and artifact prefixes to track for version changes (e.g., "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2").
     */
    val trackedDependencies: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * add plugin api dependencies to the separate configuration to be able to extract information
     * required for shared mappings during build
     */
    fun plugin(vararg dependencyNotations: Any) {
        dependencyNotations.forEach {
            project.dependencies.add(PLUGINS_APIS_CONFIGURATION, it)
        }
    }

    /**
     * add plugin sdk dependencies to the separate configuration to be able to extract information
     * required for shared mappings during build
     */
    fun sdk(vararg dependencyNotations: Any) {
        dependencyNotations.forEach {
            project.dependencies.add(PLUGIN_SDKS_CONFIGURATION, it)
        }
    }

    companion object {
        const val PLUGINS_APIS_CONFIGURATION = "pluginsApiClasspath"
        const val PLUGIN_SDKS_CONFIGURATION = "pluginSdksClasspath"
    }
}

fun ExtensionContainer.getOrCreateHostAbiExtension(): HostAppExtension =
    findByType<HostAppExtension>()
            ?: create("hostAppConfig", HostAppExtension::class.java)
                .apply {
                    sharedModules.convention(listOf())
                    trackedDependencies.convention(listOf())
                }



