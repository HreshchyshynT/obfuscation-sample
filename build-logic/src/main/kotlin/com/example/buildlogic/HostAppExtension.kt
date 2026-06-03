package com.example.buildlogic


import org.gradle.api.Project
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
     * Class/package prefixes for common dependencies whose mappings must appear
     * in every per-plugin mapping file (e.g. coroutines, kotlin functions).
     */
    val commonDependencyPrefixes: ListProperty<String> = objects.listProperty(String::class.java)

    fun commonDependency(prefix: String) {
        commonDependencyPrefixes.add(prefix)
    }

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

    fun trackedDependency(vararg dependencyNotations: Any) {
        dependencyNotations.forEach {
            project.dependencies.add(TRACKED_DEPS_CONFIGURATION, it)
        }
    }

    companion object {
        const val PLUGINS_APIS_CONFIGURATION = "pluginsApiClasspath"
        const val PLUGIN_SDKS_CONFIGURATION = "pluginSdksClasspath"
        const val TRACKED_DEPS_CONFIGURATION = "trackedDepsClasspath"
    }
}

fun ExtensionContainer.getOrCreateHostAbiExtension(): HostAppExtension =
    findByType<HostAppExtension>()
            ?: create("hostAppConfig", HostAppExtension::class.java)
                .apply {
                    commonDependencyPrefixes.convention(listOf(
                        "kotlin.coroutines.",
                        "kotlinx.coroutines.",
                        "kotlin.jvm.functions.",
                    ))
                }



