package com.example.buildlogic

import org.gradle.api.Project
import javax.inject.Inject

abstract class SharedMappingsExtension @Inject constructor(
    private val project: Project,
) {

    fun plugin(dependencyNotation: Any) {
        project.dependencies.add("sharedMappingsClasspath", dependencyNotation)
    }
}
